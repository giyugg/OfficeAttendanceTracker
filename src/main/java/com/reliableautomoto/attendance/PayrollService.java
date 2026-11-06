package com.reliableautomoto.attendance;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Computes live previews and manages saved payroll runs with editable manual deductions. */
public final class PayrollService {
    private final AttendanceDatabase database;
    private final ZoneId zone;
    private final Clock clock;

    public PayrollService(AttendanceDatabase database, ZoneId zone, Clock clock) {
        this.database = database;
        this.zone = zone;
        this.clock = clock;
    }

    public PayrollReport preview(LocalDate start, LocalDate end, Map<String, BigDecimal> manualDeductions) throws SQLException {
        validatePeriod(start, end);
        Instant until = end.plusDays(1).atStartOfDay(zone).toInstant();
        List<AttendanceRecord> records = database.attendance(null, until, false);
        Map<LocalDate, Holiday> holidays = new HashMap<>();
        for (Holiday holiday : database.holidays(start, end)) holidays.put(holiday.date(), holiday);
        Map<String, BigDecimal> deductions = manualDeductions == null ? Map.of() : Map.copyOf(manualDeductions);
        List<PayrollLine> lines = database.employees().stream()
                .filter(Employee::active)
                .map(employee -> PayrollCalculator.calculate(employee, start, end, records.stream()
                                .filter(record -> record.employeeNumber().equals(employee.number())).toList(), holidays,
                        deductions.getOrDefault(employee.number(), BigDecimal.ZERO), zone))
                .toList();
        return new PayrollReport(0, start, end, false, clock.instant(), lines);
    }

    public PayrollReport finalizePayroll(LocalDate start, LocalDate end,
                                         Map<String, BigDecimal> manualDeductions) throws SQLException {
        validatePeriod(start, end);
        PayrollReport existing = database.finalizedPayroll(start, end);
        if (existing != null) {
            if (manualDeductions == null || manualDeductions.isEmpty()) return existing;
            return database.updatePayrollManualDeductions(existing.id(), manualDeductions);
        }
        PayrollReport report = preview(start, end, manualDeductions);
        return database.finalizePayroll(report, clock.instant());
    }

    public PayrollReport savePayroll(LocalDate start, LocalDate end,
                                     Map<String, BigDecimal> manualDeductions) throws SQLException {
        return finalizePayroll(start, end, manualDeductions);
    }

    public PayrollReport savedPayroll(LocalDate start, LocalDate end) throws SQLException {
        validatePeriod(start, end);
        return database.finalizedPayroll(start, end);
    }

    public PayrollReport savedPayroll(long id) throws SQLException {
        return database.savedPayroll(id);
    }

    public List<PayrollReport> savedPayrolls() throws SQLException {
        return database.savedPayrolls();
    }

    public PayrollReport updateManualDeductions(long payrollRunId,
                                                Map<String, BigDecimal> manualDeductions) throws SQLException {
        return database.updatePayrollManualDeductions(payrollRunId, manualDeductions);
    }

    private static void validatePeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null) throw new IllegalArgumentException("Payroll start and end dates are required.");
        if (end.isBefore(start)) throw new IllegalArgumentException("Payroll end date cannot be before its start date.");
        if (start.plusYears(1).isBefore(end)) throw new IllegalArgumentException("A payroll period cannot exceed one year.");
    }
}
