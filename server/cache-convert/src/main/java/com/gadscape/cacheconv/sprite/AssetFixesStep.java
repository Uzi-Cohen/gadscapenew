package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * asset-fixes: art GadScape replaced, applied on every build from data in the module.
 * <ul>
 *   <li><b>sprite frames</b> ({@value #SPRITES}): {@code SPRITE<TAB>FRAME<TAB>PNG<TAB>FINDING<TAB>NOTE}. SPRITE is a USER
 *       sprite id (mapped through the id map: the "NR" credits coin 5265 lives at 20011), FRAME the frame index, PNG a
 *       resource under {@code sprite/}. The picture must have the frame's size; it replaces the frame's pixels (the
 *       sheet keeps its other frames, offsets and canvas).</li>
 *   <li><b>binary archive</b> (archive 10: title screen, huffman, ...): a group the user changed (USER differs from
 *       VAN211) is USER's, under BASE's group with the same name (client CACHE_NEEDS 5: GadScape's green
 *       {@code title.jpg} on the loading screen). Groups the user did not change stay BASE's.</li>
 * </ul>
 */
public final class AssetFixesStep implements Step {
    public static final String NAME = "asset-fixes";
    public static final String SPRITES = "sprite/replace.tsv";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) throws IOException {
        int n = sprites(ctx) + binary(ctx);
        ctx.report.count("asset fixes applied", n);
        ctx.log("%d asset fixes", n);
    }

    static int sprites(Ctx ctx) throws IOException {
        List<String> lines;
        try (InputStream in = AssetFixesStep.class.getClassLoader().getResourceAsStream(SPRITES)) {
            if (in == null) return 0;
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        int n = 0;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            int user = Integer.parseInt(c[0].trim()), frame = Integer.parseInt(c[1].trim());
            int id = ctx.ids.map(Kind.SPRITE, user);
            try {
                byte[] b = ctx.out.single(Arch.SPRITES, id);
                if (b == null) throw new IllegalStateException("sprite " + id + " is not in the output");
                SpriteSheet s = SpriteSheet.parse(b);
                if (frame >= s.frameCount()) throw new IllegalStateException("sprite " + id + " has " + s.frameCount() + " frames");
                BufferedImage img;
                try (InputStream in = AssetFixesStep.class.getClassLoader().getResourceAsStream("sprite/" + c[2].trim())) {
                    if (in == null) throw new IllegalStateException("missing resource sprite/" + c[2].trim());
                    img = ImageIO.read(in);
                }
                SpriteSheet.Frame f = s.frame(frame);
                if (img.getWidth() != f.w || img.getHeight() != f.h)
                    throw new IllegalStateException("picture is " + img.getWidth() + "x" + img.getHeight() + ", frame " + frame + " is " + f.w + "x" + f.h);
                List<int[]> px = new ArrayList<>();
                int[] fw = new int[s.frameCount()], fh = new int[s.frameCount()];
                for (int i = 0; i < s.frameCount(); i++) {
                    SpriteSheet.Frame fi = s.frame(i);
                    fw[i] = fi.w; fh[i] = fi.h;
                    px.add(i == frame ? img.getRGB(0, 0, f.w, f.h, null, 0, f.w) : s.argb(i));
                }
                SpriteSheet r = SpriteSheet.fromArgb(s.width, s.height, px, fw, fh);
                for (int i = 0; i < s.frameCount(); i++) { r.frame(i).ox = s.frame(i).ox; r.frame(i).oy = s.frame(i).oy; }
                byte[] nb = r.encode();
                if (Arrays.equals(nb, b)) throw new IllegalStateException("the picture is already there (delete the row)");
                SpriteSheet.parse(nb);
                GroupData g = ctx.out.group(Arch.SPRITES, id);
                ctx.out.putGroup(Arch.SPRITES, new GroupData(id, g.name, 0, g.fileIds(), names(g), new byte[][]{nb}));
                ctx.report.add(Decision.FIXED, Kind.SPRITE, id, c[3].trim() + ": frame " + frame + " <- " + c[2].trim() + (c.length > 4 ? " (" + c[4].trim() + ")" : ""));
                n++;
            } catch (RuntimeException e) {
                ctx.report.error("sprite", id, "asset-fixes " + SPRITES + ": " + e.getMessage());
            }
        }
        return n;
    }

    private static int[] names(GroupData g) { int[] r = new int[g.fileCount()]; for (int i = 0; i < r.length; i++) r[i] = g.fileNameAt(i); return r; }

    static int binary(Ctx ctx) {
        if (!Boolean.parseBoolean(ctx.opt.get("assets.binary", "true"))) return 0;
        var user = ctx.user();
        var van = ctx.van211();
        int n = 0;
        for (int g : user.groupIds(Arch.BINARY)) {
            GroupData u = user.group(Arch.BINARY, g);
            int name = user.groupName(Arch.BINARY, g);
            GroupData v = van.hasGroup(Arch.BINARY, g) && van.groupName(Arch.BINARY, g) == name ? van.group(Arch.BINARY, g) : null;
            if (v != null && u.sameContent(v)) continue;
            int target = -1;
            for (int b : ctx.out.groupIds(Arch.BINARY)) if (ctx.out.groupName(Arch.BINARY, b) == name) { target = b; break; }
            if (target < 0) { ctx.report.warn("binary", g, "user-changed binary group (name hash " + name + ") has no BASE group of that name: not written"); continue; }
            GroupData out = ctx.out.group(Arch.BINARY, target);
            if (out.sameContent(u)) continue;
            byte[][] files = new byte[u.fileCount()][];
            for (int i = 0; i < files.length; i++) files[i] = u.dataAt(i).clone();
            ctx.out.putGroup(Arch.BINARY, new GroupData(target, out.name, 0, u.fileIds(), names(u), files));
            ctx.report.add(Decision.USER_CHANGED, "binary", target, "user-changed binary group (name hash " + name + ", " + u.dataAt(0).length + " bytes) replaces BASE's");
            n++;
        }
        return n;
    }
}
