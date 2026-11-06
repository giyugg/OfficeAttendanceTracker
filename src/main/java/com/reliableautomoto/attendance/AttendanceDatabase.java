package com.reliableautomoto.attendance;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0, 1)),
                    daily_pay_cents INTEGER NOT NULL DEFAULT 0 CHECK(daily_pay_cents >= 0)
                )
                """);
            if (!columnExists(connection, "employees", "daily_pay_cents")) {
                statement.execute("ALTER TABLE employees ADD COLUMN daily_pay_cents INTEGER NOT NULL DEFAULT 0 CHECK(daily_pay_cents >= 0)");
            }
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
                CREATE TABLE IF NOT EXISTS holidays (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    holiday_name TEXT NOT NULL CHECK(length(trim(holiday_name)) BETWEEN 1 AND 200),
                    holiday_date TEXT NOT NULL UNIQUE,
                    holiday_type TEXT NOT NULL CHECK(holiday_type IN ('PAID', 'UNPAID')),
                    remarks TEXT NOT NULL DEFAULT '' CHECK(length(remarks) <= 1000)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS payroll_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    period_start TEXT NOT NULL,
                    period_end TEXT NOT NULL,
                    finalized_at INTEGER NOT NULL,
                    CHECK(period_end >= period_start),
                    UNIQUE(period_start, period_end)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS payroll_lines (
                    payroll_run_id INTEGER NOT NULL REFERENCES payroll_runs(id) ON DELETE RESTRICT,
                    employee_number TEXT NOT NULL,
                    employee_name TEXT NOT NULL,
                    daily_pay_cents INTEGER NOT NULL,
                    hourly_rate TEXT NOT NULL,
                    paid_scheduled_days INTEGER NOT NULL,
                    expected_hours TEXT NOT NULL,
                    credited_hours TEXT NOT NULL,
                    absent_hours TEXT NOT NULL,
                    absent_deduction_cents INTEGER NOT NULL,
                    overtime_hours INTEGER NOT NULL,
                    overtime_pay_cents INTEGER NOT NULL,
                    manual_deductions_cents INTEGER NOT NULL,
                    gross_pay_cents INTEGER NOT NULL,
                    net_pay_cents INTEGER NOT NULL,
                    PRIMARY KEY(payroll_run_id, employee_number)
                )
                """);
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

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) if (column.equalsIgnoreCase(rows.getString("name"))) return true;
            return false;
        }
    }

    public void addEmployee(String rawNumber, String rawName) throws SQLException {
        addEmployee(rawNumber, rawName, 0);
    }

    public void addEmployee(String rawNumber, String rawName, long dailyPayCents) throws SQLException {
        String number = EmployeeNumbers.normalize(rawNumber);
        String name = validateName(rawName);
        if (dailyPayCents < 0) throw new IllegalArgumentException("Daily pay cannot be negative.");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO employees(employee_number, full_name, daily_pay_cents) VALUES (?, ?, ?) ON CONFLICT(employee_number) DO NOTHING")) {
            statement.setString(1, number);
            statement.setString(2, name);
            statement.setLong(3, dailyPayCents);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Employee number " + number + " already exists. Select it and choose Edit.");
            }
        }
    }

    public void updateEmployee(String number, String rawName, boolean active) throws SQLException {
        updateEmployee(number, rawName, active, null);
    }

    public void updateEmployee(String number, String rawName, boolean active, Long dailyPayCents) throws SQLException {
        String name = validateName(rawName);
        if (dailyPayCents != null && dailyPayCents < 0) throw new IllegalArgumentException("Daily pay cannot be negative.");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                dailyPayCents == null
                        ? "UPDATE employees SET full_name = ?, active = ? WHERE employee_number = ?"
                        : "UPDATE employees SET full_name = ?, active = ?, daily_pay_cents = ? WHERE employee_number = ?")) {
            statement.setString(1, name);
            statement.setBoolean(2, active);
            if (dailyPayCents == null) {
                statement.setString(3, number);
            } else {
                statement.setLong(3, dailyPayCents);
                statement.setString(4, number);
            }
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Employee no longer exists.");
        }
    }

    public void setEmployeeDailyPay(String number, long dailyPayCents) throws SQLException {
        if (dailyPayCents < 0) throw new IllegalArgumentException("Daily pay cannot be negative.");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE employees SET daily_pay_cents = ? WHERE employee_number = ?")) {
            statement.setLong(1, dailyPayCents);
            statement.setString(2, number);
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
             ResultSet rows = statement.executeQuery("SELECT employee_number, full_name, active, daily_pay_cents FROM employees ORDER BY full_name COLLATE NOCASE, employee_number")) {
            while (rows.next()) results.add(new Employee(rows.getString(1), rows.getString(2), rows.getBoolean(3), rows.getLong(4)));
        }
        return List.copyOf(results);
    }

    public List<Holiday> holidays(LocalDate from, LocalDate through) throws SQLException {
        List<Holiday> results = new ArrayList<>();
        String sql = "SELECT id, holiday_name, holiday_date, holiday_type, remarks FROM holidays "
                + "WHERE (? IS NULL OR holiday_date >= ?) AND (? IS NULL OR holiday_date <= ?) ORDER BY holiday_date, id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index : new int[]{1, 2}) {
                if (from == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, from.toString());
            }
            for (int index : new int[]{3, 4}) {
                if (through == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, through.toString());
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) results.add(new Holiday(rows.getLong(1), rows.getString(2),
                        LocalDate.parse(rows.getString(3)), HolidayType.valueOf(rows.getString(4)), rows.getString(5)));
            }
        }
        return List.copyOf(results);
    }

    // Holiday mutations are intentionally package-scoped and are exposed only by the administrator UI.
    Holiday addHoliday(String rawName, LocalDate date, HolidayType type, String rawRemarks) throws SQLException {
        String name = validateHolidayName(rawName);
        String remarks = validateRemarks(rawRemarks);
        if (date == null || type == null) throw new IllegalArgumentException("Holiday date and type are required.");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO holidays(holiday_name, holiday_date, holiday_type, remarks) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, date.toString());
            statement.setString(3, type.name());
            statement.setString(4, remarks);
            try {
                statement.executeUpdate();
            } catch (SQLException error) {
                if (error.getErrorCode() == 19) throw new IllegalArgumentException("A holiday is already configured for " + date + ".", error);
                throw error;
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Holiday was saved without an identifier.");
                return new Holiday(keys.getLong(1), name, date, type, remarks);
            }
        }
    }

    void updateHoliday(long id, String rawName, LocalDate date, HolidayType type, String rawRemarks) throws SQLException {
        String name = validateHolidayName(rawName);
        String remarks = validateRemarks(rawRemarks);
        if (date == null || type == null) throw new IllegalArgumentException("Holiday date and type are required.");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE holidays SET holiday_name = ?, holiday_date = ?, holiday_type = ?, remarks = ? WHERE id = ?")) {
            statement.setString(1, name);
            statement.setString(2, date.toString());
            statement.setString(3, type.name());
            statement.setString(4, remarks);
            statement.setLong(5, id);
            try {
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Holiday no longer exists.");
            } catch (SQLException error) {
                if (error.getErrorCode() == 19) throw new IllegalArgumentException("A holiday is already configured for " + date + ".", error);
                throw error;
            }
        }
    }

    void deleteHoliday(long id) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("DELETE FROM holidays WHERE id = ?")) {
            statement.setLong(1, id);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Holiday no longer exists.");
        }
    }

    private static String validateHolidayName(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 200
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Enter a holiday name of 1-200 characters, without control characters.");
        }
        return value.strip();
    }

    private static String validateRemarks(String value) {
        String remarks = value == null ? "" : value.strip();
        if (remarks.length() > 1000 || remarks.codePoints().anyMatch(character -> Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t')) {
            throw new IllegalArgumentException("Holiday remarks must be at most 1000 characters.");
        }
        return remarks;
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

    AttendanceRecord addManualAttendance(String rawNumber, Instant timeIn, Instant timeOut) throws SQLException {
        String number = EmployeeNumbers.normalize(rawNumber);
        if (timeIn == null || timeOut == null || !timeOut.isAfter(timeIn)) {
            throw new IllegalArgumentException("Time-out must be later than time-in.");
        }
        try (Connection connection = connect(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                String name;
                try (PreparedStatement employee = connection.prepareStatement(
                        "SELECT full_name FROM employees WHERE employee_number = ?")) {
                    employee.setString(1, number);
                    try (ResultSet row = employee.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Employee number not found.");
                        name = row.getString(1);
                    }
                }
                try (PreparedStatement overlap = connection.prepareStatement("""
                        SELECT 1 FROM attendance
                        WHERE employee_number = ? AND time_in < ? AND COALESCE(time_out, 9223372036854775807) > ?
                        LIMIT 1
                        """)) {
                    overlap.setString(1, number);
                    overlap.setLong(2, timeOut.toEpochMilli());
                    overlap.setLong(3, timeIn.toEpochMilli());
                    try (ResultSet row = overlap.executeQuery()) {
                        if (row.next()) throw new IllegalArgumentException(
                                "This attendance overlaps an existing shift for the employee.");
                    }
                }
                long id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO attendance(employee_number, time_in, time_out) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, number);
                    insert.setLong(2, timeIn.toEpochMilli());
                    insert.setLong(3, timeOut.toEpochMilli());
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Attendance was saved without an identifier.");
                        id = keys.getLong(1);
                    }
                }
                transaction.execute("COMMIT");
                return new AttendanceRecord(id, number, name, timeIn, timeOut);
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

    public PayrollReport finalizedPayroll(LocalDate start, LocalDate end) throws SQLException {
        try (Connection connection = connect()) {
            return finalizedPayroll(connection, start, end);
        }
    }

    public PayrollReport savedPayroll(long id) throws SQLException {
        try (Connection connection = connect()) {
            return savedPayroll(connection, id);
        }
    }

    public List<PayrollReport> savedPayrolls() throws SQLException {
        List<PayrollReport> reports = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, period_start, period_end, finalized_at FROM payroll_runs ORDER BY period_start DESC, period_end DESC, id DESC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong(1);
                LocalDate start = LocalDate.parse(rows.getString(2));
                LocalDate end = LocalDate.parse(rows.getString(3));
                Instant finalizedAt = Instant.ofEpochMilli(rows.getLong(4));
                reports.add(new PayrollReport(id, start, end, true, finalizedAt, payrollLines(connection, id)));
            }
        }
        return List.copyOf(reports);
    }

    public PayrollReport finalizePayroll(PayrollReport preview, Instant finalizedAt) throws SQLException {
        try (Connection connection = connect(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                PayrollReport existing = finalizedPayroll(connection, preview.periodStart(), preview.periodEnd());
                if (existing != null) {
                    Map<String, BigDecimal> deductions = new HashMap<>();
                    for (PayrollLine line : preview.lines()) {
                        deductions.put(line.employeeNumber(), line.manualDeductions());
                    }
                    PayrollReport updated = updatePayrollManualDeductions(connection, existing, deductions);
                    transaction.execute("COMMIT");
                    return updated;
                }
                long runId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO payroll_runs(period_start, period_end, finalized_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, preview.periodStart().toString());
                    statement.setString(2, preview.periodEnd().toString());
                    statement.setLong(3, finalizedAt.toEpochMilli());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Payroll was finalized without an identifier.");
                        runId = keys.getLong(1);
                    }
                }
                String insert = """
                    INSERT INTO payroll_lines(payroll_run_id, employee_number, employee_name, daily_pay_cents,
                      hourly_rate, paid_scheduled_days, expected_hours, credited_hours, absent_hours,
                      absent_deduction_cents, overtime_hours, overtime_pay_cents, manual_deductions_cents,
                      gross_pay_cents, net_pay_cents)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    for (PayrollLine line : preview.lines()) {
                        statement.setLong(1, runId);
                        statement.setString(2, line.employeeNumber());
                        statement.setString(3, line.employeeName());
                        statement.setLong(4, cents(line.dailyPay()));
                        statement.setString(5, line.hourlyRate().toPlainString());
                        statement.setInt(6, line.paidScheduledDays());
                        statement.setString(7, line.expectedRegularHours().toPlainString());
                        statement.setString(8, line.creditedRegularHours().toPlainString());
                        statement.setString(9, line.absentHours().toPlainString());
                        statement.setLong(10, cents(line.absentDeduction()));
                        statement.setInt(11, line.overtimeHours());
                        statement.setLong(12, cents(line.overtimePay()));
                        statement.setLong(13, cents(line.manualDeductions()));
                        statement.setLong(14, cents(line.grossPay()));
                        statement.setLong(15, cents(line.netPay()));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                transaction.execute("COMMIT");
                return new PayrollReport(runId, preview.periodStart(), preview.periodEnd(), true, finalizedAt, payrollLines(connection, runId));
            } catch (SQLException | RuntimeException error) {
                try { transaction.execute("ROLLBACK"); }
                catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
                throw error;
            }
        }
    }

    public PayrollReport updatePayrollManualDeductions(long payrollRunId, Map<String, BigDecimal> manualDeductions) throws SQLException {
        try (Connection connection = connect(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                PayrollReport existing = savedPayroll(connection, payrollRunId);
                if (existing == null) throw new IllegalArgumentException("Saved payroll no longer exists.");
                PayrollReport updated = updatePayrollManualDeductions(connection, existing,
                        manualDeductions == null ? Map.of() : manualDeductions);
                transaction.execute("COMMIT");
                return updated;
            } catch (SQLException | RuntimeException error) {
                try { transaction.execute("ROLLBACK"); }
                catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
                throw error;
            }
        }
    }

    private static PayrollReport updatePayrollManualDeductions(Connection connection, PayrollReport existing,
                                                               Map<String, BigDecimal> manualDeductions) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE payroll_lines
                SET manual_deductions_cents = ?, net_pay_cents = ?
                WHERE payroll_run_id = ? AND employee_number = ?
                """)) {
            for (PayrollLine line : existing.lines()) {
                BigDecimal rawDeduction = manualDeductions.getOrDefault(line.employeeNumber(), line.manualDeductions());
                BigDecimal deduction = validateManualDeduction(rawDeduction);
                BigDecimal netPay = PayrollCalculator.money(line.grossPay()
                        .subtract(line.absentDeduction())
                        .subtract(deduction)
                        .add(line.overtimePay()));
                update.setLong(1, cents(deduction));
                update.setLong(2, cents(netPay));
                update.setLong(3, existing.id());
                update.setString(4, line.employeeNumber());
                update.addBatch();
            }
            update.executeBatch();
        }
        return new PayrollReport(existing.id(), existing.periodStart(), existing.periodEnd(), true,
                existing.computedAt(), payrollLines(connection, existing.id()));
    }

    private static BigDecimal validateManualDeduction(BigDecimal deduction) {
        if (deduction == null) return BigDecimal.ZERO.setScale(2);
        if (deduction.signum() < 0 || deduction.scale() > 2) {
            throw new IllegalArgumentException("Manual deductions must be non-negative amounts with at most two decimals.");
        }
        return PayrollCalculator.money(deduction);
    }

    private static PayrollReport finalizedPayroll(Connection connection, LocalDate start, LocalDate end) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, finalized_at FROM payroll_runs WHERE period_start = ? AND period_end = ?")) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                long id = row.getLong(1);
                return new PayrollReport(id, start, end, true, Instant.ofEpochMilli(row.getLong(2)), payrollLines(connection, id));
            }
        }
    }

    private static PayrollReport savedPayroll(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT period_start, period_end, finalized_at FROM payroll_runs WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                LocalDate start = LocalDate.parse(row.getString(1));
                LocalDate end = LocalDate.parse(row.getString(2));
                Instant finalizedAt = Instant.ofEpochMilli(row.getLong(3));
                return new PayrollReport(id, start, end, true, finalizedAt, payrollLines(connection, id));
            }
        }
    }

    private static List<PayrollLine> payrollLines(Connection connection, long runId) throws SQLException {
        List<PayrollLine> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT employee_number, employee_name, daily_pay_cents, hourly_rate, paid_scheduled_days, "
                        + "expected_hours, credited_hours, absent_hours, absent_deduction_cents, overtime_hours, "
                        + "overtime_pay_cents, manual_deductions_cents, gross_pay_cents, net_pay_cents "
                        + "FROM payroll_lines WHERE payroll_run_id = ? ORDER BY employee_name COLLATE NOCASE, employee_number")) {
            statement.setLong(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    lines.add(new PayrollLine(rows.getString(1), rows.getString(2), amount(rows.getLong(3)),
                            new BigDecimal(rows.getString(4)), rows.getInt(5), new BigDecimal(rows.getString(6)),
                            new BigDecimal(rows.getString(7)), new BigDecimal(rows.getString(8)), amount(rows.getLong(9)),
                            rows.getInt(10), amount(rows.getLong(11)), amount(rows.getLong(12)), amount(rows.getLong(13)),
                            amount(rows.getLong(14))));
                }
            }
        }
        return List.copyOf(lines);
    }

    private static long cents(BigDecimal amount) { return amount.movePointRight(2).longValueExact(); }
    private static BigDecimal amount(long cents) { return BigDecimal.valueOf(cents, 2); }
}
