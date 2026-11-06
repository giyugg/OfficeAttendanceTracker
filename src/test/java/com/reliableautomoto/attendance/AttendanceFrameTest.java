package com.reliableautomoto.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class AttendanceFrameTest {
    @TempDir Path directory;

    @Test void attendanceAccountCannotOpenAdministratorFeatures() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop UI requires a graphical environment");
        AttendanceDatabase database = new AttendanceDatabase(directory.resolve("roles.db"));
        Authenticator authenticator = new Authenticator(database, "admin", "attendance");
        AtomicReference<AttendanceFrame> adminReference = new AtomicReference<>();
        AtomicReference<AttendanceFrame> attendanceReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            adminReference.set(new AttendanceFrame(database, new AttendanceService(database, Clock.systemUTC()),
                    ZoneId.of("Asia/Manila"), UserRole.ADMIN, authenticator, () -> { }));
            attendanceReference.set(new AttendanceFrame(database, new AttendanceService(database, Clock.systemUTC()),
                    ZoneId.of("Asia/Manila"), UserRole.ATTENDANCE, authenticator, () -> { }));
        });
        try {
            String loaded = "0 record(s) · Selected by time-in date · Asia/Manila";
            await(() -> hasLabel(adminReference.get(), loaded) && hasLabel(attendanceReference.get(), loaded));
            JTabbedPane adminTabs = onEdt(() -> components(adminReference.get(), JTabbedPane.class).getFirst());
            JTabbedPane attendanceTabs = onEdt(() -> components(attendanceReference.get(), JTabbedPane.class).getFirst());
            assertEquals(7, adminTabs.getTabCount());
            assertEquals("  Employees  ", adminTabs.getTitleAt(1));
            assertEquals("  Employee summary  ", adminTabs.getTitleAt(2));
            assertEquals("  Date summary  ", adminTabs.getTitleAt(3));
            assertEquals("  Holidays  ", adminTabs.getTitleAt(4));
            assertEquals("  Payroll  ", adminTabs.getTitleAt(5));
            assertEquals("  Saved payrolls  ", adminTabs.getTitleAt(6));
            assertEquals(1, attendanceTabs.getTabCount());
            assertEquals("  Attendance station  ", attendanceTabs.getTitleAt(0));
            assertTrue(components(adminReference.get(), JButton.class).stream()
                    .anyMatch(button -> "Change passwords".equals(button.getText())));
            assertFalse(components(attendanceReference.get(), JButton.class).stream()
                    .anyMatch(button -> "Change passwords".equals(button.getText())));
            assertEquals(2, components(adminReference.get(), JButton.class).stream()
                    .filter(button -> "Add past attendance".equals(button.getText())).count());
            assertFalse(components(attendanceReference.get(), JButton.class).stream()
                    .anyMatch(button -> "Add past attendance".equals(button.getText())));
            JComboBox<?> employeeChoice = components(adminReference.get(), JComboBox.class).stream()
                    .filter(combo -> combo.getItemCount() > 0
                            && "-- SELECT AN EMPLOYEE --".equals(combo.getItemAt(0).toString()))
                    .findFirst().orElseThrow();
            assertEquals("-- SELECT AN EMPLOYEE --", employeeChoice.getSelectedItem().toString());
            JTable summary = components(adminReference.get(), JTable.class).stream()
                    .filter(table -> hasColumn(table, "Hours present") && !hasColumn(table, "Employee name"))
                    .findFirst().orElseThrow();
            assertEquals(5, summary.getColumnCount());
            assertFalse(hasColumn(summary, "Employee name"));
            assertFalse(hasColumn(summary, "Employee #"));
            assertEquals(0, summary.getRowCount());
            JTable dateSummary = components(adminReference.get(), JTable.class).stream()
                    .filter(table -> hasColumn(table, "Hours present") && hasColumn(table, "Employee name"))
                    .findFirst().orElseThrow();
            assertEquals(6, dateSummary.getColumnCount());
            assertTrue(hasColumn(dateSummary, "Employee #"));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                adminReference.get().dispose();
                attendanceReference.get().dispose();
            });
        }
    }

    @Test void keyboardScannerShowsTimeInTimeOutAndUnknownEmployee() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop UI requires a graphical environment");
        ZoneId zone = ZoneId.of("Asia/Manila");
        Instant start = LocalDate.now(zone).atTime(9, 0).atZone(zone).toInstant();
        AtomicReference<Instant> now = new AtomicReference<>(start);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId other) { return Clock.fixed(instant(), other); }
            @Override public Instant instant() { return now.get(); }
        };
        AttendanceDatabase database = new AttendanceDatabase(directory.resolve("ui.db"));
        database.addEmployee("00042", "Maria Santos");
        AtomicReference<AttendanceFrame> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AttendanceFrame frame = new AttendanceFrame(database, new AttendanceService(database, clock), zone);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addNotify(); // Create layout/peers without showing a window or using a physical scanner.
            frame.validate();
            reference.set(frame);
        });
        AttendanceFrame frame = reference.get();
        try {
            JTextField input = onEdt(() -> components(frame, JTextField.class).stream()
                    .filter(field -> field.getToolTipText() != null && field.getToolTipText().startsWith("Click here"))
                    .findFirst().orElseThrow());
            JTable history = onEdt(() -> components(frame, JTable.class).stream()
                    .filter(table -> hasColumn(table, "Duration")).findFirst().orElseThrow());
            submit(input, "00042");
            await(() -> hasLabel(frame, "Time-in successful") && history.getRowCount() == 1
                    && "IN".equals(history.getValueAt(0, 5)));
            assertEquals(1, database.attendance(null, null, true).size());
            now.set(start.plusSeconds(8 * 3600));
            submit(input, "00042");
            await(() -> hasLabel(frame, "Time-out successful") && "OUT".equals(history.getValueAt(0, 5)));
            assertEquals(0, database.attendance(null, null, true).size());

            // Save an offscreen rendering for visual review without opening a desktop window.
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Container content = frame.getContentPane();
                    BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    try { content.printAll(graphics); } finally { graphics.dispose(); }
                    Files.createDirectories(Path.of("target"));
                    ImageIO.write(image, "png", Path.of("target", "ui-preview.png").toFile());
                } catch (Exception error) { throw new RuntimeException(error); }
            });

            submit(input, "MISSING");
            await(() -> hasLabel(frame, "Employee not found"));
            assertEquals(1, database.attendance(null, null, false).size());
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test void payrollComputationCommitsActiveManualDeductionCellEditor() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop UI requires a graphical environment");
        ZoneId zone = ZoneId.of("Asia/Manila");
        LocalDate monday = LocalDate.of(2026, 11, 2);
        Clock clock = Clock.fixed(monday.atTime(18, 0).atZone(zone).toInstant(), zone);
        AttendanceDatabase database = new AttendanceDatabase(directory.resolve("payroll-ui.db"));
        database.addEmployee("00042", "Maria Santos", 60_000);
        database.recordScan("00042", monday.atTime(8, 0).atZone(zone).toInstant(), Duration.ZERO);
        database.recordScan("00042", monday.atTime(17, 0).atZone(zone).toInstant(), Duration.ZERO);
        AtomicReference<AttendanceFrame> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AttendanceFrame frame = new AttendanceFrame(database, new AttendanceService(database, clock), zone);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addNotify();
            frame.validate();
            reference.set(frame);
        });
        AttendanceFrame frame = reference.get();
        try {
            JTable payrollTable = onEdt(() -> components(frame, JTable.class).stream()
                    .filter(table -> hasColumn(table, "Manual deduction")).findFirst().orElseThrow());
            JTable savedPayrollTable = onEdt(() -> components(frame, JTable.class).stream()
                    .filter(table -> hasColumn(table, "Manual deduction")).skip(1).findFirst().orElseThrow());
            JButton compute = onEdt(() -> components(frame, JButton.class).stream()
                    .filter(button -> "Compute".equals(button.getText())).findFirst().orElseThrow());
            JButton finalize = onEdt(() -> components(frame, JButton.class).stream()
                    .filter(button -> "Finalize payroll".equals(button.getText())).findFirst().orElseThrow());
            JButton saveDeductions = onEdt(() -> components(frame, JButton.class).stream()
                    .filter(button -> "Save deductions".equals(button.getText())).findFirst().orElseThrow());
            SwingUtilities.invokeAndWait(compute::doClick);
            await(() -> payrollTable.getRowCount() == 1 && "600.00".equals(payrollTable.getValueAt(0, 11)));

            SwingUtilities.invokeAndWait(() -> {
                assertTrue(payrollTable.editCellAt(0, 10));
                JTextField editor = (JTextField) payrollTable.getEditorComponent();
                editor.setText("50.00");
                compute.doClick();
            });
            await(() -> !payrollTable.isEditing() && "50.00".equals(payrollTable.getValueAt(0, 10))
                    && "550.00".equals(payrollTable.getValueAt(0, 11)));

            SwingUtilities.invokeAndWait(finalize::doClick);
            await(() -> savedPayrollTable.getRowCount() == 1
                    && "50.00".equals(savedPayrollTable.getValueAt(0, 10))
                    && "550.00".equals(savedPayrollTable.getValueAt(0, 11)));
            assertTrue(onEdt(payrollTable::isEnabled));
            assertTrue(onEdt(savedPayrollTable::isEnabled));

            SwingUtilities.invokeAndWait(() -> {
                assertTrue(savedPayrollTable.editCellAt(0, 10));
                JTextField editor = (JTextField) savedPayrollTable.getEditorComponent();
                editor.setText("75.00");
                saveDeductions.doClick();
            });
            await(() -> !savedPayrollTable.isEditing()
                    && "75.00".equals(savedPayrollTable.getValueAt(0, 10))
                    && "525.00".equals(savedPayrollTable.getValueAt(0, 11))
                    && "75.00".equals(payrollTable.getValueAt(0, 10))
                    && "525.00".equals(payrollTable.getValueAt(0, 11)));
            assertEquals("525.00", database.savedPayrolls().getFirst().lines().getFirst().netPay().toPlainString());
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test void calendarDateChoosersShowDaysOfWeekAndFilterAttendanceWithoutTyping() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop UI requires a graphical environment");
        ZoneId zone = ZoneId.of("Asia/Manila");
        LocalDate tuesday = LocalDate.of(2026, 11, 3);
        LocalDate monday = LocalDate.of(2026, 11, 2);
        Clock clock = Clock.fixed(tuesday.atTime(18, 0).atZone(zone).toInstant(), zone);
        AttendanceDatabase database = new AttendanceDatabase(directory.resolve("datepicker-ui.db"));
        database.addEmployee("00042", "Maria Santos", 60_000);
        database.recordScan("00042", monday.atTime(8, 0).atZone(zone).toInstant(), Duration.ZERO);
        database.recordScan("00042", monday.atTime(17, 0).atZone(zone).toInstant(), Duration.ZERO);
        AtomicReference<AttendanceFrame> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AttendanceFrame frame = new AttendanceFrame(database, new AttendanceService(database, clock), zone);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addNotify();
            frame.validate();
            reference.set(frame);
        });
        AttendanceFrame frame = reference.get();
        try {
            List<DatePicker> pickers = onEdt(() -> components(frame, DatePicker.class));
            assertEquals(6, pickers.size());

            DatePicker dateSummaryPicker = onEdt(() -> pickers.stream()
                    .filter(picker -> "Attendance date".equals(picker.getToolTipText()))
                    .findFirst().orElseThrow());
            assertEquals("2026-11-03 (Tue)  ▾", onEdt(dateSummaryPicker::displayText));
            assertEquals(List.of("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), DatePicker.WEEKDAY_HEADERS);

            List<String> popupHeaders = onEdt(() -> components(dateSummaryPicker.popupMenu(), JLabel.class).stream()
                    .map(JLabel::getText)
                    .filter(DatePicker.WEEKDAY_HEADERS::contains)
                    .toList());
            assertEquals(DatePicker.WEEKDAY_HEADERS, popupHeaders);

            JTable dateSummaryTable = onEdt(() -> components(frame, JTable.class).stream()
                    .filter(table -> hasColumn(table, "Hours present") && hasColumn(table, "Employee name"))
                    .findFirst().orElseThrow());
            await(() -> hasLabel(frame, "2026-11-03 (Tue) · 0 employee(s) · 0 shift(s) · Total present: 0h 00m 00s · Asia/Manila"));

            // Click the Monday Nov 2 button inside the calendar popup instead of typing a date.
            SwingUtilities.invokeAndWait(() -> {
                JButton mondayCell = components(dateSummaryPicker.popupMenu(), JButton.class).stream()
                        .filter(button -> "2026-11-02 (Mon)".equals(button.getToolTipText()))
                        .findFirst().orElseThrow();
                mondayCell.doClick();
            });
            assertEquals(monday, onEdt(dateSummaryPicker::date));
            assertEquals("2026-11-02 (Mon)  ▾", onEdt(dateSummaryPicker::displayText));
            await(() -> dateSummaryTable.getRowCount() == 1
                    && hasLabel(frame, "2026-11-02 (Mon) · 1 employee(s) · 1 shift(s) · Total present: 9h 00m 00s · Asia/Manila"));
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    private static void submit(JTextField input, String value) throws Exception {
        SwingUtilities.invokeAndWait(() -> { input.setText(value); input.postActionEvent(); });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (onEdt(condition::getAsBoolean)) return;
            Thread.sleep(30);
        }
        fail("The attendance UI did not reach the expected state.");
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(action.call()); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        return result.get();
    }

    private static boolean hasLabel(Container parent, String value) {
        return components(parent, JLabel.class).stream().anyMatch(label -> value.equals(label.getText()));
    }

    private static boolean hasColumn(JTable table, String name) {
        for (int column = 0; column < table.getColumnCount(); column++) {
            if (name.equals(table.getColumnName(column))) return true;
        }
        return false;
    }

    private static <T extends Component> List<T> components(Container parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container container) result.addAll(components(container, type));
        }
        return result;
    }
}
