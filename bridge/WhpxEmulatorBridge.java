import com.android.tools.idea.io.grpc.*;



import com.android.tools.idea.io.grpc.stub.StreamObserver;



import com.android.emulator.control.*;



import com.android.tools.idea.protobuf.ByteString;



import com.android.tools.idea.protobuf.Empty;







import javax.imageio.ImageIO;



import java.awt.BasicStroke;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



import java.awt.Color;



import java.awt.Font;



import java.awt.GradientPaint;



import java.awt.Graphics2D;



import java.awt.RenderingHints;



import java.awt.image.BufferedImage;



import java.io.*;



import java.net.InetAddress;



import java.net.ServerSocket;



import java.net.Socket;



import java.nio.ByteBuffer;



import java.nio.ByteOrder;



import java.nio.MappedByteBuffer;



import java.nio.channels.FileChannel;



import java.nio.charset.StandardCharsets;



import java.nio.file.*;



import java.text.SimpleDateFormat;



import java.util.*;



import java.util.concurrent.*;



import java.util.concurrent.atomic.AtomicBoolean;







public class WhpxEmulatorBridge {



    private static final long START_TIME = System.currentTimeMillis();



    // Set when the guest kernel logs a restart; QEMU runs with -no-reboot, so it exits and the bridge starts it again.
    private static volatile boolean guestRebooting = false;
    private static volatile int bootCount = 0;
    private static volatile boolean adbOnline = false;



    private static volatile Process whpxProc = null;

    private static int serialPort = 15554;

    private static volatile Socket serialSocket = null;

    private static volatile OutputStream serialOut = null;



    private static final Object serialWriteLock = new Object();







    // Dynamic AVD configuration



    private static String sdkDir = System.getenv("ANDROID_HOME") != null ? System.getenv("ANDROID_HOME") :



        (System.getenv("ANDROID_SDK_ROOT") != null ? System.getenv("ANDROID_SDK_ROOT") :



        (System.getenv("LOCALAPPDATA") + "\\Android\\Sdk"));







    private static String getBridgeDir() {



        String sdkBridge = sdkDir + "\\emulator\\whpx_bridge";



        if (Files.isDirectory(Paths.get(sdkBridge))) {



            return sdkBridge;



        }



        return sdkBridge;



    }







    private static String bridgeDir = getBridgeDir();



    private static String avdId = "Pixel_10_Pro";



    private static String displayName = "Pixel 10 Pro";



    private static String avdDir = "";



    private static String sysDir = "";



    private static String targetStr = "android-37.0";



    private static String apiLevel = "37";



    private static boolean is16kPages = false;
    private static boolean isPixel7 = false;
    private static boolean isWear = false;
    private static boolean windowHidden = false;
    private static String skinPath = null;



    private static int lcdWidth = 1080;
    /** The AVD's hw.mainKeys. Without hardware keys Android draws a navigation bar or taskbar. */
    private static boolean mainKeys = false;



    private static int lcdHeight = 1920;



    private static int lcdDensity = 420;



    private static int ramMb = 4096;



    private static int cpuCores = 4;



    private static int consolePort = 5554;



    private static int adbPort = 5555;



    private static int inputPort = 10555;



    private static int grpcPort = 8554;



    private static String emulatorSerial = "emulator-5554";

    // Clicks arrive in panel pixels but are injected in UI coordinates. TV caps its UI
    // below the panel (1920x1080 on 4K), so an unscaled click lands at twice its position
    // and most of the screen cannot be reached. 1.0 everywhere else.
    // From the skin's layout file ("cutout hole"). Android has no other way to learn the
    // screen shape, and without it the status bar is laid out under the frame's corners.
    private static String skinCutout = "";
    private static String skinName = "";

    private static String skinDirName(String skinPath) {
        if (skinPath == null) return "";
        String n = Paths.get(skinPath).getFileName().toString();
        return n.matches("[a-z0-9_]+") ? n : "";
    }

    private static String readSkinCutout(String skinPath) {
        if (skinPath == null || skinPath.isEmpty()) return "";
        try {
            for (String line : Files.readAllLines(Paths.get(skinPath, "layout"), StandardCharsets.ISO_8859_1)) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 2 && f[0].equals("cutout") && f[1].matches("[a-z0-9_]+")) return f[1];
            }
        } catch (Exception ignored) {}
        return "";
    }

    // The guest display runs at the host monitor's rate, so a 120 Hz screen gets 120 Hz.
    /**
     * Window blurs (notification shade, app drawer), as on a real device, on hosts with 12 or
     * more cores. They raise the guest's composition from about 5 to 7.5 ms per frame (8.3 ms at
     * 120 Hz, measured on a 12-core Snapdragon X), so smaller chips keep them off.
     * WHPX_BLUR=1 or 0 overrides.
     */
    private static boolean blurEnabled() {
        String env = System.getenv("WHPX_BLUR");
        if ("1".equals(env)) return true;
        if ("0".equals(env)) return false;
        return Runtime.getRuntime().availableProcessors() >= 12;
    }

    private static int hostRefreshRate() {
        try {
            int hz = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDisplayMode().getRefreshRate();
            if (hz >= 30) return Math.min(hz, 120);
        } catch (Throwable ignored) {}
        return 60;
    }

    // Tells the renderer what size the viewer shows, so it reads back only those pixels.
    private static volatile long fullCaptureUntil = 0;

    private static void publishCaptureSize(ImageFormat req) {
        if (frameMbb == null || System.currentTimeMillis() < fullCaptureUntil) return;
        int curRot = currentRotation;
        int[] d = displaySize();
        int baseW = (curRot % 2 == 0) ? d[0] : d[1];
        int baseH = (curRot % 2 == 0) ? d[1] : d[0];
        int maxW = req.getWidth() > 0 ? req.getWidth() : baseW;
        int maxH = req.getHeight() > 0 ? req.getHeight() : baseH;
        double scale = Math.min((double) maxW / baseW, (double) maxH / baseH);
        int w = 0, h = 0;
        if (scale < 0.75) {
            w = ((int) Math.ceil(d[0] * scale) + 1) & ~1;
            h = ((int) Math.ceil(d[1] * scale) + 1) & ~1;
        }
        // Studio can have several screenshot streams open at once (two views of the device at
        // slightly different sizes). Each used to write its own size every frame, so the
        // request flipped between them and the renderer rebuilt its scaled buffer every frame,
        // which ran it up to gigabytes. Ask for one size that suits every stream: full size if
        // any stream needs it, else the largest, and write it only when it changes.
        captureRequests.put(Thread.currentThread(), new int[] { w, h });
        int cw = -1, ch = -1;
        for (int[] r : captureRequests.values()) {
            if (r[0] == 0) { cw = 0; ch = 0; break; }
            cw = Math.max(cw, r[0]);
            ch = Math.max(ch, r[1]);
        }
        if (cw < 0) return;
        synchronized (captureRequests) {
            int tail = 16 + lcdWidth * lcdHeight * 4 * 2;
            if (frameMbb.getInt(tail) == cw && frameMbb.getInt(tail + 4) == ch) return;
            writeCaptureSize(cw, ch);
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<Thread, int[]> captureRequests = new java.util.concurrent.ConcurrentHashMap<>();

    private static void writeCaptureSize(int w, int h) {
        int tail = 16 + lcdWidth * lcdHeight * 4 * 2;
        frameMbb.putInt(tail, w);
        frameMbb.putInt(tail + 4, h);
    }

    // A screenshot must not be an upscaled copy of the stream, so ask for a full frame first.
    private static void holdFullCapture(ImageFormat req) {
        if (frameMbb == null) initFrameMbb();
        if (frameMbb == null || frameMbb.getInt(4) >= displaySize()[0]) return;
        fullCaptureUntil = System.currentTimeMillis() + 500;
        writeCaptureSize(0, 0);
        try {
            for (int i = 0; i < 40 && frameMbb.getInt(4) < displaySize()[0]; i++) Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // The ramdisk tools run on Python with lz4; users are not expected to have either.
    /*
     * API 34+ guests tick from Hyper-V's synthetic timer instead of the architected timer.
     * On some Snapdragon X hosts (X1-26-100) Hyper-V's arch-timer emulation stalls the vCPU
     * between ticks and the guest runs about 100x slower; the WSL2 kernel avoids this the same
     * way. Needs kernel_whpx_stimer (stock android14-6.1 plus the whpx_stimer driver), its
     * modules, QEMU's Hyper-V enlightenments and "whpx_stimer" on the command line.
     * WHPX_TIMER=arch goes back to the stock kernel and the architected timer.
     */
    private static boolean useStimerKernel(int apiNum) {
        if (apiNum < 34 || "arch".equalsIgnoreCase(System.getenv("WHPX_TIMER"))) return false;
        return Files.exists(Paths.get(bridgeDir, "kernel_whpx_stimer"))
            && Files.isDirectory(Paths.get(bridgeDir, "modules_whpx_6_1_stimer"));
    }

    private static String pythonExe() {
        Path bundled = Paths.get(bridgeDir, "python", "python.exe");
        return Files.isRegularFile(bundled) ? bundled.toString() : "python";
    }

    // The guest's "wm size" override, 0 when there is none (read every 5 s).
    private static volatile int overrideW = 0, overrideH = 0;

    private static void startTouchScaleWatcher() {
        Thread t = new Thread(() -> {
            Pattern physical = Pattern.compile("Physical size: (\\d+)x");
            Pattern override = Pattern.compile("Override size: (\\d+)x(\\d+)");
            while (true) {
                try {
                    Thread.sleep(5000);
                    Process p = new ProcessBuilder(sdkDir + "\\platform-tools\\adb.exe", "-s", emulatorSerial, "shell", "wm size")
                        .redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    p.waitFor();
                    Matcher ph = physical.matcher(out);
                    if (!ph.find()) continue;
                    int panel = Integer.parseInt(ph.group(1));
                    Matcher ov = override.matcher(out);
                    // Foldables: Android rescales the density whenever the screen size or a
                    // framework overlay changes; put the device's own back if that happened.
                    int want = WhpxFold.expectedDensity();
                    if (want > 0) {
                        Process pd = new ProcessBuilder(sdkDir + "\\platform-tools\\adb.exe", "-s", emulatorSerial, "shell",
                            "d=$(wm density | tail -1); [ \"$d\" = \"Override density: " + want + "\" ] || { wm density " + want + "; echo reset $d; }")
                            .redirectErrorStream(true).start();
                        String dout = new String(pd.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                        pd.waitFor();
                        if (dout.startsWith("reset")) System.out.println("[WhpxFold] density was " + dout.substring(6) + "; set " + want);
                    }
                    boolean has = ov.find();
                    int w = has ? Integer.parseInt(ov.group(1)) : 0, h = has ? Integer.parseInt(ov.group(2)) : 0;
                    if (w != overrideW || h != overrideH) {
                        overrideW = w;
                        overrideH = h;
                        System.out.println("[WhpxEmulatorBridge] Guest screen " + (has ? w + "x" + h : "not overridden") + " on panel width " + panel);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Where the guest's screen is on the panel when "wm size" overrides it: scaled to fit and
     * centred. Returns {x, y, width, height} in panel pixels for rotation rot, or null.
     */
    private static int[] overrideArea(int rot) {
        int[] fold = WhpxFold.overrideSize();
        if (fold != null) {
            // Folded, the cover screen is the guest's other display and the image is all of it.
            int uw = fold[0], uh = fold[1];
            if (rot % 2 == 1) { int t = uw; uw = uh; uh = t; }
            return new int[] { 0, 0, uw, uh, uw, uh };
        }
        int uw = overrideW, uh = overrideH;
        if (uw <= 0 || uh <= 0) return null;
        int pw = lcdWidth, ph = lcdHeight;
        if (rot % 2 == 1) { int t = pw; pw = ph; ph = t; t = uw; uw = uh; uh = t; }
        double s = Math.min((double) pw / uw, (double) ph / uh);
        int cw = (int) Math.round(uw * s), ch = (int) Math.round(uh * s);
        if (cw == pw && ch == ph && uw == pw) return null;
        return new int[] { (pw - cw) / 2, (ph - ch) / 2, cw, ch, uw, uh };
    }

    /** A folded device shows only its cover screen: tell Studio where it is in the frame. */
    private static ImageFormat foldFormat(int rot) {
        // Only once the cover screen's frames arrive: Studio expects the image to be that screen.
        int[] a = WhpxFold.overrideSize();
        if (a == null || coverFrameSize() == null) return ImageFormat.getDefaultInstance();
        // In the display's natural orientation, like Studio's touch positions: Studio turns it
        // by the image's rotation itself.
        a = new int[] { 0, 0, a[0], a[1] };
        // Offsets 0, as Google's emulator sends them: the image is already just the cover screen,
        // and Studio drops clicks outside this rectangle after making them relative to it, so a
        // real offset lost every click on the left part of the screen.
        return ImageFormat.newBuilder().setFoldedDisplay(FoldedDisplay.newBuilder()
            .setXOffset(0).setYOffset(0).setWidth(a[2]).setHeight(a[3]).build()).build();
    }

    /**
     * Touches arrive in panel pixels; the guest takes them in its own (overridden) screen's pixels.
     * The override may have another shape than the panel (a folded device's cover screen), so
     * this removes the letterbox offset as well as scaling.
     */
    private static String scaleTouch(String cmd) {
        if (!cmd.startsWith("T ")) return cmd;
        int[] a = overrideArea(currentRotation);
        if (a == null) return cmd;
        String[] f = cmd.split(" ");
        if (f.length != 4) return cmd;
        try {
            double s = (double) a[4] / a[2];
            // Folded, clients get foldedDisplay and send touches relative to the cover screen,
            // as Google's emulator expects; otherwise they are in panel pixels.
            boolean relative = WhpxFold.overrideSize() != null;
            long x = Math.round((Integer.parseInt(f[2]) - (relative ? 0 : a[0])) * s);
            long y = Math.round((Integer.parseInt(f[3]) - (relative ? 0 : a[1])) * s);
            x = Math.max(0, Math.min(a[4] - 1, x));
            y = Math.max(0, Math.min(a[5] - 1, y));
            return "T " + f[1] + " " + x + " " + y;
        } catch (NumberFormatException e) {
            return cmd;
        }
    }



    private static volatile MappedByteBuffer frameMbb = null;

    /**
     * The renderer marks frames of a foldable's cover screen (the guest's display 1) in the
     * upper bits of the slot word. Returns that screen's size while its frames show, else null.
     */
    private static int[] coverFrameSize() {
        MappedByteBuffer m = frameMbb;
        if (m == null || (m.getInt(12) >>> 8) != 1) return null;
        return WhpxFold.coverSize();
    }

    /** Size of the screen whose frames the renderer is sending. */
    private static int[] displaySize() {
        int[] c = coverFrameSize();
        return c != null ? c : new int[] { lcdWidth, lcdHeight };
    }



    private static final Object frameMbbLock = new Object();



    private static volatile Process gfxServerProc = null;







    private static volatile boolean gfxShuttingDown = false;

    // One renderer per emulator. The first instance keeps the historic port and names.
    private static int gfxPort = 5000;
    private static String frameFileName = "whpx_frame.dat";
    private static String consoleLogName = "whpx_console.log";

    private static void allocateGfxPort() {
        for (int port = 5000; port < 5064; port++) {
            try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"))) {
                gfxPort = port;
                break;
            } catch (Exception ignored) {}
        }
        if (gfxPort != 5000) {
            frameFileName = "whpx_frame_" + gfxPort + ".dat";
            consoleLogName = "whpx_console_" + gfxPort + ".log";
        }
    }

    // Current simulated position, shared by Extended Controls, gRPC and `geo fix`.
    private static volatile double gpsLat = 37.4220, gpsLon = -122.0841, gpsAlt = 5.0;

    // Two guest paths need it: the renderer server feeds real GNSS (NMEA), and
    // whpx_loop.sh answers the network provider from this property.
    private static void applyGps(double lat, double lon, double alt) {
        gpsLat = lat;
        gpsLon = lon;
        gpsAlt = alt;
        sendGpsToRenderer();
        String pos = String.format(java.util.Locale.ROOT, "%.6f,%.6f", lat, lon);
        Thread t = new Thread(() -> {
            try {
                new ProcessBuilder(sdkDir + "\\platform-tools\\adb.exe", "-s", emulatorSerial, "shell", "setprop whpx.gps.pos " + pos)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }, "GpsApply");
        t.setDaemon(true);
        t.start();
        System.out.println("[WhpxEmulatorBridge] GPS position " + pos + " alt=" + alt);
    }

    private static void sendGpsToRenderer() {
        try (Socket sock = new Socket("127.0.0.1", gfxPort)) {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            msg.write("whpx:gps".getBytes(StandardCharsets.US_ASCII));
            msg.write(0);
            msg.write(String.format(java.util.Locale.ROOT, "%.6f %.6f %.1f", gpsLat, gpsLon, gpsAlt).getBytes(StandardCharsets.US_ASCII));
            msg.write(10);
            sock.getOutputStream().write(msg.toByteArray());
            sock.getOutputStream().flush();
        } catch (Exception e) {
            System.out.println("[WhpxEmulatorBridge] Could not update renderer GPS: " + e);
        }
    }

    // The whole guest display depends on this process. If it dies, SurfaceFlinger
    // aborts and Android can never come back, so restart it (the guest framework
    // reconnects by itself) and record how it died, since nothing else shows that.
    private static void superviseGfxServer(ProcessBuilder gpb) {
        Thread t = new Thread(() -> {
            int quickExits = 0;
            while (true) {
                try {
                    Process p = gfxServerProc;
                    long started = System.currentTimeMillis();
                    int code = p.waitFor();
                    if (gfxShuttingDown || (whpxProc != null && !whpxProc.isAlive())) return;
                    System.out.println("[WhpxEmulatorBridge] Renderer exited unexpectedly, code=0x" + Integer.toHexString(code));
                    quickExits = (System.currentTimeMillis() - started < 15000) ? quickExits + 1 : 0;
                    if (quickExits >= 3) {
                        System.out.println("[WhpxEmulatorBridge] Renderer keeps exiting at startup; not restarting it");
                        return;
                    }
                    Thread.sleep(300);
                    Process next = gpb.start();
                    gfxServerProc = next;
                    WhpxHostTuning.boost(next, "renderer");
                    Thread log = new Thread(() -> {
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(next.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null) System.out.println("[GfxServer] " + line);
                        } catch (Exception ignored) {}
                    }, "GfxServerLog");
                    log.setDaemon(true);
                    log.start();
                    System.out.println("[WhpxEmulatorBridge] Renderer restarted");
                    Thread.sleep(2000);
                    sendGpsToRenderer();
                } catch (Exception e) {
                    System.out.println("[WhpxEmulatorBridge] Renderer supervisor stopped: " + e);
                    return;
                }
            }
        }, "GfxServerSupervisor");
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) throws Exception {



        long parentPid = ProcessHandle.current().pid();



        String requestedAvd = null;



        int requestedConsolePort = -1;







        for (int i = 0; i < args.length; i++) {



            String a = args[i];



            if ("-list-avds".equals(a)) {

                Path userAvd = Paths.get(System.getProperty("user.home"), ".android", "avd");

                if (Files.exists(userAvd)) {

                    try (java.util.stream.Stream<Path> stream = Files.list(userAvd)) {

                        stream.filter(p -> p.toString().endsWith(".ini"))

                              .forEach(p -> {

                                  String fn = p.getFileName().toString();

                                  System.out.println(fn.substring(0, fn.length() - 4));

                              });

                    } catch (Exception ignored) {}

                }

                return;

            } else if ("-version".equals(a) || "--version".equals(a)) {

                System.out.println("Android emulator version 37.2.13.0 (build_id 16740729) (CL:N/A)");

                return;

            }



            if ("--parent-pid".equals(a) && i + 1 < args.length) {



                try {



                    long p = Long.parseLong(args[++i]);



                    if (ProcessHandle.of(p).filter(ProcessHandle::isAlive).isPresent()) {



                        parentPid = p;



                    }



                } catch (Exception ignored) {}



            } else if ("-avd".equals(a) && i + 1 < args.length) {



                requestedAvd = args[++i];



            } else if (a.startsWith("@") && a.length() > 1) {



                requestedAvd = a.substring(1);



            } else if ("-port".equals(a) && i + 1 < args.length) {



                try { requestedConsolePort = Integer.parseInt(args[++i]); } catch (Exception ignored) {}
            } else if ("-qt-hide-window".equals(a) || "-no-window".equals(a)) {
                // Studio passes -qt-hide-window and shows the device in its Running Devices panel.
                windowHidden = true;



            }



        }



        if (requestedAvd != null && !requestedAvd.isEmpty()) {



            avdId = requestedAvd;



        }



        final long pidForIni = parentPid;







        loadAvdConfig();







        System.out.println("Android emulator version 37.2.13.0 (build_id 16740729) (CL:N/A)");



        System.out.println("Starting AVD: " + avdId + " (" + displayName + ", target=" + targetStr + ", sysDir=" + sysDir + ")");



        System.out.println("CPU Acceleration: working");



        System.out.println("CPU Acceleration status: WHPX (Windows Hypervisor Platform) native ARM64 enabled");







        // 1. Clean up stale pid_*.ini files and allocate free ports



        String localAppData = System.getenv("LOCALAPPDATA");



        Path runningDir = Paths.get(localAppData, "Temp", "avd", "running");



        Files.createDirectories(runningDir);



        cleanupAndInspectRunningAvds(runningDir, avdId, pidForIni);



        allocatePorts(requestedConsolePort);



        allocateSerialPort();
        allocateGfxPort();







        emulatorSerial = "emulator-" + consolePort;
        startTouchScaleWatcher();



        System.out.println("Allocated ports for " + avdId + ": console=" + consolePort + ", adb=" + adbPort + ", grpc=" + grpcPort + ", serial=" + serialPort);







        // 2. Provision AVD disk images, hardware-qemu.ini, and universal WHPX initrd BEFORE publishing pid_<PID>.ini



        provisionAvdDisksAndInitrd();







        // 3. Start Android Console TCP server



        Thread consoleThread = new Thread(WhpxEmulatorBridge::runConsoleServer, "ConsoleServer-" + consolePort);



        consoleThread.setDaemon(true);



        consoleThread.start();







        // 4. Start gRPC Server on grpcPort (stripping grpc-encoding header for Studio DecompressorRegistry.emptyInstance())



        Server grpcServer = ServerBuilder.forPort(grpcPort)



            .intercept(new com.android.tools.idea.io.grpc.ServerInterceptor() {



                @Override



                public <ReqT, RespT> com.android.tools.idea.io.grpc.ServerCall.Listener<ReqT> interceptCall(



                    com.android.tools.idea.io.grpc.ServerCall<ReqT, RespT> call,



                    com.android.tools.idea.io.grpc.Metadata headers,



                    com.android.tools.idea.io.grpc.ServerCallHandler<ReqT, RespT> next



                ) {



                    try {



                        java.lang.reflect.Field streamField = call.getClass().getDeclaredField("stream");



                        streamField.setAccessible(true);



                        Object origStream = streamField.get(call);



                        Class<?> serverStreamIface = Class.forName("com.android.tools.idea.io.grpc.internal.ServerStream");



                        Object wrappedStream = java.lang.reflect.Proxy.newProxyInstance(



                            serverStreamIface.getClassLoader(),



                            new Class<?>[]{ serverStreamIface },



                            (proxy, method, mArgs) -> {



                                if ("writeHeaders".equals(method.getName()) && mArgs != null && mArgs.length > 0 && mArgs[0] instanceof com.android.tools.idea.io.grpc.Metadata) {



                                    com.android.tools.idea.io.grpc.Metadata m = (com.android.tools.idea.io.grpc.Metadata) mArgs[0];



                                    m.discardAll(com.android.tools.idea.io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY);



                                    m.discardAll(com.android.tools.idea.io.grpc.internal.GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY);



                                }



                                try {



                                    return method.invoke(origStream, mArgs);



                                } catch (java.lang.reflect.InvocationTargetException ite) {



                                    throw ite.getTargetException();



                                }



                            }



                        );



                        streamField.set(call, wrappedStream);



                    } catch (Throwable ignored) {}



                    return next.startCall(call, headers);



                }



            })



            .addService(controllerService)



            .addService(new UiControllerServiceImpl())



            .addService(new SnapshotServiceImpl())



            .build()



            .start();



        System.out.println("Started gRPC server on port " + grpcPort);
        String windowEnv = System.getenv("WHPX_WINDOW");
        if ("1".equals(windowEnv) || (!windowHidden && !"0".equals(windowEnv))) {
            System.out.println("[WhpxEmulatorBridge] Started outside Android Studio: opening the emulator window");
            WhpxStandaloneWindow.open(standaloneHost);
        }







        // 5. Write discovery file pid_<PID>.ini now that hardware-qemu.ini and gRPC server are ready



        Path iniFile = runningDir.resolve("pid_" + pidForIni + ".ini");



        String escapedQemu = (sdkDir + "\\emulator\\qemu\\windows-aarch64\\qemu-system-aarch64.exe").replace("\\", "\\\\");



        String iniContent = String.join("\r\n",



            "launcher.dir=" + sdkDir + "\\emulator",



            "emulator.build=16740729",



            "port.serial=" + consolePort,



            "emulator.version=37.2.13.0",



            "avd.id=" + avdId,



            "port.adb=" + adbPort,



            "avd.name=" + avdId,



            "avd.dir=" + avdDir,



            "cmdline=\"" + escapedQemu + "\" \"-netdelay\" \"none\" \"-netspeed\" \"full\" \"-avd\" \"" + avdId + "\" \"-qt-hide-window\" \"-idle-grpc-timeout\" \"300\" \"-accel\" \"whpx\"",



            "grpc.port=" + grpcPort,



            ""



        );



        Files.write(iniFile, iniContent.getBytes(StandardCharsets.UTF_8));



        iniFile.toFile().deleteOnExit();







        // 5. Ensure GfxstreamPipeServer.exe is running for live Android UI rendering



        try {



            Path frameFile = Paths.get(bridgeDir, frameFileName);










            try (RandomAccessFile zraf = new RandomAccessFile(frameFile.toFile(), "rw")) {
                int totalFileSize = 16 + lcdWidth * lcdHeight * 4 * 2 + 8;
                zraf.setLength(totalFileSize);
                zraf.seek(0);
                zraf.write(new byte[16]);
            } catch (Exception ignored) {}







            Path gfxNewExe = Paths.get(bridgeDir, "GfxstreamPipeServer_new.exe");



            Path gfxExe = Paths.get(bridgeDir, "GfxstreamPipeServer.exe");



            if (Files.exists(gfxNewExe)) {



                try {



                    Files.move(gfxNewExe, gfxExe, StandardCopyOption.REPLACE_EXISTING);



                } catch (Exception ignored) {}



            }
            // GfxstreamPipeServer.exe is the native ARM64 renderer (gfxstream and ANGLE built for
            // ARM64, in lib64-arm64). WHPX_RENDERER=x64 runs the x64 one instead, under Windows'
            // x64 emulation with the Android Emulator's own libraries, in case a GPU driver does
            // not get on with the native build.
            Path gfxX64Exe = Paths.get(bridgeDir, "GfxstreamPipeServer.x64.exe");
            if ("x64".equalsIgnoreCase(System.getenv("WHPX_RENDERER")) && Files.exists(gfxX64Exe)) {
                gfxExe = gfxX64Exe;
                System.out.println("[WhpxEmulatorBridge] using the x64 renderer (WHPX_RENDERER=x64)");
            }



            if (Files.exists(gfxExe)) {



                ProcessBuilder gpb = new ProcessBuilder(



                    gfxExe.toString(),



                    String.valueOf(lcdWidth),



                    String.valueOf(lcdHeight),



                    String.valueOf(gfxPort),



                    frameFile.toString(),



                    String.valueOf(ProcessHandle.current().pid())



                );



                gpb.directory(Paths.get(bridgeDir).toFile());
                int[] cover = WhpxFold.coverSize();
                if (cover != null) gpb.environment().put("WHPX_COVER_SIZE", cover[0] + "x" + cover[1]);
                gpb.environment().put("PATH", bridgeDir + ";" + sdkDir + "\\emulator\\lib64;" + System.getenv("PATH"));
                gpb.redirectErrorStream(true);

                gfxServerProc = gpb.start();
                WhpxHostTuning.boost(gfxServerProc, "renderer");
                superviseGfxServer(gpb);
                startBootPayloadServer();



                Thread gfxLogger = new Thread(() -> {



                    try (BufferedReader gbr = new BufferedReader(new InputStreamReader(gfxServerProc.getInputStream(), StandardCharsets.UTF_8))) {



                        String gline;



                        while ((gline = gbr.readLine()) != null) {



                            System.out.println("[GfxServer] " + gline);



                        }



                    } catch (Exception ignored) {}



                }, "GfxPipeLogger");



                gfxLogger.setDaemon(true);



                gfxLogger.start();



            }



        } catch (Exception ignored) {}







        // 6. Launch pure WHPX QEMU (windows-aarch64\qemu-system-aarch64.exe -accel whpx)



        String qemuExe = sdkDir + "\\emulator\\qemu\\windows-aarch64\\qemu-system-aarch64.exe";



        int apiNum = parseIntOrDefault(apiLevel, 34);
        boolean stimer = useStimerKernel(apiNum);
        Path universalKernel = Paths.get(bridgeDir, stimer ? "kernel_whpx_stimer" : "kernel_whpx_universal");
        Path p7Kernel = Paths.get(bridgeDir, "kernel_p7_permissive");
        String kernelPath;
        if (apiNum >= 34 && Files.exists(universalKernel)) {
            kernelPath = universalKernel.toString();
        } else if (Files.exists(p7Kernel)) {
            kernelPath = p7Kernel.toString();
        } else {
            kernelPath = Paths.get(sysDir, "kernel-ranchu").toString();
        }



        String initrdPath = avdDir + "\\initrd_whpx";



        String encryptImg = Files.exists(Paths.get(avdDir, "encryptionkey.img"))



            ? (avdDir + "\\encryptionkey.img")



            : (sysDir + "\\encryptionkey.img");







        ProcessBuilder pb = new ProcessBuilder(



            qemuExe,

            // ROMs and keymaps live in share\ next to the exe; MSYS2 builds look in ..\share\qemu.
            "-L", Paths.get(qemuExe).getParent().resolve("share").toString(),

            "-accel", stimer ? "whpx,hyperv=on" : "whpx",



            "-M", "virt",



            "-cpu", "host",



            "-smp", String.valueOf(cpuCores),



            "-m", ramMb + "M",



            "-kernel", kernelPath,



            "-initrd", initrdPath,



            "-drive", "if=none,id=system,file=" + sysDir + "\\system.img,format=raw,read-only=on,cache=unsafe",



            "-device", "virtio-blk-pci,drive=system",



            "-drive", "if=none,id=vendor,file=" + sysDir + "\\vendor.img,format=raw,read-only=on,cache=unsafe",



            "-device", "virtio-blk-pci,drive=vendor",



            "-drive", "if=none,id=userdata,file=" + avdDir + "\\userdata-qemu.img,format=qcow2,cache=unsafe",



            "-device", "virtio-blk-pci,drive=userdata",



            "-drive", "if=none,id=encrypt,file=" + encryptImg + ",format=raw,cache=unsafe",



            "-device", "virtio-blk-pci,drive=encrypt",



            "-drive", "if=none,id=cache,file=" + avdDir + "\\cache.img,format=raw,cache=unsafe",



            "-device", "virtio-blk-pci,drive=cache",



            "-netdev", "user,id=mynet,hostfwd=tcp:127.0.0.1:" + adbPort + "-:5555,hostfwd=tcp:127.0.0.1:" + inputPort + "-:10555",



            "-device", "virtio-net-pci,netdev=mynet",



            "-device", "virtio-rng-pci",



            "-device", "virtio-tablet-pci",



            "-device", "virtio-keyboard-pci",



            "-device", "virtio-gpu-pci",



            "-rtc", "base=utc,clock=host",



            "-chardev", "socket,id=ser0,host=127.0.0.1,port=" + serialPort + ",server=on,wait=off",

            "-serial", "chardev:ser0",



            "-no-reboot",
            "-display", "none",



            "-append", "console=ttyAMA0,38400 earlyprintk=ttyAMA0 audit=0 loglevel=7 androidboot.hardware=ranchu " + "androidboot.serialno=" + getSerialPrefix(sysDir) + " androidboot.boot_devices=3f000000.pcie androidboot.selinux=permissive androidboot.console=ttyAMA0 androidboot.verifiedbootstate=orange androidboot.flash.locked=0 androidboot.service.adb.tcp.port=5555 androidboot.radio.noril=yes androidboot.vendor.radio.noril=1 qemu=1 no_timer_check rcupdate.rcu_cpu_stall_timeout=60 bootconfig" + (stimer ? " whpx_stimer" : "")



        );



        pb.redirectErrorStream(true);



        whpxProc = pb.start();
        WhpxHostTuning.boostAndPin(whpxProc, "qemu", WhpxHostTuning.residentMbFor(ramMb));







        Runtime.getRuntime().addShutdownHook(new Thread(() -> {



            try { Files.deleteIfExists(iniFile); } catch (Exception ignored) {}



            if (whpxProc != null && whpxProc.isAlive()) {



                whpxProc.destroyForcibly();



            }



            gfxShuttingDown = true;
            if (gfxServerProc != null && gfxServerProc.isAlive()) {



                gfxServerProc.destroyForcibly();



            }






            if (serialSocket != null) {

                try { serialSocket.close(); } catch (Exception ignored) {}

            }

            grpcServer.shutdownNow();



        }));







        // Threads that follow one QEMU run: serial console, adb, guest bring-up, post-boot settings.
        // They run again for the fresh QEMU after Android restarts itself.
        Runnable bootThreads = () -> {
        final Process proc = whpxProc;
        final AtomicBoolean bootCmdTriggered = new AtomicBoolean(false);







        // Drain WHPX serial via TCP socket in background and trigger universal serial bring-up

        Path consoleLog = Paths.get(bridgeDir, consoleLogName);

        Thread serialReader = new Thread(() -> {

            Socket sock = null;

            for (int attempt = 0; attempt < 120 && (proc != null && proc.isAlive()); attempt++) {

                try {

                    sock = new Socket("127.0.0.1", serialPort);

                    sock.setTcpNoDelay(true);

                    break;

                } catch (Exception e) {

                    try { Thread.sleep(100); } catch (Exception ignored) {}

                }

            }

            if (sock == null) {

                System.err.println("[WHPX] Could not connect to serial port " + serialPort);

                return;

            }

            serialSocket = sock;

            try {

                serialOut = sock.getOutputStream();

                try (BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));

                     PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(consoleLog.toFile(), bootCount > 0), StandardCharsets.UTF_8), true)) {

                    String line;

                    while ((line = br.readLine()) != null) {

                        pw.println(line);
                        if (line.contains("reboot: Restarting system")) guestRebooting = true;

                        System.out.println("[WHPX] " + line);



                        if (line.contains("adbd started") || line.contains("listening on tcp:5555")) {

                            adbOnline = true;

                        }



                        if ((line.contains("console") || line.contains("init:") || line.contains("androidboot") || line.contains("/system/bin/sh") ) && bootCmdTriggered.compareAndSet(false, true)) {

                            Thread bringupThread = new Thread(WhpxEmulatorBridge::runGuestSerialBringup, "GuestSerialBringup");

                            bringupThread.setDaemon(true);

                            bringupThread.start();

                        }

                    }

                }

            } catch (Exception ignored) {}

        }, "WhpxSerialReader");

        serialReader.setDaemon(true);

        serialReader.start();



        // Also drain proc stdout to prevent pipe stalls

        Thread qemuStdoutDrainer = new Thread(() -> {

            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {

                String line;

                while ((line = br.readLine()) != null) {

                    System.out.println("[QEMU] " + line);

                }

            } catch (Exception ignored) {}

        }, "QemuStdoutDrainer");

        qemuStdoutDrainer.setDaemon(true);

        qemuStdoutDrainer.start();



        Thread bringupFallback = new Thread(() -> {

            try {

                Thread.sleep(4000);

                if (bootCmdTriggered.compareAndSet(false, true)) {

                    runGuestSerialBringup();

                }

            } catch (Exception ignored) {}

        }, "GuestBringupFallback");

        bringupFallback.setDaemon(true);

        bringupFallback.start();







        // Fallback trigger in case console prompt line had no newline



        Thread fallbackBringup = new Thread(() -> {

            for (int i = 0; i < 40 && !adbOnline && proc != null && proc.isAlive(); i++) {

                try {

                    Thread.sleep(3000);

                    runGuestSerialBringup();

                } catch (Exception ignored) {}

            }

        }, "FallbackBringup");



        fallbackBringup.setDaemon(true);



        fallbackBringup.start();







        // 6. Continuous Monitor ADB so emulator-<consolePort> transitions to 'device' automatically



        Thread adbMonitor = new Thread(() -> {



            String adbExe = sdkDir + "\\platform-tools\\adb.exe";



            String hostEmuMsg = String.format("0012host:emulator:%d", adbPort);



            boolean shimsInstalled = false;



            long lastSerialPulse = 0;



            long lastHostEmuMsg = 0;







            // Proactively disconnect any stale TCP device so Studio only sees emulator-<consolePort>



            try {



                new ProcessBuilder(adbExe, "disconnect", "127.0.0.1:" + adbPort).start().waitFor();



            } catch (Exception ignored) {}







            while (proc != null && proc.isAlive()) {



                try {



                    Thread.sleep(1000);



                    Process p = new ProcessBuilder(adbExe, "devices").start();



                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);







                    if (out.contains("emulator-" + consolePort + "\toffline")) {



                        try {



                            new ProcessBuilder(adbExe, "reconnect", "offline").start();



                            new ProcessBuilder(adbExe, "connect", "127.0.0.1:" + adbPort).start();



                        } catch (Exception ignored) {}



                    }







                    boolean hasEmu = out.contains("emulator-" + consolePort + "\tdevice");

                    boolean hasTcp = out.contains("127.0.0.1:" + adbPort + "\tdevice");

                    if (hasEmu || hasTcp) {



                        emulatorSerial = hasEmu ? ("emulator-" + consolePort) : ("127.0.0.1:" + adbPort);



                        if (!shimsInstalled) {



                            shimsInstalled = true;



                            installGuestServiceShims(adbExe);



                            try {



                                new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "setprop ro.kernel.qemu.avd_name " + avdId).start();



                            } catch (Exception ignored) {}



                        }



                        adbOnline = true;



                        if (hasEmu && hasTcp) {



                            try {



                                new ProcessBuilder(adbExe, "disconnect", "127.0.0.1:" + adbPort).start().waitFor();



                            } catch (Exception ignored) {}



                        }



                    } else {



                        adbOnline = false;



                        long now = System.currentTimeMillis();



                        if (!shimsInstalled) {



                            if (now - lastHostEmuMsg > 2000) {



                                lastHostEmuMsg = now;



                                try (Socket s = new Socket("127.0.0.1", 5037)) {



                                    s.setSoTimeout(1000);



                                    s.getOutputStream().write(hostEmuMsg.getBytes(StandardCharsets.UTF_8));



                                    s.getOutputStream().flush();



                                    s.getInputStream().read(new byte[16]);



                                } catch (Exception ignored) {}



                            }



                            if (now - lastSerialPulse > 6000) {



                                lastSerialPulse = now;



                                sendSerialSlow("\r\nip -4 addr show eth0 2>/dev/null | grep -q inet || { ifconfig eth0 10.0.2.15 netmask 255.255.255.0 up; ip link set eth0 up; }\r\n");

                                sendSerialSlow("setprop service.adb.tcp.port 5555\r\n");

                                try {

                                    Path pkPath = Paths.get(System.getProperty("user.home"), ".android", "adbkey.pub");

                                    if (Files.exists(pkPath)) {

                                        String pk = Files.readString(pkPath).trim();

                                        if (!pk.isEmpty()) {

                                            sendSerialSlow("sh -c \"echo '" + pk + "' > /adb_keys; chmod 644 /adb_keys; mkdir -p /data/misc/adb /data/vendor/adb; echo '" + pk + "' > /data/misc/adb/adb_keys; echo '" + pk + "' > /data/vendor/adb/adb_keys; chown system:shell /data/misc/adb/adb_keys /data/vendor/adb/adb_keys 2>/dev/null; chmod 0640 /data/misc/adb/adb_keys /data/vendor/adb/adb_keys 2>/dev/null; setprop vendor.qemu.adb.copykey 1\"\r\n");

                                        }

                                    }

                                } catch (Exception ignored) {}

                                sendSerialSlow("pidof adbd >/dev/null || setprop ctl.start adbd\r\n");
                                Thread.sleep(100);
                                sendSerialSlow("setprop ctl.stop vendor.biometrics.fingerprint-service.ranchu\r\n");



                            }



                        }



                    }



                } catch (Exception ignored) {}



            }



        }, "AdbMonitor");



        adbMonitor.setDaemon(true);



        adbMonitor.start();







        Thread postBootMonitor = new Thread(() -> {



            String adbExe = sdkDir + "\\platform-tools\\adb.exe";



            boolean configured = false;



            while (proc != null && proc.isAlive()) {



                try {



                    Thread.sleep(2000);



                    if (!adbOnline || emulatorSerial == null) continue;



                    // Every branch must print something: Wear and TV have neither launcher, and a
                    // silent boot_completed test left them without post-boot settings (screen timeout).
                    Process p = new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "pidof com.google.android.apps.nexuslauncher || pidof com.android.launcher3 || { [ \"$(getprop sys.boot_completed)\" = \"1\" ] && echo booted; }").start();



                    String res = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();



                    p.waitFor(2, TimeUnit.SECONDS);



                    if (!res.isEmpty()) {



                        if (!configured) {



                            configured = true;



                            applyPostBootSettings(adbExe);
                            if (WhpxFold.foldable()) {
                                final String serialNow = emulatorSerial;
                                Thread fold = new Thread(() -> WhpxFold.ensureGuest(adbExe, serialNow, bridgeDir,
                                        EmulatorControllerServiceImpl::dispatchGuestInput), "WhpxFoldSetup");
                                fold.setDaemon(true);
                                fold.start();
                            }



                            System.out.println("[WhpxEmulatorBridge] NexusLauncher active, post-boot configuration applied.");



                        }



                        new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "su 0 service call SurfaceFlinger 1008 i32 1").start();

                        Thread.sleep(5000);



                    }



                } catch (Exception ignored) {}



            }



        }, "PostBootMonitor");



        postBootMonitor.setDaemon(true);



        postBootMonitor.start();
        };
        bootThreads.run();







        // Minute Maid Auto-Reveal Thread (Fixes Google OAuth / Account Adding webviews)



        Thread minuteMaidWatcher = new Thread(() -> {



            Path pyScript = Paths.get(bridgeDir, "auto_minute_maid.py");



            if (Files.exists(pyScript)) {



                try {



                    ProcessBuilder pyPb = new ProcessBuilder(pythonExe(), "-u", pyScript.toString());
                    pyPb.environment().put("PATH", sdkDir + "\\platform-tools;" + System.getenv("PATH"));



                    pyPb.redirectErrorStream(true);



                    Process pyProc = pyPb.start();



                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {



                        try { if (pyProc != null && pyProc.isAlive()) pyProc.destroyForcibly(); } catch (Exception ignored) {}



                    }));



                    try (BufferedReader pbr = new BufferedReader(new InputStreamReader(pyProc.getInputStream(), StandardCharsets.UTF_8))) {



                        String pline;



                        while ((pline = pbr.readLine()) != null) {



                            if (pline.contains("REVEALED") || pline.contains("Evaluated")) {



                                System.out.println(pline);



                            }



                        }



                    }



                } catch (Exception ignored) {}



            }



        }, "MinuteMaidWatcher");



        minuteMaidWatcher.setDaemon(true);



        minuteMaidWatcher.start();







        whpxProc.waitFor();
        while (guestRebooting) {
            // Restarting in place stalls (arch timer) or crawls (stimer), so start a fresh QEMU on
            // the same ports: Studio, adb and the emulator window stay connected.
            guestRebooting = false;
            bootCount++;
            adbOnline = false;
            currentRotation = 0;
            currentZAngle = 0f;
            WhpxFold.onGuestRestart();
            cachedEncodedSeq = -1;
            System.out.println("[WhpxEmulatorBridge] Android restarted the device: starting QEMU again (boot " + (bootCount + 1) + ")");
            Thread.sleep(300);
            whpxProc = pb.start();
            WhpxHostTuning.boostAndPin(whpxProc, "qemu", WhpxHostTuning.residentMbFor(ramMb));
            bootThreads.run();
            whpxProc.waitFor();
        }



        gfxShuttingDown = true;
            if (gfxServerProc != null && gfxServerProc.isAlive()) {



            gfxServerProc.destroyForcibly();



        }






        Files.deleteIfExists(iniFile);



        System.exit(0);



    }







    
    private static void startBootPayloadServer() {
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(5055, 50, InetAddress.getByName("0.0.0.0"));
                System.out.println("[WhpxBridge] Boot payload server listening on 0.0.0.0:5055");
                while (true) {
                    try {
                        Socket client = server.accept();
                        new Thread(() -> {
                            try (Socket cl = client) {
                                cl.setTcpNoDelay(true);
                                System.out.println("[WhpxBridge] Guest connected for boot payload from " + cl.getRemoteSocketAddress());
                                Path tarPath = Paths.get(bridgeDir, "boot_payload.tar");
                                if (Files.exists(tarPath)) {
                                    byte[] data = Files.readAllBytes(tarPath);
                                    OutputStream out = cl.getOutputStream();
                                    out.write(data);
                                    out.flush();
                                    cl.shutdownOutput();
                                    System.out.println("[WhpxBridge] boot_payload.tar (" + data.length + " bytes) streamed to guest!");
                                } else {
                                    System.out.println("[WhpxBridge] Warning: boot_payload.tar not found!");
                                }
                            } catch (Exception e) {
                                System.out.println("[WhpxBridge] Payload transfer error: " + e);
                            }
                        }, "PayloadSender").start();
                    } catch (Exception e) {
                        System.out.println("[WhpxBridge] accept error: " + e);
                        try { Thread.sleep(200); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                System.out.println("[WhpxBridge] Boot payload server socket exception: " + e);
            }
        }, "BootPayloadServer").start();
    }

    private static void ensureGuestBringup(String adbExe) {
        Thread t = new Thread(() -> {
            String launch = "if [ -f /dev/whpx_shims_mounted ] && pgrep -f whpx_loop.sh >/dev/null; then echo guest-up; else "
                + "/data/local/tmp/whpx_daemon /data/local/tmp/start_android_ui.sh " + lcdDensity + "; fi";
            for (int i = 0; i < 40 && whpxProc != null && whpxProc.isAlive(); i++) {
                try {
                    Thread.sleep(5000);
                    Process p = new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", launch).redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    p.waitFor(10, TimeUnit.SECONDS);
                    if (out.contains("guest-up")) {
                        System.out.println("[WhpxEmulatorBridge] Guest bringup confirmed after " + i + " check(s)");
                        return;
                    }
                } catch (Exception ignored) {}
            }
            System.out.println("[WhpxEmulatorBridge] Guest bringup was never confirmed");
        }, "GuestBringup");
        t.setDaemon(true);
        t.start();
    }

    private static void installGuestServiceShims(String adbExe) {



        new Thread(() -> {



            try {



                long epochSec = System.currentTimeMillis() / 1000L;



                try {



                    new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "(date @" + epochSec + " 2>/dev/null || su 0 date @" + epochSec + " 2>/dev/null)").start().waitFor(2, TimeUnit.SECONDS);



                } catch (Exception ignored) {}







                // Push patched binaries only if not already present

                Process pCheck = new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "[ -f /data/local/tmp/patched/lib_renderControl_enc.so ] && echo 1").start();

                String checkOut = new String(pCheck.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

                pCheck.waitFor(2, TimeUnit.SECONDS);



                if (!"1".equals(checkOut)) {

                    new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\patched", "/data/local/tmp/patched").start().waitFor(15, TimeUnit.SECONDS);

                    new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\handheld_core_hardware.xml", "/data/local/tmp/handheld_core_hardware.xml").start().waitFor(3, TimeUnit.SECONDS);

                    new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\empty_perm.xml", "/data/local/tmp/empty_perm.xml").start().waitFor(3, TimeUnit.SECONDS);

                    new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\aapm.xml", "/data/local/tmp/aapm.xml").start().waitFor(3, TimeUnit.SECONDS);

                }



                // Always push current daemons, runner, input, and wallpaper assets

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\gfx_forwarder", "/data/local/tmp/gfx_forwarder").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\whpx_daemon", "/data/local/tmp/whpx_daemon").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\whpx_loop.sh", "/data/local/tmp/whpx_loop.sh").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\start_android_ui.sh", "/data/local/tmp/start_android_ui.sh").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\whpx_input.jar", "/data/local/tmp/whpx_input.jar").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\set_wallpaper.jar", "/data/local/tmp/set_wallpaper.jar").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\default_wallpaper.png", "/data/local/tmp/default_wallpaper.png").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\default_net_agent.jar", "/data/local/tmp/default_net_agent.jar").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\run_net_agent.sh", "/data/local/tmp/run_net_agent.sh").start().waitFor(15, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", bridgeDir + "\\run_input_daemon.sh", "/data/local/tmp/run_input_daemon.sh").start().waitFor(15, TimeUnit.SECONDS);

                if (sysDir != null && sysDir.contains("android-33")) {
                    Path mapper3 = Paths.get(bridgeDir, "mapper3.so");
                    if (Files.exists(mapper3)) {
                        new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", mapper3.toString(), "/data/local/tmp/mapper3.so").start().waitFor(15, TimeUnit.SECONDS);
                    }
                    Path fwPatched = Paths.get(bridgeDir, "framework_patched.jar");
                    if (Files.exists(fwPatched)) {
                        new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", fwPatched.toString(), "/data/local/tmp/framework_patched.jar").start().waitFor(30, TimeUnit.SECONDS);
                    }
                }



                new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "chmod 777 /data/local/tmp /data/local/tmp/* /data/local/tmp/patched/*").start().waitFor(3, TimeUnit.SECONDS);

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "sed -i 's/\\r$//' /data/local/tmp/*.sh /data/local/tmp/*.xml").start().waitFor(2, TimeUnit.SECONDS);



                // Launch native bringup daemon with configured density

                try { new ProcessBuilder(adbExe, "-s", emulatorSerial, "root").start().waitFor(2, TimeUnit.SECONDS); } catch (Exception ignored) {}

                new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "(su 0 /data/local/tmp/whpx_daemon /data/local/tmp/start_android_ui.sh " + lcdDensity + " 2>/dev/null || /data/local/tmp/whpx_daemon /data/local/tmp/start_android_ui.sh " + lcdDensity + ")").start().waitFor(3, TimeUnit.SECONDS);
                // adb root restarts adbd, so that launch can land on nothing, and on a slow
                // boot nothing else would ever start the network agent or input daemon.
                ensureGuestBringup(adbExe);



            } catch (Exception e) {



                System.out.println("[WhpxEmulatorBridge] Guest shim bringup error: " + e);



            }



        }, "GuestShimInstaller").start();



    }







    private static void applyPostBootSettings(String adbExe) {



        try {



            long epochSec = System.currentTimeMillis() / 1000L;



            new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell",



                "su 0 date @" + epochSec + "; " +



                "su 0 stop console; su 0 setprop ctl.stop console; su 0 setprop init.svc.console stopped; " +



                "cmd notification cancel '0|android|55|null|1000'; " +



                "cmd notification snooze --for 315360000000000 '0|android|55|null|1000'; " +



                "cmd notification set_channel_importance android SERIAL_CONSOLE 0; " +



                "cmd notification set_channel_importance android DEVELOPMENT 0; " +



                "settings put global show_serial_console_notification 0; " +



                "cmd battery set present 1; " +



                "case \"$(getprop ro.build.characteristics)\" in *watch*) ba=0; bs=3;; *) ba=1; bs=2;; esac; " +



                "cmd battery set ac $ba; cmd battery set status $bs; " +



                "cmd battery set health 2; " +



                "cmd battery set level 100; " +



                "cmd battery set voltage 4200; " +



                "cmd battery set temp 250; " +



                "settings put global auto_time 0; " +



                "settings put global auto_time_zone 0; " +



                // Through the time zone service, so the clock in the status bar is told. Setting
                // persist.sys.timezone directly first made the service see no change: it kept GMT.
                "cmd time_zone_detector suggest_manual_time_zone --zone_id " + hostTimeZone() + " || cmd alarm set-timezone " + hostTimeZone() + "; " +









                "settings put secure lockscreen.disabled 1; " +



                "settings put system screen_off_timeout 2147483647; " +



                "settings put global stay_on_while_plugged_in 7; " +



                "settings put global window_animation_scale 1.0; " +



                "settings put global transition_animation_scale 1.0; " +



                "settings put global animator_duration_scale 1.0; " +



                "settings put global disable_window_blurs " + (blurEnabled() ? 0 : 1) + "; " +



                "settings put global disable_hw_overlays 1; " +



                "su 0 service call SurfaceFlinger 1008 i32 1; " +



                "cmd window fixed-to-user-rotation enabled; " +



                "cmd window user-rotation lock 0; " +



                "settings put system accelerometer_rotation 0; " +



                "settings put system user_rotation 0; " +



                "cmd overlay enable com.android.internal.emulation." + avdId.toLowerCase() + "; " +



                "cmd overlay enable com.android.systemui.emulation." + avdId.toLowerCase() + "; " +



                "su 0 wm dismiss-keyguard; " +



                "su 0 input keyevent 82; " +



                ""



            ).start().waitFor(5, TimeUnit.SECONDS);







            // Verify WhpxInputDaemon



            Process pIn = new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "pgrep -f WhpxInputDaemon").start();



            String inPid = new String(pIn.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();



            pIn.waitFor(2, TimeUnit.SECONDS);



            if (inPid.isEmpty()) {



                new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "sh -c 'CLASSPATH=/data/local/tmp/whpx_input.jar app_process /system/bin WhpxInputDaemon </dev/null >/data/local/tmp/input.log 2>&1 &'").start();



            }







            // Verify DefaultNetworkAgent



            Process pNet = new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "pgrep -f DefaultNetworkAgent").start();



            String netPid = new String(pNet.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();



            pNet.waitFor(2, TimeUnit.SECONDS);



            if (netPid.isEmpty()) {



                new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "nohup /data/local/tmp/run_net_agent.sh >/data/local/tmp/net_agent.log 2>&1 &").start();



            }







            // Ensure Pixel default wallpaper is populated immediately



            File setWpJar = new File(bridgeDir, "set_wallpaper.jar");



            File defWpPng = new File(bridgeDir, "default_wallpaper.png");



            if (setWpJar.exists() && defWpPng.exists()) {



                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", setWpJar.getAbsolutePath(), "/data/local/tmp/set_wallpaper.jar").start().waitFor(2, TimeUnit.SECONDS);



                new ProcessBuilder(adbExe, "-s", emulatorSerial, "push", defWpPng.getAbsolutePath(), "/data/local/tmp/default_wallpaper.png").start().waitFor(2, TimeUnit.SECONDS);



                new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell", "sh -c 'CLASSPATH=/data/local/tmp/set_wallpaper.jar app_process /system/bin SetWallpaper /data/local/tmp/default_wallpaper.png'").start().waitFor(5, TimeUnit.SECONDS);



            }



        } catch (Exception ignored) {}



    }







    private static void sendSerialDirect(String cmd) {



        if (whpxProc == null || !whpxProc.isAlive()) return;



        synchronized (serialWriteLock) {



            try {



                OutputStream os = whpxProc.getOutputStream();



                String normalized = cmd.replace("\r\n", "\r\n").replace("\r\n", "\r\n");



                os.write(normalized.getBytes(StandardCharsets.UTF_8));



                os.flush();



            } catch (Exception ignored) {}



        }



    }







    private static void sendSerialSlow(String cmd) {

        if (whpxProc == null || !whpxProc.isAlive()) return;

        synchronized (serialWriteLock) {

            try {

                OutputStream os = serialOut;

                if (os == null) return;

                String normalized = cmd.replace("\r\n", "\n").replace("\n", "\r\n");

                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);

                for (byte b : bytes) {
                    os.write(b);
                    os.flush();
                    Thread.sleep(1);
                }

            } catch (Exception ignored) {}

        }

    }



    private static String getSerialPrefix(String sysDir) {

        if (sysDir != null) {

            if (sysDir.contains("android-33")) return "EMULATOR33X0X0X0";

            if (sysDir.contains("android-34")) return "EMULATOR34X0X0X0";

            if (sysDir.contains("android-35")) return "EMULATOR35X0X0X0";

            if (sysDir.contains("android-36")) return "EMULATOR36X0X0X0";

        }

        return "EMULATOR37X2X13X0";

    }



    private static void runGuestSerialBringup() {

        try {

            Thread.sleep(2000);

            Path pkPath = Paths.get(System.getProperty("user.home"), ".android", "adbkey.pub");

            String pk = Files.exists(pkPath) ? Files.readString(pkPath).trim() : "";



            for (int cycle = 0; cycle < 60 && whpxProc != null && whpxProc.isAlive(); cycle++) {

                if (cycle > 0) Thread.sleep(3000);



                sendSerialSlow("\r\nsetprop ctl.stop vendor.radio-ranchu\r\n");

                sendSerialSlow("setprop ctl.stop goldfish-logcat\r\n");

                sendSerialSlow("setprop ctl.stop vendor.camera-provider-2-4\r\n");

                sendSerialSlow("ip -4 addr show eth0 2>/dev/null | grep -q inet || /vendor/bin/ifconfig eth0 10.0.2.15/24 up 2>/dev/null\r\n");
                sendSerialSlow("ip -4 addr show eth0 2>/dev/null | grep -q inet || ifconfig eth0 10.0.2.15 netmask 255.255.255.0 up 2>/dev/null\r\n");
                sendSerialSlow("setprop ctl.start dhcpclient_def 2>/dev/null\r\n");

                sendSerialSlow("route add default gw 10.0.2.2 dev eth0 2>/dev/null\r\n");

                sendSerialSlow("setprop service.adb.tcp.port 5555\r\n");

                sendSerialSlow("setprop service.adb.listen_addrs tcp:5555\r\n");



                if (!pk.isEmpty()) {

                    sendSerialSlow("sh -c \"mkdir -p /data/misc/adb /data/vendor/adb && echo '" + pk + "' > /data/misc/adb/adb_keys && echo '" + pk + "' > /data/vendor/adb/adb_keys && chown 1000:2000 /data/misc/adb/adb_keys /data/vendor/adb/adb_keys 2>/dev/null && chown system:shell /data/misc/adb/adb_keys /data/vendor/adb/adb_keys 2>/dev/null && chmod 0640 /data/misc/adb/adb_keys /data/vendor/adb/adb_keys 2>/dev/null && setprop vendor.qemu.adb.copykey 1\"\r\n");

                }



                sendSerialSlow("pidof adbd >/dev/null || setprop ctl.start adbd\r\n");
                sendSerialSlow("setprop ctl.stop vendor.biometrics.fingerprint-service.ranchu\r\n");



                if (cycle == 0) {

                    sendSerialSlow("sh -c \"echo '#!/system/bin/sh' > /dev/whpx_r && echo 'while true; do sleep 3600; done' >> /dev/whpx_r && chmod 755 /dev/whpx_r && mount -o bind /dev/whpx_r /vendor/bin/hw/android.hardware.radio-service.ranchu 2>/dev/null\"\r\n");

                    sendSerialSlow("sh -c \"for i in 0 1 2 3 4 5; do touch /dev/hvc$i; chmod 666 /dev/hvc$i; done\"\r\n");

                    sendSerialSlow("sh -c \"(while [ ! -f /data/local/tmp/start_android_ui.sh ]; do sleep 1; done; sh /data/local/tmp/start_android_ui.sh) &\"\r\n");

                }



                if (adbOnline) {

                    for (int h = 0; h < 60 && whpxProc != null && whpxProc.isAlive(); h++) {

                        Thread.sleep(10000);

                        sendSerialSlow("setprop ctl.stop goldfish-logcat 2>/dev/null\r\n");

                    }

                    break;

                }

            }

        } catch (Exception ignored) {}

    }



    private static void loadAvdConfig() {



        String userHome = System.getProperty("user.home");



        Path avdRoot = Paths.get(userHome, ".android", "avd");



        Path mainIni = avdRoot.resolve(avdId + ".ini");



        Path candidateAvdDir = avdRoot.resolve(avdId + ".avd");







        if (Files.exists(mainIni)) {



            Map<String, String> iniMap = parseIniFile(mainIni);



            if (iniMap.containsKey("path")) {



                candidateAvdDir = Paths.get(iniMap.get("path"));



            }



            if (iniMap.containsKey("target")) {



                targetStr = iniMap.get("target");



            }



        }



        avdDir = candidateAvdDir.toString();



        displayName = avdId.replace('_', ' ');







        Path configIni = candidateAvdDir.resolve("config.ini");



        if (Files.exists(configIni)) {



            Map<String, String> cfg = parseIniFile(configIni);



            if (cfg.containsKey("avd.ini.displayname")) {



                displayName = cfg.get("avd.ini.displayname");



            }



            if (cfg.containsKey("target")) {



                targetStr = cfg.get("target");



            }



            if (cfg.containsKey("image.sysdir.1")) {



                String relSys = cfg.get("image.sysdir.1").replace("/", "\\");



                while (relSys.endsWith("\\")) relSys = relSys.substring(0, relSys.length() - 1);



                Path resolved = Paths.get(sdkDir, relSys);



                if (Files.exists(resolved)) {



                    sysDir = resolved.toString();



                }



            }



            isPixel7 = (sysDir != null && sysDir.contains("android-33")) || (avdId != null && avdId.toLowerCase().contains("pixel_7"));
        lcdWidth = parseIntOrDefault(cfg.get("hw.lcd.width"), 1080);
        String keys = cfg.getOrDefault("hw.mainKeys", "no").trim();
        mainKeys = keys.equalsIgnoreCase("yes") || keys.equalsIgnoreCase("true");



            lcdHeight = parseIntOrDefault(cfg.get("hw.lcd.height"), 1920);



            lcdDensity = parseIntOrDefault(cfg.get("hw.lcd.density"), 420);
            skinCutout = readSkinCutout(cfg.get("skin.path"));
            skinName = skinDirName(cfg.get("skin.path"));

            // Android TV caps its UI at 1920x1080 on bigger panels, so a 4K display only
            // scales that up, at four times the pixels to compose and capture: 6 fps video
            // against 23 fps at 720p. Render at the size the UI actually uses.
            String tvTags = cfg.getOrDefault("tag.ids", cfg.getOrDefault("tag.id", ""));
            if (tvTags.contains("-tv") && lcdWidth > 1920) {
                double tvScale = 1920.0 / lcdWidth;
                System.out.println("[WhpxEmulatorBridge] TV display " + lcdWidth + "x" + lcdHeight + " rendered at UI size");
                lcdHeight = ((int) Math.round(lcdHeight * tvScale)) & ~1;
                lcdDensity = (int) Math.round(lcdDensity * tvScale);
                lcdWidth = 1920;
            }



            int rawRam = parseIntOrDefault(cfg.get("hw.ramSize"), 4096);



            // The RAM set in Device Manager. Guests now have zram (compressed swap), so the
            // 3 GB floor that once covered for its absence would cost the host 1 GB for nothing.
            // A quarter of the host's RAM, or the RAM set in Device Manager if that is more; 2 to 8 GB.
            // Studio creates most devices with 2 GB, which left Android 17 phones and foldables
            // swapping to zram and janking. Wear keeps its own setting: a watch has no use for 4 GB.
            boolean wearAvd = cfg.getOrDefault("tag.ids", cfg.getOrDefault("tag.id", "")).contains("android-wear");
            long hostRamMb = 0;
            try {
                java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (os instanceof com.sun.management.OperatingSystemMXBean sun) hostRamMb = sun.getTotalMemorySize() >> 20;
            } catch (Throwable ignored) {}
            // Rounded to 256 MB: Windows reports a 16 GB PC as about 15.6 GB, which still gives 4 GB.
            int quarter = (int) (Math.round(hostRamMb / 4.0 / 256.0) * 256);
            int wantedRam = wearAvd ? rawRam : Math.max(rawRam, quarter);
            ramMb = Math.max(2048, Math.min(8192, wantedRam));
            System.out.println("[WhpxEmulatorBridge] RAM: host " + hostRamMb + " MB, Device Manager " + rawRam + " MB -> guest " + ramMb + " MB");
            WhpxFold.configure(cfg, lcdDensity);



            // Half the host's cores, or the CPU cores set in Device Manager if that is more; at most 8
            // and never more than the host has. Studio creates devices with 4, and on a 12-core host
            // 4 vCPUs made Android's UI threads compete: twice as many 120 Hz wake-ups ran more than
            // 4 ms late as with 6.
            int hostCores = Runtime.getRuntime().availableProcessors();
            int wanted = Math.max(parseIntOrDefault(cfg.get("hw.cpu.ncore"), 4), hostCores / 2);
            cpuCores = Math.max(1, Math.min(Math.min(8, hostCores), wanted));



            String tagIds = cfg.getOrDefault("tag.ids", "");
            isWear = cfg.getOrDefault("tag.ids", cfg.getOrDefault("tag.id", "")).contains("android-wear");
            skinPath = "no".equalsIgnoreCase(cfg.getOrDefault("showDeviceFrame", "yes").trim()) ? null : cfg.get("skin.path");



            is16kPages = (sysDir != null && (sysDir.toLowerCase().contains("ps16k") || sysDir.toLowerCase().contains("16k"))) || tagIds.contains("16kb") || (avdId != null && avdId.toLowerCase().contains("16k"));



        }







        String digits = targetStr.replaceAll("[^0-9.]", "");



        if (!digits.isEmpty()) {



            int dotIdx = digits.indexOf('.');



            apiLevel = dotIdx > 0 ? digits.substring(0, dotIdx) : digits;



        }



    }







    private static Map<String, String> parseIniFile(Path p) {



        Map<String, String> map = new LinkedHashMap<>();



        try {



            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {



                String trimmed = line.trim();



                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;



                int eq = trimmed.indexOf('=');



                if (eq > 0) {



                    String k = trimmed.substring(0, eq).trim();



                    String v = trimmed.substring(eq + 1).trim();



                    map.put(k, v);



                }



            }



        } catch (Exception ignored) {}



        return map;



    }







    /** The PC's time zone as an IANA id for Android, e.g. Asia/Kolkata for India Standard Time. */
    private static String hostTimeZone() {
        String id;
        try { id = java.time.ZoneId.systemDefault().normalized().getId(); } catch (Exception e) { return "Etc/UTC"; }
        // Windows maps some zones to older names; Android knows them, but shows the current ones.
        switch (id) {
            case "Asia/Calcutta": return "Asia/Kolkata";
            case "Asia/Katmandu": return "Asia/Kathmandu";
            case "Asia/Saigon": return "Asia/Ho_Chi_Minh";
            case "Asia/Rangoon": return "Asia/Yangon";
            case "Z": case "UTC": case "GMT": return "Etc/UTC";
            default: return id.matches("[A-Za-z_]+(/[A-Za-z0-9_+\\-]+)*") ? id : "Etc/UTC";
        }
    }
    private static int parseIntOrDefault(String s, int def) {



        if (s == null) return def;



        try {



            return Integer.parseInt(s.replaceAll("[^0-9]", ""));



        } catch (Exception e) {



            return def;



        }



    }







    private static void cleanupAndInspectRunningAvds(Path runningDir, String currentAvdId, long selfPid) {



        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runningDir, "pid_*.ini")) {



            for (Path entry : stream) {



                String fname = entry.getFileName().toString();



                long pid = -1;



                try {



                    pid = Long.parseLong(fname.substring(4, fname.length() - 4));



                } catch (Exception ignored) {}



                if (pid == selfPid) continue;







                boolean alive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);



                Map<String, String> ini = parseIniFile(entry);



                String otherAvd = ini.getOrDefault("avd.id", "");







                if (!alive) {



                    try { Files.deleteIfExists(entry); } catch (Exception ignored) {}



                } else if (currentAvdId.equalsIgnoreCase(otherAvd)) {



                    // Terminate previous instance of the exact same AVD to avoid lock conflicts



                    ProcessHandle.of(pid).ifPresent(ph -> {



                        ph.descendants().forEach(ProcessHandle::destroyForcibly);



                        ph.destroyForcibly();



                    });



                    try { Files.deleteIfExists(entry); } catch (Exception ignored) {}



                    try { Thread.sleep(300); } catch (Exception ignored) {}



                }



            }



        } catch (Exception ignored) {}



    }







    private static boolean isPortFree(int port) {



        try (ServerSocket ss = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {



            return true;



        } catch (Exception e) {



            return false;



        }



    }







    private static void allocatePorts(int requestedConsolePort) {



        if (requestedConsolePort >= 5554 && (requestedConsolePort % 2 == 0)) {



            int idx = (requestedConsolePort - 5554) / 2;



            int cPort = requestedConsolePort;



            int aPort = cPort + 1;



            int gPort = 8554 + (idx * 2);



            if (isPortFree(cPort) && isPortFree(aPort) && isPortFree(gPort)) {



                consolePort = cPort;



                adbPort = aPort;



                grpcPort = gPort;



                inputPort = 10555 + (consolePort - 5554);



                return;



            }



        }



        for (int i = 0; i < 16; i++) {



            int cPort = 5554 + (i * 2);



            int aPort = cPort + 1;



            int gPort = 8554 + (i * 2);



            if (isPortFree(cPort) && isPortFree(aPort) && isPortFree(gPort)) {



                consolePort = cPort;



                adbPort = aPort;



                grpcPort = gPort;



                inputPort = 10555 + (consolePort - 5554);



                return;



            }



        }



    }







        private static void allocateSerialPort() {

        int sPort = 15554 + ((consolePort - 5554) / 2) * 2;

        while (!isPortFree(sPort)) {

            sPort += 2;

        }

        serialPort = sPort;

    }



    // /metadata mounts /dev/block/vdd directly as ext4. An AVD disk created by other
    // tooling can still be GPT-wrapped, which leaves /metadata read-only and makes init
    // give up on /data. Unwrap the AVD's own partition, never the factory image: its
    // /data keys live there. The wrapped original is kept beside it.
    private static void unwrapAvdEncryptionKey(Path encAvd) {
        final long partOffset = 1048576L;
        final long maxFs = 16777216L;
        if (!Files.exists(encAvd)) return;
        Path tmp = Paths.get(encAvd + ".unwrap");
        try (FileChannel src = FileChannel.open(encAvd, StandardOpenOption.READ)) {
            ByteBuffer gpt = ByteBuffer.allocate(4);
            src.read(gpt, 512);
            ByteBuffer magic = ByteBuffer.allocate(2);
            src.read(magic, partOffset + 1080);
            boolean isGpt = gpt.get(0) == 'E' && gpt.get(1) == 'F' && gpt.get(2) == 'I' && gpt.get(3) == ' ';
            boolean hasExt4 = (magic.get(0) & 0xff) == 0x53 && (magic.get(1) & 0xff) == 0xEF;
            if (!isGpt) return;
            if (!hasExt4) {
                System.err.println("[WhpxEmulatorBridge] encryptionkey.img is GPT but has no ext4 at 1 MiB; leaving it alone");
                return;
            }
            long fsSize = Math.min(src.size() - partOffset, maxFs);
            try (FileChannel dst = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                long done = 0;
                while (done < fsSize) done += src.transferTo(partOffset + done, fsSize - done, dst);
            }
        } catch (Exception e) {
            System.err.println("[WhpxEmulatorBridge] Could not unwrap encryptionkey.img: " + e);
            return;
        }
        try {
            Path keep = Paths.get(encAvd + ".gpt.bak");
            if (!Files.exists(keep)) Files.copy(encAvd, keep);
            Files.move(tmp, encAvd, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[WhpxEmulatorBridge] Unwrapped this AVD's GPT encryptionkey.img in place");
        } catch (Exception e) {
            System.err.println("[WhpxEmulatorBridge] Could not replace encryptionkey.img: " + e);
        }
    }

    private static void prepareEncryptionKey(Path encSys, Path encAvd) {
        if (!Files.exists(encSys)) return;
        try {
            byte[] hdr = new byte[1024];
            try (InputStream is = Files.newInputStream(encSys)) {
                is.read(hdr);
            }
            boolean isGpt = (hdr[512] == 'E' && hdr[513] == 'F' && hdr[514] == 'I' && hdr[515] == ' ');
            if (isGpt) {
                long partOffset = 1048576L;
                long fsSize = Files.size(encSys) - partOffset;
                if (fsSize > 16777216L) fsSize = 16777216L;
                try (FileChannel src = FileChannel.open(encSys, StandardOpenOption.READ);
                     FileChannel dst = FileChannel.open(encAvd, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    src.transferTo(partOffset, fsSize, dst);
                }
                System.out.println("[WhpxEmulatorBridge] Unwrapped GPT metadata partition to raw ext4: " + fsSize + " bytes");
            } else {
                Files.copy(encSys, encAvd, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("[WhpxEmulatorBridge] Error preparing encryptionkey: " + e);
        }
    }

    // ext4 (0xEF53 at 1080) or f2fs (0xF2F52010 at 1024) superblock present.
    private static boolean hasFilesystem(Path img) {
        try (FileChannel ch = FileChannel.open(img, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (ch.read(b, 1024) < 58) return false;
            return (b.getShort(56) & 0xffff) == 0xEF53 || b.getInt(0) == 0xF2F52010;
        } catch (Exception e) {
            return false;
        }
    }
    // Runs a tool to completion; throws on timeout or a non-zero exit.
    private static void runTool(String name, int timeoutSec, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(name + " did not finish in " + timeoutSec + " s");
        }
        if (p.exitValue() != 0) throw new IOException(name + " failed with exit code " + p.exitValue());
    }
    private static void provisionAvdDisksAndInitrd() throws Exception {



        Files.createDirectories(Paths.get(avdDir));



        for (String lock : new String[]{"hardware-qemu.ini.lock", "userdata-qemu.img.lock", "multiinstance.lock"}) {



            try { Files.deleteIfExists(Paths.get(avdDir, lock)); } catch (Exception ignored) {}



        }







        String mke2fsExe = sdkDir + "\\platform-tools\\mke2fs.exe";
        String qemuImgExe = sdkDir + "\\emulator\\qemu-img.exe";
        String blockSize = "4096";







        // 1. cache.img



        Path cacheImg = Paths.get(avdDir, "cache.img");



        if (!Files.exists(cacheImg)) {



            new ProcessBuilder(mke2fsExe, "-F", "-t", "ext4", "-O", "^orphan_file", "-b", blockSize, cacheImg.toString(), "66M")



                .start().waitFor(15, TimeUnit.SECONDS);



        }

        // 2. encryptionkey.img



        Path encAvd = Paths.get(avdDir, "encryptionkey.img");



        Path encSys = Paths.get(sysDir, "encryptionkey.img");



        if ((!Files.exists(encAvd) || Files.size(encAvd) < 1000000L) && Files.exists(encSys)) {



            prepareEncryptionKey(encSys, encAvd);



        }
        unwrapAvdEncryptionKey(encAvd);







        // 3. userdata-qemu.img. userdata.img is the blank /data image it is converted from.
        // mke2fs writes the ext4 superblock last, so an interrupted or still-running mke2fs leaves a
        // full-size file with no filesystem. Android then neither mounts nor formats /data (vold:
        // "No key found ... read_key failed in mountFstab", init_user0 fails, reboot to bootloader),
        // and every boot loops. Studio's Wipe Data keeps userdata.img, so the bad image survived wipes.
        // Both tools now write to a temp file renamed only on success, and a userdata.img without a
        // filesystem is rebuilt together with the qcow2 made from it.
        Path userdataQcow2 = Paths.get(avdDir, "userdata-qemu.img");
        Path userdataRaw = Paths.get(avdDir, "userdata.img");
        Path sysUserdata = Paths.get(sysDir, "userdata.img");
        boolean rawOk = Files.exists(userdataRaw) && Files.size(userdataRaw) >= 50_000_000L && hasFilesystem(userdataRaw);
        if (Files.exists(userdataRaw) && !rawOk) {
            System.out.println("[WhpxEmulatorBridge] " + userdataRaw + " has no filesystem (interrupted mke2fs?): rebuilding it and userdata-qemu.img");
            Files.deleteIfExists(userdataQcow2);
        }
        if (!Files.exists(userdataQcow2) || Files.size(userdataQcow2) < 524288L) {
            if (Files.exists(encSys)) {
                prepareEncryptionKey(encSys, encAvd);
            }
            if (!rawOk) {
                Path rawTmp = Paths.get(avdDir, "userdata.img.tmp");
                Files.deleteIfExists(rawTmp);
                if (Files.exists(sysUserdata) && Files.size(sysUserdata) >= 50_000_000L && hasFilesystem(sysUserdata)) {
                    Files.copy(sysUserdata, rawTmp, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    runTool("mke2fs", 600, mke2fsExe, "-F", "-t", "ext4", "-O", "^orphan_file", "-b", blockSize, rawTmp.toString(), "10240M");
                }
                if (!hasFilesystem(rawTmp)) throw new IOException("mke2fs produced no ext4 filesystem in " + rawTmp);
                Files.move(rawTmp, userdataRaw, StandardCopyOption.REPLACE_EXISTING);
            }
            Path qcowTmp = Paths.get(avdDir, "userdata-qemu.img.tmp");
            Files.deleteIfExists(qcowTmp);
            runTool("qemu-img convert", 600, qemuImgExe, "convert", "-f", "raw", "-O", "qcow2", userdataRaw.toString(), qcowTmp.toString());
            Files.move(qcowTmp, userdataQcow2, StandardCopyOption.REPLACE_EXISTING);
        }







        if (Files.exists(userdataQcow2)) {



            try {



                new ProcessBuilder(qemuImgExe, "resize", userdataQcow2.toString(), "10G").start().waitFor(10, TimeUnit.SECONDS);



            } catch (Exception ignored) {}



        }







        // 4. Build dynamic initrd_whpx = dynamically patched ramdisk + #BOOTCONFIG trailer
        byte[] baseBytes;
        Path pyScript = Paths.get(bridgeDir, "prepare_whpx_initrd.py");
        if (Files.exists(pyScript)) {
            try {
                System.out.println("[WhpxEmulatorBridge] Preparing dynamic ramdisk base for " + sysDir);
                ProcessBuilder pyPbInitrd = new ProcessBuilder(pythonExe(), pyScript.toString(), sysDir, avdDir)
                    .inheritIO();
                if (useStimerKernel(parseIntOrDefault(apiLevel, 34))) {
                    // Modules built with kernel_whpx_stimer (same tree, compiler and signing key).
                    pyPbInitrd.environment().put("WHPX_MODULES_6_1", "modules_whpx_6_1_stimer");
                }
                Process pyProc = pyPbInitrd.start();
                pyProc.waitFor(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[WhpxEmulatorBridge] Error running prepare_whpx_initrd.py: " + e);
            }
        }

        Path generatedBase = Paths.get(avdDir, "initrd_whpx_base");
        if (Files.exists(generatedBase) && Files.size(generatedBase) > 100000L) {
            baseBytes = Files.readAllBytes(generatedBase);
        } else {
            Path universalBase = Paths.get(bridgeDir, "ramdisk_whpx_base.img");
            if (Files.exists(universalBase)) {
                baseBytes = Files.readAllBytes(universalBase);
            } else {
                baseBytes = Files.readAllBytes(Paths.get(sysDir, "ramdisk.img"));
            }
        }



        int basePad = (4 - (baseBytes.length % 4)) % 4;



        if (basePad > 0) {



            baseBytes = Arrays.copyOf(baseBytes, baseBytes.length + basePad);



        }







        String vbmetaSize = "6656";



        String vbmetaHashAlg = "sha256";



        String vbmetaDigest = "7d083757a5e20cd0c568182e509f6d02da844505ce73ef80b2f1ada3f02ae18c";



        Path vbmetaProto = Paths.get(sysDir, "VerifiedBootParams.textproto");



        if (Files.exists(vbmetaProto)) {



            for (String line : Files.readAllLines(vbmetaProto, StandardCharsets.UTF_8)) {



                String t = line.trim();



                if (t.startsWith("param:")) {



                    int q1 = t.indexOf('"');



                    int q2 = t.lastIndexOf('"');



                    if (q1 >= 0 && q2 > q1) {



                        String kv = t.substring(q1 + 1, q2);



                        if (kv.startsWith("androidboot.vbmeta.size=")) {



                            vbmetaSize = kv.substring("androidboot.vbmeta.size=".length());



                        } else if (kv.startsWith("androidboot.vbmeta.hash_alg=")) {



                            vbmetaHashAlg = kv.substring("androidboot.vbmeta.hash_alg=".length());



                        } else if (kv.startsWith("androidboot.vbmeta.digest=")) {



                            vbmetaDigest = kv.substring("androidboot.vbmeta.digest=".length());



                        }



                    }



                }



            }



        }







        String adbPubKey = "";



        Path adbKeyFile = Paths.get(System.getProperty("user.home"), ".android", "adbkey.pub");



        if (Files.exists(adbKeyFile)) {



            adbPubKey = new String(Files.readAllBytes(adbKeyFile), StandardCharsets.UTF_8).trim();



        }







        StringBuilder bc = new StringBuilder();



        bc.append("androidboot.boot_devices=\"3f000000.pcie\"\n");



        bc.append("androidboot.dalvik.vm.heapsize=\"576m\"\n");



        bc.append("androidboot.debug.hwui.renderer=\"skiagl\"\n");
        bc.append("androidboot.debug.renderengine.backend=\"skiaglthreaded\"\n");
        bc.append("androidboot.hardware=\"ranchu\"\n");
        bc.append("androidboot.hardware.egl=\"emulation\"\n");
        bc.append("androidboot.hardware.gralloc=\"ranchu\"\n");
        bc.append("androidboot.hardware.gltransport=\"pipe\"\n");
        bc.append("androidboot.hardware.vulkan=\"0\"\n");
        bc.append("androidboot.zygote.disable_gl_preload=\"1\"\n");



        bc.append("androidboot.radio.noril=\"yes\"\n");



        bc.append("androidboot.vendor.radio.noril=\"1\"\n");



        bc.append("androidboot.logcat=\"*:V\"\n");



        bc.append("androidboot.opengles.version=\"196608\"\n");



        bc.append("androidboot.lcd_density=\"").append(lcdDensity).append("\"\n");
        // The images ship config_showNavigationBar false and rely on the emulator setting
        // qemu.hw.mainkeys=0 on devices without hardware keys (whpx_init copies this over).
        // Without it large screens get neither a navigation bar nor the launcher's taskbar.
        if (!mainKeys) bc.append("androidboot.qemu.hw.mainkeys=\"0\"\n");
        bc.append(WhpxFold.bootConfig(lcdDensity));
        bc.append("androidboot.whpx.blur=\"").append(blurEnabled() ? 1 : 0).append("\"\n");
        System.out.println("[WhpxEmulatorBridge] window blurs " + (blurEnabled() ? "on" : "off") + " ("
            + Runtime.getRuntime().availableProcessors() + " host cores" + (System.getenv("WHPX_BLUR") != null ? ", WHPX_BLUR=" + System.getenv("WHPX_BLUR") : "") + ")");
        if (!skinCutout.isEmpty()) bc.append("androidboot.whpx.cutout=\"").append(skinCutout).append("\"\n");
        if (!skinName.isEmpty()) bc.append("androidboot.whpx.skin=\"").append(skinName).append("\"\n");
        bc.append("androidboot.whpx.gfx_port=\"").append(gfxPort).append("\"\n");
        bc.append("androidboot.qemu=\"1\"\n");



        if (!adbPubKey.isEmpty()) {



            bc.append("androidboot.qemu.adb.pubkey=\"").append(adbPubKey).append("\"\n");



        }



        bc.append("androidboot.qemu.avd_name=\"").append(avdId).append("\"\n");



        bc.append("androidboot.qemu.camera_hq_edge_processing=\"0\"\n");



        bc.append("androidboot.qemu.camera_protocol_ver=\"1\"\n");



        bc.append("androidboot.qemu.cpuvulkan.version=\"0\"\n");



        bc.append("androidboot.qemu.gltransport.drawFlushInterval=\"800\"\n");



        bc.append("androidboot.qemu.gltransport.name=\"pipe\"\n");



        // 0: no host media server here. Advertising 2 makes the goldfish decoders win over the
        // software ones, then abort on the first frame ("Failed to ping host to allocate memory").
        bc.append("androidboot.qemu.hwcodec.avcdec=\"0\"\n");



        bc.append("androidboot.qemu.hwcodec.hevcdec=\"0\"\n");



        bc.append("androidboot.qemu.hwcodec.vpxdec=\"0\"\n");



        bc.append("androidboot.qemu.settings.system.screen_off_timeout=\"2147483647\"\n");



        bc.append("androidboot.qemu.skin=\"").append(lcdWidth).append("x").append(lcdHeight).append("\"\n");



        bc.append("androidboot.qemu.virtiowifi=\"0\"\n");
        bc.append("androidboot.qemu.vsync=\"").append(hostRefreshRate()).append("\"\n");
        bc.append("androidboot.selinux=\"permissive\"\n");
        bc.append("androidboot.console=\"ttyAMA0\"\n");
        bc.append("androidboot.verifiedbootstate=\"orange\"\n");
        bc.append("androidboot.flash.locked=\"0\"\n");
        bc.append("androidboot.service.adb.tcp.port=\"5555\"\n");



        bc.append("androidboot.serialno=\"").append(getSerialPrefix(sysDir)).append("\"\n");



        bc.append("androidboot.vbmeta.digest=\"").append(vbmetaDigest).append("\"\n");



        bc.append("androidboot.vbmeta.hash_alg=\"").append(vbmetaHashAlg).append("\"\n");



        bc.append("androidboot.vbmeta.size=\"").append(vbmetaSize).append("\"\n");



        bc.append("androidboot.veritymode=\"disabled\"\n");







        byte[] paramBytes = bc.toString().getBytes(StandardCharsets.UTF_8);



        int pad = (4 - (paramBytes.length % 4)) % 4;



        byte[] paddedParams = Arrays.copyOf(paramBytes, paramBytes.length + pad);



        long csum = 0;



        for (byte b : paddedParams) {



            csum += (b & 0xFF);



        }



        byte[] magic = "#BOOTCONFIG\n".getBytes(StandardCharsets.US_ASCII);







        ByteBuffer trailerBuf = ByteBuffer.allocate(paddedParams.length + 8 + magic.length).order(ByteOrder.LITTLE_ENDIAN);



        trailerBuf.put(paddedParams);



        trailerBuf.putInt(paddedParams.length);



        trailerBuf.putInt((int) (csum & 0xFFFFFFFFL));



        trailerBuf.put(magic);



        byte[] trailerBytes = trailerBuf.array();







        Path outInitrd = Paths.get(avdDir, "initrd_whpx");



        try (OutputStream fos = Files.newOutputStream(outInitrd)) {



            fos.write(baseBytes);



            fos.write(trailerBytes);



        }







        // 5. Write hardware-qemu.ini so Android Studio's EmulatorConfiguration.readAvdDefinition always succeeds



        Path hwQemuIni = Paths.get(avdDir, "hardware-qemu.ini");



        String avdHome = Paths.get(System.getProperty("user.home"), ".android", "avd").toString();



        String hwQemuContent = String.join("\r\n",



            "hw.cpu.arch = arm64",



            "hw.cpu.ncore = " + cpuCores,



            "hw.ramSize = " + ramMb,



            "hw.screen = multi-touch",



            "hw.mainKeys = false",



            "hw.touchpad0 = false",



            "hw.touchpad0.width = 600",



            "hw.touchpad0.height = 400",



            "hw.trackBall = false",



            "hw.keyboard = true",



            "hw.keyboard.lid = true",



            "hw.keyboard.charmap = qwerty2",



            "hw.dPad = false",



            "hw.rotaryInput = false",



            "hw.gsmModem = true",



            "hw.gps = true",



            "hw.battery = true",



            "hw.accelerometer = true",



            "hw.accelerometer_uncalibrated = true",



            "hw.gyroscope = true",



            "hw.audioInput = true",



            "hw.audioOutput = true",



            "hw.sdCard = true",



            "disk.cachePartition = true",



            "disk.cachePartition.path = " + cacheImg.toString(),



            "disk.cachePartition.size = 66m",



            "test.quitAfterBootTimeOut = -1",



            "test.delayAdbTillBootComplete = 0",



            "test.monitorAdb = 0",



            "hw.lcd.width = " + lcdWidth,



            "hw.lcd.height = " + lcdHeight,



            "hw.lcd.depth = 32",



            "hw.lcd.circular = false",



            "hw.lcd.density = " + lcdDensity,



            "hw.lcd.transparent = false",



            "hw.lcd.backlight = true",



            "hw.lcd.vsync = 60",



            "hw.gltransport = pipe",



            "hw.gltransport.asg.writeBufferSize = 1048576",



            "hw.gltransport.asg.writeStepSize = 4096",



            "hw.gltransport.asg.dataRingSize = 32768",



            "hw.gltransport.drawFlushInterval = 800",



            "hw.displayRegion.0.1.xOffset = -1",



            "hw.displayRegion.0.1.yOffset = -1",



            "hw.displayRegion.0.1.width = 0",



            "hw.displayRegion.0.1.height = 0",



            "hw.displayRegion.0.2.xOffset = -1",



            "hw.displayRegion.0.2.yOffset = -1",



            "hw.displayRegion.0.2.width = 0",



            "hw.displayRegion.0.2.height = 0",



            "hw.displayRegion.0.3.xOffset = -1",



            "hw.displayRegion.0.3.yOffset = -1",



            "hw.displayRegion.0.3.width = 0",



            "hw.displayRegion.0.3.height = 0",



            "hw.display1.width = 0",



            "hw.display1.height = 0",



            "hw.display1.density = 0",



            "hw.display1.xOffset = -1",



            "hw.display1.yOffset = -1",



            "hw.display1.flag = 0",



            "hw.display2.width = 0",



            "hw.display2.height = 0",



            "hw.display2.density = 0",



            "hw.display2.xOffset = -1",



            "hw.display2.yOffset = -1",



            "hw.display2.flag = 0",



            "hw.display3.width = 0",



            "hw.display3.height = 0",



            "hw.display3.density = 0",



            "hw.display3.xOffset = -1",



            "hw.display3.yOffset = -1",



            "hw.display3.flag = 0",



            "hw.multi_display_window = false",



            "hw.hotplug_multi_display = false",



            "hw.gpu.enabled = true",



            "hw.gpu.mode = auto",



            "hw.initialOrientation = portrait",



            "hw.camera.back = emulated",



            "hw.camera.back.orientation = 90",



            "hw.camera.front = emulated",



            "hw.camera.front.orientation = 90",



            "vm.heapSize = 576",



            "hw.sensors.light = true",



            "hw.sensors.pressure = true",



            "hw.sensors.humidity = true",



            "hw.sensors.proximity = true",



            "hw.sensors.magnetic_field = true",



            "hw.sensors.magnetic_field_uncalibrated = true",



            "hw.sensors.gyroscope_uncalibrated = true",



            "hw.sensors.orientation = true",



            "hw.sensors.temperature = true",



            "hw.sensors.rgbclight = false",



            "hw.sensor.hinge = false",



            "hw.sensor.hinge.count = 0",



            "hw.sensor.hinge.type = 0",



            "hw.sensor.hinge.sub_type = 0",



            "hw.sensor.hinge.resizable.config = 1",



            "hw.sensor.hinge.fold_to_displayRegion.0.1_at_posture = 1",



            "hw.sensor.roll = false",



            "hw.sensor.roll.count = 0",



            "hw.sensor.roll.resize_to_displayRegion.0.1_at_posture = 6",



            "hw.sensor.roll.resize_to_displayRegion.0.2_at_posture = 6",



            "hw.sensor.roll.resize_to_displayRegion.0.3_at_posture = 6",



            "hw.sensors.heart_rate = false",



            "hw.sensors.wrist_tilt = false",



            "hw.sensors.heading = false",



            "hw.useext4 = true",



            "hw.device.name = pixel",



            "kernel.path = " + sysDir + "\\kernel-ranchu",



            "kernel.parameters = user_debug=31 ignore_loglevel androidboot.selinux=permissive sysctl.vm.overcommit_memory=1",



            "kernel.newDeviceNaming = yes",



            "kernel.supportsYaffs2 = no",



            "disk.ramdisk.path = " + sysDir + "\\ramdisk.img",



            "disk.systemPartition.initPath = " + sysDir + "\\system.img",



            "disk.systemPartition.size = 4107m",



            "disk.vendorPartition.initPath = " + sysDir + "\\vendor.img",



            "disk.vendorPartition.size = 800m",



            "disk.dataPartition.path = " + userdataQcow2.toString(),



            "disk.dataPartition.size = 10g",



            "disk.encryptionKeyPartition.path = " + encAvd.toString(),



            "PlayStore.enabled = false",



            "avd.name = " + avdId,



            "avd.id = " + avdId,



            "fastboot.forceColdBoot = true",



            "userdata.useQcow2 = true",



            "firstboot.bootFromDownloadableSnapshot = false",



            "firstboot.bootFromLocalSnapshot = false",



            "firstboot.saveToLocalSnapshot = false",



            "android.sdk.root = " + sdkDir,



            "android.avd.home = " + avdHome,



            "environment.width = 0",



            "environment.height = 0",



            ""



        );



        // Foldables list their hinge and postures, so Studio offers Closed / Half-open / Open.
        hwQemuContent = WhpxFold.patchIni(hwQemuContent);
        Files.write(hwQemuIni, hwQemuContent.getBytes(StandardCharsets.UTF_8));



    }







    private static void runConsoleServer() {



        try (ServerSocket ss = new ServerSocket(consolePort, 50, InetAddress.getByName("127.0.0.1"))) {



            while (true) {



                Socket client = ss.accept();



                Thread t = new Thread(() -> handleConsoleClient(client));



                t.setDaemon(true);



                t.start();



            }



        } catch (Exception e) {



            System.err.println("Console server error: " + e.getMessage());



        }



    }







    private static void handleConsoleClient(Socket client) {



        try (



            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));



            OutputStream out = client.getOutputStream()



        ) {



            out.write("Android Console: type 'help' for a list of commands\r\nOK\r\n".getBytes(StandardCharsets.UTF_8));



            out.flush();



            String line;



            while ((line = in.readLine()) != null) {



                String cmd = line.trim();



                String resp = "OK\r\n";



                if (cmd.equals("avd name")) {



                    resp = avdId + "\r\nOK\r\n";



                } else if (cmd.equals("avd id")) {



                    resp = avdId + "\r\nOK\r\n";



                } else if (cmd.equals("avd status")) {



                    resp = "virtual device is running\r\nOK\r\n";



                } else if (cmd.equals("avd path")) {



                    resp = avdDir + "\r\nOK\r\n";



                } else if (cmd.startsWith("geo fix ")) {
                    // Same argument order as the stock console: longitude latitude [altitude]
                    String[] g = cmd.trim().split("\\s+");
                    try {
                        double lon = Double.parseDouble(g[2]), lat = Double.parseDouble(g[3]);
                        double alt = g.length > 4 ? Double.parseDouble(g[4]) : gpsAlt;
                        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new NumberFormatException();
                        applyGps(lat, lon, alt);
                    } catch (RuntimeException e) {
                        resp = "KO: usage: geo fix <longitude> <latitude> [altitude]\r\n";
                    }
                } else if (cmd.equals("ping")) {



                    resp = "pong\r\nOK\r\n";



                } else if (cmd.equals("kill")) {



                    out.write("OK\r\n".getBytes(StandardCharsets.UTF_8));



                    out.flush();



                    if (whpxProc != null) whpxProc.destroyForcibly();



                    System.exit(0);



                } else if (cmd.equals("quit") || cmd.equals("exit")) {



                    break;



                }



                out.write(resp.getBytes(StandardCharsets.UTF_8));



                out.flush();



            }



        } catch (Exception ignored) {}



    }







    private static void initFrameMbb() {



        synchronized (frameMbbLock) {



            if (frameMbb != null) return;



            try {



                File f = new File(bridgeDir, frameFileName);



                int totalFileSize = 16 + lcdWidth * lcdHeight * 4 * 2 + 8;



                if (!f.exists() || f.length() != totalFileSize) {



                    RandomAccessFile raf = new RandomAccessFile(f, "rw");



                    raf.setLength(totalFileSize);



                    raf.close();



                }



                FileChannel fc = new RandomAccessFile(f, "rw").getChannel();



                frameMbb = fc.map(FileChannel.MapMode.READ_WRITE, 0, totalFileSize);



                frameMbb.order(ByteOrder.LITTLE_ENDIAN);



            } catch (Exception ignored) {}



        }



    }







    private static volatile int currentRotation = 0; // 0=PORTRAIT, 1=LANDSCAPE, 2=REVERSE_PORTRAIT, 3=REVERSE_LANDSCAPE

    private static volatile float currentZAngle = 0f;

    private static volatile int cachedEncodedRot = -1;

    private static volatile int cachedEncodedSeq = -1;

    private static volatile int cachedEncodedW = -1;

    private static volatile int cachedEncodedH = -1;

    private static volatile ImageFormat.ImgFormat cachedEncodedFmt = null;

    private static volatile ByteString cachedEncodedBytes = null;







    private static final long MAX_GRPC_PAYLOAD_BYTES = 9_500_000L;







    // Measurement only: once a second, writes stream stats to %TEMP%\whpx_stream_perf.log
    // while the file %TEMP%\whpx_perf_bridge exists. Otherwise it costs one clock read per frame.
    private static final java.io.File SP_FLAG = new java.io.File(System.getProperty("java.io.tmpdir"), "whpx_perf_bridge");
    private static final java.io.File SP_OUT = new java.io.File(System.getProperty("java.io.tmpdir"), "whpx_stream_perf.log");
    private static final Object SP_LOCK = new Object();
    private static volatile boolean spOn;
    private static long spCheck, spWin, spLast, spBuild, spSend, spBuildMax, spSendMax, spGapMax;
    private static int spPush, spSrc0;

    private static void pushFrame(ImageFormat req, StreamObserver<Image> resp, int seq) {
        long t0 = System.nanoTime();
        if (t0 - spCheck > 1000000000L) { spCheck = t0; spOn = SP_FLAG.exists(); }
        if (!spOn) { resp.onNext(buildScreenImage(req, seq)); return; }
        Image img = buildScreenImage(req, seq);
        long t1 = System.nanoTime();
        resp.onNext(img);
        long t2 = System.nanoTime();
        synchronized (SP_LOCK) {
            int src = frameMbb != null ? frameMbb.getInt(0) : 0;
            if (spWin == 0) { spWin = t0; spSrc0 = src; }
            spPush++;
            spBuild += t1 - t0; spBuildMax = Math.max(spBuildMax, t1 - t0);
            spSend += t2 - t1; spSendMax = Math.max(spSendMax, t2 - t1);
            if (spLast != 0) spGapMax = Math.max(spGapMax, t0 - spLast);
            spLast = t0;
            if (t2 - spWin >= 1000000000L) {
                String line = String.format(java.util.Locale.ROOT,
                    "%tT pushed=%d rendered=%d build=%.2f/%.2fms send=%.2f/%.2fms gapMax=%.1fms size=%dx%d fmt=%s bytes=%d heapMB=%d%n",
                    new java.util.Date(), spPush, src - spSrc0, spBuild / 1e6 / spPush, spBuildMax / 1e6,
                    spSend / 1e6 / spPush, spSendMax / 1e6, spGapMax / 1e6, img.getWidth(), img.getHeight(),
                    req.getFormat(), img.getImage().size(),
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
                try (java.io.FileWriter fw = new java.io.FileWriter(SP_OUT, true)) { fw.write(line); } catch (Exception ignored) {}
                spWin = 0; spPush = 0; spBuild = spSend = spBuildMax = spSendMax = spGapMax = 0;
            }
        }
    }

    /**
     * The screen image for a client. A folded foldable shows its cover screen, the guest's second
     * display, as Google's emulator does: the image is that display and foldedDisplay says so.
     */
    private static Image buildScreenImage(ImageFormat req, int seq) {
        return buildFullImage(req, seq);
    }

    private static Image buildFullImage(ImageFormat req, int seq) {

        if (frameMbb == null) initFrameMbb();



        int curRot = currentRotation;

        int[] disp = displaySize();

        int baseW = (curRot % 2 == 0) ? disp[0] : disp[1];

        int baseH = (curRot % 2 == 0) ? disp[1] : disp[0];



        int maxW = req.getWidth() > 0 ? req.getWidth() : baseW;

        int maxH = req.getHeight() > 0 ? req.getHeight() : baseH;

        double scale = Math.min((double) maxW / baseW, (double) maxH / baseH);

        if (scale > 1.0) scale = 1.0;

        int w = Math.max(2, ((int) Math.round(baseW * scale)) & ~1);

        int h = Math.max(2, ((int) Math.round(baseH * scale)) & ~1);

        ImageFormat.ImgFormat fmt = req.getFormat();



        // Enforce maximum payload size so Android Studio gRPC client never throws RESOURCE_EXHAUSTED (>10.46MB)

        long bpp = (fmt == ImageFormat.ImgFormat.RGB888) ? 3 : 4;

        if ((long) w * h * bpp > MAX_GRPC_PAYLOAD_BYTES) {

            double capScale = Math.sqrt((double) MAX_GRPC_PAYLOAD_BYTES / ((long) baseW * baseH * bpp));

            if (capScale < scale) scale = capScale;

            w = Math.max(2, ((int) Math.round(baseW * scale)) & ~1);

            h = Math.max(2, ((int) Math.round(baseH * scale)) & ~1);

        }



        Rotation.SkinRotation skinRot;

        switch (curRot) {

            case 1: skinRot = Rotation.SkinRotation.LANDSCAPE; break;

            case 2: skinRot = Rotation.SkinRotation.REVERSE_PORTRAIT; break;

            case 3: skinRot = Rotation.SkinRotation.REVERSE_LANDSCAPE; break;

            default: skinRot = Rotation.SkinRotation.PORTRAIT; break;

        }

        Rotation protoRot = Rotation.newBuilder().setRotation(skinRot).build();



        int curSeq = (frameMbb != null) ? frameMbb.getInt(0) : -1;

        int fw = (frameMbb != null) ? frameMbb.getInt(4) : 0;

        int fh = (frameMbb != null) ? frameMbb.getInt(8) : 0;

        int activeSlot = (frameMbb != null) ? (frameMbb.getInt(12) & 0xFF) : 0;

        int slotOffset = 16 + (activeSlot == 1 ? (lcdWidth * lcdHeight * 4) : 0);



        if (curSeq > 0 && curSeq == cachedEncodedSeq && w == cachedEncodedW && h == cachedEncodedH

                && fmt == cachedEncodedFmt && curRot == cachedEncodedRot && cachedEncodedBytes != null) {

            ImageFormat outFmt = ImageFormat.newBuilder()

                .setFormat(fmt)

                .setWidth(w)

                .setHeight(h)

                .setDisplay(req.getDisplay())

                .setRotation(protoRot)
                .mergeFrom(foldFormat(curRot))

                .build();

            return Image.newBuilder()

                .setFormat(outFmt)

                .setWidth(w)

                .setHeight(h)

                .setImage(cachedEncodedBytes)

                .setSeq(seq)

                .setTimestampUs(System.currentTimeMillis() * 1000L)

                .build();

        }







        ByteString bs = null;



        if (curSeq > 0 && fw > 0 && fh > 0 && (long) fw * fh <= (long) lcdWidth * lcdHeight) {



            try {



                if (curRot == 0 && w == fw && h == fh && fw == disp[0] && fmt == ImageFormat.ImgFormat.RGBA8888) {

                    ByteBuffer slice = frameMbb.duplicate();

                    slice.position(slotOffset);

                    byte[] rgba = new byte[w * h * 4];

                    slice.get(rgba);

                    for (int i = 3; i < rgba.length; i += 4) {

                        rgba[i] = (byte) 0xFF;

                    }

                    bs = com.android.tools.idea.protobuf.UnsafeByteOperations.unsafeWrap(rgba); // fresh array, not reused

                } else if (curRot == 0 && w == fw && h == fh && fw == disp[0] && fmt == ImageFormat.ImgFormat.RGB888) {

                    ByteBuffer slice = frameMbb.duplicate();

                    slice.position(slotOffset);

                    byte[] rgb = new byte[w * h * 3];

                    int dst = 0;

                    int count = w * h;

                    for (int i = 0; i < count; i++) {

                        rgb[dst++] = slice.get();

                        rgb[dst++] = slice.get();

                        rgb[dst++] = slice.get();

                        slice.get();

                    }

                    bs = com.android.tools.idea.protobuf.UnsafeByteOperations.unsafeWrap(rgb); // fresh array, not reused

                } else {

                    boolean isRgba = (fmt == ImageFormat.ImgFormat.RGBA8888);

                    byte[] raw = new byte[w * h * (isRgba ? 4 : 3)];



                    int[] sxMap = new int[curRot % 2 == 0 ? w : h];

                    int[] syMap = new int[curRot % 2 == 0 ? h : w];



                    if (curRot == 0) {

                        for (int x = 0; x < w; x++) sxMap[x] = Math.min(fw - 1, (int) ((long) x * fw / w));

                        for (int y = 0; y < h; y++) syMap[y] = Math.min(fh - 1, (int) ((long) y * fh / h));

                    } else if (curRot == 1) { // 90 deg CCW (Rotate Left in Studio) - Option D

                        for (int y = 0; y < h; y++) sxMap[y] = Math.min(fw - 1, (int) ((long) (h - 1 - y) * fw / h));

                        for (int x = 0; x < w; x++) syMap[x] = Math.min(fh - 1, (int) ((long) x * fh / w));

                    } else if (curRot == 3) { // 270 deg CCW / 90 deg CW (Rotate Right in Studio) - Option C

                        for (int y = 0; y < h; y++) sxMap[y] = Math.min(fw - 1, (int) ((long) y * fw / h));

                        for (int x = 0; x < w; x++) syMap[x] = Math.min(fh - 1, (int) ((long) (w - 1 - x) * fh / w));

                    } else { // curRot == 2, 180 deg

                        for (int x = 0; x < w; x++) sxMap[x] = Math.min(fw - 1, (int) ((long) (w - 1 - x) * fw / w));

                        for (int y = 0; y < h; y++) syMap[y] = Math.min(fh - 1, (int) ((long) (h - 1 - y) * fh / h));

                    }



                    int dst = 0;

                    if (curRot == 0) {

                        ByteBuffer slice = frameMbb.duplicate();

                        byte[] rowBuf = new byte[fw * 4];

                        int lastSrcY = -1;

                        for (int y = 0; y < h; y++) {

                            int srcY = syMap[y];

                            if (srcY != lastSrcY) {

                                slice.position(slotOffset + srcY * fw * 4);

                                slice.get(rowBuf, 0, fw * 4);

                                lastSrcY = srcY;

                            }

                            for (int x = 0; x < w; x++) {

                                int sx4 = sxMap[x] * 4;

                                raw[dst++] = rowBuf[sx4];

                                raw[dst++] = rowBuf[sx4 + 1];

                                raw[dst++] = rowBuf[sx4 + 2];

                                if (isRgba) raw[dst++] = (byte) 0xFF;

                            }

                        }

                    } else {

                        for (int y = 0; y < h; y++) {

                            for (int x = 0; x < w; x++) {

                                int sx = (curRot % 2 == 0) ? sxMap[x] : sxMap[y];

                                int sy = (curRot % 2 == 0) ? syMap[y] : syMap[x];

                                int pixelOffset = slotOffset + (sy * fw + sx) * 4;

                                int p = frameMbb.getInt(pixelOffset);

                                raw[dst++] = (byte) p;

                                raw[dst++] = (byte) (p >> 8);

                                raw[dst++] = (byte) (p >> 16);

                                if (isRgba) raw[dst++] = (byte) 0xFF;

                            }

                        }

                    }

                    bs = com.android.tools.idea.protobuf.UnsafeByteOperations.unsafeWrap(raw); // fresh array, not reused

                }



            } catch (Exception ignored) {}



        }







        if (bs != null) {

            cachedEncodedBytes = bs;

            cachedEncodedSeq = curSeq;

            cachedEncodedW = w;

            cachedEncodedH = h;

            cachedEncodedFmt = fmt;

            cachedEncodedRot = curRot;

        } else {



            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);







            Graphics2D g = bi.createGraphics();







            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);







            g.setColor(Color.WHITE);



            g.fillRect(0, 0, w, h);







            int logoSize = Math.max(36, Math.min(w, h) / 7);



            int cx = w / 2;



            int cy = h / 2 - (h / 20);







            // Google Blue 'G'



            g.setColor(new Color(66, 133, 244));



            g.setFont(new Font("SansSerif", Font.BOLD, logoSize));



            java.awt.FontMetrics fm = g.getFontMetrics();



            String logoText = "G";



            int tx = cx - fm.stringWidth(logoText) / 2;



            int ty = cy + (fm.getAscent() - fm.getDescent()) / 2;



            g.drawString(logoText, tx, ty);







            // 'powered by android' at bottom



            g.setColor(new Color(95, 99, 104));



            int botFontSize = Math.max(12, Math.min(w, h) / 36);



            g.setFont(new Font("SansSerif", Font.PLAIN, botFontSize));



            java.awt.FontMetrics fmBot = g.getFontMetrics();



            String poweredText = "powered by";



            int byY = h - (h / 10);



            g.drawString(poweredText, cx - fmBot.stringWidth(poweredText) / 2, byY);



            g.setFont(new Font("SansSerif", Font.BOLD, (int)(botFontSize * 1.3)));



            java.awt.FontMetrics fmAnd = g.getFontMetrics();



            String androidText = "android";



            int andY = byY + fmBot.getHeight() + 4;



            g.drawString(androidText, cx - fmAnd.stringWidth(androidText) / 2, andY);







            g.dispose();







            int[] pixels = ((java.awt.image.DataBufferInt) bi.getRaster().getDataBuffer()).getData();



            boolean isRgba = (fmt == ImageFormat.ImgFormat.RGBA8888);



            byte[] rawBytes = new byte[w * h * (isRgba ? 4 : 3)];



            int idx = 0;



            if (isRgba) {



                for (int i = 0; i < pixels.length; i++) {



                    int argb = pixels[i];



                    rawBytes[idx++] = (byte) ((argb >> 16) & 0xFF);



                    rawBytes[idx++] = (byte) ((argb >> 8) & 0xFF);



                    rawBytes[idx++] = (byte) (argb & 0xFF);



                    rawBytes[idx++] = (byte) ((argb >> 24) & 0xFF);



                }



            } else {



                for (int i = 0; i < pixels.length; i++) {



                    int argb = pixels[i];



                    rawBytes[idx++] = (byte) ((argb >> 16) & 0xFF);



                    rawBytes[idx++] = (byte) ((argb >> 8) & 0xFF);



                    rawBytes[idx++] = (byte) (argb & 0xFF);



                }



            }



            bs = com.android.tools.idea.protobuf.UnsafeByteOperations.unsafeWrap(rawBytes); // fresh array, not reused



        }







        ImageFormat outFmt = ImageFormat.newBuilder()



            .setFormat(fmt)



            .setWidth(w)



            .setHeight(h)



            .setDisplay(req.getDisplay())

            .setRotation(protoRot)
            .mergeFrom(foldFormat(curRot))

            .build();







        return Image.newBuilder()



            .setFormat(outFmt)



            .setWidth(w)



            .setHeight(h)



            .setImage(bs)



            .setSeq(seq)



            .setTimestampUs(System.currentTimeMillis() * 1000L)



            .build();



    }







    private static class EmulatorControllerServiceImpl extends EmulatorControllerGrpc.EmulatorControllerImplBase {



        @Override



        public void setGps(GpsState req, StreamObserver<Empty> resp) {
            applyGps(req.getLatitude(), req.getLongitude(), req.getAltitude());
            resp.onNext(Empty.getDefaultInstance());
            resp.onCompleted();
        }

        @Override
        public void getGps(Empty req, StreamObserver<GpsState> resp) {
            resp.onNext(GpsState.newBuilder().setLatitude(gpsLat).setLongitude(gpsLon).setAltitude(gpsAlt).setSatellites(8).build());
            resp.onCompleted();
        }

        @Override
        public void getVmState(Empty req, StreamObserver<VmRunState> resp) {



            resp.onNext(VmRunState.newBuilder().setState(VmRunState.RunState.RUNNING).build());



            resp.onCompleted();



        }







        @Override



        public void setVmState(VmRunState req, StreamObserver<Empty> resp) {



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



            if (req.getState() == VmRunState.RunState.SHUTDOWN || req.getState() == VmRunState.RunState.TERMINATE) {



                if (whpxProc != null) whpxProc.destroyForcibly();



                System.exit(0);



            }



        }







        @Override



        public void getStatus(Empty req, StreamObserver<EmulatorStatus> resp) {



            long uptime = System.currentTimeMillis() - START_TIME;



            VmConfiguration vmCfg = VmConfiguration.newBuilder()



                .setHypervisorType(VmConfiguration.VmHypervisorType.WHPX)



                .setNumberOfCpuCores(cpuCores)



                .setRamSizeBytes((long) ramMb * 1024L * 1024L)



                .build();



                        EntryList.Builder hwList = EntryList.newBuilder();
            String nameToUse = (avdId != null && !avdId.isEmpty()) ? avdId : "Pixel_7";
            hwList.addEntry(Entry.newBuilder().setKey("avd.name").setValue(nameToUse).build());
            hwList.addEntry(Entry.newBuilder().setKey("avd.id").setValue(nameToUse).build());
            hwList.addEntry(Entry.newBuilder().setKey("hw.device.name").setValue(nameToUse).build());
            hwList.addEntry(Entry.newBuilder().setKey("hw.device.manufacturer").setValue("Google").build());
            hwList.addEntry(Entry.newBuilder().setKey("ro.product.model").setValue(nameToUse).build());

            EmulatorStatus status = EmulatorStatus.newBuilder()
                .setHardwareConfig(hwList.build())



                .setVersion("37.2.13.0")



                .setUptime(uptime)



                .setBooted(true)



                .setHeartbeat(uptime / 1000L)



                .setVmConfig(vmCfg)



                .putGuestConfig("androidVersion", apiLevel)



                .putGuestConfig("multidisplay", "available")



                .putGuestConfig("hypervisorVersion", "WHPX")



                .build();



            resp.onNext(status);



            resp.onCompleted();



        }







        @Override



        public void getDisplayConfigurations(Empty req, StreamObserver<DisplayConfigurations> resp) {



            DisplayConfiguration disp = DisplayConfiguration.newBuilder()



                .setWidth(lcdWidth)



                .setHeight(lcdHeight)



                .setDpi(lcdDensity)



                .setFlags(0)



                .setDisplay(0)



                .build();



            DisplayConfigurations cfgs = DisplayConfigurations.newBuilder()



                .addDisplays(disp)



                .setUserConfigurable(3)



                .setMaxDisplays(11)



                .build();



            resp.onNext(cfgs);



            resp.onCompleted();



        }







        @Override



        public void getEnvironment(Empty req, StreamObserver<Environment> resp) {



            resp.onNext(Environment.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void getScreenshot(ImageFormat req, StreamObserver<Image> resp) {



            holdFullCapture(req);
            resp.onNext(buildScreenImage(req, 0));



            resp.onCompleted();



        }







        @Override



        public void streamScreenshot(ImageFormat req, StreamObserver<Image> resp) {



            System.out.println("[WhpxEmulatorBridge] streamScreenshot connected: " + req.getWidth() + "x" + req.getHeight() + " fmt=" + req.getFormat());



            Thread streamer = new Thread(() -> {



                int seq = 0;



                int lastPushedSeq = -1;



                long lastPushTime = 0;



                while (!Thread.currentThread().isInterrupted()) {



                    try {



                        if (frameMbb == null) initFrameMbb();
                        publishCaptureSize(req);



                        int curSeq = (frameMbb != null) ? frameMbb.getInt(0) : -1;



                        long now = System.currentTimeMillis();



                        if (lastPushedSeq == -1) {



                            lastPushedSeq = curSeq;



                            lastPushTime = now;



                            pushFrame(req, resp, seq++);



                        } else if (curSeq > 0 && curSeq != lastPushedSeq) {



                            lastPushedSeq = curSeq;



                            lastPushTime = now;



                            pushFrame(req, resp, seq++);



                            if (seq % 60 == 1) {



                                System.out.println("[WhpxEmulatorBridge] Streaming frame seq=" + seq + " curSeq=" + curSeq);



                            }



                        } else if (now - lastPushTime > 500) {



                            lastPushTime = now;



                            pushFrame(req, resp, seq++);



                        }



                        Thread.sleep(3);



                    } catch (Throwable t) {



                        System.out.println("[WhpxEmulatorBridge] streamScreenshot terminated: " + t);



                        break;



                    }



                }
                captureRequests.remove(Thread.currentThread());



            }, "ScreenshotStreamer");



            streamer.setDaemon(true);



            streamer.start();



        }







        @Override



        public void streamNotification(Empty req, StreamObserver<Notification> resp) {



            try {



                resp.onNext(Notification.newBuilder()



                    .setBooted(BootCompletedNotification.newBuilder().setTime(6000).build())



                    .build());
                if (WhpxFold.foldable()) {
                    resp.onNext(Notification.newBuilder()
                        .setPosture(Posture.newBuilder().setValueValue(WhpxFold.posture()).build()).build());
                }
                notificationObservers.add(resp);



            } catch (Throwable ignored) {}



        }







        @Override



        public void streamClipboard(Empty req, StreamObserver<ClipData> resp) {



            // Keep open



        }







        @Override



        public void setClipboard(ClipData req, StreamObserver<Empty> resp) {



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void getClipboard(Empty req, StreamObserver<ClipData> resp) {



            resp.onNext(ClipData.getDefaultInstance());



            resp.onCompleted();



        }







        private static volatile Process inputShellProc = null;



        private static final Object inputShellLock = new Object();



        private static volatile Socket directInputSocket = null;



        private static volatile OutputStream directInputOut = null;



        private static final Object directInputLock = new Object();



        private static volatile boolean touchDown = false;



        private static volatile int touchLastX = 0, touchLastY = 0;



        private static volatile boolean mouseDown = false;



        private static volatile int mouseLastX = 0, mouseLastY = 0;







        private static void sendDirectInput(String cmd) {











            byte[] bytes = (scaleTouch(cmd) + "\n").getBytes(StandardCharsets.UTF_8);











            boolean sent = false;











            synchronized (directInputLock) {











                for (int attempt = 0; attempt < 2; attempt++) {











                    try {











                        if (directInputSocket == null || directInputSocket.isClosed() || !directInputSocket.isConnected()) {











                            directInputSocket = new Socket("127.0.0.1", inputPort);











                            directInputSocket.setTcpNoDelay(true);











                            directInputSocket.setSendBufferSize(65536);











                            directInputOut = directInputSocket.getOutputStream();











                        }











                        directInputOut.write(bytes);











                        directInputOut.flush();











                        sent = true;











                        break;











                    } catch (Exception ex) {











                        try { if (directInputSocket != null) directInputSocket.close(); } catch (Exception ignored) {}











                        directInputSocket = null;











                        directInputOut = null;











                    }











                }











            }











            if (!sent) {











                if (cmd.startsWith("T 0 ")) {











                    dispatchGuestInput("cmd input motionevent DOWN " + cmd.substring(4));











                } else if (cmd.startsWith("T 2 ")) {











                    dispatchGuestInput("cmd input motionevent MOVE " + cmd.substring(4));











                } else if (cmd.startsWith("T 1 ")) {











                    dispatchGuestInput("cmd input motionevent UP " + cmd.substring(4));











                } else if (cmd.startsWith("K ")) {











                    dispatchGuestInput("cmd input keyevent " + cmd.substring(2));











                } else if (cmd.startsWith("KD ")) {











                    dispatchGuestInput("cmd input keyevent " + cmd.substring(3));











                } else {











                    dispatchGuestInput("cmd input " + cmd);











                }











            }











        }







        private static void dispatchGuestInput(String shellCmd) {



            if (shellCmd == null || shellCmd.isEmpty()) return;



            CompletableFuture.runAsync(() -> {



                synchronized (inputShellLock) {



                    try {



                        if (inputShellProc == null || !inputShellProc.isAlive()) {



                            String adbExe = sdkDir + "\\platform-tools\\adb.exe";



                            ProcessBuilder pb = new ProcessBuilder(adbExe, "-s", emulatorSerial, "shell");



                            pb.redirectErrorStream(true);



                            inputShellProc = pb.start();



                            final Process proc = inputShellProc;



                            Thread drain = new Thread(() -> {



                                try (InputStream is = proc.getInputStream()) {



                                    byte[] buf = new byte[512];



                                    while (is.read(buf) != -1) {}



                                } catch (Exception ignored) {}



                            }, "InputShellDrain");



                            drain.setDaemon(true);



                            drain.start();



                        }



                        OutputStream os = inputShellProc.getOutputStream();



                        os.write((shellCmd + " >/dev/null 2>&1 &\r\n").getBytes(StandardCharsets.UTF_8));



                        os.flush();



                    } catch (Exception e) {



                        inputShellProc = null;



                    }



                }



            });



        }







        private static volatile int lastDispatchedKey = -1;



        private static volatile long lastDispatchedTime = 0;







        private static void handleKeyEvent(KeyboardEvent req) {



            if (req == null) return;



            int evType = req.getEventTypeValue(); // 0=keydown, 1=keyup, 2=keypress



            String rawKey = req.getKey() != null ? req.getKey() : "";
            String key = rawKey.trim();



            String text = req.getText() != null ? req.getText() : "";



            int keyCode = req.getKeyCode();
            // Studio sends the space bar as key " " or text " ". Trimming made the key empty, and
            // the guest daemon trims each line, so "TEXT  " lost its space too: send KEYCODE_SPACE.
            if (key.isEmpty() && (!rawKey.isEmpty() || " ".equals(text))) key = " ";







            int androidKey = -1;



            switch (key) {



                case "GoBack":



                case "Back":



                case "BrowserBack":



                case "Escape":



                    androidKey = 4; // KEYCODE_BACK



                    break;



                case "GoHome":



                case "Home":



                case "BrowserHome":



                    androidKey = 3; // KEYCODE_HOME



                    break;



                case "AppSwitch":



                case "Overview":



                    androidKey = 187; // KEYCODE_APP_SWITCH



                    break;



                case "AudioVolumeUp":



                case "VolumeUp":



                    androidKey = 24; // KEYCODE_VOLUME_UP



                    break;



                case "AudioVolumeDown":



                case "VolumeDown":



                    androidKey = 25; // KEYCODE_VOLUME_DOWN



                    break;



                case "AudioVolumeMute":



                case "VolumeMute":



                    androidKey = 164; // KEYCODE_VOLUME_MUTE



                    break;



                case "Power":



                case "WakeUp":



                case "Sleep":



                case "Standby":



                    androidKey = 26; // KEYCODE_POWER



                    break;



                case "Enter":



                case "NumpadEnter":



                    androidKey = 66; // KEYCODE_ENTER



                    break;



                case "Backspace":



                    androidKey = 67; // KEYCODE_DEL



                    break;



                case "Delete":



                    androidKey = 112; // KEYCODE_FORWARD_DEL



                    break;



                case "Tab":



                    androidKey = 61; // KEYCODE_TAB



                    break;



                case "ArrowUp":



                    androidKey = 19; // KEYCODE_DPAD_UP



                    break;



                case "ArrowDown":



                    androidKey = 20; // KEYCODE_DPAD_DOWN



                    break;



                case "ArrowLeft":



                    androidKey = 21; // KEYCODE_DPAD_LEFT



                    break;



                case "ArrowRight":



                    androidKey = 22; // KEYCODE_DPAD_RIGHT



                    break;



                case "PageUp":



                    androidKey = 92; // KEYCODE_PAGE_UP



                    break;



                case "PageDown":



                    androidKey = 93; // KEYCODE_PAGE_DOWN



                    break;



                case " ":



                case "Space":



                    androidKey = 62; // KEYCODE_SPACE



                    break;



                case "Camera":



                    androidKey = 27; // KEYCODE_CAMERA



                    break;



                                default:
                    if (key.length() == 1) {
                        char c = key.charAt(0);
                        if (c >= 'a' && c <= 'z') androidKey = 29 + (c - 'a');
                        else if (c >= 'A' && c <= 'Z') androidKey = 29 + (c - 'A');
                        else if (c >= '0' && c <= '9') androidKey = 7 + (c - '0');
                        else if (c == ' ') androidKey = 62;
                        else if (c == ',') androidKey = 55;
                        else if (c == '.') androidKey = 56;
                        else if (c == '-') androidKey = 69;
                        else if (c == '=') androidKey = 70;
                        else if (c == '[') androidKey = 71;
                        else if (c == ']') androidKey = 72;
                        else if (c == '\\') androidKey = 73;
                        else if (c == ';') androidKey = 74;
                        else if (c == '\'') androidKey = 75;
                        else if (c == '/') androidKey = 76;
                        else if (c == '@') androidKey = 77;
                        else if (c == '+') androidKey = 81;
                    } else if (key.startsWith("Key") && key.length() == 4) {
                        char c = Character.toUpperCase(key.charAt(3));
                        if (c >= 'A' && c <= 'Z') androidKey = 29 + (c - 'A');
                    } else if (key.startsWith("Digit") && key.length() == 6) {
                        char c = key.charAt(5);
                        if (c >= '0' && c <= '9') androidKey = 7 + (c - '0');
                    } else if (keyCode > 0) {
                        androidKey = keyCode;
                    }
                    break;



            }







            if (androidKey > 0) {



                long now = System.currentTimeMillis();



                if (evType == 0) { // keydown



                    lastDispatchedKey = androidKey;



                    lastDispatchedTime = now;



                    System.out.println("[WhpxEmulatorBridge] KD: " + androidKey + " (raw key=" + key + ")");



                    sendDirectInput("KD " + androidKey);



                } else if (evType == 1) { // keyup



                    System.out.println("[WhpxEmulatorBridge] KU: " + androidKey + " (raw key=" + key + ")");



                    sendDirectInput("KU " + androidKey);















                } else if (evType == 2) { // keypress



                    if (androidKey == lastDispatchedKey && (now - lastDispatchedTime) < 600) {



                        System.out.println("[WhpxEmulatorBridge] Ignored redundant keypress for key " + androidKey);



                        return;



                    }



                    lastDispatchedKey = androidKey;



                    lastDispatchedTime = now;



                    System.out.println("[WhpxEmulatorBridge] K: " + androidKey + " (raw key=" + key + ")");



                    sendDirectInput("K " + androidKey);















                }



            } else {



                String toType = !text.isEmpty() ? text : (key.length() == 1 ? key : "");



                if (!toType.isEmpty()) {



                    sendDirectInput("TEXT " + toType.replace("\r\n", "").replace("\r", ""));



                }



            }



        }







        /**
         * Studio sends positions in the display's natural (portrait) orientation: it turns the
         * point back by the image's rotation (its "orientation quadrants"), as Google's emulator
         * expects. The guest takes them in its current orientation, so turn them forward again.
         * Folded, they are relative to the cover screen.
         */
        static int[] toCurrentOrientation(int x, int y, int rot) {
            int[] fold = WhpxFold.overrideSize();
            int w = fold != null ? fold[0] : lcdWidth, h = fold != null ? fold[1] : lcdHeight;
            switch (rot & 3) {
                case 1: return new int[] { y, w - x };
                case 2: return new int[] { w - x, h - y };
                case 3: return new int[] { h - y, x };
                default: return new int[] { x, y };
            }
        }

        private static void handleTouchEvent(TouchEvent req) {











            if (req == null) return;











            if (req.getTouchesCount() == 0) {











                if (touchDown) {











                    touchDown = false;











                    sendDirectInput("T 1 " + touchLastX + " " + touchLastY);











                }











                return;











            }











            int curRot = currentRotation;

            int maxW = (curRot % 2 == 0) ? lcdWidth : lcdHeight;

            int maxH = (curRot % 2 == 0) ? lcdHeight : lcdWidth;

            Touch t = req.getTouches(0);

            int[] cur = toCurrentOrientation(t.getX(), t.getY(), curRot);
            int px = Math.max(0, Math.min(maxW - 1, cur[0]));

            int py = Math.max(0, Math.min(maxH - 1, cur[1]));

            int x = px;

            int y = py;











            int pressure = t.getPressure();











            if (!touchDown) {











                touchDown = true;











                touchLastX = x;











                touchLastY = y;











                sendDirectInput("T 0 " + x + " " + y);











            } else {











                if (pressure == 0 && req.getTouchesCount() == 1) {











                    touchDown = false;











                    sendDirectInput("T 1 " + touchLastX + " " + touchLastY);











                } else if (x != touchLastX || y != touchLastY) {











                    touchLastX = x;











                    touchLastY = y;











                    sendDirectInput("T 2 " + x + " " + y);











                }











            }











        }















        static {
            WhpxDragSmoother.sender = (x, y) -> {
                mouseLastX = x;
                mouseLastY = y;
                sendDirectInput("T 2 " + x + " " + y);
            };
        }

        private static void handleMouseEvent(MouseEvent req) {



















            if (req == null) return;



















            int curRot = currentRotation;

            int maxW = (curRot % 2 == 0) ? lcdWidth : lcdHeight;

            int maxH = (curRot % 2 == 0) ? lcdHeight : lcdWidth;

            int[] cur = toCurrentOrientation(req.getX(), req.getY(), curRot);
            int px = Math.max(0, Math.min(maxW - 1, cur[0]));

            int py = Math.max(0, Math.min(maxH - 1, cur[1]));

            int x = px;

            int y = py;



















            pointerX = x;
            pointerY = y;
            boolean isDown = (req.getButtons() & 1) != 0;
            // WhpxDragSmoother may send the moves of a drag itself (from the Windows cursor), so
            // all of this runs under its lock, as its poller does.
            synchronized (WhpxDragSmoother.LOCK) {
                if (isDown) {
                    if (!mouseDown) {
                        mouseDown = true;
                        mouseLastX = x;
                        mouseLastY = y;
                        sendDirectInput("T 0 " + x + " " + y);
                        // Folded, Studio's coordinates span the cover screen, and Studio draws it at
                        // another scale: the smoother must not reuse what it learned for the open panel.
                        int[] foldArea = WhpxFold.overrideSize() != null ? overrideArea(curRot) : null;
                        WhpxDragSmoother.onDown(x, y, foldArea != null ? foldArea[2] : maxW, foldArea != null ? foldArea[3] : maxH, curRot);
                    } else if (!WhpxDragSmoother.onMove(x, y) && (x != mouseLastX || y != mouseLastY)) {
                        mouseLastX = x;
                        mouseLastY = y;
                        sendDirectInput("T 2 " + x + " " + y);
                    }
                } else if (mouseDown) {
                    mouseDown = false;
                    WhpxDragSmoother.onUp(x, y);
                    sendDirectInput("T 1 " + mouseLastX + " " + mouseLastY);
                }
            }



















        }























        // Last pointer position from Studio, including hover, so wheel scrolls land under the cursor.
        private static volatile int pointerX = -1;
        private static volatile int pointerY = -1;

        private static void handleWheelEvent(WheelEvent req) {



            if (req == null) return;



            int dx = req.getDx();
            int dy = req.getDy();
            if (dx == 0 && dy == 0) return;
            // Studio scales 120 to one wheel notch; touchpads send fractions of that.
            // Android's AXIS_VSCROLL is positive for wheel-away (scroll up) like dy, and
            // AXIS_HSCROLL is positive for scrolling right, which is the opposite of dx.
            int x = pointerX >= 0 ? pointerX : lcdWidth / 2;
            int y = pointerY >= 0 ? pointerY : lcdHeight / 2;
            sendDirectInput("W " + x + " " + y + " " + (dy / 120f) + " " + (-dx / 120f));



        }







        @Override



        public void sendKey(KeyboardEvent req, StreamObserver<Empty> resp) {



            handleKeyEvent(req);



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void sendTouch(TouchEvent req, StreamObserver<Empty> resp) {



            handleTouchEvent(req);



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void sendMouse(MouseEvent req, StreamObserver<Empty> resp) {



            handleMouseEvent(req);



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public StreamObserver<InputEvent> streamInputEvent(StreamObserver<Empty> resp) {



            return new StreamObserver<InputEvent>() {



                @Override



                public void onNext(InputEvent value) {



                    if (value == null) return;



                    if (value.hasKeyEvent()) handleKeyEvent(value.getKeyEvent());



                    if (value.hasTouchEvent()) handleTouchEvent(value.getTouchEvent());



                    if (value.hasMouseEvent()) handleMouseEvent(value.getMouseEvent());



                    if (value.hasWheelEvent()) handleWheelEvent(value.getWheelEvent());



                }



                @Override public void onError(Throwable t) {}



                @Override public void onCompleted() {



                    resp.onNext(Empty.getDefaultInstance());



                    resp.onCompleted();



                }



            };



        }







        @Override



        public StreamObserver<WheelEvent> injectWheel(StreamObserver<Empty> resp) {



            return new StreamObserver<WheelEvent>() {



                @Override public void onNext(WheelEvent value) { handleWheelEvent(value); }



                @Override public void onError(Throwable t) {}



                @Override public void onCompleted() {



                    resp.onNext(Empty.getDefaultInstance());



                    resp.onCompleted();



                }



            };



        }







        private static final java.util.List<StreamObserver<PhysicalModelValue>> physicalModelObservers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private static final java.util.List<StreamObserver<PhysicalModelValue>> hingeObservers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private static final java.util.List<StreamObserver<Notification>> notificationObservers = new java.util.concurrent.CopyOnWriteArrayList<>();

        private static PhysicalModelValue hingeValue() {
            return PhysicalModelValue.newBuilder()
                .setTarget(PhysicalModelValue.PhysicalType.HINGE_ANGLE0)
                .setStatus(PhysicalModelValue.State.OK)
                .setValue(ParameterValue.newBuilder().addData(WhpxFold.angle()).build())
                .build();
        }

        /** Studio folded or unfolded the device: tell Android, then Studio's posture menu. */
        private static void onHingeAngle(float angle) {
            if (!WhpxFold.foldable()) return;
            boolean changed = WhpxFold.setAngle(angle);
            System.out.println("[WhpxFold] hinge angle " + angle + " -> posture " + WhpxFold.posture() + (changed ? "" : " (unchanged)"));
            PhysicalModelValue v = hingeValue();
            for (StreamObserver<PhysicalModelValue> obs : hingeObservers) {
                try { obs.onNext(v); } catch (Throwable t) { hingeObservers.remove(obs); }
            }
            if (!changed) return;
            String cmds = WhpxFold.guestCommands();
            if (cmds != null) dispatchGuestInput(cmds);
            notifyPosture();
        }

        static void notifyPosture() {
            Notification n = Notification.newBuilder()
                .setPosture(Posture.newBuilder().setValueValue(WhpxFold.posture()).build()).build();
            for (StreamObserver<Notification> obs : notificationObservers) {
                try { obs.onNext(n); } catch (Throwable t) { notificationObservers.remove(obs); }
            }
        }



        @Override

        public void setPhysicalModel(PhysicalModelValue req, StreamObserver<Empty> resp) {

            if (req != null && req.getTarget() == PhysicalModelValue.PhysicalType.HINGE_ANGLE0 && req.getValue().getDataCount() >= 1) {
                onHingeAngle(req.getValue().getData(0));
            }
            if (req != null && req.getTarget() == PhysicalModelValue.PhysicalType.ROTATION && req.getValue().getDataCount() >= 3) {

                float z = req.getValue().getData(2);

                currentZAngle = z;

                float normZ = (z % 360f + 360f) % 360f;

                int rot = 0;

                if (normZ >= 45f && normZ < 135f) {

                    rot = 1;

                } else if (normZ >= 135f && normZ < 225f) {

                    rot = 2;

                } else if (normZ >= 225f && normZ < 315f) {

                    rot = 3;

                } else {

                    rot = 0;

                }

                currentRotation = rot;

                cachedEncodedSeq = -1;

                dispatchGuestInput("cmd window fixed-to-user-rotation enabled\ncmd window user-rotation lock " + rot + "\nsu 0 service call SurfaceFlinger 1008 i32 1");

                System.out.println("[WhpxEmulatorBridge] setPhysicalModel ROTATION: z=" + z + " (norm=" + normZ + ") -> rot=" + rot);



                PhysicalModelValue updated = PhysicalModelValue.newBuilder()

                    .setTarget(PhysicalModelValue.PhysicalType.ROTATION)

                    .setStatus(PhysicalModelValue.State.OK)

                    .setValue(ParameterValue.newBuilder().addData(0f).addData(0f).addData(currentZAngle).build())

                    .build();

                for (StreamObserver<PhysicalModelValue> obs : physicalModelObservers) {

                    try {

                        obs.onNext(updated);

                    } catch (Throwable t) {

                        physicalModelObservers.remove(obs);

                    }

                }

            }

            resp.onNext(Empty.getDefaultInstance());

            resp.onCompleted();

        }



        @Override

        public void getPhysicalModel(PhysicalModelValue req, StreamObserver<PhysicalModelValue> resp) {
            if (req != null && req.getTarget() == PhysicalModelValue.PhysicalType.HINGE_ANGLE0) {
                resp.onNext(hingeValue());
                resp.onCompleted();
                return;
            }

            resp.onNext(PhysicalModelValue.newBuilder()

                .setTarget(req != null ? req.getTarget() : PhysicalModelValue.PhysicalType.ROTATION)

                .setStatus(PhysicalModelValue.State.OK)

                .setValue(ParameterValue.newBuilder().addData(0f).addData(0f).addData(currentZAngle).build())

                .build());

            resp.onCompleted();

        }



        @Override

        public void streamPhysicalModel(PhysicalModelValue req, StreamObserver<PhysicalModelValue> resp) {
            if (req != null && req.getTarget() == PhysicalModelValue.PhysicalType.HINGE_ANGLE0) {
                try {
                    resp.onNext(hingeValue());
                    hingeObservers.add(resp);
                } catch (Throwable ignored) {}
                return;
            }

            try {

                resp.onNext(PhysicalModelValue.newBuilder()

                    .setTarget(req != null ? req.getTarget() : PhysicalModelValue.PhysicalType.ROTATION)

                    .setStatus(PhysicalModelValue.State.OK)

                    .setValue(ParameterValue.newBuilder().addData(0f).addData(0f).addData(currentZAngle).build())

                    .build());

                physicalModelObservers.add(resp);

            } catch (Throwable ignored) {}

        }







        @Override



        public void setEnvironment(com.android.emulator.control.Environment req, StreamObserver<Empty> resp) {



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void getXrOptions(Empty req, StreamObserver<XrOptions> resp) {



            resp.onNext(XrOptions.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void setXrOptions(XrOptions req, StreamObserver<Empty> resp) {



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void getDisplayMode(Empty req, StreamObserver<DisplayMode> resp) {



            resp.onNext(DisplayMode.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void setDisplayMode(DisplayMode req, StreamObserver<Empty> resp) {



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void getBrightness(BrightnessValue req, StreamObserver<BrightnessValue> resp) {



            resp.onNext(BrightnessValue.newBuilder().setTarget(req.getTarget()).setValue(255).build());



            resp.onCompleted();



        }







        @Override



        public void setBrightness(BrightnessValue req, StreamObserver<Empty> resp) {



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void setPosture(Posture req, StreamObserver<Empty> resp) {



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }



    }







    private static class SnapshotServiceImpl extends SnapshotServiceGrpc.SnapshotServiceImplBase {



        @Override



        public void listSnapshots(SnapshotFilter req, StreamObserver<SnapshotList> resp) {



            resp.onNext(SnapshotList.getDefaultInstance());



            resp.onCompleted();



        }



    }







    private static final EmulatorControllerServiceImpl controllerService = new EmulatorControllerServiceImpl();
    private static final WhpxStandaloneWindow.Host standaloneHost = new WhpxStandaloneWindow.Host() {
        @Override public EmulatorControllerGrpc.EmulatorControllerImplBase controller() { return controllerService; }
        @Override public int displayWidth() { return lcdWidth; }
        @Override public int displayHeight() { return lcdHeight; }
        @Override public boolean round() { return isWear && lcdWidth == lcdHeight; }
        @Override public java.io.File skinDir() { return skinPath == null || skinPath.isBlank() ? null : new java.io.File(skinPath.trim()); }
        @Override public void rotate(int quarterTurns) {
            float z = currentZAngle + 90f * quarterTurns;
            controllerService.setPhysicalModel(PhysicalModelValue.newBuilder()
                    .setTarget(PhysicalModelValue.PhysicalType.ROTATION)
                    .setValue(ParameterValue.newBuilder().addData(0f).addData(0f).addData(z).build())
                    .build(), new StreamObserver<Empty>() {
                        @Override public void onNext(Empty v) {}
                        @Override public void onError(Throwable t) {}
                        @Override public void onCompleted() {}
                    });
        }
        @Override public void showExtendedControls(int x, int y) { extendedControls.show(0, x, y, true); }
        @Override public void shutdown() {
            System.out.println("[WhpxEmulatorBridge] Emulator window closed: shutting down");
            if (whpxProc != null) whpxProc.destroyForcibly();
            System.exit(0);
        }
        @Override public String title() { return "Android Emulator - " + avdId + ":" + consolePort; }
    };
    private static final WhpxExtendedControls extendedControls = new WhpxExtendedControls(new WhpxExtendedControls.Host() {
        @Override public void setLocation(double lat, double lon, double alt) { applyGps(lat, lon, alt); }
        @Override public double[] location() { return new double[] { gpsLat, gpsLon, gpsAlt }; }
        @Override public void key(int keyCode) { EmulatorControllerServiceImpl.sendDirectInput("K " + keyCode); }
        @Override public String adbPath() { return sdkDir + "\\platform-tools\\adb.exe"; }
        @Override public String serial() { return emulatorSerial; }
        @Override public String avdName() { return avdId; }
        @Override public void shell(String command) {
            Thread t = new Thread(() -> {
                try {
                    new ProcessBuilder(adbPath(), "-s", emulatorSerial, "shell", command)
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
                } catch (Exception ignored) {}
            }, "ExtendedControlsShell");
            t.setDaemon(true);
            t.start();
        }
    });

    private static class UiControllerServiceImpl extends UiControllerGrpc.UiControllerImplBase {



        @Override



        public void showExtendedControls(PaneEntry req, StreamObserver<ExtendedControlsStatus> resp) {
            boolean changed = extendedControls.show(req.getIndexValue(), req.getPosition().getX(), req.getPosition().getY(), req.hasPosition());
            resp.onNext(ExtendedControlsStatus.newBuilder().setVisibilityChanged(changed).build());



            resp.onCompleted();



        }







        @Override



        public void closeExtendedControls(Empty req, StreamObserver<ExtendedControlsStatus> resp) {
            resp.onNext(ExtendedControlsStatus.newBuilder().setVisibilityChanged(extendedControls.close()).build());



            resp.onCompleted();



        }







        @Override



        public void setUiTheme(ThemingStyle req, StreamObserver<Empty> resp) {



            extendedControls.setDark(req.getStyle() != ThemingStyle.Style.LIGHT);



            resp.onNext(Empty.getDefaultInstance());



            resp.onCompleted();



        }







        @Override



        public void getUserConfig(Empty req, StreamObserver<UserConfig> resp) {



            resp.onNext(UserConfig.getDefaultInstance());



            resp.onCompleted();



        }



    }



}



