package com.reliableautomoto.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatorTest {
    @TempDir Path directory;

    @Test void acceptsOnlyTheTwoConfiguredAccounts() throws Exception {
        AttendanceDatabase database = new AttendanceDatabase(directory.resolve("accounts.db"));
        Authenticator authenticator = new Authenticator(database, "admin-secret", "station-secret");
        assertEquals(UserRole.ADMIN, authenticator.authenticate("admin", "admin-secret".toCharArray()).orElseThrow());
        assertEquals(UserRole.ATTENDANCE, authenticator.authenticate("attendance", "station-secret".toCharArray()).orElseThrow());
        assertTrue(authenticator.authenticate("administrator", "admin-secret".toCharArray()).isEmpty());
        assertTrue(authenticator.authenticate("ADMIN", "admin-secret".toCharArray()).isEmpty());
        assertTrue(authenticator.authenticate("admin", "wrong".toCharArray()).isEmpty());
        assertTrue(authenticator.authenticate(null, "admin-secret".toCharArray()).isEmpty());
        assertTrue(authenticator.authenticate("admin", null).isEmpty());

        Authenticator.StoredCredential admin = database.accountCredential(UserRole.ADMIN).orElseThrow();
        Authenticator.StoredCredential attendance = database.accountCredential(UserRole.ATTENDANCE).orElseThrow();
        assertEquals(16, admin.salt().length);
        assertEquals(32, admin.hash().length);
        assertFalse(java.util.Arrays.equals(admin.salt(), attendance.salt()));
        assertFalse(java.util.Arrays.equals(admin.hash(), attendance.hash()));
    }

    @Test void adminCanChangeEveryAccountPasswordAndChangesPersist() throws Exception {
        Path path = directory.resolve("changed.db");
        AttendanceDatabase database = new AttendanceDatabase(path);
        Authenticator authenticator = new Authenticator(database, "admin", "attendance");

        authenticator.changePassword(UserRole.ADMIN, UserRole.ADMIN, "new-admin-password".toCharArray());
        authenticator.changePassword(UserRole.ADMIN, UserRole.ATTENDANCE, "new-station-password".toCharArray());
        assertTrue(authenticator.authenticate("admin", "admin".toCharArray()).isEmpty());
        assertTrue(authenticator.authenticate("attendance", "attendance".toCharArray()).isEmpty());
        assertEquals(UserRole.ADMIN, authenticator.authenticate("admin", "new-admin-password".toCharArray()).orElseThrow());
        assertEquals(UserRole.ATTENDANCE, authenticator.authenticate("attendance", "new-station-password".toCharArray()).orElseThrow());

        Authenticator reopened = new Authenticator(new AttendanceDatabase(path), "admin", "attendance");
        assertEquals(UserRole.ADMIN, reopened.authenticate("admin", "new-admin-password".toCharArray()).orElseThrow());
        assertEquals(UserRole.ATTENDANCE, reopened.authenticate("attendance", "new-station-password".toCharArray()).orElseThrow());
    }

    @Test void attendanceAccountCannotChangePasswords() throws Exception {
        AttendanceDatabase database = new AttendanceDatabase(directory.resolve("restricted.db"));
        Authenticator authenticator = new Authenticator(database, "admin", "attendance");
        assertThrows(SecurityException.class, () -> authenticator.changePassword(
                UserRole.ATTENDANCE, UserRole.ATTENDANCE, "cannot-change-this".toCharArray()));
        assertEquals(UserRole.ATTENDANCE,
                authenticator.authenticate("attendance", "attendance".toCharArray()).orElseThrow());
    }

    @Test void environmentValuesSeedOnlyNewDatabases() throws Exception {
        AttendanceDatabase defaultsDatabase = new AttendanceDatabase(directory.resolve("defaults.db"));
        Authenticator defaults = Authenticator.fromEnvironment(defaultsDatabase, Map.of());
        assertEquals(UserRole.ADMIN, defaults.authenticate("admin", "admin".toCharArray()).orElseThrow());
        assertEquals(UserRole.ATTENDANCE, defaults.authenticate("attendance", "attendance".toCharArray()).orElseThrow());

        AttendanceDatabase configuredDatabase = new AttendanceDatabase(directory.resolve("configured.db"));
        Authenticator configured = Authenticator.fromEnvironment(configuredDatabase, Map.of(
                Authenticator.ADMIN_PASSWORD_ENV, "one",
                Authenticator.ATTENDANCE_PASSWORD_ENV, "two"));
        assertEquals(UserRole.ADMIN, configured.authenticate("admin", "one".toCharArray()).orElseThrow());
        assertEquals(UserRole.ATTENDANCE, configured.authenticate("attendance", "two".toCharArray()).orElseThrow());
    }

    @Test void invalidPasswordsAreRejected() throws Exception {
        AttendanceDatabase first = new AttendanceDatabase(directory.resolve("blank-admin.db"));
        AttendanceDatabase second = new AttendanceDatabase(directory.resolve("blank-station.db"));
        assertThrows(IllegalArgumentException.class, () -> new Authenticator(first, " ", "attendance"));
        assertThrows(IllegalArgumentException.class, () -> new Authenticator(second, "admin", ""));
        assertThrows(IllegalArgumentException.class, () -> Authenticator.validateNewPassword("short".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> Authenticator.validateNewPassword("valid\ninvalid".toCharArray()));
    }
}
