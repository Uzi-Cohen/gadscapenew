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
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

//go:embed payload/jre.zip
var jreZip []byte

//go:embed payload/editor.jar
var editorJar []byte

// set at build time: -X main.build=1.0-build-N -X main.stamp=<build time, ms since 1970>
var build = "dev"
var stamp = "0"

const (
	appName  = "Uzis Map Editor"
	releases = "https://github.com/Uzi-Cohen/uzis-map-editor-releases/releases/"
	// Every merge to main publishes the editor here (Build workflow), so friends get it on their next start.
	editorReleases = "https://github.com/Uzi-Cohen/gadscape/releases/"
	mapZip         = releases + "download/map/map.zip"
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
	placeSelf(dest)
	update(dest)
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
	os.WriteFile(filepath.Join(dest, "editor-version.txt"), []byte(stamp), 0o644)
	return os.WriteFile(filepath.Join(dest, "installed.txt"), []byte(build), 0o644)
}

// placeSelf keeps a copy of this exe in the install folder (the desktop shortcut keeps working after the download
// is deleted, with this exe's icon), replacing an older or different one, and points the shortcut at it.
func placeSelf(dest string) {
	self, _ := os.Executable()
	exe := filepath.Join(dest, appName+".exe")
	if self == "" || strings.EqualFold(filepath.Clean(self), filepath.Clean(exe)) {
		return
	}
	si, err1 := os.Stat(self)
	di, err2 := os.Stat(exe)
	if err1 == nil && (err2 != nil || si.Size() != di.Size() || si.ModTime().After(di.ModTime())) {
		b, err := os.ReadFile(self)
		if err == nil {
			err = os.WriteFile(exe, b, 0o755)
		}
		if err != nil {
			exe = self // the shortcut then points at this exe where it is
		}
	}
	shortcut(exe, dest)
}

// update gets the newest editor build from main when it's newer than the one here, and the newest map Publish uploaded.
// Offline or on any error the installed ones are used.
func update(dest string) {
	c := http.Client{Timeout: 15 * time.Second}
	text := func(url string) string {
		r, err := c.Get(url)
		if err != nil {
			return ""
		}
		defer r.Body.Close()
		if r.StatusCode != 200 {
			return ""
		}
		b, _ := io.ReadAll(io.LimitReader(r.Body, 100))
		return strings.TrimSpace(string(b))
	}
	local := func(name string) string {
		b, _ := os.ReadFile(filepath.Join(dest, name))
		return strings.TrimSpace(string(b))
	}

	if remote, err := strconv.ParseInt(text(editorReleases+"latest/download/editor-version.txt"), 10, 64); err == nil {
		have, _ := strconv.ParseInt(local("editor-version.txt"), 10, 64)
		if remote > have {
			tmp := filepath.Join(dest, "editor.jar.new")
			if download(editorReleases+"latest/download/editor.jar", tmp) == nil && validJar(tmp) {
				os.Remove(filepath.Join(dest, "editor.jar"))
				if os.Rename(tmp, filepath.Join(dest, "editor.jar")) == nil {
					os.WriteFile(filepath.Join(dest, "editor-version.txt"), []byte(strconv.FormatInt(remote, 10)), 0o644)
				}
			}
			os.Remove(tmp)
		}
	}

	if remote := text(releases + "download/map/map-version.txt"); remote != "" && remote != local(filepath.Join("map", "map-version.txt")) {
		tmp := filepath.Join(dest, "map.zip.new")
		if download(mapZip, tmp) == nil {
			if b, err := os.ReadFile(tmp); err == nil {
				staging := filepath.Join(dest, "map.new")
				os.RemoveAll(staging)
				if unzipStrip(b, staging) == nil {
					if _, err := os.Stat(filepath.Join(staging, "cache", "main_file_cache.dat2")); err == nil {
						os.RemoveAll(filepath.Join(dest, "map"))
						if os.Rename(staging, filepath.Join(dest, "map")) == nil {
							os.WriteFile(filepath.Join(dest, "map", "map-version.txt"), []byte(remote), 0o644)
						}
					}
				}
				os.RemoveAll(staging)
			}
		}
		os.Remove(tmp)
	}
}

func download(url, to string) error {
	c := http.Client{Timeout: 30 * time.Minute}
	r, err := c.Get(url)
	if err != nil {
		return err
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return fmt.Errorf("%s", r.Status)
	}
	f, err := os.Create(to)
	if err != nil {
		return err
	}
	_, err = io.Copy(f, r.Body)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

// validJar is true when the download is a whole editor jar.
func validJar(path string) bool {
	r, err := zip.OpenReader(path)
	if err != nil {
		return false
	}
	defer r.Close()
	for _, f := range r.File {
		if f.Name == "AppKt.class" {
			return true
		}
	}
	return false
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
