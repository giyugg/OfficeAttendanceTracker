package com.reliableautomoto.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvExporterTest {
    @TempDir Path directory;

    @Test void csvEscapesNamesPreservesPayloadAndUsesLocalOffsets() throws Exception {
        Path csv = directory.resolve("attendance.csv");
        Instant time = Instant.parse("2026-11-02T00:00:00Z");
        CsvExporter.write(csv, List.of(new AttendanceRecord(1, "000123", "Santos, \"María\"", time, time.plusSeconds(60))),
                ZoneId.of("Asia/Manila"));
        String content = Files.readString(csv);
        assertTrue(content.startsWith("\uFEFFRecord ID,"));
        assertTrue(content.contains("\"000123\""));
        assertTrue(content.contains("\"Santos, \"\"María\"\"\""));
        assertTrue(content.contains("2026-11-02T08:00:00+08:00"));
        assertTrue(content.contains("\"0h 01m 00s\",\"OUT\"\r\n"));
    }

    @Test void spreadsheetFormulasAreEscaped() {
        assertEquals("\"'=1+1\"", CsvExporter.cell("=1+1"));
        assertEquals("\"'  @SUM(A1)\"", CsvExporter.cell("  @SUM(A1)"));
        assertEquals("\"'-2\"", CsvExporter.cell("-2"));
        assertEquals("\"'\t=1+1\"", CsvExporter.cell("\t=1+1"));
        assertEquals("\"'\r+1\"", CsvExporter.cell("\r+1"));
    }

    @Test void openShiftExportsWithoutAFabricatedTimeOut() throws Exception {
        Path csv = directory.resolve("open.csv");
        CsvExporter.write(csv, List.of(new AttendanceRecord(1, "EMP-1", "Alex", Instant.EPOCH, null)), ZoneId.of("UTC"));
        assertTrue(Files.readString(csv).contains("\"\",\"In progress\",\"IN\""));
    }
}
