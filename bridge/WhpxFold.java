import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Folding for foldable AVDs (hw.sensor.hinge=yes), such as the Pixel 10 Pro Fold.
 *
 * <p>Android Studio shows Closed / Half-open / Open when hardware-qemu.ini lists the hinge and
 * its postures, and sends the hinge angle with setPhysicalModel(HINGE_ANGLE0). The emulator has no
 * hinge sensor and one screen, so the guest is folded through Android's device states:
 * /data/system/devicestate/device_state_configuration.xml gives it CLOSED (0), HALF_OPENED (1),
 * OPENED (2) and REAR_DISPLAY (3), matching the image's config_device_state_postures. It boots
 * open, and "cmd device_state state N" enters the others. Closed also gives the screen the outer
 * display's size, so apps lay out as on the cover screen.
 *
 * <p>The closed state must have DISPLAY_CONFIGURATION_OUTER_PRIMARY: the window manager's
 * per-posture auto-rotate setup looks up a state for each posture at startup and stops
 * system_server if one is missing. If system_server still fails to start, the file is removed.
 */
final class WhpxFold {
    static final int CLOSED = 1, HALF_OPENED = 2, OPENED = 3;   // Posture.PostureValue numbers

    private static boolean foldable;
    private static final List<double[]> ranges = new ArrayList<>();
    private static final List<Integer> postures = new ArrayList<>();
    private static final StringBuilder iniLines = new StringBuilder();
    private static int outerW, outerH, density;
    private static volatile float angle = 180f;
    private static volatile int posture = OPENED;
    private static volatile boolean guestReady;

    private WhpxFold() {}

    static boolean foldable() { return foldable; }
    static float angle() { return angle; }
    static int posture() { return posture; }

    /** Reads the hinge settings from the AVD's config.ini. */
    static void configure(Map<String, String> cfg, int lcdDensity) {
        foldable = "yes".equalsIgnoreCase(cfg.getOrDefault("hw.sensor.hinge", "no").trim())
                && "1".equals(cfg.getOrDefault("hw.sensor.hinge.count", "1").trim());
        ranges.clear();
        postures.clear();
        iniLines.setLength(0);
        if (!foldable) return;
        String defs = cfg.getOrDefault("hw.sensor.hinge_angles_posture_definitions", "0-30, 30-150, 150-180");
        String list = cfg.getOrDefault("hw.sensor.posture_list", "1, 2, 3");
        for (String r : defs.split(",")) {
            String[] mm = r.trim().split("-");
            try { ranges.add(new double[] { Double.parseDouble(mm[0]), Double.parseDouble(mm[1]) }); } catch (Exception e) { foldable = false; return; }
        }
        for (String p : list.split(",")) {
            try { postures.add(Integer.parseInt(p.trim())); } catch (Exception e) { foldable = false; return; }
        }
        if (ranges.size() != postures.size() || ranges.isEmpty()) { foldable = false; return; }
        outerW = parse(cfg.get("hw.displayRegion.0.1.width"), 0);
        outerH = parse(cfg.get("hw.displayRegion.0.1.height"), 0);
        density = lcdDensity;
        try { angle = Float.parseFloat(cfg.getOrDefault("hw.sensor.hinge.defaults", "180").trim().split(",")[0]); } catch (Exception e) { angle = 180f; }
        posture = postureFor(angle);
        // Everything Studio reads to offer the postures, copied from config.ini.
        for (Map.Entry<String, String> e : cfg.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("hw.sensor.hinge") || k.startsWith("hw.sensor.posture_list") || k.startsWith("hw.displayRegion.")) {
                iniLines.append(k).append(" = ").append(e.getValue().trim()).append("\r\n");
            }
        }
        if (!cfg.containsKey("hw.sensor.posture_list")) iniLines.append("hw.sensor.posture_list = ").append(list).append("\r\n");
        if (!cfg.containsKey("hw.sensor.hinge_angles_posture_definitions")) {
            iniLines.append("hw.sensor.hinge_angles_posture_definitions = ").append(defs).append("\r\n");
        }
        System.out.println("[WhpxFold] foldable: postures " + postures + " at hinge angles " + defs
                + ", outer screen " + outerW + "x" + outerH);
    }

    /** Replaces the hinge lines in hardware-qemu.ini with this AVD's when it folds. */
    static String patchIni(String ini) {
        if (!foldable) return ini;
        // Only the keys this AVD sets are replaced; the defaults for the rest stay.
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String l : iniLines.toString().split("\r\n")) if (!l.isBlank()) keys.add(l.split("=", 2)[0].trim());
        StringBuilder out = new StringBuilder();
        for (String line : ini.split("\r\n", -1)) {
            String k = line.split("=", 2)[0].trim();
            if (keys.contains(k)) continue;
            if (out.length() > 0) out.append("\r\n");
            out.append(line);
        }
        return out + "\r\n" + iniLines.toString().trim();
    }

    static int postureFor(double a) {
        for (int i = 0; i < ranges.size(); i++) {
            double[] r = ranges.get(i);
            boolean last = i == ranges.size() - 1;
            if (a >= r[0] && (a < r[1] || (last && a <= r[1]))) return postures.get(i);
        }
        return a <= 90 ? CLOSED : OPENED;
    }

    /** Records a new hinge angle. Returns true when the posture changed. */
    static boolean setAngle(float a) {
        angle = a;
        int p = postureFor(a);
        if (p == posture) return false;
        posture = p;
        return true;
    }

    /** Shell commands that put the guest in the current posture. */
    static String guestCommands() {
        if (!foldable || !guestReady) return null;
        // Android scales the density with the screen size, and does it again shortly after
        // (when the device state's display layout applies), so keep setting it until it holds.
        String keepDensity = "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do [ \"$(wm density | tail -1)\" = \"Override density: " + density
                + "\" ] || wm density " + density + "; sleep 1; done";
        String innerCutout = "cmd overlay disable com.android.shell:WhpxCoverCutout >/dev/null 2>&1; "
                + "cmd overlay disable com.android.shell:WhpxCoverCutoutRect >/dev/null 2>&1; ";
        // Overlay changes reset the density too, so the cutout goes first and the density last.
        switch (posture) {
            case CLOSED:
                if (outerW <= 0 || outerH <= 0) return "cmd device_state state 0";
                return coverCutout() + "sleep 1; cmd device_state state 0; wm size " + outerW + "x" + outerH + "; " + keepDensity;
            case HALF_OPENED:
                return innerCutout + "sleep 1; cmd device_state state 1; wm size reset; " + keepDensity;
            default:
                return innerCutout + "sleep 1; cmd device_state state reset; wm size reset; " + keepDensity;
        }
    }

    private static String coverCutoutCmds, coverCutoutSetup;

    /**
     * The cover screen's camera cutout, from the hole in the closed skin's mask. Without it the
     * inner screen's cutout (a corner hole) stays, and the system UI draws it on the cover screen.
     * Android takes cutouts in panel pixels and scales them by the screen's width, so the hole's
     * cover-screen position is scaled back up.
     */
    private static String coverCutout() {
        return coverCutoutCmds == null ? "" : coverCutoutCmds;
    }

    static void loadCoverCutout(File skinDir, int lcdWidth) {
        coverCutoutCmds = null;
        if (!foldable || skinDir == null || outerW <= 0 || lcdWidth <= 0) return;
        try {
            File mask = new File(new File(skinDir, "closed"), "mask.webp");
            if (!mask.isFile()) mask = new File(new File(skinDir, "closed"), "mask.png");
            if (!mask.isFile()) return;
            java.awt.image.BufferedImage m = javax.imageio.ImageIO.read(mask);
            if (m == null || m.getWidth() != outerW) return;
            int[] hole = interiorHole(m);
            if (hole == null) return;
            double s = (double) outerW / lcdWidth;
            double r = (hole[2] - hole[0] + 1) / 2.0 / s, cx = (hole[0] + hole[2] + 1) / 2.0 / s, cy = (hole[1] + hole[3] + 1) / 2.0 / s;
            String path = String.format(java.util.Locale.ROOT, "m %.1f,%.1f a %.1f,%.1f 0 0 0 %.1f,0 %.1f,%.1f 0 0 0 %.1f,0 z @left",
                    cx + r, cy, r, r, -2 * r, r, r, 2 * r);
            String rect = String.format(java.util.Locale.ROOT, "m %.1f,%.1f h %.1f v %.1f h %.1f z @left",
                    cx - r, cy - r, 2 * r, 2 * r, -2 * r);
            // Fabricated once per boot (re-registering an overlay applies late, about 14 s on,
            // and resets the density then); folding only enables and disables them.
            coverCutoutSetup = "cmd overlay fabricate --target android --name WhpxCoverCutout android:string/config_mainBuiltInDisplayCutout string \"" + path + "\" >/dev/null 2>&1; "
                    + "cmd overlay fabricate --target android --name WhpxCoverCutoutRect android:string/config_mainBuiltInDisplayCutoutRectApproximation string \"" + rect + "\" >/dev/null 2>&1; ";
            coverCutoutCmds = "cmd overlay enable com.android.shell:WhpxCoverCutout >/dev/null 2>&1; cmd overlay enable com.android.shell:WhpxCoverCutoutRect >/dev/null 2>&1; ";
            System.out.println("[WhpxFold] cover screen cutout at " + hole[0] + "," + hole[1] + "-" + hole[2] + "," + hole[3]);
        } catch (Exception e) {
            System.out.println("[WhpxFold] no cover screen cutout: " + e);
        }
    }

    /** Bounds {x0, y0, x1, y1} of the opaque blob in the mask's top fifth that touches no edge. */
    private static int[] interiorHole(java.awt.image.BufferedImage m) {
        int w = m.getWidth(), h = Math.min(m.getHeight(), m.getHeight() / 5);
        boolean[] seen = new boolean[w * h];
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (seen[y * w + x] || (m.getRGB(x, y) >>> 24) < 128) continue;
                int x0 = x, x1 = x, y0 = y, y1 = y, n = 0;
                boolean edge = false;
                stack.push(new int[] { x, y });
                seen[y * w + x] = true;
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    n++;
                    x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]); y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]);
                    if (p[0] == 0 || p[1] == 0 || p[0] == w - 1 || p[1] == h - 1) edge = true;
                    int[][] nb = { { p[0] + 1, p[1] }, { p[0] - 1, p[1] }, { p[0], p[1] + 1 }, { p[0], p[1] - 1 } };
                    for (int[] q : nb) {
                        if (q[0] < 0 || q[1] < 0 || q[0] >= w || q[1] >= h || seen[q[1] * w + q[0]]) continue;
                        if ((m.getRGB(q[0], q[1]) >>> 24) < 128) continue;
                        seen[q[1] * w + q[0]] = true;
                        stack.push(q);
                    }
                }
                if (!edge && n > 50 && x1 - x0 < w / 4) return new int[] { x0, y0, x1, y1 };
            }
        }
        return null;
    }

    /** The cover screen's size while closed (the guest's screen then has this size), else null. */
    static int[] overrideSize() {
        if (!foldable || !guestReady || posture != CLOSED || outerW <= 0 || outerH <= 0) return null;
        return new int[] { outerW, outerH };
    }

    /** The density the guest should have once set up, or 0 when the bridge does not manage it. */
    static int expectedDensity() {
        return foldable && guestReady ? density : 0;
    }

    /** A new QEMU run starts open: Android's device state does not survive the restart. */
    static void onGuestRestart() {
        guestReady = false;
    }

    private static String stateXml() {
        String p = "com.android.server.policy.PROPERTY_";
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!-- Written by the WHPX emulator bridge for foldable AVDs. There is no hinge sensor: the device\n"
            + "     boots open, and the bridge enters the other states when Android Studio folds it. -->\n"
            + "<device-state-config>\n"
            + state(0, "CLOSED", p + "FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED", p + "FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY", p + "EMULATED_ONLY")
            + state(1, "HALF_OPENED", p + "FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN", p + "FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY", p + "EMULATED_ONLY")
            + state(2, "OPENED", p + "FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN", p + "FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY")
            + state(3, "REAR_DISPLAY", p + "FEATURE_REAR_DISPLAY", p + "FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN", p + "EMULATED_ONLY")
            + "</device-state-config>\n";
    }

    private static String state(int id, String name, String... props) {
        StringBuilder s = new StringBuilder("  <device-state>\n    <identifier>").append(id).append("</identifier>\n    <name>")
            .append(name).append("</name>\n    <properties>\n");
        for (String pr : props) s.append("      <property>").append(pr).append("</property>\n");
        return s.append("    </properties>\n  </device-state>\n").toString();
    }

    /**
     * Installs the device states in the guest after boot. The first time, the framework restarts
     * once to load them (about 15 s); later boots find them in /data. Then applies the posture.
     */
    static void ensureGuest(String adbExe, String serial, String bridgeDir, java.util.function.Consumer<String> shell) {
        if (!foldable || serial == null) return;
        try {
            // adb can drop the device for a moment around boot (it reconnects): wait for it.
            for (int i = 0; i < 30 && !run(adbExe, serial, 10, "get-state").trim().equals("device"); i++) Thread.sleep(2000);
            // whpx_loop.sh scales the density down with a narrower screen (for 4K TVs); the
            // cover screen keeps the device's density.
            run(adbExe, serial, 10, "shell", "setprop whpx.fold 1");
            File tmp = new File(bridgeDir, "whpx_device_state_" + serial.replaceAll("[^A-Za-z0-9]", "_") + ".xml");
            Files.write(tmp.toPath(), stateXml().getBytes(StandardCharsets.UTF_8));
            run(adbExe, serial, 30, "push", tmp.getPath(), "/data/local/tmp/whpx_dsc.xml");
            tmp.delete();
            String dst = "/data/system/devicestate/device_state_configuration.xml";
            String out = run(adbExe, serial, 15, "shell",
                "cmp -s /data/local/tmp/whpx_dsc.xml " + dst + " || { mkdir -p /data/system/devicestate && cp /data/local/tmp/whpx_dsc.xml " + dst
                + " && chown -R system:system /data/system/devicestate && chmod 644 " + dst + " && restorecon -R /data/system/devicestate >/dev/null 2>&1; echo CHANGED; }; rm -f /data/local/tmp/whpx_dsc.xml");
            if (out.contains("CHANGED")) {
                System.out.println("[WhpxFold] installed device states; restarting Android's framework once to load them");
                String oldPid = run(adbExe, serial, 10, "shell", "pidof system_server").trim();
                run(adbExe, serial, 10, "shell", "logcat -b crash -c; setprop ctl.restart zygote");
                boolean up = false;
                for (int i = 0; i < 60 && !up; i++) {
                    Thread.sleep(2000);
                    String crashes = run(adbExe, serial, 10, "shell", "logcat -d -b crash | grep -c 'FATAL EXCEPTION IN SYSTEM PROCESS'").trim();
                    if (parse(crashes, 0) >= 2) {
                        System.out.println("[WhpxFold] Android's framework did not start with the device states; removing them");
                        run(adbExe, serial, 10, "shell", "rm -f " + dst + "; setprop ctl.restart zygote");
                        foldable = false;
                        return;
                    }
                    String pid = run(adbExe, serial, 10, "shell", "pidof system_server").trim();
                    String boot = run(adbExe, serial, 10, "shell", "cmd device_state print-states 2>/dev/null | grep -c CLOSED").trim();
                    up = !pid.isEmpty() && !pid.equals(oldPid) && parse(boot, 0) > 0;
                }
                if (!up) System.out.println("[WhpxFold] framework restart not confirmed");
            }
            String states = run(adbExe, serial, 10, "shell", "cmd device_state print-states 2>/dev/null | grep -c identifier=").trim();
            guestReady = parse(states, 0) >= 3;
            System.out.println("[WhpxFold] device states in guest: " + states + (guestReady ? "" : " (folding unavailable)"));
            if (guestReady && coverCutoutSetup != null) {
                // Fabricated disabled; the posture commands below enable them only when closed.
                run(adbExe, serial, 30, "shell", coverCutoutSetup
                    + "cmd overlay disable com.android.shell:WhpxCoverCutout >/dev/null 2>&1; cmd overlay disable com.android.shell:WhpxCoverCutoutRect >/dev/null 2>&1");
            }
            String cmds = guestCommands();
            // Always, open too: Android keeps the "wm size" override across boots, so a device
            // shut down folded would otherwise start open with the cover screen's size.
            if (cmds != null) shell.accept(cmds);
        } catch (Exception e) {
            System.out.println("[WhpxFold] setup failed: " + e);
        }
    }

    private static String run(String adbExe, String serial, int seconds, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbExe);
        cmd.add("-s");
        cmd.add(serial);
        for (String a : args) cmd.add(a);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor(seconds, TimeUnit.SECONDS);
        return new String(out, StandardCharsets.UTF_8);
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
