package com.reliableautomoto.attendance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;

import static com.reliableautomoto.attendance.ScanResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class AttendanceServiceTest {
    @TempDir Path directory;
    private AttendanceDatabase database;
    private static final Instant START = Instant.parse("2026-11-02T00:00:00Z");

    @BeforeEach void setup() throws Exception {
        database = new AttendanceDatabase(directory.resolve("attendance.db"));
        database.addEmployee("00042", "Maria Santos");
    }

    private ScanResult scan(String value, Instant time) throws Exception {
        return new AttendanceService(database, Clock.fixed(time, ZoneOffset.UTC)).scan(value);
    }

    @Test void firstScanTimesInSecondTimesOutAndThirdStartsAnotherShift() throws Exception {
        assertEquals(TIME_IN, scan("00042", START).status());
        assertEquals(TIME_OUT, scan("00042", START.plus(Duration.ofHours(8))).status());
        assertEquals(TIME_IN, scan("00042", START.plus(Duration.ofHours(9))).status());
        var records = database.attendance(null, null, false);
        assertEquals(2, records.size());
        assertNull(records.getFirst().timeOut());
        assertEquals(START, records.get(1).timeIn());
        assertEquals(START.plus(Duration.ofHours(8)), records.get(1).timeOut());
        assertEquals("8h 00m 00s", records.get(1).duration());
    }

    @Test void unknownAndInactiveEmployeesDoNotCreateAttendance() throws Exception {
        assertEquals(UNKNOWN_EMPLOYEE, scan("missing", START).status());
        database.updateEmployee("00042", "Maria Santos", false);
        assertEquals(INACTIVE_EMPLOYEE, scan("00042", START).status());
        assertTrue(database.attendance(null, null, false).isEmpty());
        database.updateEmployee("00042", "Maria Santos", true);
        assertEquals(TIME_IN, scan("00042", START).status());
    }

    @Test void blankNullMultilineAndLongPayloadsAreRejected() throws Exception {
        for (String value : new String[]{null, "", "   ", "000\n42", "0".repeat(101), "000\u000042"}) {
            assertEquals(INVALID, scan(value, START).status());
        }
        assertTrue(database.attendance(null, null, false).isEmpty());
    }

    @Test void employeeNumbersPreserveLeadingZerosAndUseExactCase() throws Exception {
        assertEquals(UNKNOWN_EMPLOYEE, scan("42", START).status());
        assertEquals(TIME_IN, scan(" 00042\r\n", START).status());
        database.addEmployee("EMP-A", "Alex");
        assertEquals(UNKNOWN_EMPLOYEE, scan("emp-a", START).status());
        assertEquals(TIME_IN, scan("EMP-A", START).status());
    }

    @Test void payloadIsOnlyAnEmployeeNumberAndCannotExecuteSql() throws Exception {
        assertEquals(UNKNOWN_EMPLOYEE, scan("' OR 1=1 --", START).status());
        database.addEmployee("O'NEIL-01", "John O'Neil");
        assertEquals(TIME_IN, scan("O'NEIL-01", START).status());
        assertEquals(2, database.employees().size());
    }

    @Test void rapidRepeatsAreIgnoredWithoutExtendingTheCooldown() throws Exception {
        assertEquals(TIME_IN, scan("00042", START).status());
        assertEquals(DUPLICATE, scan("00042", START.plusSeconds(1)).status());
        assertEquals(DUPLICATE, scan("00042", START.plusMillis(9999)).status());
        assertEquals(1, database.attendance(null, null, true).size());
        assertEquals(TIME_OUT, scan("00042", START.plusSeconds(10)).status());
        assertEquals(DUPLICATE, scan("00042", START.plusSeconds(11)).status());
        assertEquals(TIME_IN, scan("00042", START.plusSeconds(20)).status());
    }

    @Test void cooldownAppliesPerEmployee() throws Exception {
        database.addEmployee("00043", "Jose Reyes");
        assertEquals(TIME_IN, scan("00042", START).status());
        assertEquals(TIME_IN, scan("00043", START).status());
        assertEquals(2, database.attendance(null, null, true).size());
    }

    @Test void openShiftSurvivesAppRestartAndMidnight() throws Exception {
        Instant beforeMidnight = Instant.parse("2026-11-02T15:55:00Z");
        assertEquals(TIME_IN, scan("00042", beforeMidnight).status());
        var reopened = new AttendanceDatabase(database.path());
        var service = new AttendanceService(reopened, Clock.fixed(beforeMidnight.plusSeconds(3600), ZoneOffset.UTC));
        assertEquals(TIME_OUT, service.scan("00042").status());
        var records = reopened.attendance(null, null, false);
        assertEquals(1, records.size());
        assertEquals("1h 00m 00s", records.getFirst().duration());
    }

    @Test void clockMovingBackwardDoesNotWriteInvalidTimes() throws Exception {
        assertEquals(TIME_IN, scan("00042", START).status());
        assertEquals(DUPLICATE, scan("00042", START.minusSeconds(60)).status());
        assertEquals(1, database.attendance(null, null, true).size());
    }

    @Test void dateFilterUsesTimeInWithExclusiveUpperBound() throws Exception {
        ZoneId zone = ZoneId.of("Asia/Manila");
        Instant dayStart = LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant();
        assertEquals(TIME_IN, scan("00042", dayStart).status());
        assertEquals(TIME_OUT, scan("00042", dayStart.plusSeconds(60)).status());
        assertEquals(TIME_IN, scan("00042", dayStart.plus(Duration.ofDays(1))).status());
        var today = database.attendance(dayStart, dayStart.plus(Duration.ofDays(1)), false);
        assertEquals(1, today.size());
        assertEquals(dayStart, today.getFirst().timeIn());
        assertEquals(1, database.attendance(null, null, true).size());
    }

    @Test void duplicateRegistrationDoesNotOverwriteEmployee() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> database.addEmployee("00042", "Different name"));
        assertEquals("Maria Santos", database.employees().getFirst().name());
        database.addEmployee("00099", "Pedro Penduko", 65_000);
        assertEquals(65_000, database.employees().stream()
                .filter(employee -> "00099".equals(employee.number())).findFirst().orElseThrow().dailyPayCents());
        assertThrows(IllegalArgumentException.class, () -> database.addEmployee("NEW", "  "));
        assertThrows(IllegalArgumentException.class, () -> database.addEmployee("NEW", "Line\nBreak"));
        assertThrows(IllegalArgumentException.class, () -> database.addEmployee("NEW", "Valid Name", -1));
        database.updateEmployee("00042", "Maria Cruz", true);
        assertEquals("Maria Cruz", scan("00042", START).employeeName());
    }

    @Test void simultaneousScansFromSeparateConnectionsRecordOnlyOneTimeIn() throws Exception {
        int count = 8;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<ScanResult>>();
            for (int index = 0; index < count; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return scan("00042", START);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (Future<ScanResult> future : futures) {
                ScanResult result = future.get(10, TimeUnit.SECONDS);
                if (result.status() == TIME_IN) successes++;
                else assertEquals(DUPLICATE, result.status());
            }
            assertEquals(1, successes);
        }
        assertEquals(1, database.attendance(null, null, true).size());
    }

    @Test void schemaRejectsMultipleOpenShiftsAndOrphanRecords() throws Exception {
        scan("00042", START);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.path());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO attendance(employee_number, time_in) VALUES ('00042', 0)"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO attendance(employee_number, time_in) VALUES ('missing', 0)"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "UPDATE attendance SET time_out = 0"));
        }
    }
}
