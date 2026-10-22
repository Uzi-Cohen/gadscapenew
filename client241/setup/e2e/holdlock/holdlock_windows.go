//go:build windows

// holdlock (e2e only): holds a setup's install mutex like a running setup does.
//
//	holdlock.exe <install folder name, e.g. GadScape241Test> <seconds>
package main

import (
	"os"
	"strconv"
	"syscall"
	"time"
	"unsafe"
)

func main() {
	if len(os.Args) != 3 {
		os.Exit(2)
	}
	secs, _ := strconv.Atoi(os.Args[2])
	k := syscall.NewLazyDLL("kernel32.dll")
	name, _ := syscall.UTF16PtrFromString(`Local\GadScapeSetup-` + os.Args[1])
	h, _, _ := k.NewProc("CreateMutexW").Call(0, 0, uintptr(unsafe.Pointer(name)))
	if h == 0 {
		os.Exit(1)
	}
	k.NewProc("WaitForSingleObject").Call(h, 0xFFFFFFFF)
	time.Sleep(time.Duration(secs) * time.Second)
	k.NewProc("ReleaseMutex").Call(h)
}
