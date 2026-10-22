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
	"bytes"
	"context"
	_ "embed"
	"fmt"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"

	"gadscapeclient/kit"
)

//go:embed payload/jre.zip
var jreZip []byte

//go:embed payload/client.zip
var clientZip []byte

// the loading window's picture (exe/splash.png; test builds use exe/test-res/splash.png)
//
//go:embed splash.png
var splashPng []byte

// set at build time by exe/build.sh: -X main.build=<hash of the payload> -X main.appName=... and so on
var (
	build       = "dev"
	appName     = "GadScape"
	dirName     = "GadScape"
	serverFile  = "GadScape-server.txt"
	defaultHost = "127.0.0.1"
)

var logFile *kit.Log

func logf(format string, a ...any) { logFile.Printf(format, a...) }

func main() {
	dest := filepath.Join(os.Getenv("LOCALAPPDATA"), dirName)
	logFile = kit.OpenLog(filepath.Join(dest, "launcher.log"))
	logf("exe %s starting", build)
	client := kit.Client{AppName: appName, Dest: dest, Log: logf}
	kit.RunWithWindow(kit.WindowConfig{Title: appName, Splash: splashPng, Log: logf}, func(p *kit.Window) {
		defer func() {
			if r := recover(); r != nil {
				logf("crashed: %v\n%s", r, debug.Stack())
				fail(fmt.Sprintf("The launcher crashed: %v\n\nLog: %s", r, filepath.Join(dest, "launcher.log")))
			}
		}()
		if b, _ := os.ReadFile(filepath.Join(dest, "installed.txt")); strings.TrimSpace(string(b)) != build {
			p.Say("Installing...", -1)
			if err := install(dest); err != nil {
				fail("Couldn't install the " + appName + " client:\n" + err.Error() +
					"\n\nIf the client is already open, close it and try again.")
			}
		}
		hostFile := ensureServerFile()
		if err := client.Run(p, hostFile); err != nil {
			fail(err.Error())
		}
	})
}

// ensureServerFile returns the path of the server address file next to this exe, creating it if missing.
func ensureServerFile() string {
	self, _ := os.Executable()
	f := filepath.Join(filepath.Dir(self), serverFile)
	if _, err := os.Stat(f); err != nil {
		text := kit.ServerFileText(appName, defaultHost)
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

// unzipStrip unpacks data into dest, dropping the zip's top folder (kit.ExtractZip: no paths outside dest, no
// symlinks, size limits).
func unzipStrip(data []byte, dest string) error {
	return kit.ExtractZip(context.Background(), bytes.NewReader(data), int64(len(data)), dest, kit.ExtractOptions{StripTop: true})
}

func fail(msg string) {
	logf("FAIL: %s", msg)
	kit.MessageBox(0, appName, msg, kit.MbIconError)
	os.Exit(1)
}
