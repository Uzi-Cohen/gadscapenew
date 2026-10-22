package net.runelite.client.callback;

import java.awt.Graphics2D;
import java.util.function.Consumer;

/**
 * Added to RuneLite's client jar by the GadScape patcher ({@code client241/patcher}, command {@code brand}).
 * RuneLite's {@code Hooks.draw} calls {@link #paint} once per frame, on every game state (the login screen too),
 * right after it gets the frame's Graphics2D and before RuneLite's own always-on-top overlays. Stock RuneLite has no
 * way to draw on the login screen (overlays only render when logged in); the GadScape Login plugin registers its
 * painter here with {@link #set}.
 */
public final class GadScapePaintHook
{
	private static volatile Consumer<Graphics2D> painter;
	private static volatile boolean reported;

	private GadScapePaintHook()
	{
	}

	/** Sets (or with null, removes) the painter. */
	public static void set(Consumer<Graphics2D> p)
	{
		painter = p;
		reported = false;
	}

	public static boolean isSet()
	{
		return painter != null;
	}

	/** Called by Hooks.draw. The painter gets its own copy of the Graphics, so its settings never leak. */
	public static void paint(Graphics2D g)
	{
		Consumer<Graphics2D> p = painter;
		if (p == null || g == null)
		{
			return;
		}
		Graphics2D copy = (Graphics2D) g.create();
		try
		{
			p.accept(copy);
		}
		catch (Throwable t)
		{
			if (!reported)
			{
				reported = true;
				System.err.println("[gadscape] login screen painter failed: " + t);
				t.printStackTrace();
			}
		}
		finally
		{
			copy.dispose();
		}
	}
}
