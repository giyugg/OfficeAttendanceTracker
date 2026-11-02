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
                    .filter(table -> table.getColumnCount() == 6).findFirst().orElseThrow());
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

    private static <T extends Component> List<T> components(Container parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container container) result.addAll(components(container, type));
        }
        return result;
    }
}
