import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Asks Windows to treat QEMU and the renderer as foreground work.
 *
 * Measured on a 12-core Snapdragon X Elite with a 120 Hz guest: under the Balanced power mode,
 * 20 of 1200 frame wake-ups ran more than 4 ms late; under Best performance, about 7. With
 * Chrome open, hard paging of QEMU's memory caused 20-40 ms stalls.
 *
 * - Power throttling (EcoQoS) off and timer resolution honoured: the effect of Best performance
 *   for these processes only, without changing the power mode.
 * - Above-normal priority, so background apps don't delay a vCPU that is ready to run.
 * - A hard minimum working set for QEMU, so Windows doesn't page out the guest's RAM.
 *
 * Set WHPX_HOST_TUNING=0 to turn all of it off.
 */
final class WhpxHostTuning {
    private static final int PROCESS_SET_QUOTA = 0x0100;
    private static final int PROCESS_SET_INFORMATION = 0x0200;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x8000;
    private static final int PROCESS_POWER_THROTTLING_INFO_CLASS = 4; // ProcessPowerThrottling
    private static final int THROTTLE_EXECUTION_SPEED = 0x1;
    private static final int THROTTLE_IGNORE_TIMER_RESOLUTION = 0x4;
    private static final int QUOTA_LIMITS_HARDWS_MIN_ENABLE = 0x1;
    private static final int QUOTA_LIMITS_HARDWS_MAX_DISABLE = 0x8;

    private static MethodHandle openProcess, closeHandle, setPriorityClass, setProcessInformation,
            setWorkingSet;
    private static java.lang.invoke.VarHandle lastErrorHandle;
    private static boolean ready;

    private WhpxHostTuning() {}

    static boolean enabled() {
        return !"0".equals(System.getenv("WHPX_HOST_TUNING"));
    }

    private static synchronized boolean init() {
        if (ready) return true;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            openProcess = linker.downcallHandle(k32.find("OpenProcess").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            closeHandle = linker.downcallHandle(k32.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            setPriorityClass = linker.downcallHandle(k32.find("SetPriorityClass").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            setProcessInformation = linker.downcallHandle(k32.find("SetProcessInformation").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
            setWorkingSet = linker.downcallHandle(k32.find("SetProcessWorkingSetSizeEx").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT), lastError);
            lastErrorHandle = Linker.Option.captureStateLayout().varHandle(
                    java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
            ready = true;
        } catch (Throwable t) {
            System.out.println("[WhpxHostTuning] unavailable: " + t);
        }
        return ready;
    }

    /** Throttling off and above-normal priority. Used for QEMU and the renderer. */
    static void boost(Process p, String name) {
        apply(p, name, 0);
    }

    /** As boost(), and also keeps residentMb of the process resident. Used for QEMU. */
    static void boostAndPin(Process p, String name, long residentMb) {
        apply(p, name, residentMb);
    }

    private static void apply(Process p, String name, long residentMb) {
        if (p == null || !enabled() || !init()) return;
        StringBuilder out = new StringBuilder("[WhpxHostTuning] " + name + " pid " + p.pid() + ":");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = (MemorySegment) openProcess.invokeExact(
                    PROCESS_SET_QUOTA | PROCESS_SET_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION, 0, (int) p.pid());
            if (h.address() == 0) {
                System.out.println(out + " OpenProcess failed");
                return;
            }
            try {
                // PROCESS_POWER_THROTTLING_STATE { Version = 1, ControlMask, StateMask = 0 }:
                // the masked behaviours are controlled and switched off.
                MemorySegment st = arena.allocate(12);
                st.set(JAVA_INT, 0, 1);
                st.set(JAVA_INT, 4, THROTTLE_EXECUTION_SPEED | THROTTLE_IGNORE_TIMER_RESOLUTION);
                st.set(JAVA_INT, 8, 0);
                int ok = (int) setProcessInformation.invokeExact(h, PROCESS_POWER_THROTTLING_INFO_CLASS, st, 12);
                out.append(ok != 0 ? " throttling off" : " throttling unchanged");

                ok = (int) setPriorityClass.invokeExact(h, ABOVE_NORMAL_PRIORITY_CLASS);
                out.append(ok != 0 ? ", above-normal priority" : ", priority unchanged");

                if (residentMb > 0) {
                    long min = residentMb << 20;
                    long max = min + (1L << 30);
                    MemorySegment cap = arena.allocate(Linker.Option.captureStateLayout());
                    ok = (int) setWorkingSet.invokeExact(cap, h, min, max,
                            QUOTA_LIMITS_HARDWS_MIN_ENABLE | QUOTA_LIMITS_HARDWS_MAX_DISABLE);
                    out.append(ok != 0 ? ", " + residentMb + " MB kept resident"
                            : ", working set unchanged (error " + (int) lastErrorHandle.get(cap, 0L) + ")");
                }
            } finally {
                int ignored = (int) closeHandle.invokeExact(h);
            }
        } catch (Throwable t) {
            out.append(" failed: ").append(t);
        }
        System.out.println(out);
    }

    /** RAM to keep resident for a guest: its RAM plus QEMU's own overhead, capped at 40% of the host. */
    static long residentMbFor(int guestRamMb) {
        long hostMb = hostRamMb();
        long want = guestRamMb + 512L;
        if (hostMb <= 0) return 0;
        if (hostMb < 12L * 1024) return 0; // small hosts: pinning would just move the paging to Studio
        return Math.min(want, hostMb * 2 / 5);
    }

    private static long hostRamMb() {
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) return sun.getTotalMemorySize() >> 20;
        } catch (Throwable ignored) {
        }
        return 0;
    }
}
