package com.reliableautomoto.attendance;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class QrScanningTest {
    @TempDir Path directory;

    @Test void decodesQrPayloadAndRecordsAttendanceWithoutLosingLeadingZeros() throws Exception {
        // Synthetic QR fixtures are only used in tests. The application never generates employee QR codes.
        var image = MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode("000123", BarcodeFormat.QR_CODE, 360, 360));
        String decoded = new QrDecoder().decode(image).orElseThrow();
        assertEquals("000123", decoded);
        AttendanceDatabase database = new AttendanceDatabase(directory.resolve("test.db"));
        database.addEmployee("000123", "Test Employee");
        var service = new AttendanceService(database, Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));
        assertEquals(ScanResult.Status.TIME_IN, service.scan(decoded).status());
        assertEquals("000123", database.attendance(null, null, true).getFirst().employeeNumber());
    }

    @Test void blankFramesAndNullImagesAreNotTreatedAsScans() {
        QrDecoder decoder = new QrDecoder();
        assertTrue(decoder.decode(null).isEmpty());
        assertTrue(decoder.decode(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)).isEmpty());
    }

    @Test void continuouslyVisibleQrDoesNotRescanEvenAfterCooldown() {
        QrPresenceGate gate = new QrPresenceGate();
        assertTrue(gate.accept("000123", 0));
        for (int second = 1; second <= 30; second++) assertFalse(gate.accept("000123", second * 1_000_000_000L));
    }

    @Test void briefDecodeMissDoesNotRearmButRemovingQrDoes() {
        QrPresenceGate gate = new QrPresenceGate();
        assertTrue(gate.accept("000123", 0));
        assertFalse(gate.accept(null, 1_000_000_000L));
        assertFalse(gate.accept("000123", 1_100_000_000L));
        assertFalse(gate.accept(null, 3_000_000_000L));
        assertTrue(gate.accept("000123", 3_100_000_000L));
    }

    @Test void anotherEmployeeCanScanImmediately() {
        QrPresenceGate gate = new QrPresenceGate();
        assertTrue(gate.accept("A", 0));
        assertTrue(gate.accept("B", 100));
        assertFalse(gate.accept("B", 200));
    }
}
