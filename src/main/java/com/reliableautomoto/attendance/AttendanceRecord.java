package com.reliableautomoto.attendance;

import java.time.Duration;
import java.time.Instant;

public record AttendanceRecord(long id, String employeeNumber, String employeeName,
                               Instant timeIn, Instant timeOut) {
    public String duration() {
        if (timeOut == null) return "In progress";
        Duration elapsed = Duration.between(timeIn, timeOut);
        return "%dh %02dm %02ds".formatted(elapsed.toHours(), elapsed.toMinutesPart(), elapsed.toSecondsPart());
    }
}
