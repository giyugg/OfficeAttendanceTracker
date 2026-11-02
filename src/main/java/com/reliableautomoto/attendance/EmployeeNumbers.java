package com.reliableautomoto.attendance;

public final class EmployeeNumbers {
    private EmployeeNumbers() { }

    public static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("An employee number is required.");
        String number = value.strip();
        if (number.isEmpty() || number.length() > 100 || number.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Use an employee number of 1–100 characters, with no line breaks or control characters.");
        }
        return number;
    }
}
