package com.reliableautomoto.attendance;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public final class Authenticator {
    static final String ADMIN_PASSWORD_ENV = "RAM_ADMIN_PASSWORD";
    static final String ATTENDANCE_PASSWORD_ENV = "RAM_ATTENDANCE_PASSWORD";
    private static final int ITERATIONS = 210_000;
    private static final int HASH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int MINIMUM_NEW_PASSWORD_LENGTH = 8;

    private final AttendanceDatabase database;
    private final SecureRandom random = new SecureRandom();

    public Authenticator(AttendanceDatabase database, String initialAdminPassword,
                         String initialAttendancePassword) throws SQLException {
        this.database = java.util.Objects.requireNonNull(database);
        char[] adminPassword = requireInitialPassword(initialAdminPassword, ADMIN_PASSWORD_ENV);
        char[] attendancePassword;
        try {
            attendancePassword = requireInitialPassword(initialAttendancePassword, ATTENDANCE_PASSWORD_ENV);
        } catch (RuntimeException error) {
            Arrays.fill(adminPassword, '\0');
            throw error;
        }
        try {
            seed(UserRole.ADMIN, adminPassword);
            seed(UserRole.ATTENDANCE, attendancePassword);
        } finally {
            Arrays.fill(adminPassword, '\0');
            Arrays.fill(attendancePassword, '\0');
        }
    }

    public static Authenticator fromEnvironment(AttendanceDatabase database) throws SQLException {
        return fromEnvironment(database, System.getenv());
    }

    static Authenticator fromEnvironment(AttendanceDatabase database, Map<String, String> environment) throws SQLException {
        return new Authenticator(database,
                environment.getOrDefault(ADMIN_PASSWORD_ENV, UserRole.ADMIN.username()),
                environment.getOrDefault(ATTENDANCE_PASSWORD_ENV, UserRole.ATTENDANCE.username()));
    }

    public Optional<UserRole> authenticate(String username, char[] password) throws SQLException {
        Optional<UserRole> role = UserRole.forUsername(username);
        if (role.isEmpty() || password == null) return Optional.empty();
        Optional<StoredCredential> credential = database.accountCredential(role.get());
        if (credential.isEmpty()) return Optional.empty();
        byte[] supplied = derive(password, credential.get().salt(), credential.get().iterations());
        try {
            return MessageDigest.isEqual(credential.get().hash(), supplied) ? role : Optional.empty();
        } finally {
            Arrays.fill(supplied, (byte) 0);
        }
    }

    public void changePassword(UserRole actor, UserRole account, char[] newPassword) throws SQLException {
        if (actor != UserRole.ADMIN) throw new SecurityException("Only the administrator can change account passwords.");
        validateNewPassword(newPassword);
        database.updateAccountCredential(java.util.Objects.requireNonNull(account), createCredential(newPassword));
    }

    public static void validateNewPassword(char[] password) {
        if (password == null || password.length < MINIMUM_NEW_PASSWORD_LENGTH || password.length > 200) {
            throw new IllegalArgumentException("Use a password between 8 and 200 characters.");
        }
        for (char character : password) {
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("The password cannot contain control characters.");
            }
        }
    }

    private void seed(UserRole role, char[] initialPassword) throws SQLException {
        if (database.accountCredential(role).isEmpty()) {
            database.addAccountIfMissing(role, createCredential(initialPassword));
        }
    }

    private StoredCredential createCredential(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new StoredCredential(salt, derive(password, salt, ITERATIONS), ITERATIONS);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Secure password hashing is unavailable.", error);
        } finally {
            specification.clearPassword();
        }
    }

    private static char[] requireInitialPassword(String password, String setting) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(setting + " must not be blank.");
        }
        return password.toCharArray();
    }

    record StoredCredential(byte[] salt, byte[] hash, int iterations) {
        StoredCredential {
            salt = salt.clone();
            hash = hash.clone();
        }

        @Override public byte[] salt() { return salt.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }
}
