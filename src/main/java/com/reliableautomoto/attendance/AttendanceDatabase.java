package com.reliableautomoto.attendance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.reliableautomoto.attendance.ScanResult.Status.*;

/** Short-lived connections and IMMEDIATE transactions also serialize scans across app instances. */
public final class AttendanceDatabase {
    private final Path path;
    private final String url;

    public AttendanceDatabase(Path path) throws IOException, SQLException {
        this.path = path.toAbsolutePath().normalize();
        Files.createDirectories(this.path.getParent());
        this.url = "jdbc:sqlite:" + this.path;
        initialize();
    }

    public Path path() { return path; }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    private void initialize() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS employees (
                    employee_number TEXT PRIMARY KEY NOT NULL,
                    full_name TEXT NOT NULL CHECK(length(trim(full_name)) > 0),
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0, 1))
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS attendance (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    employee_number TEXT NOT NULL REFERENCES employees(employee_number),
                    time_in INTEGER NOT NULL,
                    time_out INTEGER,
                    CHECK(time_out IS NULL OR time_out >= time_in)
                )
                """);
            statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS one_open_attendance_per_employee
                ON attendance(employee_number) WHERE time_out IS NULL
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS attendance_employee_history ON attendance(employee_number, id DESC)");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS app_accounts (
                    username TEXT PRIMARY KEY NOT NULL CHECK(username IN ('admin', 'attendance')),
                    password_salt BLOB NOT NULL CHECK(length(password_salt) >= 16),
                    password_hash BLOB NOT NULL CHECK(length(password_hash) >= 32),
                    iterations INTEGER NOT NULL CHECK(iterations >= 100000)
                )
                """);
        }
    }

    void addAccountIfMissing(UserRole role, Authenticator.StoredCredential credential) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_accounts(username, password_salt, password_hash, iterations)
                VALUES (?, ?, ?, ?) ON CONFLICT(username) DO NOTHING
                """)) {
            statement.setString(1, role.username());
            statement.setBytes(2, credential.salt());
            statement.setBytes(3, credential.hash());
            statement.setInt(4, credential.iterations());
            statement.executeUpdate();
        }
    }

    java.util.Optional<Authenticator.StoredCredential> accountCredential(UserRole role) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT password_salt, password_hash, iterations FROM app_accounts WHERE username = ?")) {
            statement.setString(1, role.username());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new Authenticator.StoredCredential(
                        row.getBytes(1), row.getBytes(2), row.getInt(3)));
            }
        }
    }

    void updateAccountCredential(UserRole role, Authenticator.StoredCredential credential) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE app_accounts SET password_salt = ?, password_hash = ?, iterations = ? WHERE username = ?
                """)) {
            statement.setBytes(1, credential.salt());
            statement.setBytes(2, credential.hash());
            statement.setInt(3, credential.iterations());
            statement.setString(4, role.username());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Account no longer exists: " + role.username());
        }
    }

    public void addEmployee(String rawNumber, String rawName) throws SQLException {
        String number = EmployeeNumbers.normalize(rawNumber);
        String name = validateName(rawName);
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO employees(employee_number, full_name) VALUES (?, ?) ON CONFLICT(employee_number) DO NOTHING")) {
            statement.setString(1, number);
            statement.setString(2, name);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Employee number " + number + " already exists. Select it and choose Edit.");
            }
        }
    }

    public void updateEmployee(String number, String rawName, boolean active) throws SQLException {
        String name = validateName(rawName);
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE employees SET full_name = ?, active = ? WHERE employee_number = ?")) {
            statement.setString(1, name);
            statement.setBoolean(2, active);
            statement.setString(3, number);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Employee no longer exists.");
        }
    }

    private static String validateName(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 200
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Enter an employee name of 1–200 characters, without control characters.");
        }
        return value.strip();
    }

    public List<Employee> employees() throws SQLException {
        List<Employee> results = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT employee_number, full_name, active FROM employees ORDER BY full_name COLLATE NOCASE, employee_number")) {
            while (rows.next()) results.add(new Employee(rows.getString(1), rows.getString(2), rows.getBoolean(3)));
        }
        return List.copyOf(results);
    }

    public ScanResult recordScan(String number, Instant now, Duration cooldown) throws SQLException {
        // Validation also happens here so callers cannot bypass it through a different input method.
        number = EmployeeNumbers.normalize(number);
        try (Connection connection = connect(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                ScanResult result = recordInTransaction(connection, number, now, cooldown);
                transaction.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException error) {
                try { transaction.execute("ROLLBACK"); }
                catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
                throw error;
            }
        }
    }

    private ScanResult recordInTransaction(Connection connection, String number, Instant now, Duration cooldown) throws SQLException {
        String name;
        try (PreparedStatement statement = connection.prepareStatement("SELECT full_name, active FROM employees WHERE employee_number = ?")) {
            statement.setString(1, number);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return new ScanResult(UNKNOWN_EMPLOYEE, number, "", now, "Employee number not found. Ask an administrator to register it.");
                name = row.getString(1);
                if (!row.getBoolean(2)) return new ScanResult(INACTIVE_EMPLOYEE, number, name, now, "This employee is inactive. Ask an administrator to reactivate the record.");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(time_out, time_in) FROM attendance WHERE employee_number = ? ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, number);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    Instant previous = Instant.ofEpochMilli(row.getLong(1));
                    Duration elapsed = Duration.between(previous, now);
                    if (elapsed.isNegative()) return new ScanResult(DUPLICATE, number, name, now, "The system clock is earlier than the last scan. Check the computer's clock.");
                    if (elapsed.compareTo(cooldown) < 0) {
                        long wait = (cooldown.toMillis() - elapsed.toMillis() + 999) / 1000;
                        return new ScanResult(DUPLICATE, number, name, now, "Already recorded. Wait " + wait + " second(s) before scanning again.");
                    }
                }
            }
        }
        long openId = -1;
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM attendance WHERE employee_number = ? AND time_out IS NULL")) {
            statement.setString(1, number);
            try (ResultSet row = statement.executeQuery()) { if (row.next()) openId = row.getLong(1); }
        }
        if (openId >= 0) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE attendance SET time_out = ? WHERE id = ?")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setLong(2, openId);
                statement.executeUpdate();
            }
            return new ScanResult(TIME_OUT, number, name, now, "Time-out recorded successfully.");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO attendance(employee_number, time_in) VALUES (?, ?)")) {
            statement.setString(1, number);
            statement.setLong(2, now.toEpochMilli());
            statement.executeUpdate();
        }
        return new ScanResult(TIME_IN, number, name, now, "Time-in recorded successfully.");
    }

    /** Null bounds select all history; otherwise use a half-open interval by time-in. */
    public List<AttendanceRecord> attendance(Instant from, Instant until, boolean openOnly) throws SQLException {
        List<AttendanceRecord> results = new ArrayList<>();
        String sql = """
            SELECT a.id, a.employee_number, e.full_name, a.time_in, a.time_out
            FROM attendance a JOIN employees e ON e.employee_number = a.employee_number
            WHERE (? IS NULL OR a.time_in >= ?) AND (? IS NULL OR a.time_in < ?)
              AND (? = 0 OR a.time_out IS NULL)
            ORDER BY a.time_in DESC, a.id DESC
            """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index : new int[]{1, 2}) {
                if (from == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, from.toEpochMilli());
            }
            for (int index : new int[]{3, 4}) {
                if (until == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, until.toEpochMilli());
            }
            statement.setBoolean(5, openOnly);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long out = rows.getLong(5);
                    Instant timeOut = rows.wasNull() ? null : Instant.ofEpochMilli(out);
                    results.add(new AttendanceRecord(rows.getLong(1), rows.getString(2), rows.getString(3),
                            Instant.ofEpochMilli(rows.getLong(4)), timeOut));
                }
            }
        }
        return List.copyOf(results);
    }
}
