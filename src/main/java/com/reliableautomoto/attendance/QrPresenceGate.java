package com.reliableautomoto.attendance;

import java.time.Duration;

/** A continuously visible QR is delivered once. Brief decoding misses do not re-arm it. */
public final class QrPresenceGate {
    private static final long ABSENCE_NANOS = Duration.ofMillis(1500).toNanos();
    private String visible;
    private long lastSeen;

    public boolean accept(String value, long nowNanos) {
        if (value == null) {
            if (visible != null && nowNanos - lastSeen >= ABSENCE_NANOS) visible = null;
            return false;
        }
        boolean changed = !value.equals(visible);
        visible = value;
        lastSeen = nowNanos;
        return changed;
    }
}
