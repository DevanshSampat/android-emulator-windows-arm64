import com.android.emulator.control.EmulatorControllerGrpc;
import com.android.emulator.control.Image;
import com.android.emulator.control.ImageFormat;
import com.android.emulator.control.InputEvent;
import com.android.emulator.control.KeyboardEvent;
import com.android.emulator.control.MouseEvent;
import com.android.emulator.control.WheelEvent;
import com.android.tools.idea.io.grpc.stub.StreamObserver;
import com.android.tools.idea.protobuf.Empty;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * The emulator's own window, for devices started outside Android Studio (Flutter, an IDE, or
 * the command line). Studio always passes -qt-hide-window and shows the device in its Running
 * Devices panel; without that flag nothing would be on screen.
 *
 * <p>It is an in-process client of the bridge's gRPC controller: frames come from
 * streamScreenshot at the window's size, and input goes through streamInputEvent as Studio's
 * does, so scaling, rotation and key handling are the same as in Studio.
 * WHPX_WINDOW=0 turns it off; WHPX_WINDOW=1 opens it even when Studio hides the window.
 */
final class WhpxStandaloneWindow {
    interface Host {
        EmulatorControllerGrpc.EmulatorControllerImplBase controller();
        /** Display size in portrait (unrotated) pixels. */
        int displayWidth();
        int displayHeight();
        boolean round();
        /** The AVD's device frame skin folder, or null when the frame is off or there is none. */
        java.io.File skinDir();
        /** Turns the device by quarter turns, positive is counterclockwise. */
        void rotate(int quarterTurns);
        void showExtendedControls(int screenX, int screenY);
        void shutdown();
        String title();
    }

    private static final Color BG = new Color(0x20, 0x21, 0x24);
    private static final Color BAR = new Color(0x2B, 0x2D, 0x30);
    private static final Color FG = new Color(0xE8, 0xEA, 0xED);
    private static final int BAR_W = 52;
    private static final int BUTTON_H = 34;

    private final Host host;
    private JFrame frame;
    private DevicePanel panel;
    private volatile FrameStream stream;
    private Timer restartTimer;
    private int lastRotation = -1;
    private StreamObserver<InputEvent> input;
    private JPanel buttons;
    private WhpxSkin skin;

    private WhpxStandaloneWindow(Host host) {
        this.host = host;
    }

    static void open(Host host) {
        WhpxStandaloneWindow w = new WhpxStandaloneWindow(host);
        SwingUtilities.invokeLater(() -> {
            try {
                w.build();
            } catch (Throwable t) {
                System.out.println("[WhpxWindow] could not open: " + t);
            }
        });
    }

    // ---- frames ----

    /** One screenshot stream at a fixed size. The bridge's streamer ends when onNext throws. */
    private final class FrameStream implements StreamObserver<Image> {
        volatile boolean closed;
        private BufferedImage[] bufs = new BufferedImage[2];
        private int next;
        private byte[] tmp = new byte[0];

        @Override
        public void onNext(Image img) {
            if (closed) throw new IllegalStateException("window stream closed");
            int w = img.getWidth(), h = img.getHeight();
            int size = img.getImage().size();
            int bpp = (w > 0 && h > 0) ? size / (w * h) : 0;
            if (bpp < 3) return;
            if (tmp.length < size) tmp = new byte[size];
            byte[] src = tmp;
            img.getImage().copyTo(src, 0);
            BufferedImage dst = bufs[next];
            if (dst == null || dst.getWidth() != w || dst.getHeight() != h) {
                dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                bufs[next] = dst;
            }
            int[] px = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
            int n = w * h;
            for (int i = 0, o = 0; i < n; i++, o += bpp) {
                px[i] = ((src[o] & 0xFF) << 16) | ((src[o + 1] & 0xFF) << 8) | (src[o + 2] & 0xFF);
            }
            next ^= 1;
            int rot = img.getFormat().getRotation().getRotationValue();
            panel.show(dst, rot);
        }

        @Override public void onError(Throwable t) {}
        @Override public void onCompleted() {}
    }

    private void restartStream() {
        FrameStream old = stream;
        if (old != null) old.closed = true;
        double s = scale();
        int w = Math.max(64, (int) Math.round(panel.getWidth() * s));
        int h = Math.max(64, (int) Math.round(panel.getHeight() * s));
        FrameStream fs = new FrameStream();
        stream = fs;
        host.controller().streamScreenshot(ImageFormat.newBuilder()
                .setFormat(ImageFormat.ImgFormat.RGBA8888).setWidth(w).setHeight(h).build(), fs);
    }

    private double scale() {
        GraphicsConfiguration gc = panel.getGraphicsConfiguration();
        return gc != null ? gc.getDefaultTransform().getScaleX() : 1.0;
    }

    // ---- window ----

    private void build() {
        frame = new JFrame(host.title());
        frame.setIconImages(icons());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                FrameStream s = stream;
                if (s != null) s.closed = true;
                frame.setVisible(false);
                Thread t = new Thread(host::shutdown, "WhpxWindowShutdown");
                t.start();
            }
        });
        panel = new DevicePanel();
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(panel, BorderLayout.CENTER);
        root.add(toolbar(), BorderLayout.EAST);
        frame.setContentPane(root);

        skin = WhpxSkin.load(host.skinDir());
        // Size the device (with its frame) to about 80% of the screen height, keeping its aspect ratio.
        double aw = skin != null ? skin.width : host.displayWidth();
        double ah = skin != null ? skin.height : host.displayHeight();
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int ph = (int) (usable.height * 0.8);
        int pw = (int) Math.round(ph * aw / ah);
        if (pw > usable.width * 0.8) {
            pw = (int) (usable.width * 0.8);
            ph = (int) Math.round(pw * ah / aw);
        }
        panel.setPreferredSize(new Dimension(pw, ph));
        frame.pack();
        frame.setLocationRelativeTo(null);

        restartTimer = new Timer(150, e -> restartStream());
        restartTimer.setRepeats(false);
        panel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { restartTimer.restart(); }
        });
        frame.setVisible(true);
        panel.requestFocusInWindow();
        restartStream();
    }

    private JComponent toolbar() {
        // Buttons keep their height at the top of the bar; the rest of the bar stays empty.
        JPanel bar = new JPanel(new GridLayout(0, 1, 0, 0));
        buttons = bar;
        bar.setBackground(BAR);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BAR);
        outer.setPreferredSize(new Dimension(BAR_W, 10));
        outer.add(bar, BorderLayout.NORTH);
        bar.add(button("⏻", "Power", () -> key("Power")));
        bar.add(button("+", "Volume up", () -> key("AudioVolumeUp")));
        bar.add(button("−", "Volume down", () -> key("AudioVolumeDown")));
        bar.add(button("⟲", "Rotate left", () -> host.rotate(1)));
        bar.add(button("⟳", "Rotate right", () -> host.rotate(-1)));
        bar.add(button("◀", "Back", () -> key("GoBack")));
        bar.add(button("●", "Home", () -> key("GoHome")));
        bar.add(button("■", "Overview", () -> key("AppSwitch")));
        JButton more = button("⋯", "Extended controls", null);
        more.addActionListener(e -> {
            java.awt.Point p = more.getLocationOnScreen();
            host.showExtendedControls(p.x + more.getWidth() + 8, p.y);
        });
        bar.add(more);
        return outer;
    }

    /** A green rounded square with a white phone outline, so the taskbar doesn't show Java's icon. */
    private static java.util.List<java.awt.Image> icons() {
        java.util.List<java.awt.Image> list = new java.util.ArrayList<>();
        for (int n : new int[] { 16, 20, 24, 32, 48, 64, 128 }) {
            BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x3D, 0xDC, 0x84));
            g.fill(new RoundRectangle2D.Double(0, 0, n, n, n * 0.4, n * 0.4));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(Math.max(1.5f, n / 14f)));
            double w = n * 0.42, h = n * 0.68;
            g.draw(new RoundRectangle2D.Double((n - w) / 2, (n - h) / 2, w, h, n * 0.12, n * 0.12));
            double d = Math.max(1.5, n / 14.0);
            g.fill(new Ellipse2D.Double(n / 2.0 - d / 2, (n + h) / 2 - n * 0.11, d, d));
            g.dispose();
            list.add(img);
        }
        return list;
    }

    private JButton button(String text, String tip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
        b.setForeground(FG);
        b.setBackground(BAR);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setMargin(new Insets(2, 2, 2, 2));
        b.setPreferredSize(new Dimension(BAR_W - 12, BUTTON_H));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setContentAreaFilled(true); b.setBackground(BAR.brighter()); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { b.setContentAreaFilled(false); b.setBackground(BAR); }
        });
        if (action != null) b.addActionListener(e -> action.run());
        return b;
    }

    // ---- input ----

    private static final StreamObserver<Empty> IGNORE = new StreamObserver<Empty>() {
        @Override public void onNext(Empty v) {}
        @Override public void onError(Throwable t) {}
        @Override public void onCompleted() {}
    };

    private synchronized void send(InputEvent e) {
        if (input == null) input = host.controller().streamInputEvent(IGNORE);
        input.onNext(e);
    }

    private void sendKey(KeyboardEvent k) {
        send(InputEvent.newBuilder().setKeyEvent(k).build());
    }

    private void key(String name) {
        sendKey(KeyboardEvent.newBuilder().setEventType(KeyboardEvent.KeyEventType.keypress).setKey(name).build());
    }

    private void keyEvent(KeyboardEvent.KeyEventType type, String name) {
        sendKey(KeyboardEvent.newBuilder().setEventType(type).setKey(name).build());
    }

    private static String keyName(int vk) {
        switch (vk) {
            case KeyEvent.VK_ENTER: return "Enter";
            case KeyEvent.VK_BACK_SPACE: return "Backspace";
            case KeyEvent.VK_DELETE: return "Delete";
            case KeyEvent.VK_TAB: return "Tab";
            case KeyEvent.VK_ESCAPE: return "Escape";
            case KeyEvent.VK_UP: return "ArrowUp";
            case KeyEvent.VK_DOWN: return "ArrowDown";
            case KeyEvent.VK_LEFT: return "ArrowLeft";
            case KeyEvent.VK_RIGHT: return "ArrowRight";
            case KeyEvent.VK_PAGE_UP: return "PageUp";
            case KeyEvent.VK_PAGE_DOWN: return "PageDown";
            default: return null;
        }
    }

    /** Shows the newest frame and maps the pointer to device pixels. */
    private final class DevicePanel extends JComponent {
        private volatile BufferedImage image;
        private volatile int rotation;
        private boolean pressed;

        DevicePanel() {
            setOpaque(true);
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    requestFocusInWindow();
                    if (SwingUtilities.isLeftMouseButton(e) && screenRect().contains(e.getPoint())) { pressed = true; mouse(e, 1); }
                }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && pressed) { pressed = false; mouse(e, 0); }
                }
                @Override public void mouseDragged(java.awt.event.MouseEvent e) { if (pressed) mouse(e, 1); }
                @Override public void mouseMoved(java.awt.event.MouseEvent e) { if (screenRect().contains(e.getPoint())) mouse(e, 0); }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    mouse(e, pressed ? 1 : 0);
                    // Studio's scale: 120 per notch, positive dy scrolls up (wheel away).
                    int v = (int) Math.round(-e.getPreciseWheelRotation() * 120);
                    if (v == 0) return;
                    WheelEvent.Builder w = WheelEvent.newBuilder();
                    if (e.isShiftDown()) w.setDx(v); else w.setDy(v);
                    send(InputEvent.newBuilder().setWheelEvent(w.build()).build());
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    String n = keyName(e.getKeyCode());
                    if (n != null) keyEvent(KeyboardEvent.KeyEventType.keydown, n);
                }
                @Override public void keyReleased(KeyEvent e) {
                    String n = keyName(e.getKeyCode());
                    if (n != null) keyEvent(KeyboardEvent.KeyEventType.keyup, n);
                }
                @Override public void keyTyped(KeyEvent e) {
                    char c = e.getKeyChar();
                    if (c < 32 || c == 127 || c == KeyEvent.CHAR_UNDEFINED || e.isControlDown() || e.isAltDown()) return;
                    sendKey(KeyboardEvent.newBuilder()
                            .setEventType(KeyboardEvent.KeyEventType.keypress).setText(String.valueOf(c)).build());
                }
            });
        }

        void show(BufferedImage img, int rot) {
            image = img;
            rotation = rot;
            repaint();
            if (rot != lastRotation) {
                boolean turned = lastRotation >= 0 && (rot % 2) != (lastRotation % 2);
                lastRotation = rot;
                if (turned) SwingUtilities.invokeLater(this::fitWindowToRotation);
            }
        }

        /**
         * Swaps the device area's width and height when the device turns between portrait and
         * landscape, keeping it at least as tall as the toolbar and within the screen.
         */
        private void fitWindowToRotation() {
            Dimension d = getSize();
            double w = d.height, h = d.width;
            int minH = buttons.getPreferredSize().height;
            if (h < minH) { w *= minH / h; h = minH; }
            Rectangle usable = frame.getGraphicsConfiguration().getBounds();
            double maxW = usable.width * 0.95 - BAR_W;
            if (w > maxW) { h *= maxW / w; w = maxW; }
            setPreferredSize(new Dimension((int) Math.round(w), (int) Math.round(h)));
            frame.pack();
            Rectangle b = frame.getBounds();
            if (b.x + b.width > usable.x + usable.width) frame.setLocation(Math.max(usable.x, usable.x + usable.width - b.width), b.y);
        }

        /** Where the frame is drawn (the screen when there is no frame), in this component's coordinates. */
        private Rectangle2D.Double frameRect() {
            int rot = rotation;
            double fw = skin.width(rot), fh = skin.height(rot);
            double s = Math.min(getWidth() / fw, getHeight() / fh);
            return new Rectangle2D.Double((getWidth() - fw * s) / 2, (getHeight() - fh * s) / 2, fw * s, fh * s);
        }

        /** Where the device screen is drawn, in this component's coordinates. */
        private Rectangle screenRect() {
            int rot = rotation;
            if (skin != null) {
                Rectangle2D.Double f = frameRect();
                double s = f.width / skin.width(rot);
                Rectangle2D d = skin.display(rot);
                return new Rectangle((int) Math.round(f.x + d.getX() * s), (int) Math.round(f.y + d.getY() * s),
                        (int) Math.round(d.getWidth() * s), (int) Math.round(d.getHeight() * s));
            }
            double dw = (rot % 2 == 0) ? host.displayWidth() : host.displayHeight();
            double dh = (rot % 2 == 0) ? host.displayHeight() : host.displayWidth();
            double s = Math.min(getWidth() / dw, getHeight() / dh);
            int w = (int) Math.round(dw * s), h = (int) Math.round(dh * s);
            return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }

        private void mouse(java.awt.event.MouseEvent e, int buttons) {
            Rectangle r = screenRect();
            if (r.width <= 0 || r.height <= 0) return;
            int rot = rotation;
            int dw = (rot % 2 == 0) ? host.displayWidth() : host.displayHeight();
            int dh = (rot % 2 == 0) ? host.displayHeight() : host.displayWidth();
            int x = (int) Math.round((e.getX() - r.x) * (double) dw / r.width);
            int y = (int) Math.round((e.getY() - r.y) * (double) dh / r.height);
            send(InputEvent.newBuilder().setMouseEvent(MouseEvent.newBuilder()
                    .setX(Math.max(0, Math.min(dw - 1, x))).setY(Math.max(0, Math.min(dh - 1, y)))
                    .setButtons(buttons).build()).build());
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(BG);
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage img = image;
            Rectangle r = screenRect();
            if (img == null) {
                g.setColor(FG);
                g.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
                String s = "Starting " + host.title() + "…";
                g.drawString(s, (getWidth() - g.getFontMetrics().stringWidth(s)) / 2, getHeight() / 2);
            } else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int rot = rotation;
                if (skin != null) {
                    Rectangle2D.Double f = frameRect();
                    g.drawImage(skin.back(rot), (int) Math.round(f.x), (int) Math.round(f.y),
                            (int) Math.round(f.width), (int) Math.round(f.height), null);
                    g.drawImage(img, r.x, r.y, r.width, r.height, null);
                    java.awt.image.BufferedImage m = skin.mask(rot);
                    if (m != null) g.drawImage(m, r.x, r.y, r.width, r.height, null);
                } else {
                    if (host.round()) g.clip(new Ellipse2D.Double(r.x, r.y, r.width, r.height));
                    g.drawImage(img, r.x, r.y, r.width, r.height, null);
                }
            }
            g.dispose();
        }
    }
}
