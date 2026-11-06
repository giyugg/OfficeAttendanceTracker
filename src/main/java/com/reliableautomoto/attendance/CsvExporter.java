package com.reliableautomoto.attendance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class CsvExporter {
    private CsvExporter() { }

    public static void write(Path path, List<AttendanceRecord> records, ZoneId zone) throws IOException {
        DateTimeFormatter format = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF'); // Allows Excel to recognize UTF-8 names.
            writer.write("Record ID,Employee Number,Employee Name,Time In,Time Out,Duration,Status\r\n");
            for (AttendanceRecord record : records) {
                String[] values = {Long.toString(record.id()), record.employeeNumber(), record.employeeName(),
                        format.format(record.timeIn()), record.timeOut() == null ? "" : format.format(record.timeOut()),
                        record.duration(), record.timeOut() == null ? "IN" : "OUT"};
                for (int index = 0; index < values.length; index++) {
                    if (index > 0) writer.write(',');
                    writer.write(cell(values[index]));
                }
                writer.write("\r\n");
            }
        }
    }

    static String cell(String value) {
        // Quoting alone does not stop spreadsheet programs from evaluating formulas.
        String trimmed = value.stripLeading();
        if (!trimmed.isEmpty() && "=+-@\t\r".indexOf(trimmed.charAt(0)) >= 0
                || !value.isEmpty() && "\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
