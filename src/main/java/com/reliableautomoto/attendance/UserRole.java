package com.reliableautomoto.attendance;

import java.util.Arrays;
import java.util.Optional;

public enum UserRole {
    ADMIN("admin", "Administrator"),
    ATTENDANCE("attendance", "Attendance station");

    private final String username;
    private final String displayName;

    UserRole(String username, String displayName) {
        this.username = username;
        this.displayName = displayName;
    }

    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<UserRole> forUsername(String username) {
        if (username == null) return Optional.empty();
        return Arrays.stream(values()).filter(role -> role.username.equals(username)).findFirst();
    }
}
