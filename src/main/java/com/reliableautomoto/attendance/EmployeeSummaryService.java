package com.reliableautomoto.attendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds administrator-facing daily attendance summaries from stored shifts. */
public final class EmployeeSummaryService {
    private EmployeeSummaryService() { }

    public static List<EmployeeDailySummary> summarize(List<AttendanceRecord> records, ZoneId zone, Instant asOf) {
        record Key(LocalDate date, String employeeNumber, String employeeName) { }

        List<AttendanceRecord> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparing(AttendanceRecord::timeIn));
        Map<Key, List<AttendanceRecord>> grouped = new LinkedHashMap<>();
        for (AttendanceRecord attendance : ordered) {
            LocalDate date = attendance.timeIn().atZone(zone).toLocalDate();
            Key key = new Key(date, attendance.employeeNumber(), attendance.employeeName());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(attendance);
        }

        List<EmployeeDailySummary> summaries = new ArrayList<>();
        for (Map.Entry<Key, List<AttendanceRecord>> entry : grouped.entrySet()) {
            Duration present = Duration.ZERO;
            boolean inProgress = false;
            for (AttendanceRecord attendance : entry.getValue()) {
                Instant end = attendance.timeOut();
                if (end == null) {
                    end = asOf;
                    inProgress = true;
                }
                if (end.isAfter(attendance.timeIn())) {
                    present = present.plus(Duration.between(attendance.timeIn(), end));
                }
            }
            Key key = entry.getKey();
            summaries.add(new EmployeeDailySummary(key.date(), key.employeeNumber(), key.employeeName(),
                    entry.getValue(), present, inProgress));
        }
        summaries.sort(Comparator.comparing(EmployeeDailySummary::date).reversed()
                .thenComparing(EmployeeDailySummary::employeeName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(EmployeeDailySummary::employeeNumber));
        return List.copyOf(summaries);
    }
}
