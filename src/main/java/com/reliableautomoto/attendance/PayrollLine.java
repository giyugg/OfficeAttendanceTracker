package com.reliableautomoto.attendance;

import java.math.BigDecimal;

public record PayrollLine(
        String employeeNumber,
        String employeeName,
        BigDecimal dailyPay,
        BigDecimal hourlyRate,
        int paidScheduledDays,
        BigDecimal expectedRegularHours,
        BigDecimal creditedRegularHours,
        BigDecimal absentHours,
        BigDecimal absentDeduction,
        int overtimeHours,
        BigDecimal overtimePay,
        BigDecimal manualDeductions,
        BigDecimal grossPay,
        BigDecimal netPay) { }
