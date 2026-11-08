import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Smooth mouse drags on the device screen.
 *
 * <p>Android Studio sends a drag as mouse events through gRPC, but its window layer merges
 * pointer moves now and then: about once per drag, no event arrives for 25-40 ms and the next one
 * jumps by the 6-9 moves Windows reported in between (measured with a low-level mouse hook, which
 * sees a move every 4 ms throughout). Android then draws the content standing still and jumping,
 * which shows as stutter at 120 Hz. This happens with Google's x64 Studio as well.
 *
 * <p>While a drag is in progress this class reads the Windows cursor every millisecond and sends
 * the device position it maps to, so the motion has the pointer's own cadence. The mapping
 * (scale, which way the screen axes point on the device, and the few milliseconds Studio's events
 * lag the cursor) is fitted from Studio's own events on earlier drags, and every Studio event
 * during a driven drag is checked against it. When they disagree (the view was zoomed or moved,
 * or the input comes from somewhere other than this pointer, such as a script or a remote client)
 * the drag falls back to Studio's positions. Until the first fit, drags use Studio's positions.
 * WHPX_DRAG_SMOOTH=0 turns it off.
 */
final class WhpxDragSmoother {
    interface Sender {
        void move(int x, int y);
    }

    /** Held by the bridge around all mouse handling and by the poller around each move it sends. */
    static final Object LOCK = new Object();
    static volatile Sender sender;

    private static final boolean ENABLED = !"0".equals(System.getenv("WHPX_DRAG_SMOOTH"))
            && System.getProperty("os.name", "").startsWith("Windows");
    private static MethodHandle getCursorPos, timeBeginPeriod, timeEndPeriod;
    private static MemorySegment point;
    private static boolean nativeOk;

    static {
        if (ENABLED) {
            try {
                Linker linker = Linker.nativeLinker();
                Arena arena = Arena.global();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
                SymbolLookup winmm = SymbolLookup.libraryLookup("winmm", arena);
                getCursorPos = linker.downcallHandle(user32.find("GetCursorPos").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS));
                timeBeginPeriod = linker.downcallHandle(winmm.find("timeBeginPeriod").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, JAVA_INT));
                timeEndPeriod = linker.downcallHandle(winmm.find("timeEndPeriod").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, JAVA_INT));
                point = arena.allocate(8);
                nativeOk = true;
            } catch (Throwable t) {
                System.out.println("[DragSmoother] not available: " + t);
            }
        }
    }

    // Cursor history while a drag is in progress (time in ns, screen position), changes only.
    private static final int HIST = 2048;
    private static final long[] histT = new long[HIST];
    private static final int[] histX = new int[HIST], histY = new int[HIST];
    private static int histN = 0;

    // Studio's events in the current drag, for fitting.
    private static final int MAXEV = 4096;
    private static final long[] evT = new long[MAXEV];
    private static final int[] evX = new int[MAXEV], evY = new int[MAXEV];
    private static int evN = 0;

    // Fitted mapping: device delta = scale * R(rot) * screen delta, using the cursor lagMs earlier.
    private static int calRot = -1, calLagMs, calDisplayRot, calW, calH, calDrags;
    private static double calScale;

    // Current drag.
    private static boolean active, driving;
    private static int maxW, maxH, displayRot, d0x, d0y, s0x, s0y, lastX, lastY, fails, sent;
    private static long lastStudioNs;
    private static Thread poller;

    private static long cursor() {
        try {
            int ok = (int) getCursorPos.invokeExact(point);
            if (ok == 0) return Long.MIN_VALUE;
            return ((long) point.get(JAVA_INT, 0) << 32) | (point.get(JAVA_INT, 4) & 0xFFFFFFFFL);
        } catch (Throwable t) {
            return Long.MIN_VALUE;
        }
    }

    private static void record(long t, long c) {
        int x = (int) (c >> 32), y = (int) c;
        if (histN > 0) {
            int i = (histN - 1) % HIST;
            if (histX[i] == x && histY[i] == y) return;
        }
        int i = histN % HIST;
        histT[i] = t;
        histX[i] = x;
        histY[i] = y;
        histN++;
    }

    /** Screen position at time t (the last change at or before it), or null if not in history. */
    private static boolean cursorAt(long t, int[] out) {
        int first = Math.max(0, histN - HIST);
        for (int n = histN - 1; n >= first; n--) {
            int i = n % HIST;
            if (histT[i] <= t) {
                out[0] = histX[i];
                out[1] = histY[i];
                return true;
            }
        }
        if (histN > first) {  // Before the first sample: the cursor had not moved yet.
            int i = first % HIST;
            out[0] = histX[i];
            out[1] = histY[i];
            return true;
        }
        return false;
    }

    // The four orientations without mirroring: device delta direction for a screen delta.
    private static double rx(int rot, double dx, double dy) {
        switch (rot) { case 0: return dx; case 1: return dy; case 2: return -dx; default: return -dy; }
    }

    private static double ry(int rot, double dx, double dy) {
        switch (rot) { case 0: return dy; case 1: return -dx; case 2: return -dy; default: return dx; }
    }

    private static boolean calibrated() {
        return calRot >= 0 && calDisplayRot == displayRot && calW == maxW && calH == maxH;
    }

    /** Called by the bridge, holding LOCK, when Studio presses the button on the device screen. */
    static void onDown(int x, int y, int w, int h, int rot) {
        if (!nativeOk) return;
        long now = System.nanoTime();
        active = true;
        maxW = w;
        maxH = h;
        displayRot = rot;
        histN = 0;
        evN = 0;
        fails = 0;
        sent = 0;
        lastStudioNs = now;
        long c = cursor();
        if (c == Long.MIN_VALUE) { active = false; return; }
        record(now, c);
        addEvent(now, x, y);
        d0x = x;
        d0y = y;
        s0x = (int) (c >> 32);
        s0y = (int) c;
        lastX = x;
        lastY = y;
        driving = calibrated();
        startPoller();
        LOCK.notifyAll();
    }

    private static void addEvent(long t, int x, int y) {
        // Positions held at the edge of the screen (the pointer went past it) do not fit.
        if (evN > 0 && (x <= 0 || y <= 0 || x >= maxW - 1 || y >= maxH - 1)) return;
        if (evN < MAXEV) {
            evT[evN] = t;
            evX[evN] = x;
            evY[evN] = y;
            evN++;
        }
    }

    /**
     * Called by the bridge, holding LOCK, for each move Studio sends while the button is down.
     * Returns true when this class has handled it (the bridge must not send it).
     */
    static boolean onMove(int x, int y) {
        if (!active) return false;
        long now = System.nanoTime();
        lastStudioNs = now;
        addEvent(now, x, y);
        if (!driving) return false;
        long c = cursor();
        if (c != Long.MIN_VALUE) record(now, c);
        // Studio's position should lie on the pointer's recent path. Its events and the polled
        // cursor can be a pointer report (about 4 ms) apart either way, and a merged event can be
        // older, so look for the closest point over the last 25 ms rather than at one instant.
        double err = pathDistance(now - 25_000_000L, now + 2_000_000L, x, y);
        double tol = 24 + 0.03 * Math.hypot(x - d0x, y - d0y);
        if (err > tol) {
            if (++fails >= 2) {
                // Not this pointer's drag, or the view changed: use Studio's positions.
                driving = false;
                if (x != lastX || y != lastY) send(x, y);
                return true;
            }
        } else {
            fails = 0;
        }
        return true;
    }

    /** Smallest distance from (x, y) to the mapped cursor positions between t0 and t1. */
    private static double pathDistance(long t0, long t1, int x, int y) {
        double best = Double.MAX_VALUE;
        int[] s = new int[2];
        if (cursorAt(t0, s)) best = mappedDistance(s, x, y);
        int first = Math.max(0, histN - HIST);
        for (int n = histN - 1; n >= first; n--) {
            int i = n % HIST;
            if (histT[i] < t0) break;
            if (histT[i] > t1) continue;
            s[0] = histX[i];
            s[1] = histY[i];
            best = Math.min(best, mappedDistance(s, x, y));
        }
        return best;
    }

    private static double mappedDistance(int[] s, int x, int y) {
        double px = d0x + calScale * rx(calRot, s[0] - s0x, s[1] - s0y);
        double py = d0y + calScale * ry(calRot, s[0] - s0x, s[1] - s0y);
        return Math.hypot(px - x, py - y);
    }

    /** Called by the bridge, holding LOCK, when Studio releases the button. */
    static void onUp(int x, int y) {
        if (!active) return;
        addEvent(System.nanoTime(), x, y);
        boolean wasDriving = driving;
        active = false;
        driving = false;
        fit(wasDriving);
    }

    private static void send(int x, int y) {
        lastX = x;
        lastY = y;
        sent++;
        Sender s = sender;
        if (s != null) s.move(x, y);
    }

    private static void startPoller() {
        if (poller != null) return;
        poller = new Thread(WhpxDragSmoother::pollLoop, "whpx-drag-smoother");
        poller.setDaemon(true);
        poller.start();
    }

    private static void pollLoop() {
        boolean fastTimer = false;
        while (true) {
            try {
                synchronized (LOCK) {
                    while (!active) {
                        if (fastTimer) {
                            try { int r = (int) timeEndPeriod.invokeExact(1); } catch (Throwable ignored) {}
                            fastTimer = false;
                        }
                        LOCK.wait();
                    }
                    if (!fastTimer) {
                        try { int r = (int) timeBeginPeriod.invokeExact(1); } catch (Throwable ignored) {}
                        fastTimer = true;
                    }
                    long now = System.nanoTime();
                    if (now - lastStudioNs > 5_000_000_000L) {  // Studio went away mid-drag.
                        active = false;
                        driving = false;
                        continue;
                    }
                    long c = cursor();
                    if (c != Long.MIN_VALUE) {
                        record(now, c);
                        if (driving) {
                            int sx = (int) (c >> 32), sy = (int) c;
                            int x = (int) Math.round(d0x + calScale * rx(calRot, sx - s0x, sy - s0y));
                            int y = (int) Math.round(d0y + calScale * ry(calRot, sx - s0x, sy - s0y));
                            x = Math.max(0, Math.min(maxW - 1, x));
                            y = Math.max(0, Math.min(maxH - 1, y));
                            if (x != lastX || y != lastY) send(x, y);
                        }
                    }
                }
                Thread.sleep(1);
            } catch (Throwable t) {
                System.out.println("[DragSmoother] poller: " + t);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }
    }

    /** Fits the mapping from this drag's Studio events and the cursor history. */
    private static void fit(boolean wasDriving) {
        if (evN < 10 || histN < 2) {
            log(wasDriving, "short");
            return;
        }
        double maxDisp = 0;
        for (int i = 1; i < evN; i++) maxDisp = Math.max(maxDisp, Math.hypot(evX[i] - evX[0], evY[i] - evY[0]));
        if (maxDisp < 150) {
            log(wasDriving, "small");
            return;
        }
        int[] s = new int[2], s0 = new int[2];
        double bestRes = Double.MAX_VALUE, bestScale = 0;
        int bestRot = -1, bestLag = 0;
        for (int lag = 0; lag <= 15; lag++) {
            long lagNs = lag * 1_000_000L;
            if (!cursorAt(evT[0] - lagNs, s0)) continue;
            for (int rot = 0; rot < 4; rot++) {
                double num = 0, den = 0;
                for (int i = 1; i < evN; i++) {
                    if (!cursorAt(evT[i] - lagNs, s)) continue;
                    double ux = rx(rot, s[0] - s0[0], s[1] - s0[1]), uy = ry(rot, s[0] - s0[0], s[1] - s0[1]);
                    num += ux * (evX[i] - evX[0]) + uy * (evY[i] - evY[0]);
                    den += ux * ux + uy * uy;
                }
                if (den <= 0) continue;
                double scale = num / den;
                if (scale < 0.05 || scale > 20) continue;
                double res = 0;
                int n = 0;
                for (int i = 1; i < evN; i++) {
                    if (!cursorAt(evT[i] - lagNs, s)) continue;
                    double ux = rx(rot, s[0] - s0[0], s[1] - s0[1]), uy = ry(rot, s[0] - s0[0], s[1] - s0[1]);
                    double ex = evX[i] - evX[0] - scale * ux, ey = evY[i] - evY[0] - scale * uy;
                    res += ex * ex + ey * ey;
                    n++;
                }
                res = Math.sqrt(res / Math.max(1, n));
                if (res < bestRes) {
                    bestRes = res;
                    bestScale = scale;
                    bestRot = rot;
                    bestLag = lag;
                }
            }
        }
        if (bestRot < 0 || bestRes > 4 + 0.03 * maxDisp) {
            log(wasDriving, String.format("no fit (rms %.1f px)", bestRes));
            return;
        }
        boolean same = calibrated() && calRot == bestRot && Math.abs(bestScale / calScale - 1) < 0.04;
        if (same) {
            calScale = 0.7 * calScale + 0.3 * bestScale;
            calLagMs = (int) Math.round(0.7 * calLagMs + 0.3 * bestLag);
            calDrags++;
        } else {
            calScale = bestScale;
            calLagMs = bestLag;
            calRot = bestRot;
            calDisplayRot = displayRot;
            calW = maxW;
            calH = maxH;
            calDrags = 1;
        }
        log(wasDriving, String.format("fit scale %.4f rot %d lag %d ms rms %.1f px%s", bestScale, bestRot, bestLag, bestRes, same ? "" : " (new)"));
    }

    private static void log(boolean wasDriving, String what) {
        System.out.println("[DragSmoother] drag " + (wasDriving ? "driven, " + sent + " moves sent" : "from Studio")
                + ", " + evN + " Studio events: " + what);
    }
}
