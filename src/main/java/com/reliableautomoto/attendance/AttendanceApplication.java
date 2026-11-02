package com.reliableautomoto.attendance;

import javax.swing.*;
import java.awt.*;
import java.time.Clock;

public final class AttendanceApplication {
    private AttendanceApplication() { }

    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.parse(args);
            AttendanceDatabase database = new AttendanceDatabase(config.database());
            if (config.checkOnly()) {
                System.out.println("Database ready: " + database.path());
                System.out.println("Employees: " + database.employees().size());
                System.out.println("Attendance records: " + database.attendance(null, null, false).size());
                System.out.println("Display timezone: " + config.zone());
                return;
            }
            if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("A desktop display is required. Use --check to verify the database without opening a window.");
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            SwingUtilities.invokeLater(() -> {
                AttendanceFrame frame = new AttendanceFrame(database,
                        new AttendanceService(database, Clock.systemUTC()), config.zone());
                frame.setVisible(true);
            });
        } catch (Exception error) {
            System.err.println("Unable to start attendance tracker: " + error.getMessage());
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, error.getMessage(), "Attendance tracker could not start", JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
        }
    }
}
