import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

public class WhpxInputDaemon {
    private static Object im;
    private static Method inject;
    private static Method uptimeMillis;
    private static Method obtainMotion;
    private static Method setSource;
    private static Constructor<?> keyCtor;
    private static Object kcm;
    private static Method getEvents;

    private static final int SOURCE_TOUCHSCREEN = 0x00001002;
    private static final int SOURCE_KEYBOARD = 0x00000101;
    private static final int INJECT_MODE_ASYNC = 0;

    private static volatile long downTime = 0L;
    private static final ConcurrentHashMap<Integer, Long> keyTimes = new ConcurrentHashMap<>();

    private static synchronized void refreshInputManager() {
        try {
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");
            im = imCls.getMethod("getInstance").invoke(null);
            Class<?> inputEventCls = Class.forName("android.view.InputEvent");
            inject = imCls.getMethod("injectInputEvent", inputEventCls, int.class);
            System.out.println("[DAEMON] InputManager acquired successfully.");
            return;
        } catch (Throwable ignored) {}
        try {
            Class<?> imgCls = Class.forName("android.hardware.input.InputManagerGlobal");
            im = imgCls.getMethod("getInstance").invoke(null);
            Class<?> inputEventCls = Class.forName("android.view.InputEvent");
            inject = imgCls.getMethod("injectInputEvent", inputEventCls, int.class);
            System.out.println("[DAEMON] InputManagerGlobal acquired successfully.");
        } catch (Throwable e) {
            System.err.println("[DAEMON] refreshInputManager failed: " + e);
        }
    }

    private static int injectFailures = 0;

    private static void injectSafely(Object ev, int mode) {
        if (ev == null) return;
        if (im == null || inject == null) {
            refreshInputManager();
        }
        if (im != null && inject != null) {
            try {
                inject.invoke(im, ev, mode);
                injectFailures = 0;
            } catch (Throwable t) {
                System.err.println("[DAEMON] inject failed: " + t.getMessage());
                // After a framework restart this process holds a dead binder that
                // getInstance() keeps returning, so every control stays dead. Exit;
                // run_input_daemon.sh starts a fresh process with a live one.
                if (++injectFailures >= 3) System.exit(1);
            }
        }
    }

    private static void injectKeyDirect(int keyCode, boolean shift) {
        try {
            long now = (Long) uptimeMillis.invoke(null);
            int meta = shift ? 1 : 0;
            Object kd = keyCtor.newInstance(now, now, 0, keyCode, 0, meta, -1, 0, 8, SOURCE_KEYBOARD);
            injectSafely(kd, INJECT_MODE_ASYNC);
            long nowUp = Math.max((Long) uptimeMillis.invoke(null), now + 20);
            Object ku = keyCtor.newInstance(now, nowUp, 1, keyCode, 0, meta, -1, 0, 8, SOURCE_KEYBOARD);
            injectSafely(ku, INJECT_MODE_ASYNC);
        } catch (Throwable ignored) {}
    }

    private static void injectTextDirect(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') injectKeyDirect(29 + (c - 'a'), false);
            else if (c >= 'A' && c <= 'Z') injectKeyDirect(29 + (c - 'A'), true);
            else if (c >= '0' && c <= '9') injectKeyDirect(7 + (c - '0'), false);
            else if (c == ' ') injectKeyDirect(62, false);
            else if (c == '\n' || c == '\r') injectKeyDirect(66, false);
            else if (c == '\t') injectKeyDirect(61, false);
            else if (c == '\b') injectKeyDirect(67, false);
            else if (c == '.') injectKeyDirect(56, false);
            else if (c == ',') injectKeyDirect(55, false);
            else if (c == '-') injectKeyDirect(69, false);
            else if (c == '_') injectKeyDirect(69, true);
            else if (c == '=') injectKeyDirect(70, false);
            else if (c == '+') injectKeyDirect(70, true);
            else if (c == '/') injectKeyDirect(76, false);
            else if (c == '?') injectKeyDirect(76, true);
            else if (c == '@') injectKeyDirect(77, false);
            else if (c == ':') injectKeyDirect(74, true);
            else if (c == ';') injectKeyDirect(74, false);
            else {
                if (kcm != null && getEvents != null) {
                    try {
                        Object[] events = (Object[]) getEvents.invoke(kcm, new Object[]{ new char[]{ c } });
                        if (events != null) {
                            for (Object ev : events) injectSafely(ev, INJECT_MODE_ASYNC);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            Class<?> inputEventCls = Class.forName("android.view.InputEvent");
            Class<?> motionEventCls = Class.forName("android.view.MotionEvent");
            Class<?> keyEventCls = Class.forName("android.view.KeyEvent");
            Class<?> kcmCls = Class.forName("android.view.KeyCharacterMap");
            Class<?> systemClockCls = Class.forName("android.os.SystemClock");

            refreshInputManager();

            uptimeMillis = systemClockCls.getMethod("uptimeMillis");
            obtainMotion = motionEventCls.getMethod("obtain",
                    long.class, long.class, int.class, float.class, float.class, float.class, float.class,
                    int.class, float.class, float.class, int.class, int.class);
            setSource = inputEventCls.getMethod("setSource", int.class);
            keyCtor = keyEventCls.getConstructor(
                    long.class, long.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);

            try {
                kcm = kcmCls.getMethod("load", int.class).invoke(null, 0);
            } catch (Throwable ignored) {}
            if (kcm == null) {
                try {
                    kcm = kcmCls.getMethod("load", int.class).invoke(null, -1);
                } catch (Throwable ignored) {}
            }
            if (kcm != null) {
                try {
                    getEvents = kcmCls.getMethod("getEvents", char[].class);
                } catch (Throwable ignored) {}
            }

            System.out.println("WhpxInputDaemon initialized successfully!");

            // Thread 1: Read System.in as fallback
            Thread stdinThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                        String line;
                        while ((line = br.readLine()) != null) {
                            processCommand(line);
                        }
                    } catch (Throwable ignored) {}
                }
            }, "InputDaemonStdin");
            stdinThread.setDaemon(true);
            stdinThread.start();

            // Thread 2: Resilient High-Speed TCP Server on port 10555
            ServerSocket ss = null;
            while (true) {
                try {
                    if (ss == null || ss.isClosed()) {
                        ss = new ServerSocket();
                        ss.setReuseAddress(true);
                        ss.bind(new java.net.InetSocketAddress(10555));
                        System.out.println("WhpxInputDaemon listening on port 10555");
                    }
                    final Socket client = ss.accept();
                    client.setTcpNoDelay(true);
                    Thread clientThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    processCommand(line);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }, "InputDaemonClient");
                    clientThread.start();
                } catch (Throwable t) {
                    t.printStackTrace();
                    try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                    ss = null;
                    try { Thread.sleep(1000); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static synchronized void processCommand(String line) {
        if (line == null) return;
        line = line.trim();
        if (line.isEmpty()) return;
        try {
            String[] parts = line.split("\\s+");
            String cmd = parts[0];
            long now = (Long) uptimeMillis.invoke(null);

            if ("T".equals(cmd) && parts.length >= 4) {
                int action = Integer.parseInt(parts[1]); // 0=DOWN, 1=UP, 2=MOVE
                float x = Float.parseFloat(parts[2]);
                float y = Float.parseFloat(parts[3]);
                if (action == 0 || downTime == 0L) {
                    downTime = now;
                }
                long eventTime = now;
                if (action == 1) {
                    eventTime = Math.max(now, downTime + 35);
                }
                float pressure = (action == 1) ? 0.0f : 1.0f;
                Object ev = obtainMotion.invoke(null, downTime, eventTime, action, x, y, pressure, 1.0f, 0, 1.0f, 1.0f, 0, 0);
                setSource.invoke(ev, SOURCE_TOUCHSCREEN);
                injectSafely(ev, INJECT_MODE_ASYNC);
                if (action == 1) {
                    downTime = 0L;
                }
            } else if ("K".equals(cmd) && parts.length >= 2) {
                int keyCode = Integer.parseInt(parts[1]);
                int flags = 8;
                Object kd = keyCtor.newInstance(now, now, 0, keyCode, 0, 0, -1, 0, flags, SOURCE_KEYBOARD);
                injectSafely(kd, INJECT_MODE_ASYNC);
                try { Thread.sleep(25); } catch (Exception ignored) {}
                long nowUp = Math.max((Long) uptimeMillis.invoke(null), now + 30);
                Object ku = keyCtor.newInstance(now, nowUp, 1, keyCode, 0, 0, -1, 0, flags, SOURCE_KEYBOARD);
                injectSafely(ku, INJECT_MODE_ASYNC);
            } else if ("KD".equals(cmd) && parts.length >= 2) {
                int keyCode = Integer.parseInt(parts[1]);
                int flags = 8;
                long dt = now;
                keyTimes.put(keyCode, dt);
                Object kd = keyCtor.newInstance(dt, now, 0, keyCode, 0, 0, -1, 0, flags, SOURCE_KEYBOARD);
                injectSafely(kd, INJECT_MODE_ASYNC);
            } else if ("KU".equals(cmd) && parts.length >= 2) {
                int keyCode = Integer.parseInt(parts[1]);
                int flags = 8;
                Long dtObj = keyTimes.remove(keyCode);
                long dt = (dtObj != null) ? dtObj : (now - 35);
                long eventTime = Math.max(now, dt + 30);
                Object ku = keyCtor.newInstance(dt, eventTime, 1, keyCode, 0, 0, -1, 0, flags, SOURCE_KEYBOARD);
                injectSafely(ku, INJECT_MODE_ASYNC);
            } else if ("TEXT".equals(cmd) && line.length() > 5) {
                String text = line.substring(5);
                injectTextDirect(text);
            }
        } catch (Throwable t) {
            System.err.println("[DAEMON] Error processing: " + line + ": " + t);
        }
    }
}
