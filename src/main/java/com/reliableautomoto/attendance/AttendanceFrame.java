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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final Clock clock;
    private final PayrollService payrollService;
    private final ManualAttendanceService manualAttendanceService;
    private final ZoneId zone;
    private final UserRole role;
    private final Authenticator authenticator;
    private final Runnable onLogout;
    private final DateTimeFormatter timestampFormat;
    private final DateTimeFormatter summaryTimeFormat;
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
    private final DefaultTableModel employeeModel = model("Employee #", "Employee name", "Daily pay", "Status");
    private final JTable employeeTable = table(employeeModel);
    private final DefaultTableModel summaryModel = model("Date", "Time ins", "Time outs", "Hours present", "Status");
    private final JTable summaryTable = table(summaryModel);
    private final JComboBox<SummaryEmployeeChoice> summaryEmployee = new JComboBox<>();
    private final JTextField summaryStart = new JTextField(10);
    private final JTextField summaryEnd = new JTextField(10);
    private final JLabel summaryStatus = new JLabel("Select an employee to view attendance.");
    private final DefaultTableModel dateSummaryModel = model("Employee #", "Employee name", "Time ins", "Time outs", "Hours present", "Status");
    private final JTable dateSummaryTable = table(dateSummaryModel);
    private final JTextField dateSummaryDate = new JTextField(10);
    private final JLabel dateSummaryStatus = new JLabel("Choose a date to view attendance.");
    private final DefaultTableModel holidayModel = model("Date", "Holiday", "Type", "Remarks");
    private final JTable holidayTable = table(holidayModel);
    private final DefaultTableModel payrollModel = new DefaultTableModel(new String[]{"Employee", "Daily pay", "Paid days",
            "Expected hrs", "Credited hrs", "Absent hrs", "OT hrs", "Gross", "Absent deduction", "OT pay", "Manual deduction", "Net"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return column == 10; }
    };
    private final JTable payrollTable = table(payrollModel);
    private final JTextField payrollStart = new JTextField(10);
    private final JTextField payrollEnd = new JTextField(10);
    private final JLabel payrollStatus = new JLabel("Choose a payroll period.");
    private final JComboBox<String> scope = new JComboBox<>(new String[]{"Today", "Selected date", "All records", "Currently in"});
    private final JTextField dateInput = new JTextField(10);
    private final JLabel historyCount = new JLabel("No attendance records yet");
    private final JTextField historySearch = new JTextField(16);
    private List<AttendanceRecord> displayedRecords = List.of();
    private List<Employee> displayedEmployees = List.of();
    private List<Holiday> displayedHolidays = List.of();
    private PayrollReport displayedPayroll;
    private final Timer clockTimer;
    private final Timer refreshTimer;
    private long refreshVersion;
    private long summaryRefreshVersion;
    private long dateSummaryRefreshVersion;
    private boolean updatingSummaryEmployees;
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
        this.clock = service.clock();
        this.zone = zone;
        this.role = java.util.Objects.requireNonNull(role);
        this.authenticator = authenticator;
        this.onLogout = onLogout;
        this.payrollService = new PayrollService(database, zone, clock);
        this.manualAttendanceService = new ManualAttendanceService(database, zone, clock);
        timestampFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy  HH:mm:ss").withZone(zone);
        summaryTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);
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
        if (role == UserRole.ADMIN) {
            tabs.addTab("  Employees  ", employeesPanel());
            tabs.addTab("  Employee summary  ", employeeSummaryPanel());
            tabs.addTab("  Date summary  ", dateSummaryPanel());
            tabs.addTab("  Holidays  ", holidaysPanel());
            tabs.addTab("  Payroll  ", payrollPanel());
        }
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
        dateInput.setText(today().toString());
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

    private JPanel employeeSummaryPanel() {
        JPanel panel = card(new BorderLayout(0, 15));
        JPanel top = transparent(new BorderLayout(15, 0));
        JPanel intro = transparent(new GridLayout(2, 1, 0, 5));
        intro.add(label("Employee attendance summary", 22, Font.BOLD, NAVY));
        intro.add(label("Review the selected employee's time-ins, time-outs, and total hours by time-in date.", 13, Font.PLAIN, MUTED));
        top.add(intro, BorderLayout.CENTER);

        LocalDate today = today();
        summaryStart.setText(today.withDayOfMonth(1).toString());
        summaryEnd.setText(today.toString());
        summaryStart.setToolTipText("Start date (YYYY-MM-DD), inclusive");
        summaryEnd.setToolTipText("End date (YYYY-MM-DD), inclusive");
        summaryEmployee.setPrototypeDisplayValue(new SummaryEmployeeChoice("0000000000", "A reasonably long employee name"));
        summaryEmployee.addItem(new SummaryEmployeeChoice(null, "-- SELECT AN EMPLOYEE --"));

        JPanel filters = transparent(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        filters.add(label("Employee", 11, Font.PLAIN, MUTED));
        filters.add(summaryEmployee);
        filters.add(label("From", 11, Font.PLAIN, MUTED));
        filters.add(summaryStart);
        filters.add(label("To", 11, Font.PLAIN, MUTED));
        filters.add(summaryEnd);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refreshSummary());
        filters.add(refresh);
        top.add(filters, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(summaryTable), BorderLayout.CENTER);

        summaryStatus.setFont(font(12, Font.PLAIN));
        summaryStatus.setForeground(MUTED);
        JPanel bottom = transparent(new BorderLayout(10, 0));
        bottom.add(summaryStatus, BorderLayout.CENTER);
        JButton addPastAttendance = new JButton("Add past attendance");
        addPastAttendance.addActionListener(event -> addPastAttendance(today().minusDays(1)));
        bottom.add(addPastAttendance, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);
        summaryStart.addActionListener(event -> refreshSummary());
        summaryEnd.addActionListener(event -> refreshSummary());
        summaryEmployee.addActionListener(event -> {
            if (!updatingSummaryEmployees) refreshSummary();
        });
        return panel;
    }

    private void addPastAttendance(LocalDate suggestedDate) {
        if (displayedEmployees.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Register an employee before adding attendance.");
            return;
        }
        JComboBox<SummaryEmployeeChoice> employee = new JComboBox<>();
        employee.setPrototypeDisplayValue(new SummaryEmployeeChoice("0000000000", "A reasonably long employee name"));
        SummaryEmployeeChoice selected = (SummaryEmployeeChoice) summaryEmployee.getSelectedItem();
        int selectedIndex = 0;
        for (Employee item : displayedEmployees) {
            employee.addItem(new SummaryEmployeeChoice(item.number(), item.name()));
            if (selected != null && item.number().equals(selected.number())) selectedIndex = employee.getItemCount() - 1;
        }
        employee.setSelectedIndex(selectedIndex);
        LocalDate initialDate = suggestedDate != null && suggestedDate.isBefore(today())
                ? suggestedDate : today().minusDays(1);
        JTextField date = new JTextField(initialDate.toString(), 12);
        JTextField timeIn = new JTextField("08:00", 8);
        JTextField timeOut = new JTextField("17:00", 8);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 7));
        form.add(new JLabel("Employee"));
        form.add(employee);
        form.add(new JLabel("Past attendance date (YYYY-MM-DD)"));
        form.add(date);
        form.add(new JLabel("Time in (HH:mm)"));
        form.add(timeIn);
        form.add(new JLabel("Time out (HH:mm, same date)"));
        form.add(timeOut);

        while (JOptionPane.showConfirmDialog(this, form, "Add past attendance",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            SummaryEmployeeChoice choice = (SummaryEmployeeChoice) employee.getSelectedItem();
            LocalDate attendanceDate;
            LocalTime attendanceTimeIn;
            LocalTime attendanceTimeOut;
            try {
                attendanceDate = LocalDate.parse(date.getText().strip());
                attendanceTimeIn = LocalTime.parse(timeIn.getText().strip());
                attendanceTimeOut = LocalTime.parse(timeOut.getText().strip());
                if (!attendanceDate.isBefore(today())) {
                    throw new IllegalArgumentException("Manual attendance can only be added for a past date.");
                }
                if (!attendanceTimeOut.isAfter(attendanceTimeIn)) {
                    throw new IllegalArgumentException("Time-out must be later than time-in on the same date.");
                }
            } catch (DateTimeParseException error) {
                JOptionPane.showMessageDialog(this, "Enter the date as YYYY-MM-DD and times as HH:mm.",
                        "Check attendance", JOptionPane.WARNING_MESSAGE);
                continue;
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(this, error.getMessage(), "Check attendance", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            String employeeNumber = choice.number();
            String employeeName = choice.name();
            runDatabase(() -> manualAttendanceService.add(employeeNumber, attendanceDate,
                    attendanceTimeIn, attendanceTimeOut), added -> {
                selectSummaryEmployee(employeeNumber);
                dateSummaryDate.setText(attendanceDate.toString());
                refreshData();
                JOptionPane.showMessageDialog(this, "Past attendance added for " + employeeName + " on "
                        + attendanceDate + ".");
            });
            break;
        }
    }

    private void selectSummaryEmployee(String employeeNumber) {
        updatingSummaryEmployees = true;
        try {
            for (int index = 1; index < summaryEmployee.getItemCount(); index++) {
                SummaryEmployeeChoice choice = summaryEmployee.getItemAt(index);
                if (employeeNumber.equals(choice.number())) {
                    summaryEmployee.setSelectedIndex(index);
                    return;
                }
            }
        } finally {
            updatingSummaryEmployees = false;
        }
    }

    private JPanel dateSummaryPanel() {
        JPanel panel = card(new BorderLayout(0, 15));
        JPanel top = transparent(new BorderLayout(15, 0));
        JPanel intro = transparent(new GridLayout(2, 1, 0, 5));
        intro.add(label("Daily attendance summary", 22, Font.BOLD, NAVY));
        intro.add(label("Review every employee's time-ins, time-outs, and total hours for one time-in date.",
                13, Font.PLAIN, MUTED));
        top.add(intro, BorderLayout.CENTER);

        dateSummaryDate.setText(today().toString());
        dateSummaryDate.setToolTipText("Attendance date (YYYY-MM-DD)");
        JPanel filters = transparent(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        filters.add(label("Date", 11, Font.PLAIN, MUTED));
        filters.add(dateSummaryDate);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refreshDateSummary());
        filters.add(refresh);
        top.add(filters, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(dateSummaryTable), BorderLayout.CENTER);

        dateSummaryStatus.setFont(font(12, Font.PLAIN));
        dateSummaryStatus.setForeground(MUTED);
        JPanel bottom = transparent(new BorderLayout(10, 0));
        bottom.add(dateSummaryStatus, BorderLayout.CENTER);
        JButton addPastAttendance = new JButton("Add past attendance");
        addPastAttendance.addActionListener(event -> addPastAttendance(selectedPastDate()));
        bottom.add(addPastAttendance, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);
        dateSummaryDate.addActionListener(event -> refreshDateSummary());
        return panel;
    }

    private LocalDate selectedPastDate() {
        try {
            LocalDate selected = LocalDate.parse(dateSummaryDate.getText().strip());
            return selected.isBefore(today()) ? selected : today().minusDays(1);
        } catch (DateTimeParseException error) {
            return today().minusDays(1);
        }
    }

    private JPanel holidaysPanel() {
        JPanel panel = card(new BorderLayout(0, 15));
        JPanel top = transparent(new BorderLayout(15, 0));
        JPanel intro = transparent(new GridLayout(2, 1, 0, 5));
        intro.add(label("Holiday settings", 22, Font.BOLD, NAVY));
        intro.add(label("Administrator-entered holidays are applied to every unfinalized payroll preview.", 13, Font.PLAIN, MUTED));
        top.add(intro, BorderLayout.CENTER);
        JPanel controls = transparent(new FlowLayout(FlowLayout.RIGHT));
        JButton add = new JButton("Add holiday");
        add.addActionListener(event -> editHoliday(null));
        JButton edit = new JButton("Edit selected");
        edit.addActionListener(event -> {
            Holiday holiday = selectedHoliday();
            if (holiday == null) JOptionPane.showMessageDialog(this, "Select a holiday first.");
            else editHoliday(holiday);
        });
        JButton delete = new JButton("Delete selected");
        delete.addActionListener(event -> deleteHoliday());
        controls.add(add);
        controls.add(edit);
        controls.add(delete);
        top.add(controls, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(holidayTable), BorderLayout.CENTER);
        panel.add(label("Paid and unpaid non-working holidays do not create absence deductions. Dates use YYYY-MM-DD.",
                12, Font.PLAIN, MUTED), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel payrollPanel() {
        JPanel panel = card(new BorderLayout(0, 12));
        JPanel top = transparent(new BorderLayout(12, 0));
        JPanel title = transparent(new GridLayout(2, 1, 0, 4));
        title.add(label("Salary computation", 22, Font.BOLD, NAVY));
        title.add(label("Edit manual deductions in the table, then recompute or finalize.", 13, Font.PLAIN, MUTED));
        top.add(title, BorderLayout.WEST);
        JPanel controls = transparent(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        LocalDate today = today();
        payrollStart.setText(today.withDayOfMonth(1).toString());
        payrollEnd.setText(today.toString());
        controls.add(new JLabel("From"));
        controls.add(payrollStart);
        controls.add(new JLabel("Through"));
        controls.add(payrollEnd);
        JButton compute = new JButton("Compute");
        compute.addActionListener(event -> computePayroll(false));
        JButton finalize = new JButton("Finalize payroll");
        finalize.addActionListener(event -> computePayroll(true));
        controls.add(compute);
        controls.add(finalize);
        top.add(controls, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(payrollTable), BorderLayout.CENTER);
        payrollStatus.setFont(font(12, Font.PLAIN));
        payrollStatus.setForeground(MUTED);
        panel.add(payrollStatus, BorderLayout.SOUTH);
        return panel;
    }

    private Holiday selectedHoliday() {
        int selected = holidayTable.getSelectedRow();
        return selected < 0 ? null : displayedHolidays.get(holidayTable.convertRowIndexToModel(selected));
    }

    private void editHoliday(Holiday holiday) {
        JTextField name = new JTextField(holiday == null ? "" : holiday.name(), 25);
        JTextField date = new JTextField(holiday == null ? today().toString() : holiday.date().toString(), 12);
        JComboBox<HolidayType> type = new JComboBox<>(HolidayType.values());
        if (holiday != null) type.setSelectedItem(holiday.type());
        JTextArea remarks = new JTextArea(holiday == null ? "" : holiday.remarks(), 3, 25);
        remarks.setLineWrap(true);
        remarks.setWrapStyleWord(true);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 6));
        form.add(new JLabel("Holiday name"));
        form.add(name);
        form.add(new JLabel("Holiday date (YYYY-MM-DD)"));
        form.add(date);
        form.add(new JLabel("Holiday type"));
        form.add(type);
        form.add(new JLabel("Remarks (optional)"));
        form.add(new JScrollPane(remarks));
        while (JOptionPane.showConfirmDialog(this, form, holiday == null ? "Add holiday" : "Edit holiday",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            LocalDate parsed;
            try {
                parsed = LocalDate.parse(date.getText().strip());
                if (name.getText().isBlank()) throw new IllegalArgumentException("Enter a holiday name.");
            } catch (DateTimeParseException error) {
                JOptionPane.showMessageDialog(this, "Enter the holiday date as YYYY-MM-DD.", "Check holiday", JOptionPane.WARNING_MESSAGE);
                continue;
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(this, error.getMessage(), "Check holiday", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            LocalDate holidayDate = parsed;
            HolidayType holidayType = (HolidayType) type.getSelectedItem();
            runDatabase(() -> {
                if (holiday == null) database.addHoliday(name.getText(), holidayDate, holidayType, remarks.getText());
                else database.updateHoliday(holiday.id(), name.getText(), holidayDate, holidayType, remarks.getText());
                return null;
            }, ignored -> refreshData());
            break;
        }
    }

    private void deleteHoliday() {
        Holiday holiday = selectedHoliday();
        if (holiday == null) {
            JOptionPane.showMessageDialog(this, "Select a holiday first.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete " + holiday.name() + " on " + holiday.date() + "?",
                "Delete holiday", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        runDatabase(() -> { database.deleteHoliday(holiday.id()); return null; }, ignored -> refreshData());
    }

    private void computePayroll(boolean finalize) {
        if (payrollTable.isEditing() && !payrollTable.getCellEditor().stopCellEditing()) return;
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(payrollStart.getText().strip());
            end = LocalDate.parse(payrollEnd.getText().strip());
        } catch (DateTimeParseException error) {
            JOptionPane.showMessageDialog(this, "Enter payroll dates as YYYY-MM-DD.", "Check payroll period", JOptionPane.WARNING_MESSAGE);
            return;
        }
        java.util.Map<String, BigDecimal> deductions;
        try {
            deductions = displayedPayroll != null && displayedPayroll.periodStart().equals(start)
                    && displayedPayroll.periodEnd().equals(end) ? payrollDeductions() : java.util.Map.of();
        } catch (IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "Check manual deductions", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (finalize && JOptionPane.showConfirmDialog(this,
                "Finalized payroll preserves this calculation even if attendance, pay rates, or holidays later change. Continue?",
                "Finalize payroll", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        payrollStatus.setText(finalize ? "Finalizing payroll..." : "Computing live payroll preview...");
        LocalDate periodStart = start;
        LocalDate periodEnd = end;
        runDatabase(() -> finalize ? payrollService.finalizePayroll(periodStart, periodEnd, deductions)
                        : payrollService.preview(periodStart, periodEnd, deductions), this::showPayroll);
    }

    private java.util.Map<String, BigDecimal> payrollDeductions() {
        if (displayedPayroll == null || payrollModel.getRowCount() != displayedPayroll.lines().size()) return java.util.Map.of();
        java.util.Map<String, BigDecimal> values = new java.util.HashMap<>();
        for (int row = 0; row < payrollModel.getRowCount(); row++) {
            String raw = String.valueOf(payrollModel.getValueAt(row, 10)).replace(",", "").strip();
            BigDecimal value;
            try { value = raw.isEmpty() ? BigDecimal.ZERO : new BigDecimal(raw); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Manual deduction on row " + (row + 1) + " is not a valid amount."); }
            if (value.signum() < 0 || value.scale() > 2) throw new IllegalArgumentException("Manual deductions must be non-negative amounts with at most two decimals.");
            values.put(displayedPayroll.lines().get(row).employeeNumber(), value);
        }
        return values;
    }

    private void showPayroll(PayrollReport report) {
        displayedPayroll = report;
        payrollModel.setRowCount(0);
        for (PayrollLine line : report.lines()) {
            payrollModel.addRow(new Object[]{line.employeeName() + " (" + line.employeeNumber() + ")", money(line.dailyPay()),
                    line.paidScheduledDays(), line.expectedRegularHours(), line.creditedRegularHours(), line.absentHours(),
                    line.overtimeHours(), money(line.grossPay()), money(line.absentDeduction()), money(line.overtimePay()),
                    money(line.manualDeductions()), money(line.netPay())});
        }
        payrollStatus.setText(report.finalized()
                ? "FINALIZED - saved snapshot from " + timestampFormat.format(report.computedAt()) + "; later configuration changes do not alter it."
                : "Live preview - holiday, attendance, pay-rate, and deduction changes are reflected until finalization.");
        payrollStatus.setForeground(report.finalized() ? GREEN : MUTED);
        payrollTable.setEnabled(!report.finalized());
    }

    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    private void editEmployee(Employee employee) {
        JTextField number = new JTextField(employee == null ? "" : employee.number(), 24);
        JTextField name = new JTextField(employee == null ? "" : employee.name(), 24);
        JCheckBox active = new JCheckBox("Active employee", employee == null || employee.active());
        JTextField dailyPay = new JTextField(employee == null ? "0.00" : money(employee.dailyPay()), 12);
        number.setEditable(employee == null);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 7));
        form.add(new JLabel("Employee number (leading zeros are preserved)"));
        form.add(number);
        form.add(new JLabel("Full name"));
        form.add(name);
        form.add(new JLabel("Pay per day"));
        form.add(dailyPay);
        if (employee != null) form.add(active);
        while (JOptionPane.showConfirmDialog(this, form, employee == null ? "Add employee" : "Edit employee",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String employeeNumber;
            long dailyPayCents;
            try {
                employeeNumber = EmployeeNumbers.normalize(number.getText());
                if (name.getText().isBlank()) throw new IllegalArgumentException("Enter the employee's name.");
                BigDecimal amount = new BigDecimal(dailyPay.getText().replace(",", "").strip());
                if (amount.signum() < 0 || amount.scale() > 2) throw new IllegalArgumentException("Daily pay must be a non-negative amount with at most two decimals.");
                dailyPayCents = amount.movePointRight(2).longValueExact();
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(this, error.getMessage(), "Check employee details", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            String fullName = name.getText();
            boolean isActive = active.isSelected();
            runDatabase(() -> {
                if (employee == null) database.addEmployee(employeeNumber, fullName, dailyPayCents);
                else database.updateEmployee(employee.number(), fullName, isActive, dailyPayCents);
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
        LocalDate today = today();
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
                today.plusDays(1).atStartOfDay(zone).toInstant(), false).size(),
                role == UserRole.ADMIN ? database.holidays(null, null) : List.of()), snapshot -> {
            if (version != refreshVersion) return;
            displayedEmployees = snapshot.employees();
            displayedRecords = snapshot.records();
            employeeModel.setRowCount(0);
            for (Employee employee : displayedEmployees) {
                employeeModel.addRow(new Object[]{employee.number(), employee.name(), money(employee.dailyPay()), employee.active() ? "Active" : "Inactive"});
            }
            refreshSummaryEmployeeChoices();
            displayedHolidays = snapshot.holidays();
            holidayModel.setRowCount(0);
            for (Holiday holiday : displayedHolidays) {
                holidayModel.addRow(new Object[]{holiday.date(), holiday.name(), holiday.type(), holiday.remarks()});
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
            refreshSummary();
            refreshDateSummary();
        });
    }

    private void refreshSummaryEmployeeChoices() {
        if (role != UserRole.ADMIN) return;
        SummaryEmployeeChoice selected = (SummaryEmployeeChoice) summaryEmployee.getSelectedItem();
        String selectedNumber = selected == null ? null : selected.number();
        updatingSummaryEmployees = true;
        try {
            summaryEmployee.removeAllItems();
            summaryEmployee.addItem(new SummaryEmployeeChoice(null, "-- SELECT AN EMPLOYEE --"));
            int selectedIndex = 0;
            for (Employee employee : displayedEmployees) {
                summaryEmployee.addItem(new SummaryEmployeeChoice(employee.number(), employee.name()));
                if (employee.number().equals(selectedNumber)) selectedIndex = summaryEmployee.getItemCount() - 1;
            }
            summaryEmployee.setSelectedIndex(selectedIndex);
        } finally {
            updatingSummaryEmployees = false;
        }
    }

    private void refreshSummary() {
        if (role != UserRole.ADMIN || closing) return;
        SummaryEmployeeChoice choice = (SummaryEmployeeChoice) summaryEmployee.getSelectedItem();
        String employeeNumber = choice == null ? null : choice.number();
        if (employeeNumber == null) {
            summaryRefreshVersion++;
            summaryModel.setRowCount(0);
            summaryStatus.setText("Select an employee to view attendance.");
            return;
        }
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(summaryStart.getText().strip());
            end = LocalDate.parse(summaryEnd.getText().strip());
        } catch (DateTimeParseException error) {
            summaryStatus.setText("Enter valid From and To dates in YYYY-MM-DD format.");
            return;
        }
        if (end.isBefore(start)) {
            summaryStatus.setText("The To date must be the same as or later than the From date.");
            return;
        }
        Instant from = start.atStartOfDay(zone).toInstant();
        Instant until = end.plusDays(1).atStartOfDay(zone).toInstant();
        Instant asOf = clock.instant();
        long version = ++summaryRefreshVersion;
        summaryStatus.setText("Loading attendance summary…");
        runDatabase(() -> {
            List<AttendanceRecord> records = database.attendance(from, until, false);
            records = records.stream().filter(record -> employeeNumber.equals(record.employeeNumber())).toList();
            return EmployeeSummaryService.summarize(records, zone, asOf);
        }, summaries -> {
            if (version != summaryRefreshVersion) return;
            summaryModel.setRowCount(0);
            Duration total = Duration.ZERO;
            int shifts = 0;
            int openShifts = 0;
            for (EmployeeDailySummary summary : summaries) {
                String timeIns = summary.shifts().stream()
                        .map(record -> summaryTimeFormat.format(record.timeIn())).collect(java.util.stream.Collectors.joining(", "));
                String timeOuts = summary.shifts().stream()
                        .map(record -> record.timeOut() == null ? "—" : summaryTimeFormat.format(record.timeOut()))
                        .collect(java.util.stream.Collectors.joining(", "));
                summaryModel.addRow(new Object[]{summary.date(), timeIns, timeOuts, formatDuration(summary.presentDuration()),
                        summary.inProgress() ? "In progress" : "Complete"});
                total = total.plus(summary.presentDuration());
                shifts += summary.shifts().size();
                if (summary.inProgress()) openShifts++;
            }
            summaryStatus.setText(summaries.size() + " employee-day(s) · " + shifts + " shift(s) · Total present: "
                    + formatDuration(total) + (openShifts == 0 ? "" : " · " + openShifts + " open shift(s)")
                    + " · Dates use time-in in " + zone);
        });
    }

    private void refreshDateSummary() {
        if (role != UserRole.ADMIN || closing) return;
        LocalDate date;
        try {
            date = LocalDate.parse(dateSummaryDate.getText().strip());
        } catch (DateTimeParseException error) {
            dateSummaryRefreshVersion++;
            dateSummaryModel.setRowCount(0);
            dateSummaryStatus.setText("Enter a valid date in YYYY-MM-DD format.");
            return;
        }
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant until = date.plusDays(1).atStartOfDay(zone).toInstant();
        Instant asOf = clock.instant();
        long version = ++dateSummaryRefreshVersion;
        dateSummaryStatus.setText("Loading date summary…");
        runDatabase(() -> EmployeeSummaryService.summarize(
                database.attendance(from, until, false), zone, asOf), summaries -> {
            if (version != dateSummaryRefreshVersion) return;
            dateSummaryModel.setRowCount(0);
            Duration total = Duration.ZERO;
            int shifts = 0;
            int openShifts = 0;
            for (EmployeeDailySummary summary : summaries) {
                String timeIns = summary.shifts().stream()
                        .map(record -> summaryTimeFormat.format(record.timeIn()))
                        .collect(java.util.stream.Collectors.joining(", "));
                String timeOuts = summary.shifts().stream()
                        .map(record -> record.timeOut() == null ? "—" : summaryTimeFormat.format(record.timeOut()))
                        .collect(java.util.stream.Collectors.joining(", "));
                dateSummaryModel.addRow(new Object[]{summary.employeeNumber(), summary.employeeName(), timeIns, timeOuts,
                        formatDuration(summary.presentDuration()), summary.inProgress() ? "In progress" : "Complete"});
                total = total.plus(summary.presentDuration());
                shifts += summary.shifts().size();
                if (summary.inProgress()) openShifts++;
            }
            dateSummaryStatus.setText(date + " · " + summaries.size() + " employee(s) · " + shifts
                    + " shift(s) · Total present: " + formatDuration(total)
                    + (openShifts == 0 ? "" : " · " + openShifts + " open shift(s)")
                    + " · " + zone);
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
        chooser.setSelectedFile(new java.io.File("attendance-" + today() + ".csv"));
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
        clockLabel.setText(DateTimeFormatter.ofPattern("EEE, MMM dd yyyy   HH:mm:ss").withZone(zone).format(clock.instant()));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
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

    private static String formatDuration(Duration duration) {
        return "%dh %02dm %02ds".formatted(duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

    private record Snapshot(List<Employee> employees, List<AttendanceRecord> records, int openCount, int todayCount,
                            List<Holiday> holidays) { }

    private record SummaryEmployeeChoice(String number, String name) {
        @Override public String toString() { return number == null ? name : number + " — " + name; }
    }

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
