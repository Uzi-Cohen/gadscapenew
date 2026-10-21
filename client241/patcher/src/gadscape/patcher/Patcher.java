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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * GadScape patcher for RuneLite's injected-client (the OSRS game client RuneLite ships).
 *
 * <p>It makes exactly two changes, and fails if either one does not match exactly once:
 * <ol>
 *   <li><b>RSA modulus.</b> The client encrypts the login block with {@code new BigInteger("<hex>", 16)} built in a
 *   static initializer next to {@code new BigInteger("10001", 16)}. The hex string is replaced with ours.</li>
 *   <li><b>Host check.</b> The applet's init() calls a check that only allows a codebase host of jagex.com,
 *   *.runescape.com, 127.0.0.1 or 192.168.1.x and otherwise stops with the error "invalidhost". The one copy of
 *   that check that is actually called is rewritten to {@code return true}, so any host from jav_config works.
 *   (The obfuscated client also carries uncalled duplicate copies; those are left alone and reported.)</li>
 * </ol>
 * Nothing else in the jar changes: every other entry is copied byte for byte.
 *
 * <p>Usage:
 * <pre>
 *   patch   --in injected-client-1.13.3.jar --out injected-client-1.13.3-gadscape.jar
 *           --modulus (hex | @file-with-hex) [--expect-sha256 hex]
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
			+ "  patch   --in <injected-client.jar> --out <patched.jar> --modulus <hex|@file> [--expect-sha256 <hex>]\n"
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

		Map<String, byte[]> changed = new LinkedHashMap<>();
		changed.put(rsa.owner.name + ".class", write(rsa.owner));
		if (host.owner != rsa.owner)
		{
			changed.put(host.owner.name + ".class", write(host.owner));
		}
		String marker = "# Written by client241/patcher. The GadScape launcher refuses an injected-client without this file.\n"
			+ "source.sha256=" + inSha + "\n"
			+ "rsa.class=" + rsa.owner.name + "\n"
			+ "rsa.modulus=" + newHex + "\n"
			+ "host.check=" + host.owner.name + "." + m.name + m.desc + "\n";
		byte[] outBytes = jar.write(changed, MARKER, marker.getBytes(StandardCharsets.US_ASCII));

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

		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		Files.write(tmp, outBytes);
		Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		System.out.println("output " + out + " sha256 " + sha256(outBytes) + " (" + changed.size() + " classes changed: "
			+ String.join(", ", changed.keySet()) + ")");
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
	}

	// ------------------------------------------------------------------------------------------------------------
	// matchers

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

		byte[] write(Map<String, byte[]> replaced, String addName, byte[] addData) throws IOException
		{
			if (data.containsKey(addName))
			{
				throw new IOException("jar already has " + addName + " (already patched?)");
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
				ZipEntry add = new ZipEntry(addName);
				add.setTime(entries.get(0).getTime());
				zout.putNextEntry(add);
				zout.write(addData);
				zout.closeEntry();
			}
			return bos.toByteArray();
		}

		private static byte[] readAll(InputStream in) throws IOException
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
