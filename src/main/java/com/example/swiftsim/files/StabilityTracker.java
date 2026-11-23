package com.example.swiftsim.files;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stable-file gate. A non-empty file is ingested only after its size and mtime have stayed the same
 * for {@code stableMs}. {@code stableMs == 0} accepts a non-empty file on the first observation
 * (still requires the producer to have finished; zero-byte files are never ready).
 * Producers should write a temporary name and atomically rename into the outgoing directory.
 */
public final class StabilityTracker {
    private record Observation(long size, long mtimeMillis, long sinceMillis) {}

    private final ConcurrentHashMap<String, Observation> seen = new ConcurrentHashMap<>();

    public boolean ready(String key, long size, long mtimeMillis, long stableMs, long nowMillis) {
        if (size <= 0) return false;
        Observation prev = seen.get(key);
        if (prev == null || prev.size != size || prev.mtimeMillis != mtimeMillis) {
            seen.put(key, new Observation(size, mtimeMillis, nowMillis));
            return stableMs <= 0;
        }
        return nowMillis - prev.sinceMillis >= stableMs;
    }

    public void forget(String key) {
        seen.remove(key);
    }
}
