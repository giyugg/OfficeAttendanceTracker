package com.reliableautomoto.attendance;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DatePicker extends JPanel {
    public static final List<String> WEEKDAY_HEADERS = List.of("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat");
    public static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE)", Locale.ENGLISH);
    private static final DateTimeFormatter FOOTER_FORMAT =
            DateTimeFormatter.ofPattern("EEE, MMM dd, yyyy", Locale.ENGLISH);

    private static final Color NAVY = new Color(23, 42, 66);
    private static final Color MUTED = new Color(88, 104, 123);
    private static final Color OUTSIDE_MONTH = new Color(158, 170, 184);
    private static final Color BORDER_COLOR = new Color(198, 208, 220);
    private static final Color CELL_BG = Color.WHITE;
    private static final Color OUTSIDE_CELL_BG = new Color(246, 248, 251);
    private static final Color TODAY_ACCENT = new Color(16, 116, 80);
    private static final Color SUNDAY_COLOR = new Color(170, 43, 43);

    private final JButton trigger = new JButton();
    private final JPopupMenu popup = new JPopupMenu();
    private final JComboBox<Month> monthCombo = new JComboBox<>(Month.values());
    private final JSpinner yearSpinner;
    private final JPanel dayGrid = new JPanel(new GridLayout(6, 7, 3, 3));
    private final JLabel footerDateLabel = new JLabel();
    private final List<ActionListener> listeners = new ArrayList<>();
    private final LocalDate referenceToday;
    private final LocalDate maxDate;

    private LocalDate selectedDate;
    private YearMonth displayedMonth;
    private boolean updatingControls;

    public DatePicker(LocalDate initialDate) {
        this(initialDate, initialDate, null);
    }

    public DatePicker(LocalDate initialDate, LocalDate referenceToday) {
        this(initialDate, referenceToday, null);
    }

    public DatePicker(LocalDate initialDate, LocalDate referenceToday, LocalDate maxDate) {
        super(new BorderLayout());
        this.referenceToday = Objects.requireNonNull(referenceToday, "referenceToday");
        this.maxDate = maxDate;
        LocalDate clampedInitial = Objects.requireNonNull(initialDate, "initialDate");
        if (maxDate != null && clampedInitial.isAfter(maxDate)) {
            clampedInitial = maxDate;
        }
        this.selectedDate = clampedInitial;
        this.displayedMonth = YearMonth.from(clampedInitial);

        setOpaque(false);
        trigger.setFont(new Font("SansSerif", Font.PLAIN, 12));
        trigger.setForeground(NAVY);
        trigger.setBackground(Color.WHITE);
        trigger.setFocusPainted(false);
        trigger.setHorizontalAlignment(SwingConstants.LEFT);
        trigger.setBorder(new CompoundBorder(
                new LineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(5, 10, 5, 10)));
        trigger.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        trigger.addActionListener(event -> togglePopup());
        add(trigger, BorderLayout.CENTER);

        yearSpinner = new JSpinner(new SpinnerNumberModel(displayedMonth.getYear(), 1970, 2100, 1));
        JSpinner.NumberEditor yearEditor = new JSpinner.NumberEditor(yearSpinner, "#");
        yearSpinner.setEditor(yearEditor);
        yearSpinner.setPreferredSize(new Dimension(68, 26));

        buildPopup();
        updateTriggerText();
    }

    public static String formatWithDayOfWeek(LocalDate date) {
        return DISPLAY_FORMAT.format(Objects.requireNonNull(date, "date"));
    }

    public LocalDate date() {
        return selectedDate;
    }

    public void setDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (maxDate != null && date.isAfter(maxDate)) {
            throw new IllegalArgumentException("Date cannot be after " + maxDate + ".");
        }
        this.selectedDate = date;
        this.displayedMonth = YearMonth.from(date);
        updateTriggerText();
        rebuildCalendar();
    }

    public void selectDate(LocalDate date) {
        setDate(date);
        popup.setVisible(false);
        fireActionPerformed();
    }

    public void addActionListener(ActionListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public String displayText() {
        return trigger.getText();
    }

    JButton triggerButton() {
        return trigger;
    }

    JPopupMenu popupMenu() {
        return popup;
    }

    YearMonth displayedMonth() {
        return displayedMonth;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        trigger.setEnabled(enabled);
        if (!enabled) {
            popup.setVisible(false);
        }
    }

    @Override
    public void setToolTipText(String text) {
        super.setToolTipText(text);
        trigger.setToolTipText(text);
    }

    private void togglePopup() {
        if (!isEnabled()) return;
        if (popup.isVisible()) {
            popup.setVisible(false);
            return;
        }
        displayedMonth = YearMonth.from(selectedDate);
        rebuildCalendar();
        popup.show(this, 0, getHeight() + 2);
    }

    private void buildPopup() {
        popup.setBorder(new LineBorder(BORDER_COLOR, 1));
        JPanel container = new JPanel(new BorderLayout(0, 8));
        container.setBackground(Color.WHITE);
        container.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel nav = new JPanel(new BorderLayout(4, 0));
        nav.setOpaque(false);
        JButton prevMonth = navButton("◀", "Previous month", () -> changeMonth(-1));
        JButton nextMonth = navButton("▶", "Next month", () -> changeMonth(1));
        nav.add(prevMonth, BorderLayout.WEST);

        JPanel monthYear = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        monthYear.setOpaque(false);
        monthCombo.setFont(new Font("SansSerif", Font.BOLD, 12));
        monthCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Month month) {
                    setText(month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                }
                return this;
            }
        });
        monthCombo.addActionListener(event -> {
            if (updatingControls) return;
            Month month = (Month) monthCombo.getSelectedItem();
            if (month != null) {
                displayedMonth = YearMonth.of(displayedMonth.getYear(), month);
                rebuildCalendar();
            }
        });
        yearSpinner.addChangeListener(event -> {
            if (updatingControls) return;
            int year = ((Number) yearSpinner.getValue()).intValue();
            displayedMonth = YearMonth.of(year, displayedMonth.getMonth());
            rebuildCalendar();
        });
        monthYear.add(monthCombo);
        monthYear.add(yearSpinner);
        nav.add(monthYear, BorderLayout.CENTER);
        nav.add(nextMonth, BorderLayout.EAST);
        container.add(nav, BorderLayout.NORTH);

        JPanel calendarBody = new JPanel(new BorderLayout(0, 4));
        calendarBody.setOpaque(false);
        JPanel weekdayRow = new JPanel(new GridLayout(1, 7, 3, 0));
        weekdayRow.setOpaque(false);
        for (int index = 0; index < WEEKDAY_HEADERS.size(); index++) {
            String dayName = WEEKDAY_HEADERS.get(index);
            JLabel header = new JLabel(dayName, SwingConstants.CENTER);
            header.setFont(new Font("SansSerif", Font.BOLD, 11));
            header.setForeground(index == 0 ? SUNDAY_COLOR : MUTED);
            header.setBorder(new EmptyBorder(2, 2, 4, 2));
            weekdayRow.add(header);
        }
        calendarBody.add(weekdayRow, BorderLayout.NORTH);
        dayGrid.setOpaque(false);
        calendarBody.add(dayGrid, BorderLayout.CENTER);
        container.add(calendarBody, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footerDateLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        footerDateLabel.setForeground(MUTED);
        footer.add(footerDateLabel, BorderLayout.WEST);

        LocalDate quickTarget = maxDate != null && maxDate.isBefore(referenceToday) ? maxDate : referenceToday;
        String quickLabel = quickTarget.equals(referenceToday) ? "Today" : "Yesterday";
        JButton quickButton = new JButton(quickLabel);
        quickButton.setFont(new Font("SansSerif", Font.BOLD, 11));
        quickButton.setFocusPainted(false);
        quickButton.addActionListener(event -> selectDate(quickTarget));
        footer.add(quickButton, BorderLayout.EAST);
        container.add(footer, BorderLayout.SOUTH);

        popup.add(container);
        rebuildCalendar();
    }

    private static JButton navButton(String text, String tooltip, Runnable action) {
        JButton button = new JButton(text);
        button.setFont(new Font("SansSerif", Font.BOLD, 11));
        button.setMargin(new Insets(2, 7, 2, 7));
        button.setFocusPainted(false);
        button.setToolTipText(tooltip);
        button.addActionListener(event -> action.run());
        return button;
    }

    private void changeMonth(int deltaMonths) {
        displayedMonth = displayedMonth.plusMonths(deltaMonths);
        rebuildCalendar();
    }

    private void rebuildCalendar() {
        updatingControls = true;
        try {
            monthCombo.setSelectedItem(displayedMonth.getMonth());
            yearSpinner.setValue(displayedMonth.getYear());
        } finally {
            updatingControls = false;
        }
        footerDateLabel.setText(FOOTER_FORMAT.format(selectedDate));

        dayGrid.removeAll();
        LocalDate firstOfMonth = displayedMonth.atDay(1);
        int leadingDays = firstOfMonth.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : firstOfMonth.getDayOfWeek().getValue();
        LocalDate startCellDate = firstOfMonth.minusDays(leadingDays);

        for (int cell = 0; cell < 42; cell++) {
            LocalDate cellDate = startCellDate.plusDays(cell);
            boolean inMonth = YearMonth.from(cellDate).equals(displayedMonth);
            boolean isSelected = cellDate.equals(selectedDate);
            boolean isToday = cellDate.equals(referenceToday);
            boolean allowed = maxDate == null || !cellDate.isAfter(maxDate);

            JButton dayButton = new JButton(Integer.toString(cellDate.getDayOfMonth()));
            dayButton.setMargin(new Insets(4, 0, 4, 0));
            dayButton.setPreferredSize(new Dimension(36, 28));
            dayButton.setFocusPainted(false);
            dayButton.setFont(new Font("SansSerif", isSelected || isToday ? Font.BOLD : Font.PLAIN, 12));
            dayButton.setToolTipText(formatWithDayOfWeek(cellDate));
            dayButton.setEnabled(allowed);

            if (isSelected) {
                dayButton.setBackground(NAVY);
                dayButton.setForeground(Color.WHITE);
                dayButton.setBorder(new LineBorder(NAVY, 1));
            } else {
                dayButton.setBackground(inMonth ? CELL_BG : OUTSIDE_CELL_BG);
                if (!allowed) {
                    dayButton.setForeground(OUTSIDE_MONTH);
                } else if (!inMonth) {
                    dayButton.setForeground(OUTSIDE_MONTH);
                } else if (cellDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    dayButton.setForeground(SUNDAY_COLOR);
                } else {
                    dayButton.setForeground(NAVY);
                }
                dayButton.setBorder(new LineBorder(isToday ? TODAY_ACCENT : BORDER_COLOR, isToday ? 2 : 1));
            }
            dayButton.addActionListener(event -> selectDate(cellDate));
            dayGrid.add(dayButton);
        }
        dayGrid.revalidate();
        dayGrid.repaint();
    }

    private void updateTriggerText() {
        trigger.setText(formatWithDayOfWeek(selectedDate) + "  ▾");
    }

    private void fireActionPerformed() {
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, selectedDate.toString());
        for (ActionListener listener : List.copyOf(listeners)) {
            listener.actionPerformed(event);
        }
    }
}

