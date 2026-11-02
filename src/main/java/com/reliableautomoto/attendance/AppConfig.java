package com.reliableautomoto.attendance;

import java.nio.file.Path;
import java.time.ZoneId;

public record AppConfig(Path database, ZoneId zone, boolean checkOnly) {
    public static AppConfig parse(String[] args) {
        Path database = Path.of("data", "attendance.db");
        ZoneId zone = ZoneId.of("Asia/Manila");
        boolean check = false;
        for (String arg : args) {
            if (arg.startsWith("--db=")) {
                String value = arg.substring(5);
                if (value.isBlank()) throw new IllegalArgumentException("--db requires a file path.");
                database = Path.of(value);
            } else if (arg.startsWith("--zone=")) {
                zone = ZoneId.of(arg.substring(7));
            } else if (arg.equals("--check")) {
                check = true;
            } else {
                throw new IllegalArgumentException("Unknown option: " + arg + ". Options: --db=<path> --zone=<timezone> --check");
            }
        }
        return new AppConfig(database, zone, check);
    }
}
