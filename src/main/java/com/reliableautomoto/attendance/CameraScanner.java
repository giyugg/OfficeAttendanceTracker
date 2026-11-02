package com.reliableautomoto.attendance;

import com.github.sarxos.webcam.Webcam;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CameraScanner implements AutoCloseable {
    public interface Listener {
        void frame(BufferedImage image);
        void code(String employeeNumber);
        void status(String message);
        void stopped();
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "qr-camera");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    public static List<Webcam> discover() throws TimeoutException {
        return Webcam.getWebcams(10, TimeUnit.SECONDS);
    }

    public void start(Webcam camera, Listener listener) {
        if (!running.compareAndSet(false, true)) return;
        executor.submit(() -> {
            try {
                listener.status("Opening " + camera.getName() + "…");
                // Select a supported resolution instead of assuming every device supports 640 x 480.
                for (Dimension size : camera.getViewSizes()) {
                    if (size.width == 640 && size.height == 480) { camera.setViewSize(size); break; }
                }
                if (!running.get()) return;
                if (!camera.open()) throw new IllegalStateException("The camera could not be opened.");
                listener.status("Camera ready. Present one QR code, then remove it after confirmation.");
                QrDecoder decoder = new QrDecoder();
                QrPresenceGate gate = new QrPresenceGate();
                int missingFrames = 0;
                while (running.get()) {
                    BufferedImage frame = camera.getImage();
                    if (frame == null) {
                        if (++missingFrames > 30) throw new IllegalStateException("No camera frames received. Check the camera connection.");
                    } else {
                        missingFrames = 0;
                        listener.frame(frame);
                        String code = decoder.decode(frame).orElse(null);
                        if (gate.accept(code, System.nanoTime())) listener.code(code);
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception | LinkageError error) {
                listener.status("Camera unavailable: " + error.getMessage() + ". You can use a USB scanner below.");
            } finally {
                try { if (camera.isOpen()) camera.close(); }
                catch (Exception | LinkageError ignored) { /* Still restore the UI after a native driver error. */ }
                running.set(false);
                listener.stopped();
            }
        });
    }

    public void stop() { running.set(false); }

    @Override public void close() {
        stop();
        executor.shutdown();
    }
}
