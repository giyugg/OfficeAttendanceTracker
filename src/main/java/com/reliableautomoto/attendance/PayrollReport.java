package com.reliableautomoto.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PayrollReport(long id, LocalDate periodStart, LocalDate periodEnd,
                            boolean finalized, Instant computedAt, List<PayrollLine> lines) {
    public PayrollReport {
        lines = List.copyOf(lines);
    }
}
