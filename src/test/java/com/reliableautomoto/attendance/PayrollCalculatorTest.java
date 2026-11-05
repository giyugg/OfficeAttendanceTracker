package com.reliableautomoto.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PayrollCalculatorTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Manila");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    @TempDir Path directory;

    @Test void roundsEveryHourAtTheFortyFiveMinuteThreshold() {
        assertEquals(LocalTime.of(17, 0), PayrollCalculator.roundTimeOut(MONDAY.atTime(17, 0)).toLocalTime());
        assertEquals(LocalTime.of(17, 0), PayrollCalculator.roundTimeOut(MONDAY.atTime(17, 44, 59)).toLocalTime());
        assertEquals(LocalTime.of(18, 0), PayrollCalculator.roundTimeOut(MONDAY.atTime(17, 45)).toLocalTime());
        assertEquals(LocalTime.of(18, 0), PayrollCalculator.roundTimeOut(MONDAY.atTime(18, 44, 59)).toLocalTime());
        assertEquals(LocalTime.of(19, 0), PayrollCalculator.roundTimeOut(MONDAY.atTime(18, 45)).toLocalTime());
        assertEquals(3, PayrollCalculator.overtimeHours(MONDAY.atTime(19, 45)));
    }

    @Test void regularDayUsesDailyRateForAbsenceAndOneTimesHourlyRateForOvertime() throws Exception {
        AttendanceDatabase database = databaseWithEmployee();
        scan(database, MONDAY, 9, 0);
        scan(database, MONDAY, 18, 0);
        PayrollLine line = service(database).preview(MONDAY, MONDAY, Map.of()).lines().getFirst();

        assertMoney("75.00", line.hourlyRate());
        assertEquals(1, line.paidScheduledDays());
        assertEquals(new BigDecimal("8.00"), line.creditedRegularHours());
        assertEquals(1, line.overtimeHours());
        assertMoney("75.00", line.overtimePay());
        assertMoney("675.00", line.netPay());
    }

    @Test void earlyTimeoutUsesTheSameRoundingRuleForCreditedRegularHours() throws Exception {
        AttendanceDatabase database = databaseWithEmployee();
        scan(database, MONDAY, 9, 0);
        scan(database, MONDAY, 16, 44);
        PayrollLine line = service(database).preview(MONDAY, MONDAY, Map.of()).lines().getFirst();
        assertEquals(new BigDecimal("7.00"), line.creditedRegularHours());
        assertEquals(new BigDecimal("1.00"), line.absentHours());
        assertMoney("75.00", line.absentDeduction());
        assertMoney("525.00", line.netPay());
    }

    @Test void paidAndUnpaidNonWorkingHolidaysNeverCreateAbsenceDeductions() throws Exception {
        AttendanceDatabase database = databaseWithEmployee();
        Holiday paid = database.addHoliday("Paid holiday", MONDAY, HolidayType.PAID, "");
        PayrollLine paidLine = service(database).preview(MONDAY, MONDAY, Map.of()).lines().getFirst();
        assertEquals(0, paidLine.expectedRegularHours().compareTo(BigDecimal.ZERO));
        assertMoney("0.00", paidLine.absentDeduction());
        assertMoney("600.00", paidLine.netPay());

        database.updateHoliday(paid.id(), "Unpaid holiday", MONDAY, HolidayType.UNPAID, "");
        PayrollLine unpaidLine = service(database).preview(MONDAY, MONDAY, Map.of()).lines().getFirst();
        assertEquals(0, unpaidLine.paidScheduledDays());
        assertMoney("0.00", unpaidLine.absentDeduction());
        assertMoney("0.00", unpaidLine.netPay());
    }

    @Test void workOnPaidHolidayIsNotDoublePaidButOvertimeRemainsSeparate() throws Exception {
        AttendanceDatabase database = databaseWithEmployee();
        database.addHoliday("Paid holiday", MONDAY, HolidayType.PAID, "");
        scan(database, MONDAY, 9, 0);
        scan(database, MONDAY, 18, 0);
        PayrollLine line = service(database).preview(MONDAY, MONDAY, Map.of()).lines().getFirst();
        assertMoney("600.00", line.grossPay());
        assertMoney("0.00", line.absentDeduction());
        assertMoney("75.00", line.overtimePay());
        assertMoney("675.00", line.netPay());
    }

    @Test void finalizedPayrollPreservesItsSnapshotAcrossConfigurationChanges() throws Exception {
        AttendanceDatabase database = databaseWithEmployee();
        Holiday holiday = database.addHoliday("Paid holiday", MONDAY, HolidayType.PAID, "");
        PayrollService service = service(database);
        PayrollReport finalized = service.finalizePayroll(MONDAY, MONDAY, Map.of("E-1", new BigDecimal("25.00")));
        assertTrue(finalized.finalized());
        assertMoney("575.00", finalized.lines().getFirst().netPay());

        database.updateHoliday(holiday.id(), "Now unpaid", MONDAY, HolidayType.UNPAID, "changed later");
        database.setEmployeeDailyPay("E-1", 100_000);
        PayrollReport preserved = service.preview(MONDAY, MONDAY, Map.of());
        assertEquals(finalized.id(), preserved.id());
        assertMoney("600.00", preserved.lines().getFirst().dailyPay());
        assertMoney("25.00", preserved.lines().getFirst().manualDeductions());
        assertMoney("575.00", preserved.lines().getFirst().netPay());
    }

    private AttendanceDatabase databaseWithEmployee() throws Exception {
        AttendanceDatabase database = new AttendanceDatabase(directory.resolve("payroll.db"));
        database.addEmployee("E-1", "Employee One");
        database.setEmployeeDailyPay("E-1", 60_000);
        return database;
    }

    private PayrollService service(AttendanceDatabase database) {
        Instant now = MONDAY.plusDays(1).atStartOfDay(ZONE).toInstant();
        return new PayrollService(database, ZONE, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void scan(AttendanceDatabase database, LocalDate date, int hour, int minute) throws Exception {
        database.recordScan("E-1", date.atTime(hour, minute).atZone(ZONE).toInstant(), Duration.ZERO);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
