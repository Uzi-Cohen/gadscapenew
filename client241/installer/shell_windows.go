package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"runtime"
	"syscall"
	"time"
	"unsafe"
)

// Windows shell pieces: known folders, .lnk shortcuts (COM IShellLinkW), the Apps & features entry (HKCU
// registry), free disk space.

var (
	ole32    = syscall.NewLazyDLL("ole32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")
	advapi32 = syscall.NewLazyDLL("advapi32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")

	procCoInitializeEx       = ole32.NewProc("CoInitializeEx")
	procCoUninitialize       = ole32.NewProc("CoUninitialize")
	procCoCreateInstance     = ole32.NewProc("CoCreateInstance")
	procCoTaskMemFree        = ole32.NewProc("CoTaskMemFree")
	procSHGetKnownFolderPath = shell32.NewProc("SHGetKnownFolderPath")
	procRegCreateKeyEx       = advapi32.NewProc("RegCreateKeyExW")
	procRegSetValueEx        = advapi32.NewProc("RegSetValueExW")
	procRegCloseKey          = advapi32.NewProc("RegCloseKey")
	procRegDeleteTree        = advapi32.NewProc("RegDeleteTreeW")
	procGetDiskFreeSpaceEx   = kernel32.NewProc("GetDiskFreeSpaceExW")
	procOpenProcess          = kernel32.NewProc("OpenProcess")
	procWaitForSingleObject  = kernel32.NewProc("WaitForSingleObject")
	procCreateMutex          = kernel32.NewProc("CreateMutexW")
	procReleaseMutex         = kernel32.NewProc("ReleaseMutex")
)

var (
	installLock uintptr
	lockHeld    bool // this process holds installLock (taking it twice would need two releases)
)

// lockInstall takes a mutex for this install folder, so two setups (say the installed launcher and a setup the
// player started again) never update the same files at once. It waits up to wait, in short steps so that Cancel
// (ctx) ends the wait; false: another one still runs, or ctx was cancelled. Taking it again while holding it does
// nothing. A mutex belongs to a thread: the caller must stay on its OS thread (runtime.LockOSThread) until
// unlockInstall.
func lockInstall(ctx context.Context, wait time.Duration) bool {
	if lockHeld {
		return true
	}
	if installLock == 0 {
		installLock, _, _ = procCreateMutex.Call(0, 0, wptr(`Local\GadScapeSetup-`+dirName))
		if installLock == 0 {
			return true // no mutex: go on rather than refuse
		}
	}
	deadline := time.Now().Add(wait)
	for {
		step := time.Until(deadline)
		if step > 250*time.Millisecond {
			step = 250 * time.Millisecond
		} else if step < 0 {
			step = 0
		}
		switch r, _, _ := procWaitForSingleObject.Call(installLock, uintptr(step.Milliseconds())); r {
		case 0, 0x80: // WAIT_OBJECT_0, or WAIT_ABANDONED (the other one ended without releasing it)
			lockHeld = true
			return true
		case 0xFFFFFFFF: // WAIT_FAILED: go on rather than refuse
			return true
		}
		if ctx.Err() != nil || !time.Now().Before(deadline) {
			return false
		}
	}
}

// unlockInstall lets the next setup in (before starting the client or another launcher).
func unlockInstall() {
	if lockHeld {
		procReleaseMutex.Call(installLock)
		lockHeld = false
	}
}

type guid struct {
	d1     uint32
	d2, d3 uint16
	d4     [8]byte
}

var (
	folderDesktop  = guid{0xB4BFCC3A, 0xDB2C, 0x424C, [8]byte{0xB0, 0x29, 0x7F, 0xE9, 0x9A, 0x87, 0xC6, 0x41}}
	folderPrograms = guid{0xA77F5D77, 0x2E2B, 0x44C3, [8]byte{0xA6, 0xA2, 0xAB, 0xA6, 0x01, 0x05, 0x4A, 0x51}}
	clsidShellLink = guid{0x00021401, 0, 0, [8]byte{0xC0, 0, 0, 0, 0, 0, 0, 0x46}}
	iidShellLinkW  = guid{0x000214F9, 0, 0, [8]byte{0xC0, 0, 0, 0, 0, 0, 0, 0x46}}
	iidPersistFile = guid{0x0000010B, 0, 0, [8]byte{0xC0, 0, 0, 0, 0, 0, 0, 0x46}}
)

func wptr(s string) uintptr {
	p, _ := syscall.UTF16PtrFromString(s)
	return uintptr(unsafe.Pointer(p))
}

func knownFolder(id *guid) (string, error) {
	var p *uint16
	hr, _, _ := procSHGetKnownFolderPath.Call(uintptr(unsafe.Pointer(id)), 0, 0, uintptr(unsafe.Pointer(&p)))
	if hr != 0 || p == nil {
		return "", fmt.Errorf("SHGetKnownFolderPath: %#x", hr)
	}
	defer procCoTaskMemFree.Call(uintptr(unsafe.Pointer(p)))
	n := 0
	for s := unsafe.Pointer(p); *(*uint16)(unsafe.Add(s, 2*n)) != 0; n++ {
	}
	return syscall.UTF16ToString(unsafe.Slice(p, n)), nil
}

func desktopDir() (string, error)  { return knownFolder(&folderDesktop) }
func programsDir() (string, error) { return knownFolder(&folderPrograms) }

// comObj is a COM interface pointer: a pointer to its method table.
type comObj struct{ vtbl *[64]uintptr }

func (o *comObj) call(method int, args ...uintptr) uintptr {
	r, _, _ := syscall.SyscallN(o.vtbl[method], append([]uintptr{uintptr(unsafe.Pointer(o))}, args...)...)
	return r
}

// IShellLinkW / IPersistFile method numbers (IUnknown is 0-2)
const (
	mQueryInterface  = 0
	mRelease         = 2
	mSetDescription  = 7
	mSetWorkingDir   = 9
	mSetArguments    = 11
	mSetIconLocation = 17
	mSetPath         = 20
	mPersistSave     = 6
)

// createShortcut writes a .lnk file.
func createShortcut(lnk, target, args, workDir, icon, desc string) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	hr, _, _ := procCoInitializeEx.Call(0, 2) // COINIT_APARTMENTTHREADED
	if hr == 0 || hr == 1 {
		defer procCoUninitialize.Call()
	}
	var sl *comObj
	hr, _, _ = procCoCreateInstance.Call(uintptr(unsafe.Pointer(&clsidShellLink)), 0, 1, // CLSCTX_INPROC_SERVER
		uintptr(unsafe.Pointer(&iidShellLinkW)), uintptr(unsafe.Pointer(&sl)))
	if hr != 0 || sl == nil {
		return fmt.Errorf("CoCreateInstance(ShellLink): %#x", hr)
	}
	defer sl.call(mRelease)
	if hr := sl.call(mSetPath, wptr(target)); hr != 0 {
		return fmt.Errorf("SetPath: %#x", hr)
	}
	sl.call(mSetArguments, wptr(args))
	sl.call(mSetWorkingDir, wptr(workDir))
	sl.call(mSetIconLocation, wptr(icon), 0)
	sl.call(mSetDescription, wptr(desc))
	var pf *comObj
	if hr := sl.call(mQueryInterface, uintptr(unsafe.Pointer(&iidPersistFile)), uintptr(unsafe.Pointer(&pf))); hr != 0 || pf == nil {
		return fmt.Errorf("QueryInterface(IPersistFile): %#x", hr)
	}
	defer pf.call(mRelease)
	if hr := pf.call(mPersistSave, wptr(lnk), 1); hr != 0 {
		return fmt.Errorf("saving %s: %#x", lnk, hr)
	}
	return nil
}

const (
	hkeyCurrentUser = 0x80000001
	keyWrite        = 0x20006
	regSZ           = 1
	regDWORD        = 4
)

func uninstallKey() string {
	return `Software\Microsoft\Windows\CurrentVersion\Uninstall\` + dirName
}

// registerUninstall writes the Apps & features entry (HKCU: no admin rights needed).
func registerUninstall(dest, exe, version string, sizeKB uint32) error {
	var key syscall.Handle
	r, _, _ := procRegCreateKeyEx.Call(hkeyCurrentUser, wptr(uninstallKey()), 0, 0, 0, keyWrite, 0, uintptr(unsafe.Pointer(&key)), 0)
	if r != 0 {
		return fmt.Errorf("RegCreateKeyEx: %d", r)
	}
	defer procRegCloseKey.Call(uintptr(key))
	str := func(name, value string) error {
		v, _ := syscall.UTF16FromString(value)
		r, _, _ := procRegSetValueEx.Call(uintptr(key), wptr(name), 0, regSZ, uintptr(unsafe.Pointer(&v[0])), uintptr(2*len(v)))
		if r != 0 {
			return fmt.Errorf("RegSetValueEx %s: %d", name, r)
		}
		return nil
	}
	dword := func(name string, value uint32) error {
		r, _, _ := procRegSetValueEx.Call(uintptr(key), wptr(name), 0, regDWORD, uintptr(unsafe.Pointer(&value)), 4)
		if r != 0 {
			return fmt.Errorf("RegSetValueEx %s: %d", name, r)
		}
		return nil
	}
	q := `"` + exe + `"`
	return errors.Join(
		str("DisplayName", appName),
		str("DisplayIcon", q+",0"),
		str("DisplayVersion", version),
		str("Publisher", publisher),
		str("UninstallString", q+" --uninstall"),
		str("QuietUninstallString", q+" --uninstall --silent"),
		str("InstallLocation", dest),
		dword("NoModify", 1),
		dword("NoRepair", 1),
		dword("EstimatedSize", sizeKB),
	)
}

func unregisterUninstall() error {
	r, _, _ := procRegDeleteTree.Call(hkeyCurrentUser, wptr(uninstallKey()))
	if r != 0 && r != 2 { // 2: ERROR_FILE_NOT_FOUND
		return fmt.Errorf("RegDeleteTree: %d", r)
	}
	return nil
}

// freeSpace is the free space for this user on the drive of path.
func freeSpace(path string) (int64, error) {
	for p := path; p != ""; {
		if _, err := os.Stat(p); err == nil {
			path = p
			break
		}
		parent := parentDir(p)
		if parent == p {
			break
		}
		p = parent
	}
	var free, total, totalFree uint64
	r, _, err := procGetDiskFreeSpaceEx.Call(wptr(path), uintptr(unsafe.Pointer(&free)), uintptr(unsafe.Pointer(&total)), uintptr(unsafe.Pointer(&totalFree)))
	if r == 0 {
		return 0, err
	}
	return int64(free), nil
}

func parentDir(p string) string {
	for i := len(p) - 1; i > 0; i-- {
		if p[i] == '\\' || p[i] == '/' {
			return p[:i]
		}
	}
	return p
}

// waitForExit waits up to ms milliseconds for process pid to end.
func waitForExit(pid int, ms uint32) {
	h, _, _ := procOpenProcess.Call(0x00100000, 0, uintptr(pid)) // SYNCHRONIZE
	if h == 0 {
		return
	}
	defer syscall.CloseHandle(syscall.Handle(h))
	procWaitForSingleObject.Call(h, uintptr(ms))
}
