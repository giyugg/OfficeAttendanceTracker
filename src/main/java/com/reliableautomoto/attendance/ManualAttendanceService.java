package com.reliableautomoto.attendance;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/** Applies the rules for administrator-entered attendance on a past date. */
public final class ManualAttendanceService {
    private final AttendanceDatabase database;
    private final ZoneId zone;
    private final Clock clock;

    public ManualAttendanceService(AttendanceDatabase database, ZoneId zone, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.zone = Objects.requireNonNull(zone);
        this.clock = Objects.requireNonNull(clock);
    }

    public AttendanceRecord add(String employeeNumber, LocalDate date, LocalTime timeIn, LocalTime timeOut)
            throws SQLException {
        Objects.requireNonNull(date, "Attendance date is required.");
        Objects.requireNonNull(timeIn, "Time-in is required.");
        Objects.requireNonNull(timeOut, "Time-out is required.");
        if (!date.isBefore(LocalDate.now(clock.withZone(zone)))) {
            throw new IllegalArgumentException("Manual attendance can only be added for a past date.");
        }
        if (!timeOut.isAfter(timeIn)) {
            throw new IllegalArgumentException("Time-out must be later than time-in on the same date.");
        }
        Instant start = date.atTime(timeIn).atZone(zone).toInstant();
        Instant end = date.atTime(timeOut).atZone(zone).toInstant();
        return database.addManualAttendance(employeeNumber, start, end);
    }
}
