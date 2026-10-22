// GadScape-Setup.exe: the small download players start with. It downloads the GadScape client (Java, the client,
// the game cache) as listed in a release manifest signed with GadScape's release key, installs it to
// %LOCALAPPDATA%\<dirName> in the same layout the one-file GadScape.exe uses, makes the "GadScape" shortcuts and the
// Apps & features entry, and starts the client. The copy it leaves in %LOCALAPPDATA%\<dirName>\GadScape.exe is the
// everyday launcher: every start checks the manifest (briefly), updates only what changed, and starts the client;
// without a connection it just starts the installed client.
//
// Built by client241/build.sh --setup (setup/build.sh): the release channel's names, the manifest address and the
// public key are set with -ldflags -X. A setup without a key (or address) refuses to install. In test builds only,
// a GadScape-setup.txt next to the exe may give another manifest address (the key cannot be changed); release
// builds ignore it. See client241/README.md.
package main

import (
	"context"
	"crypto/ed25519"
	_ "embed"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"time"

	"gadscapeclient/kit"
	"gadscapesetup/internal/fetch"
	"gadscapesetup/internal/install"
	"gadscapesetup/internal/manifest"
	"gadscapesetup/internal/robust"
)

// the loading window's picture: client241/exe/splash.png (test builds: exe/test-res/splash.png), copied in by
// setup/build.sh
//
//go:embed splash.png
var splashPng []byte

// set by setup/build.sh with -ldflags -X
var (
	appName        = "GadScape"            // shortcuts, window, Apps & features
	dirName        = "GadScape"            // %LOCALAPPDATA%\<dirName>, uninstall key
	serverFile     = "GadScape-server.txt" // server address file in the install folder
	defaultHost    = "127.0.0.1"           // server address for new installs when the release names none
	channel        = "release"             // must match the manifest's channel
	manifestURL    = ""                    // empty: refuses to install (fail closed)
	publicKey      = ""                    // base64 ed25519; empty: refuses to install (fail closed)
	setupVersion   = "0"                   // this setup's version (setup/VERSION)
	allowLocalHTTP = ""                    // "yes" only in test builds: http://127.0.0.1 allowed
	stamp          = ""                    // manifest.Stamp line, read by the release tool
)

const (
	launcherName = "GadScape.exe" // the installed copy in the install folder
	publisher    = "GadScape"
	// overrideName: test builds only, next to the exe: "url=<manifest address>" (see readOverride)
	overrideName = "GadScape-setup.txt"

	exitOK        = 0
	exitError     = 1
	exitCancelled = 2
	exitRefused   = 3
)

type app struct {
	dest       string
	client     kit.Client
	log        *kit.Log
	w          *kit.Window
	ctx        context.Context
	silent     bool
	noStart    bool
	after      int // --after-update: the version this launcher was updated from (no second self-update)
	args       []string
	version    int
	pub        ed25519.PublicKey
	murl       *url.URL
	override   string // the GadScape-setup.txt that gave the address, if any (test builds)
	dl         *fetch.Downloader
	installed  bool // the launcher is the copy in the install folder
	startTried bool // start was called (a crash after that does not start the client again)
}

func (a *app) logf(format string, v ...any) { a.log.Printf(format, v...) }

func main() {
	args := os.Args[1:]
	a := &app{args: args}
	a.version, _ = strconv.Atoi(setupVersion)
	local := os.Getenv("LOCALAPPDATA")
	if local == "" || !filepath.IsAbs(local) {
		kit.MessageBox(0, appName, "%LOCALAPPDATA% is not set, so "+appName+" does not know where to install.", kit.MbIconError)
		os.Exit(exitError)
	}
	a.dest = filepath.Join(local, dirName)
	a.client = kit.Client{AppName: appName, Dest: a.dest}
	for _, s := range args {
		switch {
		case s == "--silent" || s == "/S":
			a.silent = true
		case s == "--no-start":
			a.noStart = true
		case strings.HasPrefix(s, "--after-update="):
			a.after, _ = strconv.Atoi(strings.TrimPrefix(s, "--after-update="))
		}
	}
	if has(args, "--uninstall-stage2") {
		os.Exit(a.uninstallStage2())
	}
	if has(args, "--uninstall") {
		os.Exit(a.uninstall())
	}
	if a.after > 0 || has(args, "--log-append") { // started by another setup: keep its lines
		a.log = kit.AppendLog(filepath.Join(a.dest, "setup.log"))
	} else {
		a.log = kit.OpenLog(filepath.Join(a.dest, "setup.log"))
	}
	a.client.Log = a.logf
	self, _ := os.Executable()
	a.installed = samePath(self, a.launcherPath())
	a.logf("%s setup %d (%s channel) starting: %s %q", appName, a.version, channel, self, args)
	a.logf("built as: %s", stamp) // also keeps the stamp in the exe for the release tool
	title := appName
	if !a.installed {
		title = appName + " Setup"
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.ctx = ctx
	code := exitOK
	kit.RunWithWindow(kit.WindowConfig{Title: title, Splash: splashPng, Log: a.logf, Cancel: cancel}, func(w *kit.Window) {
		a.w = w
		defer func() {
			if r := recover(); r != nil {
				a.logf("crashed: %v\n%s", r, debug.Stack())
				code = a.afterCrash(r)
			}
		}()
		code = a.run()
	})
	a.logf("exit %d", code)
	os.Exit(code)
}

// afterCrash: a bug in the setup must not lock players out, so an installed client is still started (the crash
// is in the log); otherwise the crash is shown.
func (a *app) afterCrash(r any) (code int) {
	msg := fmt.Sprintf("The %s setup crashed: %v\n\nLog: %s", appName, r, filepath.Join(a.dest, "setup.log"))
	if a.startTried {
		return a.fail(msg, exitError)
	}
	st, _ := install.LoadState(a.dest)
	if !install.Installed(a.client, st) {
		return a.fail(msg, exitError)
	}
	defer func() {
		if r2 := recover(); r2 != nil {
			a.logf("crashed again while starting the client: %v\n%s", r2, debug.Stack())
			code = a.fail(msg, exitError)
		}
	}()
	a.logf("starting the installed client anyway")
	return a.start(st)
}

func has(args []string, flag string) bool {
	for _, s := range args {
		if s == flag {
			return true
		}
	}
	return false
}

func samePath(a, b string) bool {
	ca, _ := filepath.Abs(a)
	cb, _ := filepath.Abs(b)
	return strings.EqualFold(filepath.Clean(ca), filepath.Clean(cb))
}

func exists(p string) bool { _, err := os.Stat(p); return err == nil }

func (a *app) launcherPath() string { return filepath.Join(a.dest, launcherName) }

// fail shows an error (unless silent) and returns code.
func (a *app) fail(msg string, code int) int {
	a.logf("FAIL: %s", msg)
	if !a.silent {
		var owner uintptr
		if a.w != nil {
			a.w.Hide()
		}
		kit.MessageBox(owner, appName, msg, kit.MbIconError|kit.MbTopMost)
	}
	return code
}

// ask shows a question; silent runs get def.
func (a *app) ask(text string, flags uint32, def int) int {
	a.logf("ask: %s", strings.ReplaceAll(text, "\n", " "))
	if a.silent {
		return def
	}
	r := kit.MessageBox(0, appName, text, flags|kit.MbTopMost)
	a.logf("answer %d", r)
	return r
}

// notice shows a short line in the window before going on.
func (a *app) notice(text string) {
	a.logf("notice: %s", text)
	a.w.Say(text, -1)
	time.Sleep(2500 * time.Millisecond)
}

// overridesAllowed: only test builds take a manifest address from a file. A release setup is downloaded into
// folders full of other files (Downloads, a zip someone repacked), and its address must not be changed from there.
func overridesAllowed() bool { return allowLocalHTTP == "yes" || channel == "test" }

// readOverride reads GadScape-setup.txt in dir: its first line that is not empty or a # comment must be
// "url=<address of a manifest.json>". No file: "", "", nil.
func readOverride(dir string) (addr, path string, err error) {
	p := filepath.Join(dir, overrideName)
	b, err := os.ReadFile(p)
	if err != nil {
		return "", "", nil
	}
	for _, line := range strings.Split(strings.TrimPrefix(string(b), "\ufeff"), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if u, ok := strings.CutPrefix(line, "url="); ok && strings.TrimSpace(u) != "" {
			return strings.TrimSpace(u), p, nil
		}
		return "", p, fmt.Errorf("%s: the first line must be url=<address of manifest.json>", p)
	}
	return "", p, fmt.Errorf("%s has no url= line", p)
}

// configure checks the key and the manifest address; an error means this setup must not install anything.
func (a *app) configure() error {
	var err error
	if a.pub, err = manifest.ParsePublicKey(publicKey); err != nil {
		return fmt.Errorf("This %s setup was built without a release key (%v), so it cannot check what it downloads and will not install anything.\n\nDownload the setup again from the %s website.", appName, err, appName)
	}
	self, _ := os.Executable()
	addr := manifestURL
	if overridesAllowed() {
		u, p, err := readOverride(filepath.Dir(self))
		if err != nil {
			return err
		}
		if u != "" {
			a.logf("manifest address from %s: %s", p, u)
			addr, a.override = u, p
		}
	}
	if addr == "" {
		return fmt.Errorf("This %s setup was built without a download address, so it cannot install anything.\n\nDownload the setup again from the %s website.", appName, appName)
	}
	u, err := url.Parse(addr)
	if err == nil {
		err = manifest.CheckURL(u, allowLocalHTTP == "yes")
	}
	if err != nil {
		return fmt.Errorf("The download address %q cannot be used: %v", addr, err)
	}
	a.murl = u
	a.dl = &fetch.Downloader{
		Client: &http.Client{
			Transport: &http.Transport{Proxy: http.ProxyFromEnvironment, TLSHandshakeTimeout: 20 * time.Second, ResponseHeaderTimeout: 30 * time.Second},
			// a redirect must follow the same rule as the address itself (https only)
			CheckRedirect: manifest.CheckRedirect(allowLocalHTTP == "yes"),
		},
		UserAgent: fmt.Sprintf("%s-Setup/%d", dirName, a.version),
		Log:       a.logf,
	}
	return nil
}

// fetchManifest downloads manifest.json and manifest.sig, checks the signature and parses it (manifest.Fetch).
func (a *app) fetchManifest(timeout time.Duration) (*manifest.Manifest, error) {
	ctx, cancel := context.WithTimeout(a.ctx, timeout)
	defer cancel()
	m, _, err := manifest.Fetch(ctx, a.dl.Get, a.murl, a.pub, a.logf)
	return m, err
}

// lock takes the install mutex, waiting up to first and then (saying so in the window) up to 3 minutes for another
// setup to finish. Cancel ends the wait. ok false: return code.
func (a *app) lock(first time.Duration) (code int, ok bool) {
	if lockInstall(a.ctx, first) {
		return 0, true
	}
	if a.ctx.Err() == nil {
		a.logf("another %s setup is running: waiting", appName)
		a.w.Say("Waiting for the other "+appName+" setup...", -1)
		if lockInstall(a.ctx, 3*time.Minute) {
			return 0, true
		}
	}
	if a.ctx.Err() != nil {
		return a.cancelled(), false
	}
	return a.fail("Another "+appName+" setup is still running. Close it and try again.", exitError), false
}

func (a *app) run() int {
	runtime.LockOSThread() // the install mutex belongs to this thread
	first := time.Duration(0)
	if a.after > 0 || has(a.args, "--log-append") {
		first = 30 * time.Second // the setup that started this one is just ending
	}
	if code, ok := a.lock(first); !ok {
		return code
	}
	removeOldLaunchers(a.dest, a.logf)
	// an update a crash or power cut interrupted is undone first: it needs no connection, so an offline start
	// still finds the installed client
	install.Recover(a.client, a.logf)
	st, err := install.LoadState(a.dest)
	if err != nil {
		a.logf("state: %v (starting over)", err)
	}
	installed := install.Installed(a.client, st)
	a.logf("installed release %d (%s), launcher version %d, client ready: %v", st.Release, st.Label, st.SetupVersion, installed)

	// an older setup started while a newer launcher is installed: let the newer one do it
	if !a.installed && st.SetupVersion > a.version && exists(a.launcherPath()) {
		a.logf("installed launcher %d is newer than this setup %d: starting it", st.SetupVersion, a.version)
		a.log.Close()
		unlockInstall()
		err := startDetached(a.launcherPath(), append(append([]string{}, a.args...), "--log-append"))
		if err == nil {
			return exitOK
		}
		a.log = kit.AppendLog(filepath.Join(a.dest, "setup.log"))
		a.logf("couldn't start the installed launcher (%v): going on with this setup", err)
		if code, ok := a.lock(30 * time.Second); !ok {
			return code
		}
		st, installed = a.reloadState() // another setup may have run while the lock was let go
	}

	if err := a.configure(); err != nil {
		if installed {
			a.logf("not checking for updates: %v", err)
			return a.start(st)
		}
		return a.fail(err.Error(), exitRefused)
	}

	if installed {
		a.w.Say("Checking for updates...", -1)
	} else {
		a.w.Say("Connecting to the download server...", -1)
	}
	timeout := 45 * time.Second
	if installed {
		timeout = 8 * time.Second
	}
	m, err := a.fetchManifest(timeout)
	var tooNew *manifest.TooNewError
	if errors.As(err, &tooNew) && m != nil && m.Setup != nil && m.Setup.Version > a.version && a.after == 0 {
		// a release this setup cannot read, but it offers a newer setup: update first
		if a.selfUpdate(m, &st) == nil {
			return exitOK
		}
		if code, ok := a.lock(0); !ok {
			return code
		}
		st, installed = a.reloadState()
	}
	if err == nil {
		err = m.CheckFor(dirName, channel, st.Release)
	}
	if err != nil {
		if a.ctx.Err() != nil {
			return a.cancelled()
		}
		return a.releaseCheckFailed(err, installed, st)
	}
	a.logf("release %d (%s): format %d, cache version %d, setup %v", m.Version, m.Label, m.Format, m.Cache.Version, m.Setup != nil)

	// a newer setup in the release replaces this one (the installed launcher, or this downloaded setup)
	if m.Setup != nil && m.Setup.Version > a.version && a.after == 0 {
		if err := a.selfUpdate(m, &st); err == nil {
			return exitOK
		} else if a.ctx.Err() != nil {
			return a.cancelled()
		} else {
			a.logf("launcher update failed, going on with version %d: %v", a.version, err)
		}
		if code, ok := a.lock(0); !ok { // it let go of the lock to hand over
			return code
		}
		st, installed = a.reloadState()
	}
	if !a.installed {
		if err := a.installLauncher(&st); err != nil {
			return a.fail(fmt.Sprintf("Couldn't install the %s launcher to %s:\n%v", appName, a.dest, err), exitError)
		}
	}

	plan := install.MakePlan(m, st, a.client)
	for _, s := range plan.Steps {
		a.logf("update %s: %s", s.File.ID, s.Reason)
	}
	a.logf("cache: %s", plan.CacheReason)
	if !plan.Empty() {
		if code, skip := a.waitForClientClosed(installed); skip {
			if code >= 0 {
				return code
			}
			return a.start(st)
		}
		in := &install.Installer{
			Client: a.client, DL: a.dl, ManifestURL: a.murl, AllowLocalHTTP: allowLocalHTTP == "yes", Log: a.logf,
			Status: a.w.Say, Cancelable: a.w.SetCancel, FreeSpace: freeSpace,
		}
		if err := in.Apply(a.ctx, m, plan, &st); err != nil {
			if a.ctx.Err() != nil {
				return a.cancelled()
			}
			if errors.Is(err, install.ErrInUse) {
				err = fmt.Errorf("%v\n\nClose %s and start it again.", err, appName)
			}
			if install.Installed(a.client, st) {
				a.logf("update failed: %v", err)
				a.fail(fmt.Sprintf("Couldn't install the %s update:\n%v\n\nStarting the version that is installed.", appName, err), exitError)
				return a.start(st)
			}
			return a.fail(fmt.Sprintf("Couldn't install %s:\n%v", appName, err), exitError)
		}
	}
	st.Release, st.Label = m.Version, m.Label
	install.CleanDownloads(a.client, m, true)
	if err := install.SaveState(a.dest, st); err != nil {
		return a.fail(fmt.Sprintf("Couldn't save %s: %v", filepath.Join(a.dest, install.StateFile), err), exitError)
	}
	a.finishInstall(m, &st)
	return a.start(st)
}

// reloadState reads the install state again (after the lock was let go for a moment).
func (a *app) reloadState() (install.State, bool) {
	st, err := install.LoadState(a.dest)
	if err != nil {
		a.logf("state: %v (starting over)", err)
	}
	return st, install.Installed(a.client, st)
}

// releaseCheckFailed handles a manifest that could not be fetched or used. Installed: the installed client starts
// (with a short notice when it matters). Not installed: nothing can be done, the player is told why.
func (a *app) releaseCheckFailed(err error, installed bool, st install.State) int {
	a.logf("release check failed: %v", err)
	var rollback *manifest.RollbackError
	var broken *manifest.BrokenError
	var tooNew *manifest.TooNewError
	badSig := errors.Is(err, manifest.ErrSignature)
	if installed {
		switch {
		case errors.As(err, &rollback):
			// a server or cache that still has an older release (just after an update): nothing to do
			a.logf("the download server still offers the older release %d: starting the installed release %d", rollback.Offered, rollback.Installed)
		case badSig:
			a.notice("Update refused (bad signature)")
		default:
			a.notice("Couldn't check for updates - starting " + appName)
		}
		return a.start(st)
	}
	switch {
	case errors.As(err, &rollback):
		return a.fail(fmt.Sprintf("The %s download server is still being updated. Try again in a few minutes.\n\n(%v)", appName, err), exitError)
	case badSig:
		return a.fail(fmt.Sprintf("The release information from the %s download server did not pass the signature check, so nothing was installed.\n\n"+
			"If %s was updated just now, the download server may still be catching up: try again in a few minutes. If it keeps happening, download the setup again from the %s website.\n\n(%v)",
			appName, appName, appName, err), exitRefused)
	case errors.As(err, &tooNew):
		return a.fail(fmt.Sprintf("This %s setup is too old for the current release. Download the setup again from the %s website.\n\n(%v)", appName, appName, err), exitRefused)
	case errors.As(err, &broken):
		return a.fail(fmt.Sprintf("The %s download server has a broken release. Try again later.\n\n(%v)", appName, err), exitError)
	}
	return a.fail(fmt.Sprintf("Couldn't download the %s release information from %s.\n\nCheck your internet connection and try again.\n\n(%v)", appName, a.murl.Host, err), exitError)
}

// finishInstall writes the server address file, the shortcuts (once) and the Apps & features entry.
func (a *app) finishInstall(m *manifest.Manifest, st *install.State) {
	host := defaultHost
	if m != nil && m.Server != "" {
		host = m.Server
	}
	if p, err := install.EnsureServerFile(a.client, serverFile, host, st); err != nil {
		a.logf("server file %s: %v", p, err)
	}
	if !st.Shortcuts {
		ok := true
		for _, f := range []func() (string, error){desktopDir, programsDir} {
			dir, err := f()
			if err == nil {
				err = createShortcut(filepath.Join(dir, appName+".lnk"), a.launcherPath(), "", a.dest, a.launcherPath(), "Play "+appName)
			}
			if err != nil {
				ok = false
				a.logf("shortcut in %s: %v", dir, err)
			} else {
				a.logf("shortcut %s", filepath.Join(dir, appName+".lnk"))
			}
		}
		st.Shortcuts = ok
	}
	label := ""
	if m != nil {
		label = m.Label
		if label == "" {
			label = strconv.FormatInt(m.Version, 10)
		}
	}
	if err := registerUninstall(a.dest, a.launcherPath(), label, sizeKB(m)); err != nil {
		a.logf("uninstall entry: %v", err)
	}
	install.SaveState(a.dest, *st)
}

func sizeKB(m *manifest.Manifest) uint32 {
	if m == nil {
		return 0
	}
	var n int64
	for _, f := range m.Files {
		n += f.Unpacked
	}
	return uint32(n >> 10)
}

// waitForClientClosed asks the player to close a running client before files are replaced. skip true: do not
// update now (code >= 0: exit with it; -1: start the installed client).
func (a *app) waitForClientClosed(installed bool) (int, bool) {
	for {
		running := kit.ProcessesIn(a.client.Java())
		if len(running) == 0 {
			return 0, false
		}
		a.logf("client running: %v", running)
		text := fmt.Sprintf("%s is open. Close every %s window to install the update, then press Retry.", appName, appName)
		if installed {
			text += "\n\nCancel starts " + appName + " without the update."
		}
		if a.ask(text, kit.MbRetryCancel|kit.MbIconWarning, kit.IDCancel) != kit.IDRetry {
			if installed {
				return -1, true
			}
			return exitCancelled, true
		}
	}
}

func (a *app) cancelled() int {
	a.logf("cancelled")
	a.w.Say("Cancelled", -1)
	return exitCancelled
}

// start starts the installed client (unless --no-start).
func (a *app) start(st install.State) int {
	a.startTried = true
	a.w.SetCancel(false)
	defer unlockInstall()
	if !install.Installed(a.client, st) && !exists(a.client.Javaw()) {
		return a.fail(appName+" is not installed completely. Start the setup again.", exitError)
	}
	hostFile := filepath.Join(a.dest, serverFile)
	if !exists(hostFile) {
		install.EnsureServerFile(a.client, serverFile, defaultHost, &st)
		install.SaveState(a.dest, st)
	}
	if a.noStart {
		a.logf("ready; not starting the client (--no-start)")
		return exitOK
	}
	unlockInstall() // starting the client changes no files
	a.w.Say("Starting "+appName+"...", -1)
	if err := a.client.Run(a.w, hostFile); err != nil {
		return a.fail(err.Error(), exitError)
	}
	return exitOK
}

// syncOverride makes the installed launcher use the address this setup uses: the GadScape-setup.txt that gave it
// is copied next to the launcher (test builds), and one left there from before is removed otherwise.
func (a *app) syncOverride() {
	dst := filepath.Join(a.dest, overrideName)
	switch {
	case a.override == "":
		if err := os.Remove(dst); err == nil {
			a.logf("removed %s (this setup was not configured by one)", dst)
		}
	case !samePath(a.override, dst):
		if err := copyFile(a.override, dst); err != nil {
			a.logf("copying %s: %v", a.override, err)
		}
	}
}

// installLauncher copies this setup into the install folder as the everyday launcher (syncOverride).
func (a *app) installLauncher(st *install.State) error {
	self, err := os.Executable()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(a.dest, 0o755); err != nil {
		return err
	}
	if err := replaceFile(self, a.launcherPath(), a.logf); err != nil {
		return err
	}
	st.SetupVersion = a.version
	a.syncOverride()
	a.logf("launcher installed: %s (version %d)", a.launcherPath(), a.version)
	return install.SaveState(a.dest, *st)
}

// selfUpdate downloads the release's setup, checks it, makes it the installed launcher and starts it.
func (a *app) selfUpdate(m *manifest.Manifest, st *install.State) error {
	f := m.Setup.File
	u, err := manifest.Resolve(a.murl, f.URL, allowLocalHTTP == "yes")
	if err != nil {
		return err
	}
	a.logf("launcher update %d -> %d: %s", a.version, m.Setup.Version, u.Redacted())
	dir := filepath.Join(a.dest, "downloads")
	os.MkdirAll(dir, 0o755)
	p := filepath.Join(dir, "setup-"+f.SHA256[:16]+".exe")
	err = a.dl.Download(a.ctx, u.String(), p, f.Size, f.SHA256, func(n int64) { a.w.Bytes("Updating the launcher", n, f.Size) })
	if err != nil {
		return err
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return err
	}
	s, err := manifest.FindStamp(b)
	if err != nil {
		return err
	}
	// never switch to a setup with another key (it could not check this release) or for another channel
	if s.Key != publicKey || s.Channel != channel || s.App != dirName || s.Version != m.Setup.Version {
		os.Remove(p)
		return fmt.Errorf("the new setup does not fit this install (%s)", s)
	}
	if err := replaceFile(p, a.launcherPath(), a.logf); err != nil {
		return err
	}
	os.Remove(p)
	st.SetupVersion = m.Setup.Version
	a.syncOverride()
	install.SaveState(a.dest, *st)
	args := []string{}
	for _, s := range a.args {
		if !strings.HasPrefix(s, "--after-update=") {
			args = append(args, s)
		}
	}
	args = append(args, "--after-update="+strconv.Itoa(a.version))
	a.w.Say("Starting the new launcher...", -1)
	a.logf("starting launcher %d; it goes on in this log", m.Setup.Version)
	a.log.Close() // the new launcher appends to the same file
	unlockInstall()
	if err := startDetached(a.launcherPath(), args); err != nil {
		a.log = kit.AppendLog(filepath.Join(a.dest, "setup.log"))
		return err // the caller takes the lock again before it goes on
	}
	return nil
}

// replaceFile puts src at dst: a running dst (this launcher) is renamed to dst.old first, which Windows allows
// for a running exe; the old copy is deleted on a later start (removeOldLaunchers).
func replaceFile(src, dst string, logf func(string, ...any)) error {
	if samePath(src, dst) {
		return nil
	}
	tmp := dst + ".new"
	if err := copyFile(src, tmp); err != nil {
		return err
	}
	if exists(dst) {
		old := dst + ".old"
		if os.Remove(old) != nil && exists(old) {
			old = fmt.Sprintf("%s.old%d", dst, os.Getpid())
		}
		if err := robust.Rename(dst, old); err != nil {
			os.Remove(tmp)
			return fmt.Errorf("couldn't move the old %s aside: %v", filepath.Base(dst), err)
		}
		if err := robust.Rename(tmp, dst); err != nil {
			robust.Rename(old, dst)
			return err
		}
		if os.Remove(old) == nil {
			logf("replaced %s", dst)
		}
		return nil
	}
	return robust.Rename(tmp, dst)
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(dst)
		return err
	}
	return out.Close()
}

// removeOldLaunchers deletes GadScape.exe.old* left by a launcher update (the old process may take a moment to
// end, so it keeps trying for a few seconds in the background).
func removeOldLaunchers(dest string, logf func(string, ...any)) {
	old, _ := filepath.Glob(filepath.Join(dest, launcherName+".old*"))
	if len(old) == 0 {
		return
	}
	go func() {
		for i := 0; i < 20 && len(old) > 0; i++ {
			var left []string
			for _, p := range old {
				if os.Remove(p) != nil && exists(p) {
					left = append(left, p)
				} else {
					logf("removed %s", p)
				}
			}
			old = left
			time.Sleep(500 * time.Millisecond)
		}
	}()
}

// startDetached starts a program and does not wait for it.
func startDetached(exe string, args []string) error {
	cmd := exec.Command(exe, args...)
	cmd.Dir = filepath.Dir(exe)
	return cmd.Start()
}
