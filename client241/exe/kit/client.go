package kit

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Client is an installed GadScape client in Dest (%LOCALAPPDATA%\<dirName>): Java in java\, the client in app\,
// RuneLite's home (settings, saved accounts, game cache) in home\. The one-file exe and the setup both use this
// layout, so either can start a client the other installed.
type Client struct {
	AppName string
	Dest    string
	Log     func(format string, a ...any)
}

func (c Client) Home() string  { return filepath.Join(c.Dest, "home") }
func (c Client) App() string   { return filepath.Join(c.Dest, "app") }
func (c Client) Java() string  { return filepath.Join(c.Dest, "java") }
func (c Client) Javaw() string { return filepath.Join(c.Dest, "java", "bin", "javaw.exe") }

// CacheDir is where the game keeps its cache: RuneLite tells the game to use <home>\.runelite\jagexcache.
func (c Client) CacheDir() string {
	return filepath.Join(c.Home(), ".runelite", "jagexcache", "oldschool", "LIVE")
}

func (c Client) logf(format string, a ...any) {
	if c.Log != nil {
		c.Log(format, a...)
	}
}

// JavaArgs is the javaw command line (without javaw itself) that starts the client with the server address file
// hostFile. Windows paths: ';' joins the class path.
func (c Client) JavaArgs(hostFile string) []string {
	app := c.App()
	return []string{
		"@" + filepath.Join(app, "jvm-args.txt"),
		"-Duser.home=" + c.Home(),
		"-cp", filepath.Join(app, "lib", "*") + ";" + filepath.Join(app, "lib-win", "*"),
		"gadscape.launcher.Launcher",
		"--host-file", hostFile,
		"--home", c.Home(),
	}
}

// CleanEnv drops the Jagex Launcher's JX_* variables: the game client would otherwise try a Jagex account login.
func CleanEnv(env []string) []string {
	var out []string
	for _, e := range env {
		if !strings.HasPrefix(strings.ToUpper(e), "JX_") {
			out = append(out, e)
		}
	}
	return out
}

// ServerFileText is the text of a new server address file holding host.
func ServerFileText(appName, host string) string {
	return "# " + appName + " client: the first line that is not a comment is the server address\r\n" +
		"# (a host name or IP, no port: the game always uses port 43594). 127.0.0.1 = this computer.\r\n" +
		host + "\r\n"
}

// CrashReport tells what stopped the client: the end of its output and of RuneLite's log.
func (c Client) CrashReport(err error, ran time.Duration) string {
	msg := fmt.Sprintf("The %s client closed %d seconds after starting", c.AppName, int(ran.Seconds()))
	if err != nil {
		msg += " (" + err.Error() + ")"
	}
	msg += ".\n\nSend this to the GadScape team (Ctrl+C copies this box):\n"
	tail := func(path string, n int) {
		b, e := os.ReadFile(path)
		if e != nil {
			return
		}
		lines := strings.Split(strings.TrimSpace(string(b)), "\n")
		if len(lines) > n {
			lines = lines[len(lines)-n:]
		}
		msg += "\n--- " + filepath.Base(path) + " ---\n" + strings.Join(lines, "\n")
	}
	tail(filepath.Join(c.Dest, "client-output.log"), 20)
	tail(filepath.Join(c.Home(), ".runelite", "logs", "client.log"), 10)
	return msg
}

// Log is a simple log file (truncated when opened).
type Log struct{ f *os.File }

// OpenLog creates (or truncates) the log file at path; a log that cannot be opened just logs nothing.
func OpenLog(path string) *Log { return openLog(path, true) }

// AppendLog opens the log file at path to add to it (for a program started by another that wrote it and has
// closed it). It seeks to the end instead of using O_APPEND, which Wine does not honour.
func AppendLog(path string) *Log { return openLog(path, false) }

func openLog(path string, truncate bool) *Log {
	os.MkdirAll(filepath.Dir(path), 0o755)
	flags := os.O_CREATE | os.O_WRONLY
	if truncate {
		flags |= os.O_TRUNC
	}
	f, err := os.OpenFile(path, flags, 0o644)
	if err != nil {
		return &Log{}
	}
	if !truncate {
		f.Seek(0, io.SeekEnd)
	}
	return &Log{f: f}
}

// Printf writes one time-stamped line.
func (l *Log) Printf(format string, a ...any) {
	if l == nil || l.f == nil {
		return
	}
	fmt.Fprintf(l.f, time.Now().Format("15:04:05.000")+" "+format+"\n", a...)
	l.f.Sync()
}

// Close closes the file.
func (l *Log) Close() {
	if l != nil && l.f != nil {
		l.f.Close()
		l.f = nil
	}
}
