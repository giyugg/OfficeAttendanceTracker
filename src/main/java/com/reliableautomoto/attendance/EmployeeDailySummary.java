package com.reliableautomoto.attendance;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** One employee's attendance totals for shifts that started on the same local date. */
public record EmployeeDailySummary(LocalDate date, String employeeNumber, String employeeName,
                                   List<AttendanceRecord> shifts, Duration presentDuration,
                                   boolean inProgress) {
    public EmployeeDailySummary {
        shifts = List.copyOf(shifts);
    }
}
