package com.reliableautomoto.attendance;

import java.time.Instant;

public record ScanResult(Status status, String employeeNumber, String employeeName,
                         Instant timestamp, String message) {
    public enum Status { TIME_IN, TIME_OUT, UNKNOWN_EMPLOYEE, INACTIVE_EMPLOYEE, DUPLICATE, INVALID }
    public boolean successful() { return status == Status.TIME_IN || status == Status.TIME_OUT; }
}
