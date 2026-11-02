package com.reliableautomoto.attendance;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @Test void defaultsAndOverrides() {
        AppConfig defaults = AppConfig.parse(new String[]{});
        assertEquals(Path.of("data", "attendance.db"), defaults.database());
        assertEquals(ZoneId.of("Asia/Manila"), defaults.zone());
        assertFalse(defaults.checkOnly());
        AppConfig custom = AppConfig.parse(new String[]{"--db=custom.db", "--zone=UTC", "--check"});
        assertEquals(Path.of("custom.db"), custom.database());
        assertEquals(ZoneId.of("UTC"), custom.zone());
        assertTrue(custom.checkOnly());
    }

    @Test void invalidArgumentsFailClearly() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(new String[]{"--unknown"}));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(new String[]{"--db="}));
        assertThrows(java.time.DateTimeException.class, () -> AppConfig.parse(new String[]{"--zone=invalid"}));
    }
}
