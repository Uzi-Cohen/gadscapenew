import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

/**
 * A tiny stand-in server for testing the GadScape 241 client without the real game server. Development only.
 *
 * <p>It serves the vanilla 241 cache over JS5 (so the client reaches its login screen) and answers a login attempt
 * in one of two ways:
 * <ul>
 *   <li>{@code --login probe} (default): accepts the login connection, decrypts the login block's RSA part with the
 *   TEST private key and checks it (first byte 1, session id = the one this server sent), prints the username, then
 *   answers "invalid username or password" (response 3). This proves the client's patched RSA modulus matches the key.</li>
 *   <li>{@code --login refuse}: stops listening as soon as the client's JS5 connection is up, so the login connection is
 *   refused, the same as logging in with no server running.</li>
 * </ul>
 *
 * <p>Run (Java 11+, no dependencies):
 * {@code java client241/devserver/TestServer.java --cache <cache dir> --rsa <private.key> [--port 43594] [--login probe|refuse]}
 *
 * <p>Formats follow blurite/rsprot osrs-241 (MIT): JS5 handshake opcode 15 + revision + 4 ints, requests of 4 bytes,
 * responses split in 512-byte blocks with 0xFF markers; login opcode 14 then 16 (GAMELOGIN) with the RSA block.
 */
public final class TestServer
{
	static final int REVISION = 241;
	static Cache cache;
	static RSAPrivateKey key;
	static String loginMode = "probe";
	static ServerSocket server;
	static final AtomicBoolean js5Up = new AtomicBoolean();

	public static void main(String[] args) throws Exception
	{
		String cacheDir = null, rsa = null;
		int port = 43594;
		for (int i = 0; i < args.length; i++)
		{
			switch (args[i])
			{
				case "--cache": cacheDir = args[++i]; break;
				case "--rsa": rsa = args[++i]; break;
				case "--port": port = Integer.parseInt(args[++i]); break;
				case "--login": loginMode = args[++i]; break;
				default: throw new IllegalArgumentException(args[i]);
			}
		}
		cache = new Cache(Paths.get(cacheDir));
		if (rsa != null)
		{
			String pem = new String(Files.readAllBytes(Paths.get(rsa)), StandardCharsets.US_ASCII)
				.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
			key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
		}
		server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
		log("listening on 127.0.0.1:" + port + ", cache " + cacheDir + " (" + cache.indexCount + " archives), login mode " + loginMode);
		while (true)
		{
			Socket s;
			try
			{
				s = server.accept();
			}
			catch (IOException e)
			{
				log("stopped listening (" + e.getMessage() + "); open connections keep running");
				Thread.currentThread().join();
				return;
			}
			new Thread(() -> handle(s), "conn-" + s.getPort()).start();
		}
	}

	static void handle(Socket s)
	{
		try (s)
		{
			s.setTcpNoDelay(true);
			DataInputStream in = new DataInputStream(s.getInputStream());
			OutputStream out = s.getOutputStream();
			int op = in.readUnsignedByte();
			if (op == 15)
			{
				js5(s, in, out);
			}
			else if (op == 14)
			{
				login(in, out);
			}
			else
			{
				log("unknown first opcode " + op + " from " + s.getRemoteSocketAddress());
			}
		}
		catch (EOFException e)
		{
			log("connection closed by client");
		}
		catch (Exception e)
		{
			log("connection error: " + e);
		}
	}

	// ---------------------------------------------------------------------------------------------------------- js5

	static void js5(Socket s, DataInputStream in, OutputStream out) throws Exception
	{
		int rev = in.readInt();
		for (int i = 0; i < 4; i++)
		{
			in.readInt();
		}
		if (rev != REVISION)
		{
			log("js5: client revision " + rev + ", expected " + REVISION + ": out of date");
			out.write(6);
			return;
		}
		out.write(0);
		out.flush();
		log("js5: connected, revision " + rev);
		if (js5Up.compareAndSet(false, true) && loginMode.equals("refuse"))
		{
			server.close(); // nothing listens any more: the login connection will be refused
		}
		LinkedBlockingDeque<Integer> queue = new LinkedBlockingDeque<>();
		int[] xor = {0};
		Thread reader = new Thread(() -> {
			try
			{
				while (true)
				{
					int op = in.readUnsignedByte();
					int archive = in.readUnsignedByte();
					int group = in.readUnsignedShort();
					if (op == 0 || op == 1)
					{
						int req = (archive << 16) | group;
						if (op == 1)
						{
							queue.addFirst(req);
						}
						else
						{
							queue.addLast(req);
						}
					}
					else if (op == 4)
					{
						xor[0] = archive;
					}
				}
			}
			catch (IOException e)
			{
				queue.addFirst(-1);
			}
		}, "js5-read");
		reader.setDaemon(true);
		reader.start();
		long served = 0, bytes = 0;
		while (true)
		{
			int req = queue.take();
			if (req < 0)
			{
				log("js5: closed after " + served + " groups, " + bytes / 1024 + " KiB");
				return;
			}
			int archive = req >>> 16, group = req & 0xFFFF;
			byte[] container = archive == 255 && group == 255 ? cache.masterIndex : cache.container(archive, group);
			if (container == null)
			{
				log("js5: missing group " + archive + "/" + group);
				continue;
			}
			byte[] resp = js5Response(archive, group, container);
			if (xor[0] != 0)
			{
				for (int i = 0; i < resp.length; i++)
				{
					resp[i] ^= xor[0];
				}
			}
			out.write(resp);
			if (queue.isEmpty())
			{
				out.flush();
			}
			served++;
			bytes += resp.length;
			if (served % 2000 == 0)
			{
				log("js5: " + served + " groups served (" + bytes / 1024 / 1024 + " MiB)");
			}
		}
	}

	/** archive u8, group u16, then the container (compression u8, length i32, ...) with a 0xFF before every 511 bytes after the first 512. */
	static byte[] js5Response(int archive, int group, byte[] container)
	{
		ByteArrayOutputStream b = new ByteArrayOutputStream(container.length + 16 + container.length / 511);
		b.write(archive);
		b.write(group >> 8);
		b.write(group);
		int written = 3;
		for (byte x : container)
		{
			if (written == 512)
			{
				b.write(0xFF);
				written = 1;
			}
			b.write(x);
			written++;
		}
		return b.toByteArray();
	}

	// -------------------------------------------------------------------------------------------------------- login

	static void login(DataInputStream in, OutputStream out) throws Exception
	{
		long sessionKey = new SecureRandom().nextLong();
		DataOutputStream d = new DataOutputStream(out);
		d.write(0);
		d.writeLong(sessionKey);
		d.flush();
		int op = in.readUnsignedByte();
		int size = in.readUnsignedShort();
		byte[] payload = new byte[size];
		in.readFully(payload);
		log("login: opcode " + op + ", " + size + " bytes");
		DataInputStream p = new DataInputStream(new ByteArrayInputStream(payload));
		int version = p.readInt();
		int subVersion = p.readInt();
		int serverVersion = p.readInt();
		int clientType = p.readUnsignedByte();
		int platform = p.readUnsignedByte();
		int extAuth = p.readUnsignedByte();
		int rsaSize = p.readUnsignedShort();
		byte[] rsaBlock = new byte[rsaSize];
		p.readFully(rsaBlock);
		log("login: revision " + version + " sub " + subVersion + " server " + serverVersion + " clientType " + clientType
			+ " platform " + platform + " extAuth " + extAuth + ", RSA block " + rsaSize + " bytes");
		if (key == null)
		{
			log("login: no --rsa key given, cannot check the RSA block");
			out.write(3);
			return;
		}
		byte[] plain = new BigInteger(rsaBlock).modPow(key.getPrivateExponent(), key.getModulus()).toByteArray();
		DataInputStream r = new DataInputStream(new ByteArrayInputStream(plain));
		int magic = r.readUnsignedByte();
		if (magic != 1)
		{
			log("login: RSA CHECK FAILED: first byte " + magic + " (the client's modulus does not match this key)");
			out.write(10);
			return;
		}
		int[] seed = {r.readInt(), r.readInt(), r.readInt(), r.readInt()};
		long session = r.readLong();
		log("login: RSA OK (magic 1), session id " + (session == sessionKey ? "matches" : "DOES NOT MATCH") + " the one sent");
		// rest of the RSA block: otp type + 4 bytes, auth type, password: not printed.
		byte[] rest = new byte[p.available()];
		p.readFully(rest);
		byte[] dec = xteaDecrypt(rest, seed);
		int end = 0;
		while (end < dec.length && dec[end] != 0)
		{
			end++;
		}
		log("login: username '" + new String(dec, 0, end, StandardCharsets.ISO_8859_1) + "' (XTEA part decrypted with the seed from the RSA block)");
		out.write(3); // "invalid username or password": this test server has no accounts
		out.flush();
		Thread.sleep(500);
	}

	static byte[] xteaDecrypt(byte[] data, int[] key)
	{
		byte[] out = data.clone();
		int blocks = data.length / 8;
		for (int b = 0; b < blocks; b++)
		{
			int o = b * 8;
			int v0 = getInt(out, o), v1 = getInt(out, o + 4);
			int delta = 0x9E3779B9;
			int sum = delta * 32;
			for (int i = 0; i < 32; i++)
			{
				v1 -= (((v0 << 4) ^ (v0 >>> 5)) + v0) ^ (sum + key[(sum >>> 11) & 3]);
				sum -= delta;
				v0 -= (((v1 << 4) ^ (v1 >>> 5)) + v1) ^ (sum + key[sum & 3]);
			}
			putInt(out, o, v0);
			putInt(out, o + 4, v1);
		}
		return out;
	}

	static int getInt(byte[] b, int o)
	{
		return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
	}

	static void putInt(byte[] b, int o, int v)
	{
		b[o] = (byte) (v >> 24);
		b[o + 1] = (byte) (v >> 16);
		b[o + 2] = (byte) (v >> 8);
		b[o + 3] = (byte) v;
	}

	static void log(String s)
	{
		System.out.println("[testserver] " + s);
	}

	// -------------------------------------------------------------------------------------------------------- cache

	/** Read-only Jagex disk cache: main_file_cache.dat2 (520-byte sectors) + idxN (6-byte entries). */
	static final class Cache
	{
		final RandomAccessFile dat;
		final RandomAccessFile[] idx = new RandomAccessFile[256];
		final int indexCount;
		final byte[] masterIndex;

		Cache(Path dir) throws IOException
		{
			dat = new RandomAccessFile(dir.resolve("main_file_cache.dat2").toFile(), "r");
			for (int i = 0; i < 256; i++)
			{
				Path p = dir.resolve("main_file_cache.idx" + i);
				if (Files.exists(p))
				{
					idx[i] = new RandomAccessFile(p.toFile(), "r");
				}
			}
			indexCount = (int) (idx[255].length() / 6);
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			DataOutputStream d = new DataOutputStream(b);
			d.write(0);
			d.writeInt(indexCount * 8);
			for (int i = 0; i < indexCount; i++)
			{
				byte[] c = container(255, i);
				if (c == null)
				{
					d.writeInt(0);
					d.writeInt(0);
					continue;
				}
				CRC32 crc = new CRC32();
				crc.update(c);
				d.writeInt((int) crc.getValue());
				d.writeInt(referenceTableVersion(c));
			}
			d.flush();
			masterIndex = b.toByteArray();
		}

		static int referenceTableVersion(byte[] c) throws IOException
		{
			int compression = c[0] & 0xFF;
			int len = getInt(c, 1);
			byte[] data;
			if (compression == 0)
			{
				data = java.util.Arrays.copyOfRange(c, 5, 5 + len);
			}
			else if (compression == 2)
			{
				try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(c, 9, len)))
				{
					data = gz.readAllBytes();
				}
			}
			else
			{
				return 0; // bzip2 reference tables are not used by OSRS
			}
			int protocol = data[0] & 0xFF;
			return protocol >= 6 ? getInt(data, 1) : 0;
		}

		/** The group's container bytes without the 2-byte version trailer, or null if absent. */
		synchronized byte[] container(int archive, int group) throws IOException
		{
			byte[] raw = read(archive, group);
			if (raw == null || raw.length < 5)
			{
				return null;
			}
			int compression = raw[0] & 0xFF;
			int len = getInt(raw, 1);
			int total = 5 + (compression != 0 ? 4 : 0) + len;
			if (total > raw.length)
			{
				throw new IOException("group " + archive + "/" + group + " is truncated");
			}
			return java.util.Arrays.copyOf(raw, total);
		}

		byte[] read(int archive, int group) throws IOException
		{
			RandomAccessFile ix = idx[archive];
			if (ix == null || (long) (group + 1) * 6 > ix.length())
			{
				return null;
			}
			byte[] e = new byte[6];
			ix.seek(group * 6L);
			ix.readFully(e);
			int size = ((e[0] & 0xFF) << 16) | ((e[1] & 0xFF) << 8) | (e[2] & 0xFF);
			int sector = ((e[3] & 0xFF) << 16) | ((e[4] & 0xFF) << 8) | (e[5] & 0xFF);
			if (size <= 0 || sector <= 0)
			{
				return null;
			}
			boolean ext = group > 0xFFFF;
			int header = ext ? 10 : 8, chunkData = 520 - header;
			byte[] out = new byte[size];
			byte[] buf = new byte[520];
			int pos = 0, chunk = 0;
			while (pos < size)
			{
				if (sector == 0)
				{
					throw new IOException("group " + archive + "/" + group + ": sector chain ends early");
				}
				int n = Math.min(chunkData, size - pos);
				dat.seek(sector * 520L);
				dat.readFully(buf, 0, header + n);
				int g = ext ? getInt(buf, 0) : ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
				int o = ext ? 4 : 2;
				int c = ((buf[o] & 0xFF) << 8) | (buf[o + 1] & 0xFF);
				int next = ((buf[o + 2] & 0xFF) << 16) | ((buf[o + 3] & 0xFF) << 8) | (buf[o + 4] & 0xFF);
				int a = buf[o + 5] & 0xFF;
				if (g != group || c != chunk || a != archive)
				{
					throw new IOException("group " + archive + "/" + group + ": bad sector " + sector);
				}
				System.arraycopy(buf, header, out, pos, n);
				pos += n;
				chunk++;
				sector = next;
			}
			return out;
		}
	}
}
