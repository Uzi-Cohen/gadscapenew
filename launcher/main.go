// Uzi's Map Editor, one file: Java 21 and the editor are packed inside this exe. The first run (and the first run
// of a newer exe) unpacks them and puts a shortcut on the desktop;
// every run starts the editor. Nothing else needs to be installed.
// Installs to %LOCALAPPDATA%\UzisMapEditor.
package main

import (
	"archive/zip"
	"bytes"
	_ "embed"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

//go:embed payload/jre.zip
var jreZip []byte

//go:embed payload/editor.jar
var editorJar []byte

// set at build time (-X main.build=...)
var build = "dev"

const (
	appName = "Uzis Map Editor"
	mapZip  = "https://github.com/Uzi-Cohen/uzis-map-editor-releases/releases/download/map/map.zip"
)

func main() {
	// its own folder: the old installer's folder (Programs\Uzis Map Editor) has an auto-updater that puts the old
	// launcher back
	dest := filepath.Join(os.Getenv("LOCALAPPDATA"), "UzisMapEditor")
	if b, _ := os.ReadFile(filepath.Join(dest, "installed.txt")); strings.TrimSpace(string(b)) != build {
		if err := install(dest); err != nil {
			fail("Couldn't install Uzi's Map Editor:\n" + err.Error())
		}
	}
	javaw := filepath.Join(dest, "java", "bin", "javaw.exe")
	cmd := exec.Command(javaw, "-Dume.appdir="+filepath.Join(dest, "app"), "-Dume.noupdate=true", "-jar", filepath.Join(dest, "editor.jar"))
	cmd.Dir = dest
	if err := cmd.Start(); err != nil {
		fail("Couldn't start Uzi's Map Editor:\n" + err.Error())
	}
}

func install(dest string) error {
	// close a running copy so its files can be replaced
	exec.Command("taskkill", "/F", "/FI", "IMAGENAME eq javaw.exe", "/FI", "WINDOWTITLE eq Uzi*").Run()
	os.RemoveAll(filepath.Join(dest, "java"))
	os.RemoveAll(filepath.Join(dest, "app"))
	if err := os.MkdirAll(filepath.Join(dest, "app"), 0o755); err != nil {
		return err
	}
	if err := unzipStrip(jreZip, filepath.Join(dest, "java")); err != nil {
		return fmt.Errorf("unpacking Java: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dest, "editor.jar"), editorJar, 0o644); err != nil {
		return err
	}
	os.WriteFile(filepath.Join(dest, "app", "version.txt"), []byte(build), 0o644)
	// this exe goes in too, so the desktop shortcut works after the download is deleted
	self, _ := os.Executable()
	exe := filepath.Join(dest, appName+".exe")
	if self != "" && !strings.EqualFold(filepath.Clean(self), filepath.Clean(exe)) {
		b, err := os.ReadFile(self)
		if err == nil {
			err = os.WriteFile(exe, b, 0o755)
		}
		if err != nil {
			exe = self // the shortcut then points at this exe where it is
		}
	}
	getMap(dest)
	shortcut(exe, dest)
	return os.WriteFile(filepath.Join(dest, "installed.txt"), []byte(build), 0o644)
}

// The shared map, if there's none yet (best effort: the editor can also open any cache folder).
func getMap(dest string) {
	if _, err := os.Stat(filepath.Join(dest, "map", "cache", "main_file_cache.dat2")); err == nil {
		return
	}
	c := http.Client{Timeout: 10 * time.Minute}
	resp, err := c.Get(mapZip)
	if err != nil || resp.StatusCode != 200 {
		return
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return
	}
	// map.zip holds map/cache/... and map/xteas.json
	unzipStrip(b, filepath.Join(dest, "map"))
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

func shortcut(exe, dir string) {
	q := func(s string) string { return strings.ReplaceAll(s, "'", "''") }
	ps := `$l = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'Uzi''s Map Editor.lnk')); ` +
		`$l.TargetPath = '` + q(exe) + `'; $l.WorkingDirectory = '` + q(dir) + `'; $l.Save()`
	c := exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", ps)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	c.Run()
}

func fail(msg string) {
	t, _ := syscall.UTF16PtrFromString("Uzi's Map Editor")
	m, _ := syscall.UTF16PtrFromString(msg)
	syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), 0x10)
	os.Exit(1)
}
