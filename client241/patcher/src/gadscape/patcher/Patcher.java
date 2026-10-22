package gadscape.patcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * GadScape patcher for RuneLite's jars: the injected-client (the OSRS game client RuneLite ships) and RuneLite's
 * own client jar (branding).
 *
 * <p>{@code patch} (injected-client) makes these changes, and fails if any one does not match exactly once:
 * <ol>
 *   <li><b>RSA modulus.</b> The client encrypts the login block with {@code new BigInteger("<hex>", 16)} built in a
 *   static initializer next to {@code new BigInteger("10001", 16)}. The hex string is replaced with ours.</li>
 *   <li><b>Host check.</b> The applet's init() calls a check that only allows a codebase host of jagex.com,
 *   *.runescape.com, 127.0.0.1 or 192.168.1.x and otherwise stops with the error "invalidhost". The one copy of
 *   that check that is actually called is rewritten to {@code return true}, so any host from jav_config works.
 *   (The obfuscated client also carries uncalled duplicate copies; those are left alone and reported.)</li>
 *   <li><b>Friends chat capacity</b> ({@code --fc-capacity}, default {@value #DEFAULT_FC_CAPACITY}, 0 = leave it).
 *   The friends chat list is built with room for 500 members ({@code super(500)} in the constructor of the class
 *   implementing {@code net.runelite.api.FriendsChatManager}); member 501 throws in the client. GadScape's 211
 *   client raised it to 2000 (NRFriendsChatMixin) because the server's auto-joined "help" channel has no cap.</li>
 * </ol>
 * It also records (without changing anything) where the login screen keeps its login index and response lines,
 * for the GadScape Login plugin ({@code login.*} keys in the marker file).
 * Nothing else in the jar changes: every other entry is copied byte for byte.
 *
 * <p>{@code brand} (RuneLite's client jar) puts GadScape's name, icon, splash logo and gold accent colour into
 * RuneLite and adds the paint hook the GadScape Login plugin draws the login screen with; see {@link #brand}.
 *
 * <p>Usage:
 * <pre>
 *   patch   --in injected-client-1.13.3.jar --out injected-client-1.13.3-gadscape.jar
 *           --modulus (hex | @file-with-hex) [--expect-sha256 hex] [--fc-capacity N]
 *   brand   --in client-1.13.3.jar --out client-1.13.3-gadscape.jar --title NAME --icon PNG --splash PNG
 *           [--accent RRGGBB | none] [--expect-sha256 hex]
 *   inspect --in injected-client.jar          (prints what the patcher would match, changes nothing)
 *   genkey  --out-dir dir [--force]           (new TEST RSA key pair; see README.md)
 * </pre>
 */
public final class Patcher
{
	private static final String BIGINTEGER = "java/math/BigInteger";
	private static final String HOST_ERROR = "invalidhost";
	/** Added to the patched jar so the launcher can tell a patched injected-client from a stock one. */
	static final String MARKER = "gadscape-patch.properties";
	/** Added to the branded RuneLite client jar. */
	static final String CLIENT_MARKER = "gadscape-client-patch.properties";
	/** Friends chat capacity of GadScape's 211 client (NRFriendsChatMixin). */
	static final int DEFAULT_FC_CAPACITY = 2000;

	public static void main(String[] args) throws Exception
	{
		if (args.length == 0)
		{
			usage();
			return;
		}
		Map<String, String> opts = parse(args);
		try
		{
			switch (args[0])
			{
				case "patch":
					patch(opts);
					break;
				case "brand":
					brand(opts);
					break;
				case "inspect":
					inspect(Paths.get(require(opts, "in")));
					break;
				case "genkey":
					genkey(Paths.get(require(opts, "out-dir")), opts.containsKey("force"));
					break;
				default:
					usage();
					System.exit(2);
			}
		}
		catch (PatchException e)
		{
			System.err.println("PATCH FAILED: " + e.getMessage());
			System.exit(1);
		}
	}

	private static void usage()
	{
		System.out.println("gadscape patcher\n"
			+ "  patch   --in <injected-client.jar> --out <patched.jar> --modulus <hex|@file> [--expect-sha256 <hex>] [--fc-capacity <n>]\n"
			+ "  brand   --in <client.jar> --out <branded.jar> --title <name> --icon <png> --splash <png> [--accent <RRGGBB>|none] [--expect-sha256 <hex>]\n"
			+ "  inspect --in <injected-client.jar>\n"
			+ "  genkey  --out-dir <dir> [--force]");
	}

	private static Map<String, String> parse(String[] args)
	{
		Map<String, String> m = new HashMap<>();
		for (int i = 1; i < args.length; i++)
		{
			String a = args[i];
			if (!a.startsWith("--"))
			{
				throw new IllegalArgumentException("unexpected argument " + a);
			}
			String k = a.substring(2);
			if (k.equals("force"))
			{
				m.put(k, "true");
			}
			else
			{
				if (i + 1 >= args.length)
				{
					throw new IllegalArgumentException("missing value for " + a);
				}
				m.put(k, args[++i]);
			}
		}
		return m;
	}

	private static String require(Map<String, String> opts, String k)
	{
		String v = opts.get(k);
		if (v == null)
		{
			throw new IllegalArgumentException("missing --" + k);
		}
		return v;
	}

	// ------------------------------------------------------------------------------------------------------------
	// patch

	private static void patch(Map<String, String> opts) throws Exception
	{
		Path in = Paths.get(require(opts, "in"));
		Path out = Paths.get(require(opts, "out"));
		String modArg = require(opts, "modulus");
		String newHex = (modArg.startsWith("@") ? new String(Files.readAllBytes(Paths.get(modArg.substring(1))), StandardCharsets.US_ASCII) : modArg)
			.trim().toLowerCase(Locale.ROOT);
		BigInteger newMod = new BigInteger(newHex, 16);
		if (!newMod.toString(16).equals(newHex))
		{
			throw new IllegalArgumentException("modulus must be lowercase hex without leading zeros");
		}

		byte[] inBytes = Files.readAllBytes(in);
		String inSha = sha256(inBytes);
		System.out.println("input  " + in + " sha256 " + inSha);
		String expect = opts.get("expect-sha256");
		if (expect != null && !expect.equalsIgnoreCase(inSha))
		{
			fail("input jar sha256 is " + inSha + " but --expect-sha256 says " + expect
				+ "; refusing to patch a different injected-client than the one pinned in the lock file");
		}

		Jar jar = Jar.read(inBytes);
		Map<String, ClassNode> classes = jar.classes();

		// 1. RSA modulus
		RsaSite rsa = findRsa(classes);
		BigInteger oldMod = new BigInteger(rsa.ldc.cst.toString(), 16);
		System.out.println("rsa    found in " + rsa.owner.name + "." + rsa.method.name + rsa.method.desc
			+ ": " + oldMod.bitLength() + "-bit modulus " + abbrev(rsa.ldc.cst.toString()) + ", exponent 0x10001");
		if (newMod.bitLength() != oldMod.bitLength())
		{
			fail("new modulus is " + newMod.bitLength() + " bits but the client's is " + oldMod.bitLength()
				+ " bits; generate the key with 'genkey' (same size as OSRS)");
		}
		if (newMod.equals(oldMod))
		{
			fail("new modulus equals the modulus already in the client (is this jar already patched?)");
		}
		rsa.ldc.cst = newHex;
		System.out.println("rsa    replaced with " + abbrev(newHex));

		// 2. host check
		HostSite host = findHostCheck(classes);
		MethodNode m = host.method;
		m.instructions = new InsnList();
		m.instructions.add(new InsnNode(Opcodes.ICONST_1));
		m.instructions.add(new InsnNode(Opcodes.IRETURN));
		m.tryCatchBlocks.clear();
		if (m.localVariables != null)
		{
			m.localVariables.clear();
		}
		m.maxStack = 1;
		m.maxLocals = Type.getArgumentsAndReturnSizes(m.desc) >> 2;
		if ((m.access & Opcodes.ACC_STATIC) != 0)
		{
			m.maxLocals -= 1;
		}
		System.out.println("host   " + host.owner.name + "." + m.name + m.desc + " (called from " + host.callers
			+ ") now returns true; uncalled duplicate copies left as they are: " + host.deadCopies);

		// 3. friends chat capacity (the GadScape 211 client's NRFriendsChatMixin raised it to 2000; the server's
		// auto-joined "help" channel has no 95-member cap)
		int fcCapacity = Integer.parseInt(opts.getOrDefault("fc-capacity", String.valueOf(DEFAULT_FC_CAPACITY)));
		FcSite fc = null;
		if (fcCapacity > 0)
		{
			if (fcCapacity > Short.MAX_VALUE)
			{
				fail("--fc-capacity must be at most " + Short.MAX_VALUE);
			}
			fc = findFriendsChat(classes);
			System.out.println("fchat  " + fc.owner.name + ".<init> capacity " + fc.push.operand + " -> " + fcCapacity);
			fc.push.operand = fcCapacity;
		}

		// 4. login screen fields for the GadScape Login plugin (read only, nothing is changed)
		Map<String, String> login = findLogin(classes);
		System.out.println("login  " + (login.isEmpty() ? "fields not found; the GadScape Login plugin will fall back to the game's own login screen" : login));

		Map<String, byte[]> changed = new LinkedHashMap<>();
		changed.put(rsa.owner.name + ".class", write(rsa.owner));
		if (host.owner != rsa.owner)
		{
			changed.put(host.owner.name + ".class", write(host.owner));
		}
		if (fc != null && !changed.containsKey(fc.owner.name + ".class"))
		{
			changed.put(fc.owner.name + ".class", write(fc.owner));
		}
		StringBuilder marker = new StringBuilder("# Written by client241/patcher. The GadScape launcher refuses an injected-client without this file.\n"
			+ "source.sha256=" + inSha + "\n"
			+ "rsa.class=" + rsa.owner.name + "\n"
			+ "rsa.modulus=" + newHex + "\n"
			+ "host.check=" + host.owner.name + "." + m.name + m.desc + "\n");
		if (fc != null)
		{
			marker.append("friendschat.class=").append(fc.owner.name).append('\n')
				.append("friendschat.capacity=").append(fcCapacity).append('\n');
		}
		for (Map.Entry<String, String> e : login.entrySet())
		{
			marker.append(e.getKey()).append('=').append(e.getValue()).append('\n');
		}
		Map<String, byte[]> added = new LinkedHashMap<>();
		added.put(MARKER, marker.toString().getBytes(StandardCharsets.US_ASCII));
		byte[] outBytes = jar.write(changed, added);

		// verify the result by reading it back with the same matchers
		Map<String, ClassNode> check = Jar.read(outBytes).classes();
		RsaSite rsa2 = findRsa(check);
		if (!rsa2.ldc.cst.equals(newHex))
		{
			fail("verification: patched jar does not carry the new modulus");
		}
		String oldHex = oldMod.toString(16);
		for (ClassNode cn : check.values())
		{
			for (MethodNode mn : cn.methods)
			{
				for (AbstractInsnNode insn : mn.instructions)
				{
					if (insn instanceof LdcInsnNode && oldHex.equals(((LdcInsnNode) insn).cst))
					{
						fail("verification: the original modulus is still present in " + cn.name + "." + mn.name);
					}
				}
			}
		}
		MethodNode hm = findMethod(check.get(host.owner.name), m.name, m.desc);
		if (hm == null || hm.instructions.size() != 2 || hm.instructions.getFirst().getOpcode() != Opcodes.ICONST_1)
		{
			fail("verification: host check was not rewritten");
		}
		if (fc != null && findFriendsChat(check, fcCapacity).push.operand != fcCapacity)
		{
			fail("verification: friends chat capacity was not rewritten");
		}

		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		Files.write(tmp, outBytes);
		Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		System.out.println("output " + out + " sha256 " + sha256(outBytes) + " (" + changed.size() + " classes changed: "
			+ String.join(", ", changed.keySet()) + ")");
	}

	// ------------------------------------------------------------------------------------------------------------
	// brand (RuneLite's client jar)

	/** Resources in RuneLite's client jar that carry RuneLite's look. */
	private static final String ICON_128 = "net/runelite/client/ui/runelite_128.png";
	private static final String ICON_16 = "net/runelite/client/ui/runelite_16.png";
	private static final String SPLASH = "net/runelite/client/ui/runelite_splash.png";
	private static final String PROPERTIES = "net/runelite/client/runelite.properties";
	private static final String HOOK_CLASS = "net/runelite/client/callback/GadScapePaintHook";
	/** The hook class, compiled by patcher/build.sh, inside the patcher jar. */
	private static final String HOOK_RESOURCE = "/gadscape/patcher/GadScapePaintHook.class.bin";

	/**
	 * Brands RuneLite's own client jar (client-1.13.3.jar; not Jagex code) as GadScape. Each change must match
	 * exactly once or the command fails:
	 * <ol>
	 *   <li>Window and tray icon ({@code ui/runelite_128.png}, {@code ui/runelite_16.png}) and the splash logo
	 *   ({@code ui/runelite_splash.png}, 200 x 200) are replaced with --icon / --splash.</li>
	 *   <li>{@code runelite.properties}: {@code runelite.title} (window title, tray, notifications) = --title and
	 *   {@code runelite.discord.enable=false}. Every other line is kept as it is.</li>
	 *   <li>The splash window's title "RuneLite Launcher" (shown in the task bar while loading) = --title.</li>
	 *   <li>{@code ColorScheme.BRAND_ORANGE} and {@code BRAND_ORANGE_TRANSPARENT} (RuneLite's accent colour,
	 *   220,138,0) = --accent, GadScape gold by default (same as the 211 client). {@code --accent none} keeps it.</li>
	 *   <li>{@code Hooks.draw}, which runs once per frame on every game state (login screen included), calls
	 *   {@code GadScapePaintHook.paint(graphics)} right after it gets the frame's Graphics2D. The hook class is added
	 *   to the jar; it does nothing until a plugin (GadScape Login) registers a painter.</li>
	 * </ol>
	 */
	private static void brand(Map<String, String> opts) throws Exception
	{
		Path in = Paths.get(require(opts, "in"));
		Path out = Paths.get(require(opts, "out"));
		String title = require(opts, "title").trim();
		if (title.isEmpty() || !title.matches("[\\x20-\\x7E]+"))
		{
			fail("--title must be printable ASCII");
		}
		java.awt.image.BufferedImage icon = readImage(Paths.get(require(opts, "icon")));
		java.awt.image.BufferedImage splash = readImage(Paths.get(require(opts, "splash")));
		String accentArg = opts.getOrDefault("accent", "F0C450");
		java.awt.Color accent = accentArg.equalsIgnoreCase("none") ? null : new java.awt.Color(Integer.parseInt(accentArg, 16));

		byte[] inBytes = Files.readAllBytes(in);
		String inSha = sha256(inBytes);
		System.out.println("input  " + in + " sha256 " + inSha);
		String expect = opts.get("expect-sha256");
		if (expect != null && !expect.equalsIgnoreCase(inSha))
		{
			fail("input jar sha256 is " + inSha + " but --expect-sha256 says " + expect
				+ "; refusing to brand a different RuneLite client than the one pinned in the lock file");
		}
		Jar jar = Jar.read(inBytes);
		if (jar.data.containsKey(CLIENT_MARKER))
		{
			fail("this RuneLite client jar is already branded (" + CLIENT_MARKER + ")");
		}
		Map<String, byte[]> changed = new LinkedHashMap<>();

		// 1. icons and splash logo
		for (String r : new String[]{ICON_128, ICON_16, SPLASH})
		{
			if (!jar.data.containsKey(r))
			{
				fail("brand: no " + r + " in the jar");
			}
		}
		changed.put(ICON_128, png(scaled(icon, 128, 128)));
		changed.put(ICON_16, png(scaled(icon, 16, 16)));
		changed.put(SPLASH, png(scaled(splash, 200, 200)));
		System.out.println("images icon 128/16 and splash 200x200 replaced");

		// 2. runelite.properties
		byte[] props = jar.data.get(PROPERTIES);
		if (props == null)
		{
			fail("brand: no " + PROPERTIES + " in the jar");
		}
		String text = new String(props, StandardCharsets.ISO_8859_1);
		text = setProperty(text, "runelite.title", title);
		text = setProperty(text, "runelite.discord.enable", "false");
		changed.put(PROPERTIES, text.getBytes(StandardCharsets.ISO_8859_1));
		System.out.println("props  runelite.title=" + title + ", runelite.discord.enable=false");

		// 2b. RuneLite signs this jar (META-INF/RL.SF, RL.RSA, a SHA-256 per entry in MANIFEST.MF). Java refuses a
		// signed jar whose entries changed (SecurityException: digest error), so the signature goes. Nothing in
		// RuneLite checks its own signature at run time (only RuneLite's installer/launcher do, and we do not use them).
		int dropped = jar.unsign();
		System.out.println("unsign removed RuneLite's jar signature (" + dropped + " files; MANIFEST.MF keeps its main section)");

		Map<String, ClassNode> classes = jar.classes();

		// 3. splash window title
		ClassNode splashClass = requireClass(classes, "net/runelite/client/ui/SplashScreen");
		int titles = 0;
		for (MethodNode mn : splashClass.methods)
		{
			for (AbstractInsnNode insn : mn.instructions)
			{
				if (insn instanceof LdcInsnNode && "RuneLite Launcher".equals(((LdcInsnNode) insn).cst))
				{
					((LdcInsnNode) insn).cst = title;
					titles++;
				}
			}
		}
		if (titles != 1)
		{
			fail("brand: expected 1 \"RuneLite Launcher\" in SplashScreen, found " + titles);
		}
		changed.put(splashClass.name + ".class", write(splashClass));
		System.out.println("splash title \"RuneLite Launcher\" -> \"" + title + "\"");

		// 4. accent colour
		if (accent != null)
		{
			ClassNode colors = requireClass(classes, "net/runelite/client/ui/ColorScheme");
			MethodNode clinit = findMethod(colors, "<clinit>", "()V");
			int recolored = 0;
			for (AbstractInsnNode insn : clinit == null ? new InsnList() : clinit.instructions)
			{
				if (!(insn instanceof FieldInsnNode) || insn.getOpcode() != Opcodes.PUTSTATIC
					|| !((FieldInsnNode) insn).name.startsWith("BRAND_ORANGE"))
				{
					continue;
				}
				// NEW Color; DUP; r; g; b; [alpha]; INVOKESPECIAL Color.<init>; PUTSTATIC
				AbstractInsnNode init = prev(insn);
				boolean alpha = init instanceof MethodInsnNode && ((MethodInsnNode) init).desc.equals("(IIII)V");
				AbstractInsnNode b = prev(alpha ? prev(init) : init);
				AbstractInsnNode g = prev(b);
				AbstractInsnNode r = prev(g);
				if (!(init instanceof MethodInsnNode) || !((MethodInsnNode) init).owner.equals("java/awt/Color")
					|| intValue(r) != 220 || intValue(g) != 138 || intValue(b) != 0)
				{
					fail("brand: " + ((FieldInsnNode) insn).name + " is not new Color(220, 138, 0[, a]) any more");
				}
				clinit.instructions.set(r, pushInt(accent.getRed()));
				clinit.instructions.set(g, pushInt(accent.getGreen()));
				clinit.instructions.set(b, pushInt(accent.getBlue()));
				recolored++;
			}
			if (recolored != 2)
			{
				fail("brand: expected BRAND_ORANGE and BRAND_ORANGE_TRANSPARENT in ColorScheme, recoloured " + recolored);
			}
			changed.put(colors.name + ".class", write(colors));
			System.out.println("accent BRAND_ORANGE(_TRANSPARENT) 220,138,0 -> " + accent.getRed() + "," + accent.getGreen() + "," + accent.getBlue());
		}

		// 5. paint hook in Hooks.draw
		ClassNode hooks = requireClass(classes, "net/runelite/client/callback/Hooks");
		MethodNode draw = findMethod(hooks, "draw", "(Lnet/runelite/api/MainBufferProvider;Ljava/awt/Graphics;II)V");
		if (draw == null)
		{
			fail("brand: no Hooks.draw(MainBufferProvider, Graphics, int, int)");
		}
		List<VarInsnNode> stores = new ArrayList<>();
		for (AbstractInsnNode insn : draw.instructions)
		{
			if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals("getGraphics")
				&& ((MethodInsnNode) insn).desc.equals("(Lnet/runelite/api/MainBufferProvider;)Ljava/awt/Graphics2D;")
				&& next(insn) instanceof VarInsnNode && next(insn).getOpcode() == Opcodes.ASTORE)
			{
				stores.add((VarInsnNode) next(insn));
			}
		}
		if (stores.size() != 1)
		{
			fail("brand: expected 1 'Graphics2D g = getGraphics(mainBufferProvider)' in Hooks.draw, found " + stores.size());
		}
		InsnList call = new InsnList();
		call.add(new VarInsnNode(Opcodes.ALOAD, stores.get(0).var));
		call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_CLASS, "paint", "(Ljava/awt/Graphics2D;)V", false));
		draw.instructions.insert(stores.get(0), call);
		draw.maxStack = Math.max(draw.maxStack, 1);
		changed.put(hooks.name + ".class", write(hooks));
		System.out.println("hook   Hooks.draw calls " + HOOK_CLASS.replace('/', '.') + ".paint(graphics) each frame");

		Map<String, byte[]> added = new LinkedHashMap<>();
		byte[] hook;
		try (InputStream hs = Patcher.class.getResourceAsStream(HOOK_RESOURCE))
		{
			if (hs == null)
			{
				fail("brand: the patcher jar has no " + HOOK_RESOURCE + " (build it with patcher/build.sh)");
			}
			hook = readAll(hs);
		}
		ClassNode hookNode = new ClassNode();
		new ClassReader(hook).accept(hookNode, 0);
		if (!hookNode.name.equals(HOOK_CLASS) || findMethod(hookNode, "paint", "(Ljava/awt/Graphics2D;)V") == null)
		{
			fail("brand: " + HOOK_RESOURCE + " is not " + HOOK_CLASS);
		}
		added.put(HOOK_CLASS + ".class", hook);
		String marker = "# Written by client241/patcher brand. RuneLite's client jar with GadScape's name, icons and login paint hook.\n"
			+ "source.sha256=" + inSha + "\n"
			+ "title=" + title + "\n"
			+ "accent=" + (accent == null ? "none" : String.format("%06X", accent.getRGB() & 0xFFFFFF)) + "\n"
			+ "hook=" + HOOK_CLASS.replace('/', '.') + "\n";
		added.put(CLIENT_MARKER, marker.getBytes(StandardCharsets.US_ASCII));
		byte[] outBytes = jar.write(changed, added);

		// verify by reading back
		Jar back = Jar.read(outBytes);
		Map<String, ClassNode> check = back.classes();
		MethodNode d2 = findMethod(check.get("net/runelite/client/callback/Hooks"), "draw", draw.desc);
		boolean hooked = false;
		for (AbstractInsnNode insn : d2.instructions)
		{
			hooked |= insn instanceof MethodInsnNode && ((MethodInsnNode) insn).owner.equals(HOOK_CLASS);
		}
		if (!hooked || !check.containsKey(HOOK_CLASS) || !new String(back.data.get(PROPERTIES), StandardCharsets.ISO_8859_1).contains("runelite.title=" + title + "\n"))
		{
			fail("verification: branded jar is missing the hook, the hook class or the title");
		}
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		Files.write(tmp, outBytes);
		Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		System.out.println("output " + out + " sha256 " + sha256(outBytes) + " (" + changed.size() + " entries changed, "
			+ added.size() + " added)");
	}

	private static ClassNode requireClass(Map<String, ClassNode> classes, String name)
	{
		ClassNode cn = classes.get(name);
		if (cn == null)
		{
			fail("brand: no class " + name + " in the jar");
		}
		return cn;
	}

	/** Replaces the value of key=... in a .properties text (exactly one line), or appends it. */
	static String setProperty(String text, String key, String value)
	{
		String[] lines = text.split("\n", -1);
		int hits = 0;
		for (int i = 0; i < lines.length; i++)
		{
			if (lines[i].startsWith(key + "="))
			{
				lines[i] = key + "=" + value;
				hits++;
			}
		}
		if (hits > 1)
		{
			fail("brand: " + key + " is in runelite.properties " + hits + " times");
		}
		String joined = String.join("\n", lines);
		if (hits == 0)
		{
			joined = joined + (joined.endsWith("\n") || joined.isEmpty() ? "" : "\n") + key + "=" + value + "\n";
		}
		return joined;
	}

	private static int intValue(AbstractInsnNode n)
	{
		if (n instanceof IntInsnNode)
		{
			return ((IntInsnNode) n).operand;
		}
		if (n != null && n.getOpcode() >= Opcodes.ICONST_M1 && n.getOpcode() <= Opcodes.ICONST_5)
		{
			return n.getOpcode() - Opcodes.ICONST_0;
		}
		if (n instanceof LdcInsnNode && ((LdcInsnNode) n).cst instanceof Integer)
		{
			return (Integer) ((LdcInsnNode) n).cst;
		}
		return Integer.MIN_VALUE;
	}

	private static AbstractInsnNode pushInt(int v)
	{
		if (v >= -1 && v <= 5)
		{
			return new InsnNode(Opcodes.ICONST_0 + v);
		}
		return new IntInsnNode(v <= Byte.MAX_VALUE ? Opcodes.BIPUSH : Opcodes.SIPUSH, v);
	}

	private static java.awt.image.BufferedImage readImage(Path p) throws IOException
	{
		java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
		if (img == null)
		{
			fail("not an image: " + p);
		}
		return img;
	}

	/** High-quality downscale (halving steps, then bicubic) into a w x h ARGB image, keeping the aspect ratio. */
	static java.awt.image.BufferedImage scaled(java.awt.image.BufferedImage src, int w, int h)
	{
		java.awt.image.BufferedImage cur = src;
		while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h)
		{
			cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2, cur.getWidth() / 2, cur.getHeight() / 2);
		}
		double k = Math.min((double) w / cur.getWidth(), (double) h / cur.getHeight());
		return draw(cur, w, h, (int) Math.round(cur.getWidth() * k), (int) Math.round(cur.getHeight() * k));
	}

	private static java.awt.image.BufferedImage draw(java.awt.image.BufferedImage src, int w, int h, int dw, int dh)
	{
		java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = dst.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
		g.dispose();
		return dst;
	}

	private static byte[] png(java.awt.image.BufferedImage img) throws IOException
	{
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", bos);
		return bos.toByteArray();
	}

	private static byte[] readAll(InputStream in) throws IOException
	{
		return Jar.readAll(in);
	}

	private static void inspect(Path in) throws Exception
	{
		byte[] bytes = Files.readAllBytes(in);
		System.out.println("jar    " + in + " sha256 " + sha256(bytes));
		Jar jar = Jar.read(bytes);
		byte[] mk = jar.data.get(MARKER);
		System.out.println("marker " + (mk == null ? "none (stock jar)" : "present (patched jar)"));
		Map<String, ClassNode> classes = jar.classes();
		RsaSite rsa = findRsa(classes);
		System.out.println("rsa    " + rsa.owner.name + "." + rsa.method.name + rsa.method.desc + " modulus "
			+ new BigInteger(rsa.ldc.cst.toString(), 16).bitLength() + "-bit " + rsa.ldc.cst);
		try
		{
			HostSite host = findHostCheck(classes);
			System.out.println("host   " + host.owner.name + "." + host.method.name + host.method.desc + " called from "
				+ host.callers + "; uncalled copies " + host.deadCopies);
		}
		catch (PatchException e)
		{
			System.out.println("host   " + e.getMessage());
		}
		try
		{
			FcSite fc = findFriendsChat(classes);
			System.out.println("fchat  " + fc.owner.name + ".<init> capacity " + fc.push.operand);
		}
		catch (PatchException e)
		{
			System.out.println("fchat  " + e.getMessage());
		}
		System.out.println("login  " + findLogin(classes));
	}

	// ------------------------------------------------------------------------------------------------------------
	// matchers

	private static final class FcSite
	{
		ClassNode owner;
		IntInsnNode push;
	}

	private static FcSite findFriendsChat(Map<String, ClassNode> classes)
	{
		return findFriendsChat(classes, 500);
	}

	/**
	 * The constructor of the class that implements net.runelite.api.FriendsChatManager passes its capacity to the
	 * user-list superclass: {@code SIPUSH <capacity>; INVOKESPECIAL <super>.<init>(I)V} (500 in build 241).
	 * Exactly one class and one such push are required.
	 */
	private static FcSite findFriendsChat(Map<String, ClassNode> classes, int expect)
	{
		List<FcSite> found = new ArrayList<>();
		for (ClassNode cn : classes.values())
		{
			if (cn.interfaces == null || !cn.interfaces.contains("net/runelite/api/FriendsChatManager"))
			{
				continue;
			}
			for (MethodNode mn : cn.methods)
			{
				if (!mn.name.equals("<init>"))
				{
					continue;
				}
				for (AbstractInsnNode insn : mn.instructions)
				{
					AbstractInsnNode call = next(insn);
					if (insn.getOpcode() == Opcodes.SIPUSH && call instanceof MethodInsnNode && call.getOpcode() == Opcodes.INVOKESPECIAL
						&& ((MethodInsnNode) call).owner.equals(cn.superName) && ((MethodInsnNode) call).name.equals("<init>")
						&& ((MethodInsnNode) call).desc.equals("(I)V"))
					{
						FcSite s = new FcSite();
						s.owner = cn;
						s.push = (IntInsnNode) insn;
						found.add(s);
					}
				}
			}
		}
		if (found.size() != 1)
		{
			fail("friends chat: expected exactly 1 'super(<capacity>)' in a FriendsChatManager constructor, found " + found.size());
		}
		FcSite s = found.get(0);
		if (s.push.operand != expect && s.push.operand != DEFAULT_FC_CAPACITY && expect == 500)
		{
			fail("friends chat: capacity is " + s.push.operand + ", expected 500 (a different client build?)");
		}
		return s;
	}

	/**
	 * Where the game keeps its login screen state, for the GadScape Login plugin (it sets the login index by
	 * reflection and reads the response lines to show login errors). Read only. Returns an empty map when the code
	 * does not look as expected; the plugin then leaves the game's own login screen alone.
	 * <ul>
	 *   <li>{@code client.getLoginIndex()} is {@code GETSTATIC owner.field I; LDC mult; IMUL; IRETURN} (either order):
	 *   login.class, login.index.field, login.index.multiplier (the stored value times this is the index).</li>
	 *   <li>A static method of that class taking three Strings stores them, in order, into three static String fields
	 *   (the game's three login response lines): login.response.fields=f1,f2,f3. Every such method must agree.</li>
	 * </ul>
	 */
	static Map<String, String> findLogin(Map<String, ClassNode> classes)
	{
		Map<String, String> out = new LinkedHashMap<>();
		ClassNode client = classes.get("client");
		MethodNode getter = client == null ? null : findMethod(client, "getLoginIndex", "()I");
		if (getter == null)
		{
			return out;
		}
		FieldInsnNode field = null;
		Integer mult = null;
		int real = 0;
		for (AbstractInsnNode insn : getter.instructions)
		{
			if (insn.getOpcode() < 0)
			{
				continue;
			}
			real++;
			if (insn.getOpcode() == Opcodes.GETSTATIC && ((FieldInsnNode) insn).desc.equals("I"))
			{
				field = (FieldInsnNode) insn;
			}
			else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Integer)
			{
				mult = (Integer) ((LdcInsnNode) insn).cst;
			}
			else if (insn.getOpcode() != Opcodes.IMUL && insn.getOpcode() != Opcodes.IRETURN)
			{
				return out;
			}
		}
		if (field == null || mult == null || real != 4 || (mult & 1) == 0)
		{
			return out;
		}
		ClassNode login = classes.get(field.owner);
		if (login == null)
		{
			return out;
		}
		String responses = null;
		for (MethodNode mn : login.methods)
		{
			if ((mn.access & Opcodes.ACC_STATIC) == 0 || !mn.desc.equals("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"))
			{
				continue;
			}
			String[] byArg = new String[3];
			int stores = 0;
			for (AbstractInsnNode insn : mn.instructions)
			{
				if (insn.getOpcode() != Opcodes.PUTSTATIC)
				{
					continue;
				}
				FieldInsnNode put = (FieldInsnNode) insn;
				AbstractInsnNode load = prev(insn);
				if (!put.owner.equals(login.name) || !put.desc.equals("Ljava/lang/String;") || !(load instanceof VarInsnNode)
					|| load.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) load).var > 2)
				{
					stores = -100;
					break;
				}
				byArg[((VarInsnNode) load).var] = put.name;
				stores++;
			}
			if (stores != 3 || byArg[0] == null || byArg[1] == null || byArg[2] == null)
			{
				continue;
			}
			String r = byArg[0] + "," + byArg[1] + "," + byArg[2];
			if (responses != null && !responses.equals(r))
			{
				return out; // two setters that disagree: do not guess
			}
			responses = r;
		}
		out.put("login.class", login.name);
		out.put("login.index.field", field.name);
		out.put("login.index.multiplier", String.valueOf(mult));
		if (responses != null)
		{
			out.put("login.response.fields", responses);
		}
		return out;
	}

	private static final class RsaSite
	{
		ClassNode owner;
		MethodNode method;
		LdcInsnNode ldc;
	}

	/**
	 * Finds {@code new BigInteger("<hex of 128+ digits>", 16)} in a method that also builds
	 * {@code new BigInteger("10001", 16)}. Exactly one match is required.
	 */
	private static RsaSite findRsa(Map<String, ClassNode> classes)
	{
		List<RsaSite> found = new ArrayList<>();
		int bigHexAnywhere = 0;
		for (ClassNode cn : classes.values())
		{
			for (MethodNode mn : cn.methods)
			{
				LdcInsnNode modulus = null;
				int moduli = 0;
				boolean exponent = false;
				for (AbstractInsnNode insn : mn.instructions)
				{
					String hex = bigIntegerHexArg(insn);
					if (hex == null)
					{
						continue;
					}
					if (hex.equals("10001"))
					{
						exponent = true;
					}
					else if (hex.length() >= 128 && hex.matches("[0-9a-f]+"))
					{
						modulus = (LdcInsnNode) insn;
						moduli++;
						bigHexAnywhere++;
					}
				}
				if (moduli > 0 && exponent)
				{
					if (moduli != 1)
					{
						fail("rsa: " + cn.name + "." + mn.name + " builds " + moduli + " large hex BigIntegers; expected 1");
					}
					RsaSite s = new RsaSite();
					s.owner = cn;
					s.method = mn;
					s.ldc = modulus;
					found.add(s);
				}
			}
		}
		if (found.size() != 1)
		{
			fail("rsa: expected exactly 1 method building BigInteger(\"10001\",16) and a hex modulus, found "
				+ found.size() + " (large hex BigIntegers anywhere: " + bigHexAnywhere + ")");
		}
		if (bigHexAnywhere != 1)
		{
			fail("rsa: expected exactly 1 large hex BigInteger in the jar, found " + bigHexAnywhere);
		}
		return found.get(0);
	}

	/** If insn is the LDC of {@code new BigInteger(LDC, 16)}, returns the string; else null. */
	private static String bigIntegerHexArg(AbstractInsnNode insn)
	{
		if (!(insn instanceof LdcInsnNode) || !(((LdcInsnNode) insn).cst instanceof String))
		{
			return null;
		}
		AbstractInsnNode radix = next(insn);
		AbstractInsnNode init = next(radix);
		AbstractInsnNode dup = prev(insn);
		AbstractInsnNode neu = prev(dup);
		if (radix instanceof IntInsnNode && radix.getOpcode() == Opcodes.BIPUSH && ((IntInsnNode) radix).operand == 16
			&& init instanceof MethodInsnNode && ((MethodInsnNode) init).owner.equals(BIGINTEGER)
			&& ((MethodInsnNode) init).name.equals("<init>") && ((MethodInsnNode) init).desc.equals("(Ljava/lang/String;I)V")
			&& dup != null && dup.getOpcode() == Opcodes.DUP
			&& neu instanceof TypeInsnNode && neu.getOpcode() == Opcodes.NEW && ((TypeInsnNode) neu).desc.equals(BIGINTEGER))
		{
			return (String) ((LdcInsnNode) insn).cst;
		}
		return null;
	}

	private static final class HostSite
	{
		ClassNode owner;
		MethodNode method;
		List<String> callers = new ArrayList<>();
		List<String> deadCopies = new ArrayList<>();
	}

	/**
	 * Finds the methods that return boolean and contain the constants "invalidhost", "192.168.1." and
	 * ".runescape.com" (Jagex's codebase host whitelist). Exactly one of them must be called from somewhere, and
	 * client.init must be one of its callers (in 1.13.3: to.ahl(B)Z, called from client.init, client.aba and client.abu;
	 * to.ajp and to.aju are uncalled copies). Its body is replaced, so every caller gets "allowed".
	 */
	private static HostSite findHostCheck(Map<String, ClassNode> classes)
	{
		List<ClassNode> owners = new ArrayList<>();
		List<MethodNode> checks = new ArrayList<>();
		for (ClassNode cn : classes.values())
		{
			for (MethodNode mn : cn.methods)
			{
				if (Type.getReturnType(mn.desc) != Type.BOOLEAN_TYPE)
				{
					continue;
				}
				boolean err = false, lan = false, rs = false;
				for (AbstractInsnNode insn : mn.instructions)
				{
					if (insn instanceof LdcInsnNode)
					{
						Object c = ((LdcInsnNode) insn).cst;
						err |= HOST_ERROR.equals(c);
						lan |= "192.168.1.".equals(c);
						rs |= ".runescape.com".equals(c);
					}
				}
				if (err && lan && rs)
				{
					owners.add(cn);
					checks.add(mn);
				}
			}
		}
		if (checks.isEmpty())
		{
			fail("host: no host whitelist method found (constants \"invalidhost\", \"192.168.1.\", \".runescape.com\")");
		}

		// who calls each candidate (by name+desc, on the owner or any subclass of it)
		HostSite site = null;
		List<String> dead = new ArrayList<>();
		for (int i = 0; i < checks.size(); i++)
		{
			ClassNode owner = owners.get(i);
			MethodNode check = checks.get(i);
			List<String> callers = new ArrayList<>();
			for (ClassNode cn : classes.values())
			{
				for (MethodNode mn : cn.methods)
				{
					for (AbstractInsnNode insn : mn.instructions)
					{
						if (insn instanceof MethodInsnNode)
						{
							MethodInsnNode call = (MethodInsnNode) insn;
							if (call.name.equals(check.name) && call.desc.equals(check.desc)
								&& isSubclass(classes, call.owner, owner.name))
							{
								callers.add(cn.name + "." + mn.name);
							}
						}
					}
				}
			}
			String id = owner.name + "." + check.name + check.desc;
			if (callers.isEmpty())
			{
				dead.add(id);
				continue;
			}
			if (site != null)
			{
				fail("host: more than one called host whitelist method: " + site.owner.name + "." + site.method.name + " and " + id);
			}
			site = new HostSite();
			site.owner = owner;
			site.method = check;
			site.callers = callers;
		}
		if (site == null)
		{
			fail("host: host whitelist methods found but none is called: " + dead);
		}
		boolean fromInit = false;
		for (String c : site.callers)
		{
			fromInit |= c.equals("client.init");
		}
		if (!fromInit)
		{
			fail("host: the called host whitelist method is not called from client.init(): " + site.callers);
		}
		site.deadCopies = dead;
		return site;
	}

	private static boolean isSubclass(Map<String, ClassNode> classes, String cls, String ancestor)
	{
		for (String c = cls; c != null; )
		{
			if (c.equals(ancestor))
			{
				return true;
			}
			ClassNode cn = classes.get(c);
			c = cn == null ? null : cn.superName;
		}
		return false;
	}

	private static MethodNode findMethod(ClassNode cn, String name, String desc)
	{
		for (MethodNode mn : cn.methods)
		{
			if (mn.name.equals(name) && mn.desc.equals(desc))
			{
				return mn;
			}
		}
		return null;
	}

	private static AbstractInsnNode next(AbstractInsnNode n)
	{
		if (n == null)
		{
			return null;
		}
		n = n.getNext();
		while (n != null && n.getOpcode() < 0)
		{
			n = n.getNext();
		}
		return n;
	}

	private static AbstractInsnNode prev(AbstractInsnNode n)
	{
		if (n == null)
		{
			return null;
		}
		n = n.getPrevious();
		while (n != null && n.getOpcode() < 0)
		{
			n = n.getPrevious();
		}
		return n;
	}

	private static byte[] write(ClassNode cn)
	{
		// No COMPUTE_FRAMES: the RSA change swaps one constant and the host change has no branches, so the existing
		// max stack / frames stay valid and no class hierarchy lookups are needed.
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------------------------------------------------
	// test key

	private static void genkey(Path dir, boolean force) throws Exception
	{
		Path priv = dir.resolve("private.key");
		if (Files.exists(priv) && !force)
		{
			fail(priv + " already exists; pass --force to replace it (the client must then be re-patched)");
		}
		Files.createDirectories(dir);
		KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
		gen.initialize(new RSAKeyGenParameterSpec(1024, RSAKeyGenParameterSpec.F4));
		KeyPair kp = gen.generateKeyPair();
		RSAPrivateCrtKey pk = (RSAPrivateCrtKey) kp.getPrivate();
		RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
		if (pub.getModulus().bitLength() != 1024)
		{
			fail("generated modulus is not 1024 bits");
		}

		String pem = "-----BEGIN PRIVATE KEY-----\n"
			+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pk.getEncoded())
			+ "\n-----END PRIVATE KEY-----\n";
		writeSecret(priv, pem);
		Files.write(dir.resolve("public-modulus.hex"), (pub.getModulus().toString(16) + "\n").getBytes(StandardCharsets.US_ASCII));
		String yml = "# GadScape rev241 TEST key, same values as private.key, in the worlds.yml format the old server reads\n"
			+ "# (com.gadscape.crypto.Rsa.init: base 36). For the TEST server only.\n"
			+ "rsaPrivateExponent: \"" + pk.getPrivateExponent().toString(36) + "\"\n"
			+ "rsaModulus: \"" + pk.getModulus().toString(36) + "\"\n";
		writeSecret(dir.resolve("worlds-test-rsa.yml"), yml);
		String readme = "GadScape rev241 TEST RSA key pair (1024-bit, public exponent 65537), made by client241/patcher genkey.\n"
			+ "For the TEST server only. Never use it on the live server, never commit private.key.\n\n"
			+ "private.key          PKCS#8 PEM (\"BEGIN PRIVATE KEY\"). Point the server at it with -Dgadscape.rsa.private=<path>.\n"
			+ "                     Java: strip the PEM lines, Base64-decode, KeyFactory.getInstance(\"RSA\")\n"
			+ "                     .generatePrivate(new PKCS8EncodedKeySpec(der)) -> RSAPrivateKey; rsprot wants\n"
			+ "                     RsaKeyPair(privateExponent, modulus). OpenRune's RsaProvider (BouncyCastle PemReader) reads it too.\n"
			+ "worlds-test-rsa.yml  the same key as rsaPrivateExponent/rsaModulus in base 36 (old server worlds.yml format).\n"
			+ "public-modulus.hex   the public modulus in hex; client241/patcher writes it into the client.\n";
		Files.write(dir.resolve("README.txt"), readme.getBytes(StandardCharsets.US_ASCII));
		System.out.println("wrote " + priv + ", public-modulus.hex, worlds-test-rsa.yml, README.txt in " + dir);
		System.out.println("public modulus " + abbrev(pub.getModulus().toString(16)));
	}

	/** Writes a file that holds the private key: owner read/write only (0600) where the file system has POSIX modes. */
	private static void writeSecret(Path f, String text) throws IOException
	{
		Files.deleteIfExists(f);
		try
		{
			Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		}
		catch (UnsupportedOperationException e)
		{
			Files.createFile(f); // Windows: the folder's ACL applies
		}
		Files.write(f, text.getBytes(StandardCharsets.US_ASCII));
	}

	// ------------------------------------------------------------------------------------------------------------
	// util

	private static String abbrev(String hex)
	{
		return hex.length() <= 24 ? hex : hex.substring(0, 12) + "..." + hex.substring(hex.length() - 12) + " (" + hex.length() + " hex digits)";
	}

	static String sha256(byte[] b) throws Exception
	{
		StringBuilder sb = new StringBuilder();
		for (byte x : MessageDigest.getInstance("SHA-256").digest(b))
		{
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}

	static final class PatchException extends RuntimeException
	{
		PatchException(String msg)
		{
			super(msg);
		}
	}

	static void fail(String msg)
	{
		throw new PatchException(msg);
	}

	/** A jar kept in memory with its entries in their original order. */
	static final class Jar
	{
		final List<ZipEntry> entries = new ArrayList<>();
		final Map<String, byte[]> data = new HashMap<>();

		static Jar read(byte[] bytes) throws IOException
		{
			Jar j = new Jar();
			try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(bytes)))
			{
				ZipEntry e;
				while ((e = zin.getNextEntry()) != null)
				{
					if (j.data.containsKey(e.getName()))
					{
						throw new IOException("duplicate jar entry " + e.getName());
					}
					j.entries.add(e);
					j.data.put(e.getName(), readAll(zin));
				}
			}
			return j;
		}

		/**
		 * Drops a jar signature: META-INF/*.SF, *.RSA, *.DSA, *.EC go, MANIFEST.MF keeps only its main section (the
		 * per-entry digests go). Returns the number of signature files removed.
		 */
		int unsign()
		{
			int removed = 0;
			for (java.util.Iterator<ZipEntry> it = entries.iterator(); it.hasNext(); )
			{
				String n = it.next().getName();
				String u = n.toUpperCase(Locale.ROOT);
				if (u.startsWith("META-INF/") && u.indexOf('/', 9) < 0
					&& (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC")))
				{
					it.remove();
					data.remove(n);
					removed++;
				}
			}
			byte[] mf = data.get("META-INF/MANIFEST.MF");
			if (mf != null)
			{
				String text = new String(mf, StandardCharsets.UTF_8).replace("\r\n", "\n");
				int end = text.indexOf("\n\n");
				String main = end < 0 ? text : text.substring(0, end + 1);
				data.put("META-INF/MANIFEST.MF", (main.endsWith("\n") ? main : main + "\n").concat("\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
			}
			return removed;
		}

		Map<String, ClassNode> classes()
		{
			Map<String, ClassNode> m = new HashMap<>();
			for (ZipEntry e : entries)
			{
				if (e.getName().endsWith(".class"))
				{
					ClassNode cn = new ClassNode();
					new ClassReader(data.get(e.getName())).accept(cn, 0);
					m.put(cn.name, cn);
				}
			}
			return m;
		}

		byte[] write(Map<String, byte[]> replaced, Map<String, byte[]> added) throws IOException
		{
			for (String a : added.keySet())
			{
				if (data.containsKey(a))
				{
					throw new IOException("jar already has " + a + " (already patched?)");
				}
			}
			for (String k : replaced.keySet())
			{
				if (!data.containsKey(k))
				{
					throw new IOException("no entry " + k);
				}
			}
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			try (ZipOutputStream zout = new ZipOutputStream(bos))
			{
				for (ZipEntry e : entries)
				{
					ZipEntry ne = new ZipEntry(e.getName());
					ne.setTime(e.getTime());
					zout.putNextEntry(ne);
					byte[] b = replaced.getOrDefault(e.getName(), data.get(e.getName()));
					zout.write(b);
					zout.closeEntry();
				}
				for (Map.Entry<String, byte[]> a : added.entrySet())
				{
					ZipEntry add = new ZipEntry(a.getKey());
					add.setTime(entries.get(0).getTime());
					zout.putNextEntry(add);
					zout.write(a.getValue());
					zout.closeEntry();
				}
			}
			return bos.toByteArray();
		}

		static byte[] readAll(InputStream in) throws IOException
		{
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0)
			{
				bos.write(buf, 0, n);
			}
			return bos.toByteArray();
		}
	}
}
