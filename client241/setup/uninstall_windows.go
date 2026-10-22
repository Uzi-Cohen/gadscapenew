package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"gadscapeclient/kit"
	"gadscapesetup/internal/robust"
)

// --uninstall (Apps & features, or GadScape.exe --uninstall [--silent] [--keep-home|--remove-home]):
// removes the install folder, then the shortcuts and the Apps & features entry. The home folder (RuneLite settings,
// saved logins, game cache) is only removed when the player says so (silent: --remove-home).
//
// The launcher cannot delete its own exe while it runs, so stage 1 copies itself to %TEMP% and starts that copy
// (stage 2), which waits for stage 1 to end, does the work and then has cmd.exe delete it.

func (a *app) uninstall() int {
	self, err := os.Executable()
	if err != nil {
		return exitError
	}
	tmp := filepath.Join(os.TempDir(), fmt.Sprintf("%s-uninstall-%d.exe", dirName, os.Getpid()))
	if err := copyFile(self, tmp); err != nil {
		a.log = kit.OpenLog(filepath.Join(os.TempDir(), dirName+"-uninstall.log"))
		return a.fail("Couldn't start the uninstaller: "+err.Error(), exitError)
	}
	args := []string{"--uninstall-stage2", "--parent=" + strconv.Itoa(os.Getpid())}
	for _, s := range a.args {
		if s != "--uninstall" {
			args = append(args, s)
		}
	}
	if err := startDetached(tmp, args); err != nil {
		os.Remove(tmp)
		return exitError
	}
	return exitOK
}

func (a *app) uninstallStage2() int {
	a.log = kit.OpenLog(filepath.Join(os.TempDir(), dirName+"-uninstall.log"))
	self, _ := os.Executable()
	defer deleteLater(self)
	for _, s := range a.args {
		if v, ok := strings.CutPrefix(s, "--parent="); ok {
			if pid, err := strconv.Atoi(v); err == nil {
				waitForExit(pid, 15000)
			}
		}
	}
	a.logf("uninstall %s from %s %q", appName, a.dest, a.args)
	if !lockInstall(context.Background(), time.Minute) {
		return a.fail("A "+appName+" setup is running. Close it and try again.", exitError)
	}
	if !a.silent && a.ask(fmt.Sprintf("Remove %s from this computer?", appName), kit.MbYesNo|kit.MbIconQuest, kit.IDYes) != kit.IDYes {
		return exitCancelled
	}
	for {
		running := kit.ProcessesIn(a.dest)
		if len(running) == 0 {
			break
		}
		a.logf("still running: %v", running)
		if a.ask(fmt.Sprintf("%s is still open. Close it, then press Retry.", appName), kit.MbRetryCancel|kit.MbIconWarning, kit.IDCancel) != kit.IDRetry {
			return a.fail(appName+" was not removed because it is still open.", exitCancelled)
		}
	}
	home := a.client.Home()
	removeHome := has(a.args, "--remove-home")
	if !a.silent && !has(a.args, "--keep-home") && !removeHome && exists(home) {
		removeHome = a.ask(fmt.Sprintf("Also delete your %s settings, saved logins and game cache?\n\n%s\n\nNo keeps them for when you install %s again.", appName, home, appName),
			kit.MbYesNo|kit.MbIconQuest|kit.MbDefButton2, kit.IDNo) == kit.IDYes
	}
	// Files first, the launcher (GadScape.exe) after them, and the shortcuts and the Apps & features entry only
	// once all of that is gone: if something cannot be deleted (a file a virus scanner or another program holds),
	// the entry still points at a launcher that runs the uninstall again.
	var errs []error
	ents, _ := os.ReadDir(a.dest)
	for _, e := range ents {
		if (strings.EqualFold(e.Name(), "home") && !removeHome) || strings.EqualFold(e.Name(), launcherName) {
			continue
		}
		if err := robust.RemoveAll(filepath.Join(a.dest, e.Name())); err != nil {
			errs = append(errs, err)
		}
	}
	if len(errs) == 0 {
		if err := robust.Remove(a.launcherPath()); err != nil && !errors.Is(err, os.ErrNotExist) {
			errs = append(errs, err)
		}
	}
	a.logf("home folder %s: removed=%v", home, removeHome && len(errs) == 0)
	if err := errors.Join(errs...); err != nil {
		return a.fail(fmt.Sprintf("%s could not be removed completely, some files could not be deleted:\n%v\n\n"+
			"Close every %s window (and any program that has files in %s open), then run the uninstall again from Apps & features.",
			appName, err, appName, a.dest), exitError)
	}
	if left, _ := os.ReadDir(a.dest); len(left) == 0 {
		os.Remove(a.dest)
	}
	for _, f := range []func() (string, error){desktopDir, programsDir} {
		if dir, err := f(); err == nil {
			p := filepath.Join(dir, appName+".lnk")
			if err := robust.Remove(p); err == nil {
				a.logf("removed %s", p)
			} else if !errors.Is(err, os.ErrNotExist) {
				errs = append(errs, err)
			}
		}
	}
	if err := unregisterUninstall(); err != nil {
		errs = append(errs, err)
	} else {
		a.logf("removed the Apps & features entry")
	}
	if err := errors.Join(errs...); err != nil {
		return a.fail(fmt.Sprintf("%s was removed, but a shortcut or its Apps & features entry could not be deleted:\n%v", appName, err), exitError)
	}
	a.logf("uninstalled")
	if !a.silent {
		msg := appName + " was removed."
		if !removeHome && exists(home) {
			msg += "\n\nYour settings and saved logins are still in " + home + "."
		}
		kit.MessageBox(0, appName, msg, kit.MbIconInfo|kit.MbTopMost)
	}
	return exitOK
}

// deleteLater has cmd.exe delete this temporary uninstaller once it has ended.
func deleteLater(exe string) {
	cmd := exec.Command("cmd.exe")
	cmd.SysProcAttr = &syscall.SysProcAttr{
		CmdLine:       `cmd.exe /c ping -n 3 127.0.0.1 >nul & del /f /q "` + exe + `"`,
		HideWindow:    true,
		CreationFlags: 0x08000000, // CREATE_NO_WINDOW
	}
	cmd.Start()
}
