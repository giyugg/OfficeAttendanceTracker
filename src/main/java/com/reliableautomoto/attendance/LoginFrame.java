package com.reliableautomoto.attendance;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.function.Consumer;

public final class LoginFrame extends JFrame {
    private static final Color BACKGROUND = new Color(242, 245, 248);
    private static final Color NAVY = new Color(23, 42, 66);
    private static final Color MUTED = new Color(88, 104, 123);
    private static final Color RED = new Color(170, 43, 43);

    private final Authenticator authenticator;
    private final Consumer<UserRole> onAuthenticated;
    private final JComboBox<String> username = new JComboBox<>(new String[]{
            UserRole.ADMIN.username(), UserRole.ATTENDANCE.username()
    });
    private final JPasswordField password = new JPasswordField();
    private final JLabel error = new JLabel(" ");
    private final JButton signIn = new JButton("Sign in");

    public LoginFrame(Authenticator authenticator, Consumer<UserRole> onAuthenticated) {
        super("Reliable AutoMoto | Sign in");
        this.authenticator = java.util.Objects.requireNonNull(authenticator);
        this.onAuthenticated = java.util.Objects.requireNonNull(onAuthenticated);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);
        setContentPane(content());
        getRootPane().setDefaultButton(signIn);
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel content() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BACKGROUND);
        root.setBorder(new EmptyBorder(42, 55, 42, 55));

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(new EmptyBorder(32, 36, 32, 36));
        card.setPreferredSize(new Dimension(430, 410));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;

        constraints.gridy = 0;
        constraints.insets = new Insets(0, 0, 7, 0);
        card.add(label("RELIABLE AUTOMOTO", 12, Font.BOLD, MUTED), constraints);
        constraints.gridy++;
        card.add(label("Attendance tracker", 27, Font.BOLD, NAVY), constraints);
        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 28, 0);
        card.add(label("Sign in to continue", 14, Font.PLAIN, MUTED), constraints);

        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 7, 0);
        card.add(label("Account", 12, Font.BOLD, MUTED), constraints);
        username.setFont(new Font("SansSerif", Font.PLAIN, 15));
        username.setPreferredSize(new Dimension(300, 37));
        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 18, 0);
        card.add(username, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 7, 0);
        card.add(label("Password", 12, Font.BOLD, MUTED), constraints);
        password.setFont(new Font("SansSerif", Font.PLAIN, 15));
        password.setPreferredSize(new Dimension(300, 37));
        password.addActionListener(event -> attemptLogin());
        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 5, 0);
        card.add(password, constraints);

        error.setFont(new Font("SansSerif", Font.PLAIN, 12));
        error.setForeground(RED);
        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 14, 0);
        card.add(error, constraints);

        signIn.setFont(new Font("SansSerif", Font.BOLD, 14));
        signIn.setPreferredSize(new Dimension(300, 40));
        signIn.addActionListener(event -> attemptLogin());
        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 0, 0);
        card.add(signIn, constraints);

        root.add(card);
        return root;
    }

    private void attemptLogin() {
        char[] suppliedPassword = password.getPassword();
        String selectedUsername = (String) username.getSelectedItem();
        try {
            authenticator.authenticate(selectedUsername, suppliedPassword).ifPresentOrElse(role -> {
                signIn.setEnabled(false);
                dispose();
                onAuthenticated.accept(role);
            }, () -> {
                error.setText("Incorrect account or password.");
                password.setText("");
                password.requestFocusInWindow();
            });
        } catch (Exception databaseError) {
            error.setText("Unable to verify the account. Check the database connection.");
            password.setText("");
            password.requestFocusInWindow();
        } finally {
            Arrays.fill(suppliedPassword, '\0');
        }
    }

    @Override public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) password.requestFocusInWindow();
    }

    private static JLabel label(String text, int size, int style, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", style, size));
        label.setForeground(color);
        return label;
    }
}
