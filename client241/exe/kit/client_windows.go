package kit

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

// Run starts the installed client with the server address file hostFile and watches it until its window shows
// (hiding w after a few seconds). A client that closes before showing a window has crashed: the error then is the
// crash report to show the player.
func (c Client) Run(w *Window, hostFile string) error {
	javaw := c.Javaw()
	args := c.JavaArgs(hostFile)
	c.logf("starting %s %s", javaw, strings.Join(args, " "))
	cmd := exec.Command(javaw, args...)
	cmd.Dir = c.App()
	cmd.Env = CleanEnv(os.Environ())
	out, err := os.Create(filepath.Join(c.Dest, "client-output.log"))
	if err == nil {
		cmd.Stdout = out
		cmd.Stderr = out
		defer out.Close()
	}
	started := time.Now()
	if err := cmd.Start(); err != nil {
		return errors.New("Couldn't start the client:\n" + err.Error())
	}
	if w.Shown() {
		w.Say("Starting...", -1)
	}
	// a client that closes right away has crashed: say why instead of just vanishing
	exited := make(chan error, 1)
	go func() { exited <- cmd.Wait() }()
	for wait := 0; ; wait++ {
		select {
		case err := <-exited:
			return errors.New(c.CrashReport(err, time.Since(started)))
		case <-time.After(time.Second):
		}
		if HasVisibleWindow(cmd.Process.Pid) {
			c.logf("client window is up after %ds", wait+1)
			return nil
		}
		if wait == 3 {
			w.Hide()
		}
		if wait == 40 {
			c.logf("client running but no window after 40s")
			return errors.New(c.CrashReport(fmt.Errorf("it is running but never showed a window"), time.Since(started)))
		}
	}
}

var (
	procCreateToolhelp32Snapshot = kernel32.NewProc("CreateToolhelp32Snapshot")
	procProcess32First           = kernel32.NewProc("Process32FirstW")
	procProcess32Next            = kernel32.NewProc("Process32NextW")
	procOpenProcess              = kernel32.NewProc("OpenProcess")
	procQueryFullImageName       = kernel32.NewProc("QueryFullProcessImageNameW")
)

type processEntry32 struct {
	size            uint32
	usage           uint32
	processID       uint32
	defaultHeapID   uintptr
	moduleID        uint32
	threads         uint32
	parentProcessID uint32
	priClassBase    int32
	flags           uint32
	exeFile         [260]uint16
}

// ProcessesIn lists the running processes whose program file is inside dir (for example the client's javaw.exe
// in <dest>\java), as "pid path". Processes it may not look at are skipped.
func ProcessesIn(dir string) []string {
	snap, _, _ := procCreateToolhelp32Snapshot.Call(0x2, 0) // TH32CS_SNAPPROCESS
	if snap == uintptr(syscall.InvalidHandle) || snap == 0 {
		return nil
	}
	defer syscall.CloseHandle(syscall.Handle(snap))
	prefix := strings.ToLower(filepath.Clean(dir)) + string(filepath.Separator)
	self := os.Getpid()
	var found []string
	var e processEntry32
	e.size = uint32(unsafe.Sizeof(e))
	for r, _, _ := procProcess32First.Call(snap, uintptr(unsafe.Pointer(&e))); r != 0; r, _, _ = procProcess32Next.Call(snap, uintptr(unsafe.Pointer(&e))) {
		pid := int(e.processID)
		if pid == 0 || pid == self {
			continue
		}
		h, _, _ := procOpenProcess.Call(0x1000, 0, uintptr(pid)) // PROCESS_QUERY_LIMITED_INFORMATION
		if h == 0 {
			continue
		}
		buf := make([]uint16, 1024)
		n := uint32(len(buf))
		ok, _, _ := procQueryFullImageName.Call(h, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&n)))
		syscall.CloseHandle(syscall.Handle(h))
		if ok == 0 {
			continue
		}
		p := syscall.UTF16ToString(buf[:n])
		if strings.HasPrefix(strings.ToLower(filepath.Clean(p)), prefix) {
			found = append(found, fmt.Sprintf("%d %s", pid, p))
		}
	}
	return found
}
