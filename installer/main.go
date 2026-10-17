// Uzi's Map Editor installer: double-click and it downloads the newest editor (with its own Java and the map),
// installs it to %LOCALAPPDATA%\Programs\Uzis Map Editor, puts a shortcut on the desktop and starts it.
// When a good install is already there it just starts it.
package main

import (
	"archive/zip"
	"bufio"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	appName = "Uzis Map Editor"
	latest  = "https://github.com/Uzi-Cohen/uzis-map-editor-releases/releases/latest/download/"
	fullZip = latest + "UzisMapEditor-full.zip"
	minJava = 21
)

func main() {
	dest := filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", appName)
	exe := filepath.Join(dest, appName+".exe")
	if !installed(dest) || outdated(dest) {
		fmt.Println("Installing Uzi's Map Editor. This takes a minute or two...")
		if err := install(dest); err != nil {
			fmt.Println("\nInstall failed:", err)
			fmt.Println("Press Enter to close.")
			bufio.NewReader(os.Stdin).ReadString('\n')
			os.Exit(1)
		}
		shortcut(exe, dest)
		fmt.Println("Done. There's a shortcut on your desktop now.")
	}
	cmd := exec.Command(exe)
	cmd.Dir = dest
	if err := cmd.Start(); err != nil {
		fmt.Println("Couldn't start the editor:", err)
		time.Sleep(10 * time.Second)
	}
}

// outdated is true when a newer editor is out than the one installed (a broken install gets replaced too).
// Offline, the installed one is used.
func outdated(dest string) bool {
	c := http.Client{Timeout: 10 * time.Second}
	resp, err := c.Get(latest + "version.txt")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return false
	}
	b, _ := io.ReadAll(resp.Body)
	have, _ := os.ReadFile(filepath.Join(dest, "app", "version.txt"))
	return strings.TrimSpace(string(b)) != strings.TrimSpace(string(have))
}

// installed is true when the editor and a new enough Java are there.
func installed(dest string) bool {
	if _, err := os.Stat(filepath.Join(dest, appName+".exe")); err != nil {
		return false
	}
	b, err := os.ReadFile(filepath.Join(dest, "runtime", "release"))
	if err != nil {
		return false
	}
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "JAVA_VERSION=") {
			v := strings.Trim(strings.TrimPrefix(l, "JAVA_VERSION="), "\"\r ")
			major, _ := strconv.Atoi(strings.SplitN(v, ".", 2)[0])
			return major >= minJava
		}
	}
	return false
}

func install(dest string) error {
	tmp, err := os.MkdirTemp("", "ume-install-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	zipPath := filepath.Join(tmp, "full.zip")
	if err := download(fullZip, zipPath); err != nil {
		return err
	}
	fmt.Println("Unpacking...")
	staged := filepath.Join(tmp, "out")
	if err := unzip(zipPath, staged); err != nil {
		return err
	}
	src := filepath.Join(staged, appName)
	if _, err := os.Stat(filepath.Join(src, appName+".exe")); err != nil {
		return fmt.Errorf("the download looks broken, try again")
	}
	// close a running copy so its files can be replaced
	exec.Command("taskkill", "/F", "/IM", appName+".exe").Run()
	time.Sleep(500 * time.Millisecond)
	os.RemoveAll(dest)
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	if err := os.Rename(src, dest); err != nil {
		return err
	}
	return nil
}

func download(url, to string) error {
	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("download failed (%s)", resp.Status)
	}
	f, err := os.Create(to)
	if err != nil {
		return err
	}
	defer f.Close()
	total := resp.ContentLength
	var done int64
	buf := make([]byte, 1<<20)
	last := time.Now()
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, err := f.Write(buf[:n]); err != nil {
				return err
			}
			done += int64(n)
			if time.Since(last) > 500*time.Millisecond {
				last = time.Now()
				if total > 0 {
					fmt.Printf("\rDownloading... %d%% (%d of %d MB)  ", done*100/total, done>>20, total>>20)
				} else {
					fmt.Printf("\rDownloading... %d MB  ", done>>20)
				}
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return rerr
		}
	}
	fmt.Println("\rDownloading... done                    ")
	return nil
}

func unzip(zipPath, dest string) error {
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer r.Close()
	for _, f := range r.File {
		p := filepath.Join(dest, filepath.FromSlash(f.Name))
		if !strings.HasPrefix(p, filepath.Clean(dest)+string(os.PathSeparator)) {
			return fmt.Errorf("bad path in download: %s", f.Name)
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
	ps := `$l = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'Uzi''s Map Editor.lnk')); ` +
		`$l.TargetPath = '` + strings.ReplaceAll(exe, "'", "''") + `'; $l.WorkingDirectory = '` + strings.ReplaceAll(dir, "'", "''") + `'; $l.Save()`
	exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps).Run()
}
