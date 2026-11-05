package com.reliableautomoto.attendance;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmployeeSummaryServiceTest {
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    @Test void groupsMultipleShiftsByEmployeeAndTimeInDate() {
        Instant firstIn = Instant.parse("2026-11-02T01:00:00Z");
        Instant firstOut = Instant.parse("2026-11-02T04:00:00Z");
        Instant secondIn = Instant.parse("2026-11-02T05:00:00Z");
        Instant secondOut = Instant.parse("2026-11-02T10:30:00Z");
        var records = List.of(
                new AttendanceRecord(2, "00042", "Maria Santos", secondIn, secondOut),
                new AttendanceRecord(1, "00042", "Maria Santos", firstIn, firstOut));

        var summary = EmployeeSummaryService.summarize(records, MANILA, secondOut).getFirst();

        assertEquals(java.time.LocalDate.of(2026, 11, 2), summary.date());
        assertEquals(List.of(1L, 2L), summary.shifts().stream().map(AttendanceRecord::id).toList());
        assertEquals(Duration.ofHours(8).plusMinutes(30), summary.presentDuration());
        assertFalse(summary.inProgress());
    }

    @Test void includesElapsedTimeForOpenShiftAndKeepsEmployeesSeparate() {
        Instant timeIn = Instant.parse("2026-11-02T01:00:00Z");
        Instant asOf = timeIn.plus(Duration.ofHours(4).plusMinutes(15));
        var records = List.of(
                new AttendanceRecord(1, "B", "Beta", timeIn, null),
                new AttendanceRecord(2, "A", "Alpha", timeIn, timeIn.plusSeconds(3600)));

        var summaries = EmployeeSummaryService.summarize(records, MANILA, asOf);

        assertEquals(List.of("A", "B"), summaries.stream().map(EmployeeDailySummary::employeeNumber).toList());
        assertEquals(Duration.ofHours(1), summaries.get(0).presentDuration());
        assertEquals(Duration.ofHours(4).plusMinutes(15), summaries.get(1).presentDuration());
        assertTrue(summaries.get(1).inProgress());
    }

    @Test void assignsOvernightShiftToItsTimeInDate() {
        Instant timeIn = Instant.parse("2026-11-02T15:00:00Z"); // 23:00 in Manila
        Instant timeOut = timeIn.plus(Duration.ofHours(3));

        var summary = EmployeeSummaryService.summarize(
                List.of(new AttendanceRecord(1, "00042", "Maria Santos", timeIn, timeOut)), MANILA, timeOut);

        assertEquals(java.time.LocalDate.of(2026, 11, 2), summary.getFirst().date());
        assertEquals(Duration.ofHours(3), summary.getFirst().presentDuration());
    }
}
