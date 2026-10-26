// GadScape-Setup.exe: the setup players download from the website. It downloads GadScape.exe (the one-file client)
// from the GadScape server, checks it against the SHA-256 the server lists next to it, installs it to
// %LOCALAPPDATA%\GadScape (where the old setup put the old client, so an old install is upgraded in place), points
// it at the live server, makes the Desktop and Start menu shortcuts and the Apps & features entry, and starts it.
// The game cache comes from the game server the first time the client starts.
//
// It runs as itself: nothing is unpacked to the temp folder and started from there (what an Inno Setup does, and
// what antivirus blocks with "Unable to execute file in the temporary directory").
//
// Build: client241/installer/build.sh. Run again any time to update; GadScape-Setup.exe --uninstall removes it.
package main

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"gadscapeclient/kit"
)

//go:embed splash.png
var splashPng []byte

// payload/GadScape.exe, when the setup is built with the client inside (installer/build.sh --exe FILE): the setup
// then installs that client and downloads nothing. Antivirus treats a small program that downloads an exe and
// runs it as malware; a setup that carries the game, like the old GadScape setup, does not look like that.
//
//go:embed payload
var payload embed.FS

func embeddedExe() []byte {
	b, err := payload.ReadFile("payload/GadScape.exe")
	if err != nil {
		return nil
	}
	return b
}

var (
	appName    = "GadScape"
	dirName    = "GadScape"
	publisher  = "GadScape"
	version    = "2.0"
	serverHost = "gadscape.duckdns.org"
	exeURL     = "http://gadscape.duckdns.org:8088/GadScape.exe"
)

// the old (Inno Setup) GadScape setup's Apps & features entry
const oldInnoKey = `Software\Microsoft\Windows\CurrentVersion\Uninstall\{78754FA4-EE0D-4719-8B5F-9D1343A83987}_is1`

var logFile *kit.Log

func logf(format string, a ...any) { logFile.Printf(format, a...) }

var errCancelled = errors.New("cancelled")

func main() {
	dest := filepath.Join(os.Getenv("LOCALAPPDATA"), dirName)
	os.MkdirAll(dest, 0o755)
	logFile = kit.OpenLog(filepath.Join(dest, "setup.log"))
	for _, a := range os.Args[1:] {
		if a == "--uninstall" {
			uninstall(dest)
			return
		}
	}
	cancelled := make(chan struct{})
	var failMsg string
	kit.RunWithWindow(kit.WindowConfig{
		Title:  appName + " Setup",
		Splash: splashPng,
		Cancel: func() {
			select {
			case <-cancelled:
			default:
				close(cancelled)
			}
		},
		Log: logf,
	}, func(w *kit.Window) {
		w.SetCancel(true)
		if err := run(w, dest, cancelled); err != nil {
			if err != errCancelled {
				logf("failed: %v", err)
				failMsg = err.Error()
			}
		}
	})
	if failMsg != "" {
		kit.MessageBox(0, appName+" Setup", "GadScape could not be installed:\n\n"+failMsg+
			"\n\nCheck your internet connection and run the setup again.", kit.MbIconError)
		os.Exit(1)
	}
}

func run(w *kit.Window, dest string, cancelled <-chan struct{}) error {
	exe := filepath.Join(dest, "GadScape.exe")
	inside := embeddedExe()
	var want string
	if inside != nil {
		h := sha256.Sum256(inside)
		want = hex.EncodeToString(h[:])
	} else {
		w.Say("Connecting to the GadScape server...", -1)
		var err error
		if want, err = fetchHash(cancelled); err != nil {
			return err
		}
	}
	if have, err := fileHash(exe); err == nil && have == want {
		logf("GadScape.exe is already the newest (%s)", want)
	} else {
		part := exe + ".download"
		var err error
		if inside != nil {
			err = unpackExe(w, part, inside, cancelled)
		} else {
			err = download(w, part, want, cancelled)
		}
		if err != nil {
			os.Remove(part)
			return err
		}
		w.SetCancel(false)
		w.Say("Installing...", 100)
		if err := replace(w, part, exe); err != nil {
			return err
		}
		// the old client's Java and program files (build 211), and the unpacked copy of the previous client:
		// GadScape.exe unpacks itself again on its first start. home\ (settings, saved logins) stays.
		for _, d := range []string{"runtime", "app", "java"} {
			os.RemoveAll(filepath.Join(dest, d))
		}
		os.Remove(filepath.Join(dest, "installed.txt"))
		for _, f := range []string{"unins000.exe", "unins000.dat", "unins000.msg"} {
			os.Remove(filepath.Join(dest, f))
		}
	}
	w.SetCancel(false)
	w.Say("Installing...", 100)
	writeServerFile(dest)
	setup := filepath.Join(dest, "GadScape-Setup.exe")
	if self, err := os.Executable(); err == nil && !strings.EqualFold(filepath.Clean(self), filepath.Clean(setup)) {
		if err := copyFile(self, setup); err != nil {
			logf("copying the setup: %v", err)
		}
	}
	shortcuts(exe, dest)
	deleteKey(oldInnoKey)
	if err := registerUninstall(dest, setup, version, uint32(dirSizeKB(dest))); err != nil {
		logf("Apps & features entry: %v", err)
	}
	w.Say("Starting GadScape...", 100)
	cmd := exec.Command(exe)
	cmd.Dir = dest
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("starting GadScape: %v", err)
	}
	logf("started %s", exe)
	time.Sleep(1500 * time.Millisecond)
	return nil
}

var httpClient = &http.Client{
	Transport: &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		ResponseHeaderTimeout: 30 * time.Second,
		IdleConnTimeout:       30 * time.Second,
	},
}

// fetchHash reads GadScape.exe.sha256 next to GadScape.exe on the server (the first word is the hash).
func fetchHash(cancelled <-chan struct{}) (string, error) {
	var last error
	for try := 0; try < 3; try++ {
		select {
		case <-cancelled:
			return "", errCancelled
		default:
		}
		resp, err := httpClient.Get(exeURL + ".sha256?r=" + fmt.Sprint(time.Now().UnixNano()))
		if err == nil {
			b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
			resp.Body.Close()
			if resp.StatusCode == 200 {
				f := strings.Fields(string(b))
				if len(f) > 0 && len(f[0]) == 64 {
					if _, err := hex.DecodeString(f[0]); err == nil {
						return strings.ToLower(f[0]), nil
					}
				}
				return "", errors.New("the server's GadScape.exe.sha256 is not a SHA-256")
			}
			err = fmt.Errorf("the server answered %s", resp.Status)
		}
		last = err
		logf("hash: %v", err)
		time.Sleep(2 * time.Second)
	}
	return "", fmt.Errorf("could not reach the GadScape server (%v)", last)
}

// download fetches GadScape.exe into part with a progress bar, continuing where a broken download stopped.
func download(w *kit.Window, part, want string, cancelled <-chan struct{}) error {
	os.Remove(part)
	var last error
	for try := 0; try < 4; try++ {
		err := downloadOnce(w, part, cancelled)
		if err == nil {
			have, herr := fileHash(part)
			if herr != nil {
				return herr
			}
			if have == want {
				return nil
			}
			logf("hash mismatch: have %s want %s", have, want)
			os.Remove(part)
			err = errors.New("the download was damaged")
		}
		if err == errCancelled {
			return err
		}
		last = err
		logf("download try %d: %v", try+1, err)
		w.Say("Connection lost, trying again...", -1)
		time.Sleep(time.Duration(2+2*try) * time.Second)
	}
	return fmt.Errorf("downloading GadScape failed (%v)", last)
}

func downloadOnce(w *kit.Window, part string, cancelled <-chan struct{}) error {
	var have int64
	if st, err := os.Stat(part); err == nil {
		have = st.Size()
	}
	req, _ := http.NewRequest("GET", exeURL, nil)
	if have > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", have))
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	flags := os.O_CREATE | os.O_WRONLY
	total := resp.ContentLength
	switch resp.StatusCode {
	case 206:
		flags |= os.O_APPEND
		if total >= 0 {
			total += have
		}
	case 200:
		flags |= os.O_TRUNC
		have = 0
	default:
		return fmt.Errorf("the server answered %s", resp.Status)
	}
	f, err := os.OpenFile(part, flags, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()
	buf := make([]byte, 256*1024)
	lastSay := time.Time{}
	start, startHave := time.Now(), have
	for {
		select {
		case <-cancelled:
			return errCancelled
		default:
		}
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, err := f.Write(buf[:n]); err != nil {
				return err
			}
			have += int64(n)
		}
		if time.Since(lastSay) > 150*time.Millisecond || rerr != nil {
			lastSay = time.Now()
			pct := -1
			text := fmt.Sprintf("Downloading GadScape...  %s", mb(have))
			if total > 0 {
				pct = int(have * 100 / total)
				text = fmt.Sprintf("Downloading GadScape...  %d%%  (%s of %s)", pct, mb(have), mb(total))
			}
			if secs := time.Since(start).Seconds(); secs > 1 {
				text += fmt.Sprintf("  %.1f MB/s", float64(have-startHave)/secs/1e6)
			}
			w.Say(text, pct)
		}
		if rerr == io.EOF {
			if total > 0 && have != total {
				return io.ErrUnexpectedEOF
			}
			return nil
		}
		if rerr != nil {
			return rerr
		}
	}
}

// unpackExe writes the client this setup carries to part, with the progress bar.
func unpackExe(w *kit.Window, part string, data []byte, cancelled <-chan struct{}) error {
	f, err := os.Create(part)
	if err != nil {
		return err
	}
	defer f.Close()
	const step = 1 << 20
	for off := 0; off < len(data); off += step {
		select {
		case <-cancelled:
			return errCancelled
		default:
		}
		end := off + step
		if end > len(data) {
			end = len(data)
		}
		if _, err := f.Write(data[off:end]); err != nil {
			return err
		}
		w.Say(fmt.Sprintf("Installing GadScape...  %d%%", end*100/len(data)), end*100/len(data))
	}
	return f.Sync()
}

func mb(n int64) string { return fmt.Sprintf("%.0f MB", float64(n)/1e6) }

func fileHash(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// replace puts the downloaded file in place of GadScape.exe, asking to close the game while it runs.
func replace(w *kit.Window, part, exe string) error {
	for {
		var err error
		for i := 0; i < 20; i++ { // Windows Defender may hold a new file for a moment
			if err = os.Rename(part, exe); err == nil {
				return nil
			}
			if _, serr := os.Stat(exe); serr == nil {
				old := exe + ".old"
				os.Remove(old)
				if os.Rename(exe, old) == nil {
					if err = os.Rename(part, exe); err == nil {
						os.Remove(old)
						return nil
					}
					os.Rename(old, exe)
				}
			}
			time.Sleep(250 * time.Millisecond)
		}
		logf("replacing GadScape.exe: %v", err)
		if kit.MessageBox(w.HWND(), appName+" Setup", "GadScape is still running.\n\nClose the game, then press OK.",
			kit.MbIconWarning|kit.MbOKCancel) != 1 {
			return errCancelled
		}
	}
}

func writeServerFile(dest string) {
	text := "# GadScape client: the first line that is not a comment is the server address\r\n" +
		"# (a host name or IP, no port: the game always uses port 43594).\r\n" + serverHost + "\r\n"
	if err := os.WriteFile(filepath.Join(dest, "GadScape-server.txt"), []byte(text), 0o644); err != nil {
		logf("server file: %v", err)
	}
}

func shortcuts(exe, dest string) {
	if d, err := desktopDir(); err == nil {
		if err := createShortcut(filepath.Join(d, appName+".lnk"), exe, "", dest, exe, "Play "+appName); err != nil {
			logf("desktop shortcut: %v", err)
		}
	}
	if p, err := programsDir(); err == nil {
		os.Remove(filepath.Join(p, appName, appName+".lnk")) // the old setup's Start menu folder
		os.Remove(filepath.Join(p, appName, "Uninstall "+appName+".lnk"))
		os.Remove(filepath.Join(p, appName))
		if err := createShortcut(filepath.Join(p, appName+".lnk"), exe, "", dest, exe, "Play "+appName); err != nil {
			logf("start menu shortcut: %v", err)
		}
	}
}

func copyFile(src, dst string) error {
	b, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	tmp := dst + ".tmp"
	if err := os.WriteFile(tmp, b, 0o755); err != nil {
		return err
	}
	os.Remove(dst)
	return os.Rename(tmp, dst)
}

func dirSizeKB(dir string) int64 {
	var n int64
	filepath.Walk(dir, func(_ string, fi os.FileInfo, err error) error {
		if err == nil && !fi.IsDir() {
			n += fi.Size()
		}
		return nil
	})
	return n / 1024
}

func deleteKey(sub string) {
	procRegDeleteTree.Call(hkeyCurrentUser, wptr(sub))
}

// uninstall removes the client, its shortcuts and the Apps & features entry. home\ (settings, saved logins,
// the game cache) is removed only if the player says so.
func uninstall(dest string) {
	if kit.MessageBox(0, appName+" Setup", "Remove GadScape from this computer?", kit.MbIconQuest|kit.MbOKCancel) != 1 {
		return
	}
	for _, d := range []string{"java", "app", "runtime"} {
		os.RemoveAll(filepath.Join(dest, d))
	}
	for _, f := range []string{"GadScape.exe", "GadScape-server.txt", "installed.txt", "launcher.log", "client-output.log"} {
		os.Remove(filepath.Join(dest, f))
	}
	if d, err := desktopDir(); err == nil {
		os.Remove(filepath.Join(d, appName+".lnk"))
	}
	if p, err := programsDir(); err == nil {
		os.Remove(filepath.Join(p, appName+".lnk"))
	}
	unregisterUninstall()
	if kit.MessageBox(0, appName+" Setup", "Also delete your GadScape settings, saved logins and game files?",
		kit.MbIconQuest|kit.MbYesNo) == 6 {
		os.RemoveAll(filepath.Join(dest, "home"))
	}
	kit.MessageBox(0, appName+" Setup", "GadScape was removed.", kit.MbIconInfo)
	// this exe cannot delete itself while it runs: a hidden cmd does it a moment later
	self := filepath.Join(dest, "GadScape-Setup.exe")
	cmd := exec.Command("cmd", "/c", "ping -n 3 127.0.0.1 >nul & del /f /q \""+self+"\" & del /f /q \""+
		filepath.Join(dest, "setup.log")+"\" & rmdir \""+dest+"\"")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	cmd.Start()
}
