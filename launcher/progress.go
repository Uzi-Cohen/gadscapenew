package main

import (
	"fmt"
	"runtime"
	"syscall"
	"unsafe"
)

// A small window that says what the launcher is doing (installing, downloading an editor update or a new map).
// It stays hidden unless there is something to show, so a normal start looks the same as before.

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	comctl32 = syscall.NewLazyDLL("comctl32.dll")
	gdi32    = syscall.NewLazyDLL("gdi32.dll")

	procCreateWindowEx     = user32.NewProc("CreateWindowExW")
	procDestroyWindow      = user32.NewProc("DestroyWindow")
	procShowWindowAsync    = user32.NewProc("ShowWindowAsync")
	procSetWindowText      = user32.NewProc("SetWindowTextW")
	procSendMessage        = user32.NewProc("SendMessageW")
	procPostMessage        = user32.NewProc("PostMessageW")
	procPostThreadMessage  = user32.NewProc("PostThreadMessageW")
	procGetMessage         = user32.NewProc("GetMessageW")
	procTranslateMessage   = user32.NewProc("TranslateMessage")
	procDispatchMessage    = user32.NewProc("DispatchMessageW")
	procGetSystemMetrics   = user32.NewProc("GetSystemMetrics")
	procGetCurrentThreadId = kernel32.NewProc("GetCurrentThreadId")
	procInitCommonControls = comctl32.NewProc("InitCommonControlsEx")
	procGetStockObject     = gdi32.NewProc("GetStockObject")
)

const (
	wsPopup      = 0x80000000
	wsChild      = 0x40000000
	wsVisible    = 0x10000000
	wsCaption    = 0x00C00000
	wsExTopmost  = 0x00000008
	wsExAppWin   = 0x00040000
	ssLeft       = 0x0
	wmSetFont    = 0x0030
	wmQuit       = 0x0012
	pbmSetRange  = 0x0406 // PBM_SETRANGE32
	pbmSetPos    = 0x0402
	swShow       = 5
	iccProgress  = 0x20
	defaultFont  = 17 // DEFAULT_GUI_FONT
	windowWidth  = 460
	windowHeight = 130
)

type winMsg struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	ptX     int32
	ptY     int32
	private uint32
}

type progress struct {
	hwnd, label, bar uintptr
	tid              uintptr
	shown            bool
	lastText         string
	lastPct          int
}

func wstr(s string) uintptr {
	p, _ := syscall.UTF16PtrFromString(s)
	return uintptr(unsafe.Pointer(p))
}

// withProgress runs work on another goroutine while this thread runs the window (hidden until work says something).
func withProgress(work func(p *progress)) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	p := newProgress()
	if p.hwnd == 0 {
		work(p) // no window: just do it
		return
	}
	go func() {
		defer procPostThreadMessage.Call(p.tid, wmQuit, 0, 0)
		work(p)
	}()
	var m winMsg
	for {
		r, _, _ := procGetMessage.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		procDispatchMessage.Call(uintptr(unsafe.Pointer(&m)))
	}
	procDestroyWindow.Call(p.hwnd)
}

func newProgress() *progress {
	p := &progress{lastPct: -2}
	icc := struct{ size, icc uint32 }{8, iccProgress}
	procInitCommonControls.Call(uintptr(unsafe.Pointer(&icc)))
	sw, _, _ := procGetSystemMetrics.Call(0)
	sh, _, _ := procGetSystemMetrics.Call(1)
	x := (int(sw) - windowWidth) / 2
	y := (int(sh) - windowHeight) / 2
	p.hwnd, _, _ = procCreateWindowEx.Call(wsExTopmost|wsExAppWin, wstr("STATIC"), wstr("Uzi's Map Editor"),
		wsPopup|wsCaption, uintptr(x), uintptr(y), windowWidth, windowHeight, 0, 0, 0, 0)
	if p.hwnd == 0 {
		return p
	}
	p.label, _, _ = procCreateWindowEx.Call(0, wstr("STATIC"), wstr(""), wsChild|wsVisible|ssLeft,
		16, 14, windowWidth-40, 22, p.hwnd, 0, 0, 0)
	p.bar, _, _ = procCreateWindowEx.Call(0, wstr("msctls_progress32"), 0, wsChild|wsVisible,
		16, 44, windowWidth-40, 22, p.hwnd, 0, 0, 0)
	font, _, _ := procGetStockObject.Call(defaultFont)
	procSendMessage.Call(p.label, wmSetFont, font, 1)
	procSendMessage.Call(p.bar, pbmSetRange, 0, 100)
	p.tid, _, _ = procGetCurrentThreadId.Call()
	return p
}

// say shows the window with text and how far along it is (pct 0-100; -1 for "can't tell").
func (p *progress) say(text string, pct int) {
	if p.hwnd == 0 {
		return
	}
	if !p.shown {
		p.shown = true
		procShowWindowAsync.Call(p.hwnd, swShow)
	}
	if text != p.lastText {
		p.lastText = text
		procSetWindowText.Call(p.label, wstr(text))
	}
	if pct < 0 {
		pct = 0
	}
	if pct != p.lastPct {
		p.lastPct = pct
		procPostMessage.Call(p.bar, pbmSetPos, uintptr(pct), 0)
	}
}

// bytes is a download step for say: "what... 12 of 65 MB".
func (p *progress) bytes(what string, done, total int64) {
	const mb = 1 << 20
	if total <= 0 {
		p.say(fmt.Sprintf("%s %d MB", what, done/mb), -1)
		return
	}
	p.say(fmt.Sprintf("%s %d of %d MB", what, done/mb, (total+mb-1)/mb), int(done*100/total))
}
