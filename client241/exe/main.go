// GadScape client, one file: Java 21 and the client (RuneLite 1.13.3 with the patched game client, GadScape's
// branding and login screen) are packed inside this exe. The first run (and the first run of a newer exe) unpacks
// them to %LOCALAPPDATA%\<dirName>; every run starts the client.
//
// The server address is the first line of <serverFile> next to the exe. The file is created with the build's default
// server when it is missing. RuneLite settings and the game cache stay in %LOCALAPPDATA%\<dirName>\home, away from
// the player's normal RuneLite and OSRS.
//
// The names differ per build channel (release: "GadScape", %LOCALAPPDATA%\GadScape, GadScape-server.txt; test:
// "GadScape 241 Test", %LOCALAPPDATA%\GadScape241Test, GadScape-241-Test-server.txt) and are set by exe/build.sh
// with -ldflags -X. Built by client241/exe/build.sh (see client241/README.md). Structure follows the map editor's
// launcher/.
package main

import (
	"archive/zip"
	"bytes"
	_ "embed"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime/debug"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

//go:embed payload/jre.zip
var jreZip []byte

//go:embed payload/client.zip
var clientZip []byte

// set at build time by exe/build.sh: -X main.build=<hash of the payload> -X main.appName=... and so on
var (
	build       = "dev"
	appName     = "GadScape"
	dirName     = "GadScape"
	serverFile  = "GadScape-server.txt"
	defaultHost = "127.0.0.1"
)

func main() {
	dest := filepath.Join(os.Getenv("LOCALAPPDATA"), dirName)
	startLog(dest)
	logf("exe %s starting", build)
	withProgress(func(p *progress) {
		defer func() {
			if r := recover(); r != nil {
				logf("crashed: %v\n%s", r, debug.Stack())
				fail(fmt.Sprintf("The launcher crashed: %v\n\nLog: %s", r, filepath.Join(dest, "launcher.log")))
			}
		}()
		if b, _ := os.ReadFile(filepath.Join(dest, "installed.txt")); strings.TrimSpace(string(b)) != build {
			p.say("Installing...", -1)
			if err := install(dest); err != nil {
				fail("Couldn't install the " + appName + " client:\n" + err.Error() +
					"\n\nIf the client is already open, close it and try again.")
			}
		}
		hostFile := ensureServerFile()
		home := filepath.Join(dest, "home")
		app := filepath.Join(dest, "app")
		javaw := filepath.Join(dest, "java", "bin", "javaw.exe")
		args := []string{
			"@" + filepath.Join(app, "jvm-args.txt"),
			"-Duser.home=" + home,
			"-cp", filepath.Join(app, "lib", "*") + ";" + filepath.Join(app, "lib-win", "*"),
			"gadscape.launcher.Launcher",
			"--host-file", hostFile,
			"--home", home,
		}
		logf("starting %s %s", javaw, strings.Join(args, " "))
		cmd := exec.Command(javaw, args...)
		cmd.Dir = app
		cmd.Env = cleanEnv()
		out, err := os.Create(filepath.Join(dest, "client-output.log"))
		if err == nil {
			cmd.Stdout = out
			cmd.Stderr = out
			defer out.Close()
		}
		started := time.Now()
		if err := cmd.Start(); err != nil {
			fail("Couldn't start the client:\n" + err.Error())
		}
		if p.shown {
			p.say("Starting...", -1)
		}
		// a client that closes right away has crashed: say why instead of just vanishing
		exited := make(chan error, 1)
		go func() { exited <- cmd.Wait() }()
		for wait := 0; ; wait++ {
			select {
			case err := <-exited:
				fail(crashReport(dest, home, err, time.Since(started)))
			case <-time.After(time.Second):
			}
			if hasVisibleWindow(cmd.Process.Pid) {
				logf("client window is up after %ds", wait+1)
				break
			}
			if wait == 3 {
				p.hide()
			}
			if wait == 40 {
				logf("client running but no window after 40s")
				fail(crashReport(dest, home, fmt.Errorf("it is running but never showed a window"), time.Since(started)))
			}
		}
	})
}

// ensureServerFile returns the path of the server address file next to this exe, creating it if missing.
func ensureServerFile() string {
	self, _ := os.Executable()
	f := filepath.Join(filepath.Dir(self), serverFile)
	if _, err := os.Stat(f); err != nil {
		text := "# " + appName + " client: the first line that is not a comment is the server address\r\n" +
			"# (a host name or IP, no port: the game always uses port 43594). 127.0.0.1 = this computer.\r\n" +
			defaultHost + "\r\n"
		if os.WriteFile(f, []byte(text), 0o644) != nil {
			// the exe's folder is read-only: keep the file with the install instead
			f = filepath.Join(os.Getenv("LOCALAPPDATA"), dirName, serverFile)
			if _, err := os.Stat(f); err != nil {
				os.WriteFile(f, []byte(text), 0o644)
			}
		}
	}
	logf("server address file %s", f)
	return f
}

// cleanEnv drops the Jagex Launcher's JX_* variables: the game client would otherwise try a Jagex account login.
func cleanEnv() []string {
	var env []string
	for _, e := range os.Environ() {
		if !strings.HasPrefix(strings.ToUpper(e), "JX_") {
			env = append(env, e)
		}
	}
	return env
}

func install(dest string) error {
	os.RemoveAll(filepath.Join(dest, "java"))
	os.RemoveAll(filepath.Join(dest, "app"))
	if err := unzipStrip(jreZip, filepath.Join(dest, "java")); err != nil {
		return fmt.Errorf("unpacking Java: %v", err)
	}
	if err := unzipStrip(clientZip, filepath.Join(dest, "app")); err != nil {
		return fmt.Errorf("unpacking the client: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dest, "java", "bin", "javaw.exe")); err != nil {
		return fmt.Errorf("Java is missing after unpacking: %v", err)
	}
	return os.WriteFile(filepath.Join(dest, "installed.txt"), []byte(build), 0o644)
}

// unzipStrip unpacks data into dest, dropping the zip's top folder.
func unzipStrip(data []byte, dest string) error {
	r, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return err
	}
	clean := filepath.Clean(dest) + string(os.PathSeparator)
	for _, f := range r.File {
		name := f.Name
		if i := strings.Index(name, "/"); i >= 0 {
			name = name[i+1:]
		} else {
			continue
		}
		if name == "" {
			continue
		}
		p := filepath.Join(dest, filepath.FromSlash(name))
		if !strings.HasPrefix(p, clean) {
			return fmt.Errorf("bad path %s", f.Name)
		}
		if f.FileInfo().IsDir() {
			os.MkdirAll(p, 0o755)
			continue
		}
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			return err
		}
		in, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.Create(p)
		if err != nil {
			in.Close()
			return err
		}
		_, err = io.Copy(out, in)
		in.Close()
		out.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

// crashReport tells what stopped the client: the end of its output and of RuneLite's log.
func crashReport(dest, home string, err error, ran time.Duration) string {
	msg := fmt.Sprintf("The %s client closed %d seconds after starting", appName, int(ran.Seconds()))
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
	tail(filepath.Join(dest, "client-output.log"), 20)
	tail(filepath.Join(home, ".runelite", "logs", "client.log"), 10)
	return msg
}

var logFile *os.File

func startLog(dest string) {
	os.MkdirAll(dest, 0o755)
	f, err := os.OpenFile(filepath.Join(dest, "launcher.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return
	}
	logFile = f
}

func logf(format string, a ...any) {
	if logFile != nil {
		fmt.Fprintf(logFile, time.Now().Format("15:04:05.000")+" "+format+"\n", a...)
		logFile.Sync()
	}
}

func fail(msg string) {
	logf("FAIL: %s", msg)
	t, _ := syscall.UTF16PtrFromString(appName)
	m, _ := syscall.UTF16PtrFromString(msg)
	syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), 0x10)
	os.Exit(1)
}
