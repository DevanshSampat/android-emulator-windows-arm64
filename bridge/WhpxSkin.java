import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Android Studio device skin (the device frame): the "layout" file in the skin folder, its
 * frame image and its screen mask. The installer ships the images as PNG data under their .webp
 * names, because Studio's WebP decoder can't load on ARM64.
 *
 * <p>Only the portrait layout is read; other orientations are the portrait frame turned, as
 * Studio does.
 */
final class WhpxSkin {
    /** Frame size, in skin pixels. */
    final int width, height;
    /** Where the screen is in the frame, in skin pixels. */
    final Rectangle display;
    private final BufferedImage back, mask;
    private final int backX, backY;
    private final BufferedImage[] backRot = new BufferedImage[4], maskRot = new BufferedImage[4];

    private WhpxSkin(int width, int height, Rectangle display, BufferedImage back, int backX, int backY, BufferedImage mask) {
        this.width = width;
        this.height = height;
        this.display = display;
        this.back = back;
        this.backX = backX;
        this.backY = backY;
        this.mask = mask;
    }

    /** Loads the skin in dir, or returns null if there is none or it has no frame image. */
    static WhpxSkin load(File dir) {
        if (dir == null) return null;
        try {
            File layout = new File(dir, "layout");
            if (!layout.isFile()) return null;
            Map<String, Object> root = parse(new String(Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8));
            Map<String, Object> lp = map(root, "layouts", "portrait");
            Map<String, Object> pp = map(root, "parts", "portrait");
            Map<String, Object> disp = map(root, "parts", "device", "display");
            if (lp == null || pp == null || disp == null) return null;
            int w = num(lp, "width"), h = num(lp, "height");
            // part entries name the parts and place them in the layout.
            int px = 0, py = 0, dx = 0, dy = 0;
            for (Map.Entry<String, Object> e : lp.entrySet()) {
                if (!e.getKey().startsWith("part") || !(e.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> part = (Map<String, Object>) e.getValue();
                String name = String.valueOf(part.get("name"));
                if ("portrait".equals(name)) { px = num(part, "x"); py = num(part, "y"); }
                else if ("device".equals(name)) { dx = num(part, "x"); dy = num(part, "y"); }
            }
            Rectangle display = new Rectangle(dx + num(disp, "x"), dy + num(disp, "y"), num(disp, "width"), num(disp, "height"));
            Map<String, Object> bg = map(pp, "background");
            Map<String, Object> fg = map(pp, "foreground");
            BufferedImage back = bg != null ? image(dir, bg.get("image")) : null;
            if (w <= 0 || h <= 0 || display.width <= 0 || display.height <= 0 || back == null) return null;
            BufferedImage mask = fg != null ? image(dir, fg.get("mask")) : null;
            return new WhpxSkin(w, h, display, back, px, py, mask);
        } catch (Throwable t) {
            System.out.println("[WhpxSkin] could not load " + dir + ": " + t);
            return null;
        }
    }

    /** Frame size when turned by rot quarter turns counterclockwise (0-3). */
    int width(int rot) { return rot % 2 == 0 ? width : height; }
    int height(int rot) { return rot % 2 == 0 ? height : width; }

    /** Turns skin coordinates of the portrait frame into those of the frame turned by rot. */
    private AffineTransform turn(int rot) {
        AffineTransform t = new AffineTransform();
        switch (rot & 3) {
            case 1: t.translate(0, width); t.rotate(-Math.PI / 2); break;   // landscape: top to the left
            case 2: t.translate(width, height); t.rotate(Math.PI); break;
            case 3: t.translate(height, 0); t.rotate(Math.PI / 2); break;
            default: break;
        }
        return t;
    }

    /** Screen rectangle in the frame turned by rot, in skin pixels. */
    Rectangle2D display(int rot) {
        return turn(rot).createTransformedShape(display).getBounds2D();
    }

    private BufferedImage turned(BufferedImage src, BufferedImage[] cache, int rot, int x, int y, int cw, int ch) {
        rot &= 3;
        if (cache[rot] != null) return cache[rot];
        int w = rot % 2 == 0 ? cw : ch, h = rot % 2 == 0 ? ch : cw;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform t = new AffineTransform();
        switch (rot) {
            case 1: t.translate(0, cw); t.rotate(-Math.PI / 2); break;
            case 2: t.translate(cw, ch); t.rotate(Math.PI); break;
            case 3: t.translate(ch, 0); t.rotate(Math.PI / 2); break;
            default: break;
        }
        g.transform(t);
        g.drawImage(src, x, y, null);
        g.dispose();
        cache[rot] = out;
        return out;
    }

    /** The frame image, turned, covering the whole frame. */
    BufferedImage back(int rot) {
        return turned(back, backRot, rot, backX, backY, width, height);
    }

    /** The screen mask, turned, covering the screen rectangle; null if the skin has none. */
    BufferedImage mask(int rot) {
        return mask == null ? null : turned(mask, maskRot, rot, 0, 0, mask.getWidth(), mask.getHeight());
    }

    // ---- layout file ----

    private static BufferedImage image(File dir, Object name) throws Exception {
        if (name == null) return null;
        File f = new File(dir, name.toString());
        if (!f.isFile()) return null;
        return ImageIO.read(f);
    }

    private static int num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        try { return v == null ? 0 : (int) Math.round(Double.parseDouble(v.toString())); } catch (NumberFormatException e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String p : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(p);
        }
        return cur instanceof Map ? (Map<String, Object>) cur : null;
    }

    /** Parses the skin format: "key value" lines and "key { ... }" blocks. */
    static Map<String, Object> parse(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\\R")) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{' || c == '}' || Character.isWhitespace(c)) {
                    if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
                    if (c != ' ' && c != '\t' && !Character.isWhitespace(c)) tokens.add(String.valueOf(c));
                } else {
                    cur.append(c);
                }
            }
            if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
            tokens.add("\n");
        }
        int[] pos = { 0 };
        return block(tokens, pos);
    }

    private static Map<String, Object> block(List<String> t, int[] pos) {
        Map<String, Object> m = new LinkedHashMap<>();
        while (pos[0] < t.size()) {
            String k = t.get(pos[0]++);
            if (k.equals("\n")) continue;
            if (k.equals("}")) break;
            // Skip line breaks between a key and its value or block.
            while (pos[0] < t.size() && t.get(pos[0]).equals("\n") && pos[0] + 1 < t.size() && t.get(pos[0] + 1).equals("{")) pos[0]++;
            if (pos[0] < t.size() && t.get(pos[0]).equals("{")) {
                pos[0]++;
                m.put(k, block(t, pos));
            } else {
                StringBuilder v = new StringBuilder();
                while (pos[0] < t.size() && !t.get(pos[0]).equals("\n") && !t.get(pos[0]).equals("}")) {
                    if (v.length() > 0) v.append(' ');
                    v.append(t.get(pos[0]++));
                }
                m.put(k, v.toString());
            }
        }
        return m;
    }
}
