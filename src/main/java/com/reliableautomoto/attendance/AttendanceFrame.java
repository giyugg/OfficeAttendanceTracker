package com.reliableautomoto.attendance;

import com.github.sarxos.webcam.Webcam;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class AttendanceFrame extends JFrame {
    private static final Color BACKGROUND = new Color(242, 245, 248);
    private static final Color NAVY = new Color(23, 42, 66);
    private static final Color MUTED = new Color(88, 104, 123);
    private static final Color GREEN = new Color(16, 116, 80);
    private static final Color RED = new Color(170, 43, 43);
    private final AttendanceDatabase database;
    private final AttendanceService service;
    private final ZoneId zone;
    private final UserRole role;
    private final Authenticator authenticator;
    private final Runnable onLogout;
    private final DateTimeFormatter timestampFormat;
    private final ExecutorService databaseWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "attendance-database");
        thread.setDaemon(true);
        return thread;
    });
    private final CameraScanner scanner = new CameraScanner();
    private final PreviewPanel preview = new PreviewPanel();
    private final JComboBox<Webcam> cameraChoice = new JComboBox<>();
    private final JButton findCameras = new JButton("Find cameras");
    private final JButton startCamera = new JButton("Start camera");
    private final JButton stopCamera = new JButton("Stop");
    private final JLabel cameraStatus = new JLabel("Use a webcam or connect a USB QR scanner.");
    private final JTextField scannerInput = new JTextField();
    private final JLabel resultTitle = new JLabel("Ready to scan");
    private final JLabel resultName = new JLabel("Present your employee QR code");
    private final JTextArea resultDetail = new JTextArea("The first scan records time-in. Scan again to record time-out.");
    private final JLabel clockLabel = new JLabel();
    private final JLabel activeCount = new JLabel("0");
    private final JLabel openCount = new JLabel("0");
    private final JLabel todayCount = new JLabel("0");
    private final DefaultTableModel attendanceModel = model("Employee #", "Employee name", "Time in", "Time out", "Duration", "Status");
    private final JTable attendanceTable = table(attendanceModel);
    private final DefaultTableModel employeeModel = model("Employee #", "Employee name", "Status");
    private final JTable employeeTable = table(employeeModel);
    private final JComboBox<String> scope = new JComboBox<>(new String[]{"Today", "Selected date", "All records", "Currently in"});
    private final JTextField dateInput = new JTextField(10);
    private final JLabel historyCount = new JLabel("No attendance records yet");
    private final JTextField historySearch = new JTextField(16);
    private List<AttendanceRecord> displayedRecords = List.of();
    private List<Employee> displayedEmployees = List.of();
    private final Timer clockTimer;
    private final Timer refreshTimer;
    private long refreshVersion;
    private boolean closing;

    public AttendanceFrame(AttendanceDatabase database, AttendanceService service, ZoneId zone) {
        this(database, service, zone, UserRole.ADMIN, null, null);
    }

    public AttendanceFrame(AttendanceDatabase database, AttendanceService service, ZoneId zone,
                           UserRole role, Runnable onLogout) {
        this(database, service, zone, role, null, onLogout);
    }

    public AttendanceFrame(AttendanceDatabase database, AttendanceService service, ZoneId zone,
                           UserRole role, Authenticator authenticator, Runnable onLogout) {
        super("Reliable AutoMoto | QR Attendance");
        this.database = database;
        this.service = service;
        this.zone = zone;
        this.role = java.util.Objects.requireNonNull(role);
        this.authenticator = authenticator;
        this.onLogout = onLogout;
        timestampFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy  HH:mm:ss").withZone(zone);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 740));
        setSize(1260, 870);
        setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(BACKGROUND);
        root.setBorder(new EmptyBorder(22, 24, 16, 24));
        root.add(header(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(font(14, Font.BOLD));
        tabs.addTab("  Attendance station  ", attendancePanel());
        if (role == UserRole.ADMIN) tabs.addTab("  Employees  ", employeesPanel());
        root.add(tabs, BorderLayout.CENTER);
        JLabel footer = new JLabel("Database: " + database.path() + "   |   Timezone: " + zone);
        footer.setForeground(MUTED);
        footer.setFont(font(11, Font.PLAIN));
        footer.setToolTipText(database.path().toString());
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);

        clockTimer = new Timer(1000, event -> updateClock());
        clockTimer.start();
        refreshTimer = new Timer(30_000, event -> refreshData());
        refreshTimer.start();
        updateClock();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                closeResources();
            }
            @Override public void windowOpened(WindowEvent event) { scannerInput.requestFocusInWindow(); }
        });
        refreshData();
    }

    private void closeResources() {
        if (closing) return;
        closing = true;
        clockTimer.stop();
        refreshTimer.stop();
        scanner.close();
        databaseWorker.shutdown();
    }

    @Override public void dispose() {
        closeResources();
        super.dispose();
    }

    private JPanel header() {
        JPanel panel = transparent(new BorderLayout(15, 0));
        JPanel title = transparent(new GridLayout(2, 1, 0, 4));
        title.add(label("RELIABLE AUTOMOTO", 12, Font.BOLD, MUTED));
        title.add(label("Employee attendance", 28, Font.BOLD, NAVY));
        panel.add(title, BorderLayout.WEST);
        clockLabel.setFont(font(14, Font.PLAIN));
        clockLabel.setForeground(NAVY);
        clockLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel session = transparent(new BorderLayout(0, 5));
        session.add(clockLabel, BorderLayout.NORTH);
        JPanel account = transparent(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        account.add(label("Signed in: " + role.username(), 11, Font.PLAIN, MUTED));
        if (role == UserRole.ADMIN && authenticator != null) {
            JButton passwords = new JButton("Change passwords");
            passwords.addActionListener(event -> changePassword());
            account.add(passwords);
        }
        if (onLogout != null) {
            JButton logout = new JButton("Log out");
            logout.addActionListener(event -> logout());
            account.add(logout);
        }
        session.add(account, BorderLayout.SOUTH);
        panel.add(session, BorderLayout.EAST);
        return panel;
    }

    private void logout() {
        if (JOptionPane.showConfirmDialog(this, "Log out of the " + role.displayName() + " account?",
                "Log out", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        dispose();
        if (onLogout != null) onLogout.run();
    }

    private void changePassword() {
        JComboBox<String> account = new JComboBox<>(new String[]{
                UserRole.ADMIN.username(), UserRole.ATTENDANCE.username()
        });
        JPasswordField newPassword = new JPasswordField(24);
        JPasswordField confirmation = new JPasswordField(24);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 7));
        form.add(new JLabel("Account"));
        form.add(account);
        form.add(new JLabel("New password (at least 8 characters)"));
        form.add(newPassword);
        form.add(new JLabel("Confirm new password"));
        form.add(confirmation);

        while (JOptionPane.showConfirmDialog(this, form, "Change account password",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            char[] entered = newPassword.getPassword();
            char[] repeated = confirmation.getPassword();
            try {
                Authenticator.validateNewPassword(entered);
                if (!java.util.Arrays.equals(entered, repeated)) {
                    throw new IllegalArgumentException("The passwords do not match.");
                }
            } catch (IllegalArgumentException error) {
                java.util.Arrays.fill(entered, '\0');
                java.util.Arrays.fill(repeated, '\0');
                newPassword.setText("");
                confirmation.setText("");
                JOptionPane.showMessageDialog(this, error.getMessage(), "Check password", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            java.util.Arrays.fill(repeated, '\0');
            UserRole selected = account.getSelectedIndex() == 0 ? UserRole.ADMIN : UserRole.ATTENDANCE;
            char[] passwordForUpdate = entered.clone();
            java.util.Arrays.fill(entered, '\0');
            runDatabase(() -> {
                try {
                    authenticator.changePassword(role, selected, passwordForUpdate);
                    return selected;
                } finally {
                    java.util.Arrays.fill(passwordForUpdate, '\0');
                }
            }, changed -> JOptionPane.showMessageDialog(this,
                    "Password changed for the " + changed.username() + " account."));
            break;
        }
    }

    private JPanel attendancePanel() {
        JPanel panel = transparent(new BorderLayout(0, 16));
        panel.setBorder(new EmptyBorder(18, 0, 0, 0));
        JPanel stats = transparent(new GridLayout(1, 3, 14, 0));
        stats.add(stat("ACTIVE EMPLOYEES", activeCount));
        stats.add(stat("CURRENTLY CLOCKED IN", openCount));
        stats.add(stat("SHIFTS STARTED TODAY", todayCount));
        panel.add(stats, BorderLayout.NORTH);

        JPanel station = transparent(new GridLayout(1, 2, 16, 0));
        station.add(cameraPanel());
        station.add(scanPanel());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, station, historyPanel());
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(10);
        split.setResizeWeight(0.55);
        split.setDividerLocation(330);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel cameraPanel() {
        JPanel panel = card(new BorderLayout(0, 8));
        panel.add(label("Scan employee QR", 17, Font.BOLD, NAVY), BorderLayout.NORTH);
        preview.setPreferredSize(new Dimension(400, 190));
        preview.setMinimumSize(new Dimension(200, 100));
        panel.add(preview, BorderLayout.CENTER);
        JPanel bottom = transparent(new BorderLayout(0, 5));
        JPanel controls = transparent(new BorderLayout(6, 0));
        cameraChoice.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value instanceof Webcam camera ? camera.getName() : "Select a camera");
                return this;
            }
        });
        cameraChoice.setPrototypeDisplayValue(null);
        cameraChoice.setPreferredSize(new Dimension(150, 28));
        controls.add(cameraChoice, BorderLayout.CENTER);
        JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        buttons.add(findCameras);
        buttons.add(startCamera);
        buttons.add(stopCamera);
        controls.add(buttons, BorderLayout.EAST);
        startCamera.setEnabled(false);
        stopCamera.setEnabled(false);
        findCameras.addActionListener(event -> discoverCameras());
        startCamera.addActionListener(event -> startSelectedCamera());
        stopCamera.addActionListener(event -> {
            scanner.stop();
            stopCamera.setEnabled(false);
            cameraStatus.setText("Stopping camera…");
        });
        cameraStatus.setFont(font(11, Font.PLAIN));
        cameraStatus.setForeground(MUTED);
        bottom.add(controls, BorderLayout.NORTH);
        bottom.add(cameraStatus, BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel scanPanel() {
        JPanel panel = card(new BorderLayout(0, 14));
        JPanel feedback = transparent(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(4, 0, 8, 0);
        resultTitle.setFont(font(26, Font.BOLD));
        resultTitle.setForeground(NAVY);
        constraints.gridy = 0;
        feedback.add(resultTitle, constraints);
        resultName.setFont(font(18, Font.BOLD));
        resultName.setForeground(NAVY);
        constraints.gridy++;
        feedback.add(resultName, constraints);
        resultDetail.setEditable(false);
        resultDetail.setOpaque(false);
        resultDetail.setLineWrap(true);
        resultDetail.setWrapStyleWord(true);
        resultDetail.setFont(font(13, Font.PLAIN));
        resultDetail.setForeground(MUTED);
        resultDetail.setRows(3);
        constraints.gridy++;
        feedback.add(resultDetail, constraints);
        panel.add(feedback, BorderLayout.CENTER);

        JPanel input = transparent(new BorderLayout(0, 7));
        input.add(label("USB scanner / employee number", 12, Font.BOLD, MUTED), BorderLayout.NORTH);
        JPanel entry = transparent(new BorderLayout(7, 0));
        scannerInput.setFont(font(19, Font.PLAIN));
        scannerInput.setToolTipText("Click here, then scan. Configure your USB scanner to send Enter after the employee number.");
        scannerInput.addActionListener(event -> submitScan(scannerInput.getText()));
        entry.add(scannerInput, BorderLayout.CENTER);
        JButton record = new JButton("Record scan");
        record.addActionListener(event -> submitScan(scannerInput.getText()));
        entry.add(record, BorderLayout.EAST);
        input.add(entry, BorderLayout.CENTER);
        input.add(label("Repeat scans are ignored for 10 seconds. Remove the QR between scans.", 11, Font.PLAIN, MUTED), BorderLayout.SOUTH);
        panel.add(input, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel historyPanel() {
        JPanel panel = card(new BorderLayout(0, 10));
        panel.setMinimumSize(new Dimension(600, 170));
        JPanel top = transparent(new BorderLayout(8, 0));
        top.add(label("Attendance history", 17, Font.BOLD, NAVY), BorderLayout.WEST);
        JPanel controls = transparent(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        dateInput.setText(LocalDate.now(zone).toString());
        dateInput.setEnabled(false);
        dateInput.setToolTipText("YYYY-MM-DD; shifts are selected by their time-in date.");
        controls.add(scope);
        controls.add(dateInput);
        JButton apply = new JButton("Refresh");
        apply.addActionListener(event -> refreshData());
        dateInput.addActionListener(event -> refreshData());
        scope.addActionListener(event -> {
            dateInput.setEnabled(scope.getSelectedIndex() == 1);
            refreshData();
        });
        controls.add(apply);
        JButton export = new JButton("Export CSV");
        export.addActionListener(event -> exportCsv());
        controls.add(export);
        top.add(controls, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(attendanceTable), BorderLayout.CENTER);
        JPanel bottom = transparent(new BorderLayout());
        historyCount.setFont(font(11, Font.PLAIN));
        historyCount.setForeground(MUTED);
        bottom.add(historyCount, BorderLayout.WEST);
        JPanel search = transparent(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        search.add(label("Find employee", 11, Font.PLAIN, MUTED));
        search.add(historySearch);
        installSearch(attendanceTable, historySearch);
        bottom.add(search, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel employeesPanel() {
        JPanel panel = card(new BorderLayout(0, 15));
        JPanel top = transparent(new BorderLayout(15, 0));
        JPanel intro = transparent(new GridLayout(2, 1, 0, 5));
        intro.add(label("Employee directory", 22, Font.BOLD, NAVY));
        intro.add(label("Register the employee number exactly as it appears in the existing QR code.", 13, Font.PLAIN, MUTED));
        top.add(intro, BorderLayout.CENTER);
        JPanel controls = transparent(new FlowLayout(FlowLayout.RIGHT));
        JButton add = new JButton("Add employee");
        add.addActionListener(event -> editEmployee(null));
        JButton edit = new JButton("Edit selected");
        edit.addActionListener(event -> {
            int selected = employeeTable.getSelectedRow();
            if (selected < 0) {
                JOptionPane.showMessageDialog(this, "Select an employee first.");
                return;
            }
            editEmployee(displayedEmployees.get(employeeTable.convertRowIndexToModel(selected)));
        });
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refreshData());
        controls.add(add);
        controls.add(edit);
        controls.add(refresh);
        top.add(controls, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(employeeTable), BorderLayout.CENTER);
        JPanel bottom = transparent(new BorderLayout());
        bottom.add(label("Inactive employees cannot scan. Reactivate an employee before closing an open shift.", 12, Font.PLAIN, MUTED), BorderLayout.WEST);
        JTextField search = new JTextField(18);
        search.setToolTipText("Find an employee by name or number");
        installSearch(employeeTable, search);
        bottom.add(search, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private void editEmployee(Employee employee) {
        JTextField number = new JTextField(employee == null ? "" : employee.number(), 24);
        JTextField name = new JTextField(employee == null ? "" : employee.name(), 24);
        JCheckBox active = new JCheckBox("Active employee", employee == null || employee.active());
        number.setEditable(employee == null);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 7));
        form.add(new JLabel("Employee number (leading zeros are preserved)"));
        form.add(number);
        form.add(new JLabel("Full name"));
        form.add(name);
        if (employee != null) form.add(active);
        while (JOptionPane.showConfirmDialog(this, form, employee == null ? "Add employee" : "Edit employee",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String employeeNumber;
            try {
                employeeNumber = EmployeeNumbers.normalize(number.getText());
                if (name.getText().isBlank()) throw new IllegalArgumentException("Enter the employee's name.");
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(this, error.getMessage(), "Check employee details", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            String fullName = name.getText();
            boolean isActive = active.isSelected();
            runDatabase(() -> {
                if (employee == null) database.addEmployee(employeeNumber, fullName);
                else database.updateEmployee(employee.number(), fullName, isActive);
                return employeeNumber;
            }, ignored -> refreshData());
            break;
        }
    }

    private void submitScan(String value) {
        if (closing) return;
        scannerInput.setText("");
        resultTitle.setText("Checking employee…");
        resultTitle.setForeground(NAVY);
        resultName.setText("Waiting for database confirmation");
        resultDetail.setText("Please wait for the scan result.");
        runDatabase(() -> service.scan(value), result -> {
            resultTitle.setText(switch (result.status()) {
                case TIME_IN -> "Time-in successful";
                case TIME_OUT -> "Time-out successful";
                case DUPLICATE -> "Scan ignored";
                case UNKNOWN_EMPLOYEE -> "Employee not found";
                case INACTIVE_EMPLOYEE -> "Inactive employee";
                case INVALID -> "Invalid employee number";
            });
            resultTitle.setForeground(result.successful() ? GREEN : result.status() == ScanResult.Status.DUPLICATE ? MUTED : RED);
            resultName.setText(result.employeeName().isEmpty() ? "No attendance recorded" : result.employeeName() + "  ·  " + result.employeeNumber());
            resultDetail.setText(result.message() + "\n" + timestampFormat.format(result.timestamp()));
            if (result.successful()) {
                Toolkit.getDefaultToolkit().beep();
                refreshData();
            }
            scannerInput.requestFocusInWindow();
        }, error -> {
            resultTitle.setText("Scan failed");
            resultTitle.setForeground(RED);
            resultName.setText("Attendance has not been confirmed");
            resultDetail.setText("The database could not confirm this scan. Check the error and attendance history before retrying.");
            showError(error);
        });
    }

    private void discoverCameras() {
        findCameras.setEnabled(false);
        startCamera.setEnabled(false);
        cameraStatus.setText("Looking for cameras…");
        new SwingWorker<List<Webcam>, Void>() {
            @Override protected List<Webcam> doInBackground() throws Exception { return CameraScanner.discover(); }
            @Override protected void done() {
                if (closing) return;
                findCameras.setEnabled(true);
                try {
                    List<Webcam> cameras = get();
                    cameraChoice.removeAllItems();
                    cameras.forEach(cameraChoice::addItem);
                    startCamera.setEnabled(!cameras.isEmpty());
                    cameraStatus.setText(cameras.isEmpty() ? "No webcam found. Connect one or use a USB QR scanner." : "Choose a camera and click Start camera.");
                } catch (Exception | LinkageError error) {
                    cameraStatus.setText("Camera discovery failed. You can still use a USB QR scanner.");
                    showError(error);
                }
            }
        }.execute();
    }

    private void startSelectedCamera() {
        Webcam camera = (Webcam) cameraChoice.getSelectedItem();
        if (camera == null) return;
        startCamera.setEnabled(false);
        findCameras.setEnabled(false);
        cameraChoice.setEnabled(false);
        stopCamera.setEnabled(true);
        scanner.start(camera, new CameraScanner.Listener() {
            @Override public void frame(BufferedImage image) { onUi(() -> preview.setFrame(image)); }
            @Override public void code(String employeeNumber) { onUi(() -> submitScan(employeeNumber)); }
            @Override public void status(String message) { onUi(() -> { cameraStatus.setText(message); cameraStatus.setToolTipText(message); }); }
            @Override public void stopped() {
                onUi(() -> {
                    preview.setFrame(null);
                    startCamera.setEnabled(true);
                    findCameras.setEnabled(true);
                    cameraChoice.setEnabled(true);
                    stopCamera.setEnabled(false);
                });
            }
        });
    }

    private void refreshData() {
        if (closing) return;
        int selection = scope.getSelectedIndex();
        LocalDate today = LocalDate.now(zone);
        LocalDate selectedDate = today;
        if (selection == 1) {
            try { selectedDate = LocalDate.parse(dateInput.getText().strip()); }
            catch (DateTimeParseException error) {
                historyCount.setText("Enter a valid date: YYYY-MM-DD");
                return;
            }
        }
        Instant from = selection <= 1 ? selectedDate.atStartOfDay(zone).toInstant() : null;
        Instant until = selection <= 1 ? selectedDate.plusDays(1).atStartOfDay(zone).toInstant() : null;
        long version = ++refreshVersion;
        runDatabase(() -> new Snapshot(database.employees(), database.attendance(from, until, selection == 3),
                database.attendance(null, null, true).size(), database.attendance(today.atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant(), false).size()), snapshot -> {
            if (version != refreshVersion) return;
            displayedEmployees = snapshot.employees();
            displayedRecords = snapshot.records();
            employeeModel.setRowCount(0);
            for (Employee employee : displayedEmployees) {
                employeeModel.addRow(new Object[]{employee.number(), employee.name(), employee.active() ? "Active" : "Inactive"});
            }
            attendanceModel.setRowCount(0);
            for (AttendanceRecord record : displayedRecords) {
                attendanceModel.addRow(new Object[]{record.employeeNumber(), record.employeeName(),
                        timestampFormat.format(record.timeIn()), record.timeOut() == null ? "—" : timestampFormat.format(record.timeOut()),
                        record.duration(), record.timeOut() == null ? "IN" : "OUT"});
            }
            activeCount.setText(Long.toString(displayedEmployees.stream().filter(Employee::active).count()));
            openCount.setText(Integer.toString(snapshot.openCount()));
            todayCount.setText(Integer.toString(snapshot.todayCount()));
            historyCount.setText(displayedRecords.size() + " record(s) · Selected by time-in date · " + zone);
        });
    }

    private void exportCsv() {
        List<AttendanceRecord> records = new ArrayList<>();
        for (int row = 0; row < attendanceTable.getRowCount(); row++) {
            records.add(displayedRecords.get(attendanceTable.convertRowIndexToModel(row)));
        }
        if (records.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no visible attendance records to export.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export visible attendance records");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        chooser.setSelectedFile(new java.io.File("attendance-" + LocalDate.now(zone) + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            path = path.resolveSibling(path.getFileName() + ".csv");
        }
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace " + path.getFileName() + "?",
                "Replace CSV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        Path destination = path;
        runDatabase(() -> { CsvExporter.write(destination, records, zone); return records.size(); },
                count -> JOptionPane.showMessageDialog(this, count + " record(s) exported to:\n" + destination.toAbsolutePath()));
    }

    private <T> void runDatabase(Callable<T> task, Consumer<T> success) {
        runDatabase(task, success, this::showError);
    }

    private <T> void runDatabase(Callable<T> task, Consumer<T> success, Consumer<Throwable> failure) {
        if (closing) return;
        CompletableFuture.supplyAsync(() -> {
            try { return task.call(); }
            catch (Exception error) { throw new CompletionException(error); }
        }, databaseWorker).whenComplete((result, error) -> onUi(() -> {
            if (error != null) failure.accept(error);
            else success.accept(result);
        }));
    }

    private void onUi(Runnable action) {
        SwingUtilities.invokeLater(() -> { if (!closing) action.run(); });
    }

    private void showError(Throwable error) {
        while (error.getCause() != null && (error instanceof CompletionException || error instanceof ExecutionException)) error = error.getCause();
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        JOptionPane.showMessageDialog(this, message, "Attendance tracker", JOptionPane.ERROR_MESSAGE);
    }

    private void updateClock() {
        clockLabel.setText(DateTimeFormatter.ofPattern("EEE, MMM dd yyyy   HH:mm:ss").withZone(zone).format(Instant.now()));
    }

    private static void installSearch(JTable table, JTextField input) {
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>((DefaultTableModel) table.getModel());
        table.setRowSorter(sorter);
        input.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                String text = input.getText().strip();
                sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(text), 0, 1));
            }
            @Override public void insertUpdate(DocumentEvent event) { update(); }
            @Override public void removeUpdate(DocumentEvent event) { update(); }
            @Override public void changedUpdate(DocumentEvent event) { update(); }
        });
    }

    private static JPanel stat(String title, JLabel value) {
        JPanel card = card(new BorderLayout(0, 6));
        card.add(label(title, 11, Font.BOLD, MUTED), BorderLayout.NORTH);
        value.setFont(font(25, Font.BOLD));
        value.setForeground(NAVY);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private static JTable table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFont(font(13, Font.PLAIN));
        table.setRowHeight(32);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(12, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setFont(font(12, Font.BOLD));
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    private static JPanel card(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(14, 16, 14, 16));
        return panel;
    }

    private static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static JLabel label(String text, int size, int style, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font(size, style));
        label.setForeground(color);
        return label;
    }

    private static Font font(int size, int style) { return new Font("SansSerif", style, size); }

    private record Snapshot(List<Employee> employees, List<AttendanceRecord> records, int openCount, int todayCount) { }

    private static final class PreviewPanel extends JPanel {
        private BufferedImage frame;
        PreviewPanel() { setBackground(NAVY); }
        void setFrame(BufferedImage frame) { this.frame = frame; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (frame == null) {
                    canvas.setColor(new Color(173, 192, 211));
                    canvas.setFont(font(15, Font.PLAIN));
                    String text = "Camera preview";
                    canvas.drawString(text, (getWidth() - canvas.getFontMetrics().stringWidth(text)) / 2, getHeight() / 2);
                } else {
                    double scale = Math.min((double) getWidth() / frame.getWidth(), (double) getHeight() / frame.getHeight());
                    int width = (int) (frame.getWidth() * scale);
                    int height = (int) (frame.getHeight() * scale);
                    canvas.drawImage(frame, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
                }
            } finally { canvas.dispose(); }
        }
    }
}
