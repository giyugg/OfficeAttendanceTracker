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
            Authenticator authenticator = Authenticator.fromEnvironment(database);
            SwingUtilities.invokeLater(() -> {
                AttendanceService service = new AttendanceService(database, Clock.systemUTC());
                showLogin(database, service, config, authenticator);
            });
        } catch (Exception error) {
            System.err.println("Unable to start attendance tracker: " + error.getMessage());
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, error.getMessage(), "Attendance tracker could not start", JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
        }
    }

    private static void showLogin(AttendanceDatabase database, AttendanceService service, AppConfig config,
                                  Authenticator authenticator) {
        LoginFrame login = new LoginFrame(authenticator, role -> {
            AttendanceFrame frame = new AttendanceFrame(database, service, config.zone(), role, authenticator,
                    () -> showLogin(database, service, config, authenticator));
            frame.setVisible(true);
        });
        login.setVisible(true);
    }
}
