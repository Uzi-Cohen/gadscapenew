package main

import (
	"bytes"
	_ "embed"
	"fmt"
	"image"
	"image/draw"
	"image/png"
	"os"
	"runtime"
	"sync"
	"syscall"
	"unsafe"
)

// The editor's own loading window (the same splash.png, the same orange text), drawn by the launcher while it installs
// or downloads, with a progress bar along the bottom. It stays hidden unless there is something to show; once the
// editor starts, its own splash opens in the same place.

//go:embed splash.png
var splashPng []byte

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	gdi32    = syscall.NewLazyDLL("gdi32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")

	procRegisterClassEx    = user32.NewProc("RegisterClassExW")
	procCreateWindowEx     = user32.NewProc("CreateWindowExW")
	procDestroyWindow      = user32.NewProc("DestroyWindow")
	procDefWindowProc      = user32.NewProc("DefWindowProcW")
	procShowWindowAsync    = user32.NewProc("ShowWindowAsync")
	procInvalidateRect     = user32.NewProc("InvalidateRect")
	procBeginPaint         = user32.NewProc("BeginPaint")
	procEndPaint           = user32.NewProc("EndPaint")
	procPostThreadMessage  = user32.NewProc("PostThreadMessageW")
	procGetMessage         = user32.NewProc("GetMessageW")
	procTranslateMessage   = user32.NewProc("TranslateMessage")
	procDispatchMessage    = user32.NewProc("DispatchMessageW")
	procGetSystemMetrics   = user32.NewProc("GetSystemMetrics")
	procLoadCursor         = user32.NewProc("LoadCursorW")
	procFillRect           = user32.NewProc("FillRect")
	procGetCurrentThreadId = kernel32.NewProc("GetCurrentThreadId")
	procGetModuleHandle    = kernel32.NewProc("GetModuleHandleW")
	procCreateDIBSection   = gdi32.NewProc("CreateDIBSection")
	procCreateCompatibleDC = gdi32.NewProc("CreateCompatibleDC")
	procCreateCompatBitmap = gdi32.NewProc("CreateCompatibleBitmap")
	procSelectObject       = gdi32.NewProc("SelectObject")
	procDeleteObject       = gdi32.NewProc("DeleteObject")
	procDeleteDC           = gdi32.NewProc("DeleteDC")
	procBitBlt             = gdi32.NewProc("BitBlt")
	procCreateFont         = gdi32.NewProc("CreateFontW")
	procSetBkMode          = gdi32.NewProc("SetBkMode")
	procSetTextColor       = gdi32.NewProc("SetTextColor")
	procTextOut            = gdi32.NewProc("TextOutW")
	procCreateSolidBrush   = gdi32.NewProc("CreateSolidBrush")
	procExtractIcon        = shell32.NewProc("ExtractIconW")
)

const (
	wsPopup      = 0x80000000
	wsExAppWin   = 0x00040000
	wmPaint      = 0x000F
	wmEraseBkgnd = 0x0014
	wmNcHitTest  = 0x0084
	wmQuit       = 0x0012
	htCaption    = 2
	swShow       = 5
	srcCopy      = 0x00CC0020
	transparent  = 1
	idcArrow     = 32512
)

// the editor's label: #ff981f, Verdana bold 13px, black shadow 1px down-right, 24px from the left
const (
	textColor   = 0x001F98FF // COLORREF is 0x00BBGGRR
	shadowColor = 0x00000000
	barBorder   = 0x00000000
	barEmpty    = 0x00182028
	barFill     = 0x001F98FF
	textX       = 24
)

type rect struct{ left, top, right, bottom int32 }

type paintStruct struct {
	hdc         uintptr
	erase       int32
	rc          rect
	restore     int32
	incUpdate   int32
	rgbReserved [32]byte
}

type wndClassEx struct {
	size       uint32
	style      uint32
	wndProc    uintptr
	clsExtra   int32
	wndExtra   int32
	instance   uintptr
	icon       uintptr
	cursor     uintptr
	background uintptr
	menuName   *uint16
	className  *uint16
	iconSm     uintptr
}

type bitmapInfoHeader struct {
	size          uint32
	width         int32
	height        int32
	planes        uint16
	bitCount      uint16
	compression   uint32
	sizeImage     uint32
	xPelsPerMeter int32
	yPelsPerMeter int32
	clrUsed       uint32
	clrImportant  uint32
}

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
	hwnd   uintptr
	tid    uintptr
	splash uintptr // DIB of splash.png
	font   uintptr
	w, h   int32
	shown  bool

	mu   sync.Mutex
	text string
	pct  int // -1: no bar
}

var current *progress

func wstr(s string) *uint16 {
	p, _ := syscall.UTF16PtrFromString(s)
	return p
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
	p := &progress{pct: -1}
	img, err := png.Decode(bytes.NewReader(splashPng))
	if err != nil {
		return p
	}
	b := img.Bounds()
	p.w, p.h = int32(b.Dx()), int32(b.Dy())
	p.splash = dibOf(img)
	if p.splash == 0 {
		return p
	}
	p.font, _, _ = procCreateFont.Call(^uintptr(13-1), 0, 0, 0, 700, 0, 0, 0, 1, 0, 0, 5, 0, uintptr(unsafe.Pointer(wstr("Verdana"))))

	inst, _, _ := procGetModuleHandle.Call(0)
	cursor, _, _ := procLoadCursor.Call(0, idcArrow)
	exe, _ := os.Executable()
	icon, _, _ := procExtractIcon.Call(inst, uintptr(unsafe.Pointer(wstr(exe))), 0)
	if icon <= 1 {
		icon = 0
	}
	wc := wndClassEx{
		wndProc:   syscall.NewCallback(splashProc),
		instance:  inst,
		icon:      icon,
		cursor:    cursor,
		className: wstr("UzisMapEditorSplash"),
		iconSm:    icon,
	}
	wc.size = uint32(unsafe.Sizeof(wc))
	procRegisterClassEx.Call(uintptr(unsafe.Pointer(&wc)))

	sw, _, _ := procGetSystemMetrics.Call(0)
	sh, _, _ := procGetSystemMetrics.Call(1)
	x := (int32(sw) - p.w) / 2
	y := (int32(sh) - p.h) / 2
	current = p
	p.hwnd, _, _ = procCreateWindowEx.Call(wsExAppWin, uintptr(unsafe.Pointer(wc.className)),
		uintptr(unsafe.Pointer(wstr("Uzi's Map Editor"))), wsPopup,
		uintptr(x), uintptr(y), uintptr(p.w), uintptr(p.h), 0, 0, inst, 0)
	p.tid, _, _ = procGetCurrentThreadId.Call()
	return p
}

// dibOf turns the splash into a GDI bitmap.
func dibOf(img image.Image) uintptr {
	b := img.Bounds()
	rgba := image.NewRGBA(image.Rect(0, 0, b.Dx(), b.Dy()))
	draw.Draw(rgba, rgba.Bounds(), img, b.Min, draw.Src)
	bi := bitmapInfoHeader{width: int32(b.Dx()), height: -int32(b.Dy()), planes: 1, bitCount: 32}
	bi.size = uint32(unsafe.Sizeof(bi))
	var bits unsafe.Pointer
	h, _, _ := procCreateDIBSection.Call(0, uintptr(unsafe.Pointer(&bi)), 0, uintptr(unsafe.Pointer(&bits)), 0, 0)
	if h == 0 || bits == nil {
		return 0
	}
	dst := unsafe.Slice((*byte)(bits), len(rgba.Pix))
	for i := 0; i < len(rgba.Pix); i += 4 { // RGBA -> BGRA
		dst[i], dst[i+1], dst[i+2], dst[i+3] = rgba.Pix[i+2], rgba.Pix[i+1], rgba.Pix[i], 255
	}
	return h
}

func splashProc(hwnd, msg, wParam, lParam uintptr) uintptr {
	switch msg {
	case wmEraseBkgnd:
		return 1
	case wmNcHitTest:
		return htCaption // drag it anywhere, like a title bar
	case wmPaint:
		if current != nil {
			current.paint(hwnd)
			return 0
		}
	}
	r, _, _ := procDefWindowProc.Call(hwnd, msg, wParam, lParam)
	return r
}

func (p *progress) paint(hwnd uintptr) {
	var ps paintStruct
	hdc, _, _ := procBeginPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
	defer procEndPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))

	// draw into a buffer first so the bar doesn't flicker
	buf, _, _ := procCreateCompatibleDC.Call(hdc)
	bmp, _, _ := procCreateCompatBitmap.Call(hdc, uintptr(p.w), uintptr(p.h))
	oldBmp, _, _ := procSelectObject.Call(buf, bmp)
	src, _, _ := procCreateCompatibleDC.Call(hdc)
	oldSrc, _, _ := procSelectObject.Call(src, p.splash)
	procBitBlt.Call(buf, 0, 0, uintptr(p.w), uintptr(p.h), src, 0, 0, srcCopy)
	procSelectObject.Call(src, oldSrc)
	procDeleteDC.Call(src)

	p.mu.Lock()
	text, pct := p.text, p.pct
	p.mu.Unlock()

	barTop := p.h - 22
	textY := p.h - 44
	if pct < 0 {
		textY = p.h - 38 // where the editor puts its text when there's no bar
	}
	oldFont, _, _ := procSelectObject.Call(buf, p.font)
	procSetBkMode.Call(buf, transparent)
	t := syscall.StringToUTF16(text)
	if len(t) > 1 {
		procSetTextColor.Call(buf, shadowColor)
		procTextOut.Call(buf, textX+1, uintptr(textY+1), uintptr(unsafe.Pointer(&t[0])), uintptr(len(t)-1))
		procSetTextColor.Call(buf, textColor)
		procTextOut.Call(buf, textX, uintptr(textY), uintptr(unsafe.Pointer(&t[0])), uintptr(len(t)-1))
	}
	procSelectObject.Call(buf, oldFont)

	if pct >= 0 {
		right := p.w - textX
		fill(buf, rect{textX, barTop, right, barTop + 10}, barBorder)
		fill(buf, rect{textX + 1, barTop + 1, right - 1, barTop + 9}, barEmpty)
		if w := (right - textX - 2) * int32(min(pct, 100)) / 100; w > 0 {
			fill(buf, rect{textX + 1, barTop + 1, textX + 1 + w, barTop + 9}, barFill)
		}
	}

	procBitBlt.Call(hdc, 0, 0, uintptr(p.w), uintptr(p.h), buf, 0, 0, srcCopy)
	procSelectObject.Call(buf, oldBmp)
	procDeleteObject.Call(bmp)
	procDeleteDC.Call(buf)
}

func fill(hdc uintptr, r rect, color uintptr) {
	br, _, _ := procCreateSolidBrush.Call(color)
	procFillRect.Call(hdc, uintptr(unsafe.Pointer(&r)), br)
	procDeleteObject.Call(br)
}

// say shows the window with text and how far along it is (pct 0-100; -1 for no bar).
func (p *progress) say(text string, pct int) {
	if p.hwnd == 0 {
		return
	}
	p.mu.Lock()
	changed := text != p.text || pct != p.pct
	p.text, p.pct = text, pct
	p.mu.Unlock()
	if !p.shown {
		p.shown = true
		procShowWindowAsync.Call(p.hwnd, swShow)
	}
	if changed {
		procInvalidateRect.Call(p.hwnd, 0, 0)
	}
}

// bytes is a download step for say: "what 12 of 65 MB".
func (p *progress) bytes(what string, done, total int64) {
	const mb = 1 << 20
	if total <= 0 {
		p.say(fmt.Sprintf("%s %d MB", what, done/mb), 0)
		return
	}
	p.say(fmt.Sprintf("%s %d of %d MB", what, done/mb, (total+mb-1)/mb), int(done*100/total))
}
