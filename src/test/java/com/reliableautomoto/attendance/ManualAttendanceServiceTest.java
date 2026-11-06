package com.reliableautomoto.attendance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class ManualAttendanceServiceTest {
    @TempDir Path directory;
    private AttendanceDatabase database;
    private ManualAttendanceService service;

    @BeforeEach void setup() throws Exception {
        database = new AttendanceDatabase(directory.resolve("manual.db"));
        database.addEmployee("2026-001", "Acuna, Kyle A.");
        ZoneId zone = ZoneId.of("Asia/Manila");
        service = new ManualAttendanceService(database, zone,
                Clock.fixed(Instant.parse("2026-11-05T04:00:00Z"), zone));
    }

    @Test void administratorCanBackfillCompletedAttendanceOnPastDate() throws Exception {
        AttendanceRecord added = service.add("2026-001", LocalDate.of(2026, 10, 15),
                LocalTime.of(8, 0), LocalTime.of(17, 0));

        assertEquals("2026-001", added.employeeNumber());
        assertEquals("Acuna, Kyle A.", added.employeeName());
        assertEquals("9h 00m 00s", added.duration());
        assertEquals(added, database.attendance(null, null, false).getFirst());
    }

    @Test void todayFutureAndInvalidTimeRangesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.add("2026-001", LocalDate.of(2026, 11, 5),
                LocalTime.of(8, 0), LocalTime.of(17, 0)));
        assertThrows(IllegalArgumentException.class, () -> service.add("2026-001", LocalDate.of(2026, 11, 6),
                LocalTime.of(8, 0), LocalTime.of(17, 0)));
        assertThrows(IllegalArgumentException.class, () -> service.add("2026-001", LocalDate.of(2026, 10, 15),
                LocalTime.of(17, 0), LocalTime.of(8, 0)));
    }

    @Test void overlappingAndUnknownEmployeeAttendanceAreRejected() throws Exception {
        service.add("2026-001", LocalDate.of(2026, 10, 15), LocalTime.of(8, 0), LocalTime.of(17, 0));

        assertThrows(IllegalArgumentException.class, () -> service.add("2026-001", LocalDate.of(2026, 10, 15),
                LocalTime.of(13, 0), LocalTime.of(18, 0)));
        assertThrows(IllegalArgumentException.class, () -> service.add("MISSING", LocalDate.of(2026, 10, 15),
                LocalTime.of(8, 0), LocalTime.of(17, 0)));
        assertEquals(1, database.attendance(null, null, false).size());
    }

    @Test void adjacentShiftsRemainAllowed() throws Exception {
        service.add("2026-001", LocalDate.of(2026, 10, 15), LocalTime.of(8, 0), LocalTime.of(12, 0));
        service.add("2026-001", LocalDate.of(2026, 10, 15), LocalTime.of(12, 0), LocalTime.of(17, 0));

        assertEquals(2, database.attendance(null, null, false).size());
    }
}
