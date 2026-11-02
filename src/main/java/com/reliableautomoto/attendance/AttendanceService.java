package com.reliableautomoto.attendance;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;

public final class AttendanceService {
    public static final Duration SCAN_COOLDOWN = Duration.ofSeconds(10);
    private final AttendanceDatabase database;
    private final Clock clock;

    public AttendanceService(AttendanceDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public ScanResult scan(String rawNumber) throws SQLException {
        String number;
        try { number = EmployeeNumbers.normalize(rawNumber); }
        catch (IllegalArgumentException error) {
            return new ScanResult(ScanResult.Status.INVALID, "", "", clock.instant(), error.getMessage());
        }
        return database.recordScan(number, clock.instant(), SCAN_COOLDOWN);
    }
}
