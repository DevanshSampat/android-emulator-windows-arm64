import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * The window behind the 3-dot button. Android Studio does not draw Extended Controls: it asks
 * the emulator to open its own window, so without this the button does nothing.
 *
 * Only panels with a working backend are shown. The rest are listed with the reason, because a
 * control that looks live but changes nothing is worse than no control.
 */
final class WhpxExtendedControls {

    /** What the panels need from the bridge. */
    interface Host {
        void setLocation(double lat, double lon, double alt);
        double[] location();
        void shell(String command);
        void key(int keyCode);
        String adbPath();
        String serial();
        String avdName();
    }

    // PaneEntry.PaneIndex values.
    private static final int KEEP_CURRENT = 0, LOCATION = 1, BATTERY = 4, DPAD = 7, TV_REMOTE = 8, BUGREPORT = 14;

    private static final String P_LOCATION = "Location", P_BATTERY = "Battery", P_REMOTE = "Remote",
            P_BUGREPORT = "Bug report", P_OTHER = "Not available";

    // android.view.KeyEvent codes.
    private static final int KEY_HOME = 3, KEY_BACK = 4, KEY_UP = 19, KEY_DOWN = 20, KEY_LEFT = 21, KEY_RIGHT = 22,
            KEY_CENTER = 23, KEY_VOL_UP = 24, KEY_VOL_DOWN = 25, KEY_POWER = 26, KEY_MENU = 82, KEY_MUTE = 164;

    private static final String[][] PLACES = {
        { "Mountain View, USA", "37.4220", "-122.0841" },
        { "New York, USA", "40.7128", "-74.0060" },
        { "London, UK", "51.5074", "-0.1278" },
        { "Mumbai, India", "19.0760", "72.8777" },
        { "Bengaluru, India", "12.9716", "77.5946" },
        { "Delhi, India", "28.6139", "77.2090" },
        { "Tokyo, Japan", "35.6762", "139.6503" },
        { "Sydney, Australia", "-33.8688", "151.2093" },
    };

    private final Host host;
    private JFrame frame;
    private JList<String> nav;
    private JPanel cards;
    private final Map<String, JPanel> panels = new LinkedHashMap<>();
    private JTextField latField, lonField, altField;
    private JLabel locStatus;
    private boolean placeSelected;
    private volatile boolean dark = true;
    private volatile boolean visible;

    WhpxExtendedControls(Host host) {
        this.host = host;
    }

    /** Returns true if this call changed the window's visibility. */
    boolean show(int pane, int x, int y, boolean hasPosition) {
        boolean changed = !visible;
        visible = true;
        SwingUtilities.invokeLater(() -> {
            try {
                boolean first = frame == null;
                if (first) build();
                applyTheme();
                select(pane);
                loadLocation();
                if (first && hasPosition) place(x, y);
                frame.setVisible(true);
                frame.setExtendedState(JFrame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            } catch (Throwable t) {
                visible = false;
                System.err.println("[WhpxEmulatorBridge] Extended controls failed: " + t);
            }
        });
        return changed;
    }

    boolean close() {
        boolean changed = visible;
        visible = false;
        SwingUtilities.invokeLater(() -> {
            if (frame != null) frame.setVisible(false);
        });
        return changed;
    }

    void setDark(boolean value) {
        dark = value;
        SwingUtilities.invokeLater(() -> {
            if (frame != null) applyTheme();
        });
    }

    private void select(int pane) {
        String name;
        switch (pane) {
            case KEEP_CURRENT: return;
            case LOCATION: name = P_LOCATION; break;
            case BATTERY: name = P_BATTERY; break;
            case DPAD:
            case TV_REMOTE: name = P_REMOTE; break;
            case BUGREPORT: name = P_BUGREPORT; break;
            default: name = P_OTHER; break;
        }
        nav.setSelectedValue(name, true);
    }

    private void place(int centerX, int centerY) {
        Rectangle s = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int x = Math.max(s.x, Math.min(centerX - frame.getWidth() / 2, s.x + s.width - frame.getWidth()));
        int y = Math.max(s.y, Math.min(centerY - frame.getHeight() / 2, s.y + s.height - frame.getHeight()));
        frame.setLocation(x, y);
    }

    /**
     * Android Studio's icon, from bin\studio.ico of the Studio whose runtime runs the bridge.
     * Without it the window shows the runtime's default, which is the IntelliJ logo. Java cannot
     * read .ico files, but the 256 px entry is stored as PNG, so that one is decoded and scaled
     * down for the title bar and taskbar. Empty if anything is missing.
     */
    static java.util.List<java.awt.Image> studioIcons() {
        java.util.List<java.awt.Image> out = new java.util.ArrayList<>();
        try {
            File ico = new File(new File(System.getProperty("java.home")).getParentFile(), "bin" + File.separator + "studio.ico");
            byte[] d = java.nio.file.Files.readAllBytes(ico.toPath());
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int count = b.getShort(4) & 0xFFFF;
            java.awt.image.BufferedImage big = null;
            for (int i = 0; i < count && big == null; i++) {
                int size = b.getInt(6 + 16 * i + 8), off = b.getInt(6 + 16 * i + 12);
                if (off < 0 || size <= 8 || off + size > d.length || (d[off] & 0xFF) != 0x89 || d[off + 1] != 'P') continue;
                big = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(d, off, size));
            }
            if (big == null) return out;
            out.add(big);
            for (int px : new int[] { 64, 48, 32, 24, 20, 16 }) {
                java.awt.image.BufferedImage s = new java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = s.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(big.getScaledInstance(px, px, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
                g.dispose();
                out.add(s);
            }
        } catch (Exception | Error ignored) {
            out.clear();
        }
        return out;
    }

    private void build() {
        frame = new JFrame("Extended controls - " + host.avdName());
        java.util.List<java.awt.Image> icons = studioIcons();
        if (!icons.isEmpty()) frame.setIconImages(icons);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                visible = false;
                frame.setVisible(false);
            }
        });

        panels.put(P_LOCATION, locationPanel());
        panels.put(P_BATTERY, batteryPanel());
        panels.put(P_REMOTE, remotePanel());
        panels.put(P_BUGREPORT, bugReportPanel());
        panels.put(P_OTHER, otherPanel());

        CardLayout layout = new CardLayout();
        cards = new JPanel(layout);
        DefaultListModel<String> names = new DefaultListModel<>();
        for (Map.Entry<String, JPanel> e : panels.entrySet()) {
            cards.add(e.getValue(), e.getKey());
            names.addElement(e.getKey());
        }
        nav = new JList<>(names);
        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nav.setFixedCellHeight(38);
        nav.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, false);
                c.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 12));
                return c;
            }
        });
        nav.addListSelectionListener(e -> {
            String v = nav.getSelectedValue();
            if (v != null) layout.show(cards, v);
        });
        nav.setSelectedIndex(0);

        JScrollPane left = new JScrollPane(nav);
        left.setBorder(BorderFactory.createEmptyBorder());
        left.setPreferredSize(new Dimension(170, 0));
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(left, BorderLayout.WEST);
        frame.getContentPane().add(cards, BorderLayout.CENTER);
        frame.setSize(760, 500);
        frame.setMinimumSize(new Dimension(640, 420));
        frame.setLocationByPlatform(true);
    }

    // ---- Location

    private JPanel locationPanel() {
        JPanel p = page("Location", "Sets GPS and the network location Android reports to apps.");
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 0, 5, 12);
        g.anchor = GridBagConstraints.WEST;

        JComboBox<String> places = new JComboBox<>();
        places.addItem("Choose a place...");
        for (String[] place : PLACES) places.addItem(place[0]);
        latField = new JTextField(14);
        lonField = new JTextField(14);
        altField = new JTextField(14);
        places.addActionListener(e -> {
            int i = places.getSelectedIndex() - 1;
            if (i >= 0) {
                latField.setText(PLACES[i][1]);
                lonField.setText(PLACES[i][2]);
                placeSelected = true;
            }
        });

        row(form, g, 0, "Place", places);
        row(form, g, 1, "Latitude", latField);
        row(form, g, 2, "Longitude", lonField);
        row(form, g, 3, "Altitude (m)", altField);

        JButton set = new JButton("Set location");
        locStatus = new JLabel(" ");
        set.addActionListener(e -> {
            try {
                double lat = Double.parseDouble(latField.getText().trim());
                double lon = Double.parseDouble(lonField.getText().trim());
                double alt = Double.parseDouble(altField.getText().trim());
                if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new NumberFormatException();
                host.setLocation(lat, lon, alt);
                placeSelected = false;
                locStatus.setText(String.format(Locale.ROOT, "Location set to %.4f, %.4f", lat, lon));
            } catch (NumberFormatException ex) {
                locStatus.setText("Latitude must be -90 to 90 and longitude -180 to 180.");
            }
        });
        g.gridx = 1; g.gridy = 4;
        form.add(set, g);
        g.gridy = 5;
        form.add(locStatus, g);
        p.add(north(form), BorderLayout.CENTER);
        return p;
    }

    private void loadLocation() {
        if (latField == null || placeSelected) return;
        double[] l = host.location();
        latField.setText(String.format(Locale.ROOT, "%.4f", l[0]));
        lonField.setText(String.format(Locale.ROOT, "%.4f", l[1]));
        altField.setText(String.format(Locale.ROOT, "%.1f", l[2]));
    }

    // ---- Battery

    private JPanel batteryPanel() {
        JPanel p = page("Battery", "Simulated battery state. It stays until you change it or the emulator restarts.");
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(8, 0, 8, 12);
        g.anchor = GridBagConstraints.WEST;

        JSlider level = new JSlider(0, 100, 100);
        level.setMajorTickSpacing(25);
        level.setPaintTicks(true);
        level.setPreferredSize(new Dimension(260, 48));
        JLabel value = new JLabel("100%");
        JPanel levelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        levelRow.add(level);
        levelRow.add(value);
        JComboBox<String> charger = new JComboBox<>(new String[] { "AC charger", "None" });
        JComboBox<String> status = new JComboBox<>(new String[] { "Charging", "Discharging", "Not charging", "Full" });
        JButton apply = new JButton("Apply");
        JLabel done = new JLabel(" ");

        level.addChangeListener(e -> value.setText(level.getValue() + "%"));
        charger.addActionListener(e -> status.setSelectedIndex(charger.getSelectedIndex() == 0 ? 0 : 1));
        apply.addActionListener(e -> {
            int ac = charger.getSelectedIndex() == 0 ? 1 : 0;
            int st = status.getSelectedIndex() + 2;  // BATTERY_STATUS_CHARGING is 2
            // The guest loop re-asserts these properties, so the state survives its next tick.
            host.shell("setprop whpx.batt.level " + level.getValue() + "; setprop whpx.batt.ac " + ac
                    + "; setprop whpx.batt.status " + st + "; cmd battery set level " + level.getValue()
                    + "; cmd battery set ac " + ac + "; cmd battery set status " + st);
            done.setText("Battery set to " + level.getValue() + "%");
        });

        row(form, g, 0, "Charge level", levelRow);
        row(form, g, 1, "Charger", charger);
        row(form, g, 2, "Status", status);
        g.gridx = 1; g.gridy = 3;
        form.add(apply, g);
        g.gridy = 4;
        form.add(done, g);
        p.add(north(form), BorderLayout.CENTER);
        return p;
    }

    // ---- Remote

    private JPanel remotePanel() {
        JPanel p = page("Remote", "D-pad and TV remote buttons. Keys go to the emulator, so watch its screen.");
        JPanel pad = new JPanel(new GridLayout(3, 3, 6, 6));
        pad.add(new JLabel());
        pad.add(keyButton("▲", KEY_UP));
        pad.add(new JLabel());
        pad.add(keyButton("◀", KEY_LEFT));
        pad.add(keyButton("OK", KEY_CENTER));
        pad.add(keyButton("▶", KEY_RIGHT));
        pad.add(new JLabel());
        pad.add(keyButton("▼", KEY_DOWN));
        pad.add(new JLabel());
        pad.setPreferredSize(new Dimension(230, 170));

        JPanel keys = new JPanel(new GridLayout(4, 2, 6, 6));
        keys.add(keyButton("Back", KEY_BACK));
        keys.add(keyButton("Home", KEY_HOME));
        keys.add(keyButton("Menu", KEY_MENU));
        keys.add(keyButton("Power", KEY_POWER));
        keys.add(keyButton("Volume +", KEY_VOL_UP));
        keys.add(keyButton("Volume -", KEY_VOL_DOWN));
        keys.add(keyButton("Mute", KEY_MUTE));
        keys.setPreferredSize(new Dimension(230, 170));

        JPanel both = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));
        both.add(pad);
        both.add(keys);
        p.add(north(both), BorderLayout.CENTER);
        return p;
    }

    private JButton keyButton(String label, int code) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 2, 2, 2));
        b.addActionListener(e -> host.key(code));
        return b;
    }

    // ---- Bug report

    private JPanel bugReportPanel() {
        JPanel p = page("Bug report", "Saves a full Android bug report to your Desktop. This takes a few minutes.");
        JButton save = new JButton("Save bug report");
        JLabel state = new JLabel(" ");
        save.addActionListener(e -> {
            save.setEnabled(false);
            state.setText("Collecting...");
            Thread t = new Thread(() -> {
                String message;
                try {
                    File desktop = new File(System.getProperty("user.home"), "Desktop");
                    File out = new File(desktop, "bugreport-" + host.avdName() + "-" + System.currentTimeMillis() + ".zip");
                    Process proc = new ProcessBuilder(host.adbPath(), "-s", host.serial(), "bugreport", out.getPath())
                            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                    int rc = proc.waitFor();
                    message = rc == 0 && out.exists() ? "Saved " + out.getName() : "Bug report failed (adb exit " + rc + ")";
                } catch (Exception ex) {
                    message = "Bug report failed: " + ex.getMessage();
                }
                String result = message;
                SwingUtilities.invokeLater(() -> {
                    state.setText(result);
                    save.setEnabled(true);
                });
            }, "WhpxBugReport");
            t.setDaemon(true);
            t.start();
        });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
        row.add(save);
        JPanel stack = new JPanel(new GridLayout(2, 1));
        stack.add(row);
        stack.add(state);
        p.add(north(stack), BorderLayout.CENTER);
        return p;
    }

    // ---- Unavailable

    private JPanel otherPanel() {
        JPanel p = page("Not available", "These need hardware this emulator does not have yet.");
        JTextArea text = new JTextArea(
                "Cellular, Phone: no modem in this VM.\n\n"
                + "Camera, Microphone: no camera or audio device.\n\n"
                + "Virtual sensors, Fingerprint: not connected to the host.\n\n"
                + "Snapshots: not supported on WHPX.\n\n"
                + "Google Play: use the Play Store app inside the emulator.");
        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        p.add(text, BorderLayout.CENTER);
        return p;
    }

    // ---- Layout helpers

    private JPanel page(String title, String subtitle) {
        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 18f));
        JLabel s = new JLabel(subtitle);
        JPanel head = new JPanel(new GridLayout(2, 1, 0, 4));
        head.add(t);
        head.add(s);
        p.add(head, BorderLayout.NORTH);
        return p;
    }

    private static JPanel north(Component c) {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(c);
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(left, BorderLayout.NORTH);
        return holder;
    }

    private static void row(JPanel form, GridBagConstraints g, int y, String label, Component c) {
        g.gridy = y;
        g.gridx = 0;
        form.add(new JLabel(label), g);
        g.gridx = 1;
        form.add(c, g);
    }

    private void applyTheme() {
        Color bg = dark ? new Color(0x2B2D30) : new Color(0xF5F5F5);
        Color side = dark ? new Color(0x1E1F22) : new Color(0xE4E4E4);
        Color fg = dark ? new Color(0xDFE1E5) : new Color(0x1E1F22);
        Color field = dark ? new Color(0x393B40) : Color.WHITE;
        paint(frame.getContentPane(), bg, fg, field);
        nav.setBackground(side);
        nav.setForeground(fg);
        nav.setSelectionBackground(dark ? new Color(0x2E436E) : new Color(0xC5D9F7));
        nav.setSelectionForeground(fg);
        frame.repaint();
    }

    private static void paint(Container root, Color bg, Color fg, Color field) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextField || c instanceof JComboBox || c instanceof JButton) {
                c.setBackground(field);
                c.setForeground(fg);
            } else {
                c.setBackground(bg);
                c.setForeground(fg);
            }
            if (c instanceof JTextField) ((JTextField) c).setCaretColor(fg);
            if (c instanceof Container) paint((Container) c, bg, fg, field);
        }
    }
}
