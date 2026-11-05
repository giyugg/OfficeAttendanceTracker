package com.reliableautomoto.attendance;

import java.time.LocalDate;

public record Holiday(long id, String name, LocalDate date, HolidayType type, String remarks) { }
