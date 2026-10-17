package net.runelite.client.plugins.gadscapelogin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Accounts saved with "Remember me", kept in the client's settings folder (gadscape-accounts.properties).
 * Passwords are encrypted (AES-GCM) with a key tied to this PC and Windows user, so the file is no use
 * copied to another machine, and nothing is ever stored as plain text.
 */
final class SavedAccounts
{
	static final class Account
	{
		final String username;
		final String password;

		Account(String username, String password)
		{
			this.username = username;
			this.password = password;
		}
	}

	static final int MAX = 10;

	private final File file;
	private final List<Account> accounts = new ArrayList<>();
	private boolean remember = true;
	private String lastUsername = "";

	SavedAccounts(File dir)
	{
		this.file = new File(dir, "gadscape-accounts.properties");
		load();
	}

	synchronized List<Account> list()
	{
		return new ArrayList<>(accounts);
	}

	synchronized boolean isRemember()
	{
		return remember;
	}

	synchronized String getLastUsername()
	{
		return lastUsername;
	}

	synchronized void setRemember(boolean value)
	{
		remember = value;
		save();
	}

	/** Saves (or moves to the front) an account after it logged in successfully. */
	synchronized void put(String username, String password)
	{
		accounts.removeIf(a -> a.username.equalsIgnoreCase(username));
		accounts.add(0, new Account(username, password));
		while (accounts.size() > MAX)
		{
			accounts.remove(accounts.size() - 1);
		}
		lastUsername = username;
		save();
	}

	synchronized void setLastUsername(String username)
	{
		lastUsername = username;
		save();
	}

	synchronized void remove(String username)
	{
		accounts.removeIf(a -> a.username.equalsIgnoreCase(username));
		save();
	}

	// ---- file ----

	private void load()
	{
		if (!file.isFile())
		{
			return;
		}
		try
		{
			Properties p = new Properties();
			try (java.io.Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
			{
				p.load(r);
			}
			remember = !"false".equals(p.getProperty("remember"));
			lastUsername = p.getProperty("last", "");
			byte[] salt = Base64.getDecoder().decode(p.getProperty("salt", ""));
			if (salt.length == 0)
			{
				return;
			}
			SecretKeySpec key = key(salt);
			for (int i = 0; i < MAX; i++)
			{
				String user = p.getProperty("account." + i + ".user");
				String enc = p.getProperty("account." + i + ".pass");
				if (user == null || enc == null)
				{
					continue;
				}
				String pass = decrypt(key, enc);
				if (pass != null)
				{
					accounts.add(new Account(user, pass));
				}
			}
		}
		catch (Exception e)
		{
			// unreadable file: start empty rather than break the login screen
			accounts.clear();
		}
	}

	private void save()
	{
		try
		{
			Properties p = new Properties();
			byte[] salt = saltFromFileOrNew();
			SecretKeySpec key = key(salt);
			p.setProperty("salt", Base64.getEncoder().encodeToString(salt));
			p.setProperty("remember", Boolean.toString(remember));
			p.setProperty("last", lastUsername == null ? "" : lastUsername);
			for (int i = 0; i < accounts.size(); i++)
			{
				p.setProperty("account." + i + ".user", accounts.get(i).username);
				p.setProperty("account." + i + ".pass", encrypt(key, accounts.get(i).password));
			}
			file.getParentFile().mkdirs();
			File tmp = new File(file.getPath() + ".tmp");
			try (java.io.Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8))
			{
				p.store(w, "GadScape saved accounts. Passwords are encrypted for this PC only.");
			}
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (Exception ignored)
		{
			// saving is best effort; logging in never depends on it
		}
	}

	private byte[] savedSalt;

	private byte[] saltFromFileOrNew() throws Exception
	{
		if (savedSalt != null)
		{
			return savedSalt;
		}
		if (file.isFile())
		{
			Properties p = new Properties();
			try (java.io.Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
			{
				p.load(r);
			}
			byte[] s = Base64.getDecoder().decode(p.getProperty("salt", ""));
			if (s.length == 16)
			{
				return savedSalt = s;
			}
		}
		byte[] s = new byte[16];
		new SecureRandom().nextBytes(s);
		return savedSalt = s;
	}

	private static SecretKeySpec key(byte[] salt) throws Exception
	{
		String machine = System.getProperty("user.name", "") + "|" + System.getenv().getOrDefault("COMPUTERNAME", "")
			+ "|" + System.getProperty("os.name", "") + "|" + System.getProperty("user.home", "");
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(salt);
		md.update(machine.getBytes(StandardCharsets.UTF_8));
		return new SecretKeySpec(md.digest(), "AES");
	}

	private static String encrypt(SecretKeySpec key, String text) throws Exception
	{
		byte[] iv = new byte[12];
		new SecureRandom().nextBytes(iv);
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
		byte[] out = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
		byte[] all = new byte[iv.length + out.length];
		System.arraycopy(iv, 0, all, 0, iv.length);
		System.arraycopy(out, 0, all, iv.length, out.length);
		return Base64.getEncoder().encodeToString(all);
	}

	private static String decrypt(SecretKeySpec key, String b64)
	{
		try
		{
			byte[] all = Base64.getDecoder().decode(b64);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
			return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
