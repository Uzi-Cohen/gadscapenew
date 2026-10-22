package gadscape.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The build channel, baked into the launcher jar by client241/build.sh as gadscape/launcher/channel.properties.
 *
 * <ul>
 *   <li><b>release</b> (build.sh default): window title "GadScape", world "GadScape", default server = the live
 *   server, home %LOCALAPPDATA%\GadScape or ~/.gadscape-client.</li>
 *   <li><b>test</b> (build.sh --test): "GadScape (test)", world "GadScape 241 Test", default server 127.0.0.1,
 *   home %LOCALAPPDATA%\GadScape241Test or ~/.gadscape-241-test (the folders the TEST client always used), so the
 *   two can be installed side by side.</li>
 * </ul>
 * A launcher jar without channel.properties (built by hand) is a test build.
 */
final class Channel
{
	final String name;
	final String title;
	final String activity;
	final String defaultHost;
	final String windowsDir;
	final String unixDir;

	private Channel(Properties p)
	{
		boolean release = "release".equals(p.getProperty("channel"));
		name = release ? "release" : "test";
		title = p.getProperty("title", release ? "GadScape" : "GadScape (test)");
		activity = p.getProperty("activity", release ? "GadScape" : "GadScape 241 Test");
		defaultHost = p.getProperty("default.host", "127.0.0.1");
		windowsDir = p.getProperty("home.windows", release ? "GadScape" : "GadScape241Test");
		unixDir = p.getProperty("home.unix", release ? ".gadscape-client" : ".gadscape-241-test");
	}

	static Channel load()
	{
		Properties p = new Properties();
		try (InputStream in = Channel.class.getResourceAsStream("channel.properties"))
		{
			if (in != null)
			{
				p.load(in);
			}
		}
		catch (IOException ignored)
		{
			// a test build
		}
		return new Channel(p);
	}
}
