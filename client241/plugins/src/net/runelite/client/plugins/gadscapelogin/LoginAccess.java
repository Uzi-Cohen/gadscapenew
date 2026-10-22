package net.runelite.client.plugins.gadscapelogin;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The login screen state that RuneLite 1.13.3's API does not expose: setting the login index (which login screen
 * page the game shows) and reading the game's three login response lines (its error message).
 *
 * <p>client241/patcher finds where the obfuscated game client keeps them and writes that into
 * gadscape-patch.properties in the patched injected-client ({@code login.class}, {@code login.index.field},
 * {@code login.index.multiplier}, {@code login.response.fields}). The game stores the index multiplied by an odd
 * constant: {@code getLoginIndex() = stored * multiplier}, so setting it stores {@code index * multiplier^-1}
 * (mod 2^32). Anything missing or not as expected: {@link #load} returns null and the plugin leaves the game's own
 * login screen alone.
 */
final class LoginAccess
{
	private static final Logger log = LoggerFactory.getLogger(LoginAccess.class);
	static final String MARKER = "gadscape-patch.properties";

	private final Field index;
	private final int multiplier;
	private final int inverse;
	private final Field[] responses;

	private LoginAccess(Field index, int multiplier, Field[] responses)
	{
		this.index = index;
		this.multiplier = multiplier;
		this.inverse = inverse(multiplier);
		this.responses = responses;
	}

	/** Inverse of an odd number modulo 2^32 (Newton's iteration doubles the correct bits each step). */
	static int inverse(int odd)
	{
		int x = odd;
		for (int i = 0; i < 5; i++)
		{
			x *= 2 - odd * x;
		}
		return x;
	}

	static LoginAccess load(ClassLoader cl)
	{
		try
		{
			URL url = cl.getResource(MARKER);
			if (url == null)
			{
				log.warn("no {} on the classpath: GadScape login screen off", MARKER);
				return null;
			}
			Properties p = new Properties();
			try (InputStream in = url.openStream())
			{
				p.load(in);
			}
			String cls = p.getProperty("login.class");
			String idx = p.getProperty("login.index.field");
			String mult = p.getProperty("login.index.multiplier");
			String resp = p.getProperty("login.response.fields");
			if (cls == null || idx == null || mult == null)
			{
				log.warn("{} has no login.* keys (patched by an older patcher?): GadScape login screen off", MARKER);
				return null;
			}
			Class<?> c = Class.forName(cls, false, cl);
			Field index = staticField(c, idx, int.class);
			int m = Integer.parseInt(mult);
			if ((m & 1) == 0)
			{
				return null;
			}
			Field[] responses = null;
			if (resp != null)
			{
				String[] names = resp.split(",");
				if (names.length == 3)
				{
					responses = new Field[3];
					for (int i = 0; i < 3; i++)
					{
						responses[i] = staticField(c, names[i].trim(), String.class);
					}
				}
			}
			return new LoginAccess(index, m, responses);
		}
		catch (Exception | LinkageError e)
		{
			log.warn("login screen fields not usable: GadScape login screen off", e);
			return null;
		}
	}

	private static Field staticField(Class<?> c, String name, Class<?> type) throws NoSuchFieldException
	{
		Field f = c.getDeclaredField(name);
		if (!Modifier.isStatic(f.getModifiers()) || f.getType() != type)
		{
			throw new NoSuchFieldException(c.getName() + "." + name + " is not a static " + type.getSimpleName());
		}
		f.setAccessible(true);
		return f;
	}

	/** Checks the field against the game's own getter, so a wrong field is never written. */
	boolean matches(int loginIndexFromApi)
	{
		try
		{
			return index.getInt(null) * multiplier == loginIndexFromApi;
		}
		catch (IllegalAccessException e)
		{
			return false;
		}
	}

	void setLoginIndex(int value)
	{
		try
		{
			index.setInt(null, value * inverse);
		}
		catch (IllegalAccessException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** The game's login response lines joined with spaces, or "" when unknown. */
	String loginMessage()
	{
		if (responses == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Field f : responses)
		{
			try
			{
				Object v = f.get(null);
				if (v != null && !v.toString().trim().isEmpty())
				{
					if (sb.length() > 0)
					{
						sb.append(' ');
					}
					sb.append(v.toString().trim());
				}
			}
			catch (IllegalAccessException ignored)
			{
				// leave it out
			}
		}
		return sb.toString();
	}
}
