package com.homehub.platform;

import com.homehub.alerts.Alert;
import com.homehub.alerts.Alert.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Disk and memory on the hub (#102): a warning when the database's disk gets full or the hub runs short of memory,
 * and an all clear once it is comfortably better again (5 points), so a value that wobbles around the limit is
 * reported once. No I/O; one thread calls it.
 */
final class HubResources {

    static final String DISK_KEY = "disk:hub";
    static final String MEMORY_KEY = "memory:hub";
    static final String DEVICE = "Hub";
    static final int MARGIN = 5;

    private final int diskWarn;
    private final int memoryWarn;
    private Alert diskProblem;     // null: fine
    private Alert memoryProblem;

    HubResources(int diskWarn, int memoryWarn) {
        this.diskWarn = diskWarn;
        this.memoryWarn = memoryWarn;
    }

    /** One look at the disk: its size and free space in bytes. */
    List<Alert> disk(long total, long usable, Instant now) {
        if (total <= 0) return List.of();
        int used = (int) Math.round(100.0 * (total - usable) / total);
        if (diskProblem == null && used >= diskWarn) {
            diskProblem = new Alert(DISK_KEY, Level.PROBLEM, DEVICE,
                    "The hub's disk is " + used + "% full: " + gigabytes(usable) + " free.", now);
            return List.of(diskProblem);
        }
        if (diskProblem != null && used <= diskWarn - MARGIN) {
            diskProblem = null;
            return List.of(new Alert(DISK_KEY, Level.CLEARED, DEVICE,
                    "The hub's disk has room again: " + used + "% full.", now));
        }
        return List.of();
    }

    /**
     * One look at memory, from /proc/meminfo's values in kB: short of memory when what is available falls below
     * {@code memoryWarn}% of all memory, or swap (if any) is more than {@code 100 - memoryWarn}% full.
     */
    List<Alert> memory(Map<String, Long> meminfo, Instant now) {
        Long total = meminfo.get("MemTotal"), available = meminfo.get("MemAvailable");
        if (total == null || available == null || total <= 0) return List.of();
        long swapTotal = meminfo.getOrDefault("SwapTotal", 0L), swapFree = meminfo.getOrDefault("SwapFree", 0L);
        int freePercent = (int) Math.round(100.0 * available / total);
        int swapUsed = swapTotal > 0 ? (int) Math.round(100.0 * (swapTotal - swapFree) / swapTotal) : 0;
        boolean short_ = freePercent < memoryWarn || swapUsed > 100 - memoryWarn;
        boolean fine = freePercent >= memoryWarn + MARGIN && swapUsed <= 100 - memoryWarn - MARGIN;
        if (memoryProblem == null && short_) {
            memoryProblem = new Alert(MEMORY_KEY, Level.PROBLEM, DEVICE, "The hub is short of memory: "
                    + megabytes(available) + " free" + (swapTotal > 0 ? ", swap " + swapUsed + "% used" : "") + ".", now);
            return List.of(memoryProblem);
        }
        if (memoryProblem != null && fine) {
            memoryProblem = null;
            return List.of(new Alert(MEMORY_KEY, Level.CLEARED, DEVICE,
                    "The hub has enough memory again: " + megabytes(available) + " free.", now));
        }
        return List.of();
    }

    /** Problems going on now, for the dashboard. */
    List<Alert> active() {
        List<Alert> active = new ArrayList<>();
        if (diskProblem != null) active.add(diskProblem);
        if (memoryProblem != null) active.add(memoryProblem);
        return active;
    }

    private static String gigabytes(long bytes) {
        double gb = bytes / 1e9;
        return (gb < 10 ? String.format(java.util.Locale.ROOT, "%.1f", gb) : String.valueOf(Math.round(gb))) + " GB";
    }

    private static String megabytes(long kb) {
        return Math.round(kb / 1024.0) + " MB";
    }
}
