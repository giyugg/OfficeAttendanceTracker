package com.reliableautomoto.attendance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure payroll rules. Persistence and finalization are handled by {@link PayrollService}. */
public final class PayrollCalculator {
    public static final LocalTime REGULAR_START = LocalTime.of(8, 0);
    public static final LocalTime REGULAR_END = LocalTime.of(17, 0);
    public static final int REGULAR_HOURS_PER_DAY = 8;

    private PayrollCalculator() { }

    /** Minutes 00-44 round down; minutes 45-59 round up, for every hour. */
    public static LocalDateTime roundTimeOut(LocalDateTime actual) {
        LocalDateTime hour = actual.truncatedTo(ChronoUnit.HOURS);
        return actual.getMinute() >= 45 ? hour.plusHours(1) : hour;
    }

    public static int overtimeHours(LocalDateTime actualTimeOut) {
        LocalDateTime rounded = roundTimeOut(actualTimeOut);
        LocalDateTime threshold = actualTimeOut.toLocalDate().atTime(REGULAR_END);
        return (int) Math.max(0, ChronoUnit.HOURS.between(threshold, rounded));
    }

    static PayrollLine calculate(Employee employee, LocalDate start, LocalDate end,
                                 List<AttendanceRecord> attendance, Map<LocalDate, Holiday> holidays,
                                 BigDecimal manualDeduction, ZoneId zone) {
        BigDecimal dailyPay = employee.dailyPay();
        BigDecimal hourlyRate = dailyPay.divide(BigDecimal.valueOf(REGULAR_HOURS_PER_DAY), 6, RoundingMode.HALF_UP);
        Map<LocalDate, DayAttendance> worked = attendanceByDay(attendance, start, end, zone);
        int paidDays = 0;
        int expectedDays = 0;
        BigDecimal creditedHours = BigDecimal.ZERO;
        int overtimeHours = 0;

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            boolean scheduled = date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
            if (!scheduled) continue;
            Holiday holiday = holidays.get(date);
            DayAttendance day = worked.getOrDefault(date, DayAttendance.EMPTY);
            if (holiday == null) {
                paidDays++;
                expectedDays++;
                creditedHours = creditedHours.add(hours(day.regularMinutes()));
            } else if (holiday.type() == HolidayType.PAID) {
                // Daily pay already covers this day; attendance cannot add a second daily payment.
                paidDays++;
            } else if (day.regularMinutes() > 0) {
                // An unpaid holiday becomes payable for regular hours only when regular work is performed.
                paidDays++;
                expectedDays++;
                creditedHours = creditedHours.add(hours(day.regularMinutes()));
            }
            overtimeHours += day.overtimeHours();
        }

        BigDecimal expectedHours = BigDecimal.valueOf((long) expectedDays * REGULAR_HOURS_PER_DAY);
        creditedHours = creditedHours.min(expectedHours).setScale(2, RoundingMode.HALF_UP);
        BigDecimal absentHours = expectedHours.subtract(creditedHours).max(BigDecimal.ZERO);
        BigDecimal absentDeduction = money(absentHours.multiply(hourlyRate));
        BigDecimal overtimePay = money(BigDecimal.valueOf(overtimeHours).multiply(hourlyRate));
        BigDecimal deductions = money(manualDeduction == null ? BigDecimal.ZERO : manualDeduction.max(BigDecimal.ZERO));
        BigDecimal grossPay = money(dailyPay.multiply(BigDecimal.valueOf(paidDays)));
        BigDecimal netPay = money(grossPay.subtract(absentDeduction).subtract(deductions).add(overtimePay));
        return new PayrollLine(employee.number(), employee.name(), money(dailyPay), hourlyRate.stripTrailingZeros(),
                paidDays, expectedHours, creditedHours, absentHours, absentDeduction, overtimeHours,
                overtimePay, deductions, grossPay, netPay);
    }

    private static Map<LocalDate, DayAttendance> attendanceByDay(List<AttendanceRecord> records,
                                                                 LocalDate start, LocalDate end, ZoneId zone) {
        Map<LocalDate, List<Interval>> intervals = new HashMap<>();
        Map<LocalDate, Integer> overtime = new HashMap<>();
        for (AttendanceRecord record : records) {
            if (record.timeOut() == null) continue;
            ZonedDateTime in = record.timeIn().atZone(zone);
            ZonedDateTime out = record.timeOut().atZone(zone);
            LocalDate first = in.toLocalDate().isBefore(start) ? start : in.toLocalDate();
            LocalDate last = out.toLocalDate().isAfter(end) ? end : out.toLocalDate();
            for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
                ZonedDateTime regularStart = date.atTime(REGULAR_START).atZone(zone);
                ZonedDateTime intervalStart = in.isAfter(regularStart) ? in : regularStart;
                ZonedDateTime regularEnd = date.atTime(REGULAR_END).atZone(zone);
                ZonedDateTime intervalEnd = regularEnd;
                if (!out.toLocalDate().isAfter(date)) {
                    ZonedDateTime roundedOut = roundTimeOut(out.toLocalDateTime()).atZone(zone);
                    if (roundedOut.isBefore(regularEnd)) intervalEnd = roundedOut;
                }
                if (intervalEnd.isAfter(intervalStart)) {
                    intervals.computeIfAbsent(date, ignored -> new ArrayList<>())
                            .add(new Interval(intervalStart.toInstant(), intervalEnd.toInstant()));
                }
            }
            LocalDate outDate = out.toLocalDate();
            if (!outDate.isBefore(start) && !outDate.isAfter(end)) {
                // Multiple shifts must not multiply the same after-hours span.
                overtime.merge(outDate, overtimeHours(out.toLocalDateTime()), Math::max);
            }
        }
        Map<LocalDate, DayAttendance> result = new HashMap<>();
        Set<LocalDate> dates = new HashSet<>(intervals.keySet());
        dates.addAll(overtime.keySet());
        for (LocalDate date : dates) {
            result.put(date, new DayAttendance(mergedMinutes(intervals.getOrDefault(date, List.of())), overtime.getOrDefault(date, 0)));
        }
        return result;
    }

    private static long mergedMinutes(List<Interval> values) {
        if (values.isEmpty()) return 0;
        List<Interval> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparing(Interval::start));
        Instant start = sorted.getFirst().start();
        Instant end = sorted.getFirst().end();
        long minutes = 0;
        for (int index = 1; index < sorted.size(); index++) {
            Interval next = sorted.get(index);
            if (next.start().isAfter(end)) {
                minutes += Duration.between(start, end).toMinutes();
                start = next.start();
                end = next.end();
            } else if (next.end().isAfter(end)) {
                end = next.end();
            }
        }
        return Math.min(480, minutes + Duration.between(start, end).toMinutes());
    }

    private static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }

    private record Interval(Instant start, Instant end) { }
    private record DayAttendance(long regularMinutes, int overtimeHours) {
        private static final DayAttendance EMPTY = new DayAttendance(0, 0);
    }
}
