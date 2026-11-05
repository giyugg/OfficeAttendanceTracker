package com.reliableautomoto.attendance;

import java.math.BigDecimal;

public record Employee(String number, String name, boolean active, long dailyPayCents) {
    public Employee(String number, String name, boolean active) {
        this(number, name, active, 0);
    }

    public BigDecimal dailyPay() {
        return BigDecimal.valueOf(dailyPayCents, 2);
    }
}
