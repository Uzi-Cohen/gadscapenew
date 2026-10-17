package net.runelite.client.callback;

import java.awt.Graphics2D;
import java.util.function.Consumer;

/**
 * Lets a plugin draw on top of the login screen. Overlays only draw while logged in, but {@link Hooks#draw}
 * runs every frame, so it calls this.
 */
public final class LoginScreenPainter
{
	private static volatile Consumer<Graphics2D> painter;

	private LoginScreenPainter()
	{
	}

	public static void set(Consumer<Graphics2D> p)
	{
		painter = p;
	}

	static void paint(Graphics2D g)
	{
		Consumer<Graphics2D> p = painter;
		if (p == null)
		{
			return;
		}
		Graphics2D copy = (Graphics2D) g.create();
		try
		{
			p.accept(copy);
		}
		catch (Exception ignored)
		{
			// never let the login screen take the client down
		}
		finally
		{
			copy.dispose();
		}
	}
}
