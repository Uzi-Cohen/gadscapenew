package kit

import (
	"bytes"
	"fmt"
	"image"
	"image/draw"
	"image/png"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"syscall"
	"unsafe"
)

// A small loading window (a splash picture with gold text), drawn while the exe unpacks Java and the client or the
// setup downloads them, with a progress bar along the bottom and, for the setup, a Cancel button. It stays hidden
// unless there is something to show; once RuneLite starts, its own splash opens. Adapted from the map editor's
// launcher (launcher/progress.go in this repo).

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
	procDrawText           = user32.NewProc("DrawTextW")
	procGetWindowRect      = user32.NewProc("GetWindowRect")
	procMessageBox         = user32.NewProc("MessageBoxW")
	procSetForeground      = user32.NewProc("SetForegroundWindow")
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
	procCreateSolidBrush   = gdi32.NewProc("CreateSolidBrush")
	procExtractIcon        = shell32.NewProc("ExtractIconW")
	procEnumWindows        = user32.NewProc("EnumWindows")
	procGetWindowThreadPid = user32.NewProc("GetWindowThreadProcessId")
	procIsWindowVisible    = user32.NewProc("IsWindowVisible")
)

const (
	wsPopup      = 0x80000000
	wsExAppWin   = 0x00040000
	wmClose      = 0x0010
	wmPaint      = 0x000F
	wmEraseBkgnd = 0x0014
	wmNcHitTest  = 0x0084
	wmLButtonUp  = 0x0202
	wmQuit       = 0x0012
	htClient     = 1
	htCaption    = 2
	swShow       = 5
	srcCopy      = 0x00CC0020
	transparent  = 1
	idcArrow     = 32512

	dtCenter      = 0x1
	dtVCenter     = 0x4
	dtSingleLine  = 0x20
	dtNoPrefix    = 0x800
	dtEndEllipsis = 0x8000
)

// label: GadScape gold, Verdana bold 13px, black shadow 1px down-right, 24px from the left
const (
	textColor   = 0x0050C4F0 // COLORREF is 0x00BBGGRR: GadScape gold F0C450
	shadowColor = 0x00000000
	barBorder   = 0x00000000
	barEmpty    = 0x00161C5C // GadScape maroon 5C1C16
	barFill     = 0x0050C4F0
	grayText    = 0x00808080
	textX       = 24
	buttonW     = 72
	buttonH     = 20
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

// WindowConfig sets up the loading window.
type WindowConfig struct {
	Title  string // window title (task bar); the class name is made from it
	Splash []byte // PNG; the bottom 50 px stay free for the text and the bar
	// Cancel, if set, adds a Cancel button (also Alt+F4) that calls it from the window's thread.
	Cancel func()
	Log    func(format string, a ...any)
}

// Window is the loading window. Its methods may be called from any goroutine.
type Window struct {
	hwnd   uintptr
	tid    uintptr
	splash uintptr // DIB of the splash picture
	font   uintptr
	w, h   int32
	shown  bool
	cfg    WindowConfig

	mu         sync.Mutex
	text       string
	pct        int // -1: no bar
	cancelOn   bool
	cancelUsed bool
}

var current *Window

func wstr(s string) *uint16 {
	p, _ := syscall.UTF16PtrFromString(s)
	return p
}

// RunWithWindow runs work on another goroutine while this thread runs the window (hidden until work says
// something). It returns when work returns.
func RunWithWindow(cfg WindowConfig, work func(w *Window)) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := newWindow(cfg)
	if w.hwnd == 0 {
		work(w) // no window: just do it
		return
	}
	go func() {
		defer procPostThreadMessage.Call(w.tid, wmQuit, 0, 0)
		work(w)
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
	procDestroyWindow.Call(w.hwnd)
}

func (w *Window) logf(format string, a ...any) {
	if w.cfg.Log != nil {
		w.cfg.Log(format, a...)
	}
}

func newWindow(cfg WindowConfig) *Window {
	w := &Window{pct: -1, cfg: cfg, cancelOn: cfg.Cancel != nil}
	img, err := png.Decode(bytes.NewReader(cfg.Splash))
	if err != nil {
		return w
	}
	b := img.Bounds()
	w.w, w.h = int32(b.Dx()), int32(b.Dy())
	w.splash = dibOf(img)
	if w.splash == 0 {
		return w
	}
	w.font, _, _ = procCreateFont.Call(^uintptr(13-1), 0, 0, 0, 700, 0, 0, 0, 1, 0, 0, 5, 0, uintptr(unsafe.Pointer(wstr("Verdana"))))

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
		className: wstr(strings.ReplaceAll(cfg.Title, " ", "") + "Splash"),
		iconSm:    icon,
	}
	wc.size = uint32(unsafe.Sizeof(wc))
	procRegisterClassEx.Call(uintptr(unsafe.Pointer(&wc)))

	sw, _, _ := procGetSystemMetrics.Call(0)
	sh, _, _ := procGetSystemMetrics.Call(1)
	x := (int32(sw) - w.w) / 2
	y := (int32(sh) - w.h) / 2
	current = w
	w.hwnd, _, _ = procCreateWindowEx.Call(wsExAppWin, uintptr(unsafe.Pointer(wc.className)),
		uintptr(unsafe.Pointer(wstr(cfg.Title))), wsPopup,
		uintptr(x), uintptr(y), uintptr(w.w), uintptr(w.h), 0, 0, inst, 0)
	w.tid, _, _ = procGetCurrentThreadId.Call()
	w.logf("splash window %#x", w.hwnd)
	return w
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

// the Cancel button sits at the right end of the text line
func (w *Window) buttonRect() rect {
	textY := w.h - 44
	return rect{w.w - textX - buttonW, textY - 3, w.w - textX, textY - 3 + buttonH}
}

func (w *Window) inButton(lParam uintptr, screen bool) bool {
	if w.cfg.Cancel == nil {
		return false
	}
	x, y := int32(int16(lParam&0xffff)), int32(int16((lParam>>16)&0xffff))
	if screen {
		var wr rect
		procGetWindowRect.Call(w.hwnd, uintptr(unsafe.Pointer(&wr)))
		x, y = x-wr.left, y-wr.top
	}
	b := w.buttonRect()
	return x >= b.left && x < b.right && y >= b.top && y < b.bottom
}

func (w *Window) pressCancel() {
	w.mu.Lock()
	ok := w.cancelOn && !w.cancelUsed
	if ok {
		w.cancelUsed = true
	}
	w.mu.Unlock()
	if ok {
		w.logf("Cancel pressed")
		w.cfg.Cancel()
		procInvalidateRect.Call(w.hwnd, 0, 0)
	}
}

func splashProc(hwnd, msg, wParam, lParam uintptr) (ret uintptr) {
	defer func() {
		if r := recover(); r != nil {
			if current != nil {
				current.logf("window crashed on message %#x: %v\n%s", msg, r, debug.Stack())
			}
			ret, _, _ = procDefWindowProc.Call(hwnd, msg, wParam, lParam)
		}
	}()
	w := current
	switch msg {
	case wmEraseBkgnd:
		return 1
	case wmNcHitTest:
		if w != nil && w.inButton(lParam, true) {
			return htClient
		}
		return htCaption // drag it anywhere, like a title bar
	case wmLButtonUp:
		if w != nil && w.inButton(lParam, false) {
			w.pressCancel()
			return 0
		}
	case wmClose:
		if w != nil && w.cfg.Cancel != nil {
			w.pressCancel()
			return 0
		}
	case wmPaint:
		if w != nil {
			w.paint(hwnd)
			return 0
		}
	}
	r, _, _ := procDefWindowProc.Call(hwnd, msg, wParam, lParam)
	return r
}

func drawText(hdc uintptr, s string, r rect, flags uintptr) {
	t := syscall.StringToUTF16(s)
	if len(t) > 1 {
		procDrawText.Call(hdc, uintptr(unsafe.Pointer(&t[0])), uintptr(len(t)-1), uintptr(unsafe.Pointer(&r)), flags)
	}
}

func (w *Window) paint(hwnd uintptr) {
	var ps paintStruct
	hdc, _, _ := procBeginPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
	defer procEndPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))

	// draw into a buffer first so the bar doesn't flicker
	buf, _, _ := procCreateCompatibleDC.Call(hdc)
	bmp, _, _ := procCreateCompatBitmap.Call(hdc, uintptr(w.w), uintptr(w.h))
	oldBmp, _, _ := procSelectObject.Call(buf, bmp)
	src, _, _ := procCreateCompatibleDC.Call(hdc)
	oldSrc, _, _ := procSelectObject.Call(src, w.splash)
	procBitBlt.Call(buf, 0, 0, uintptr(w.w), uintptr(w.h), src, 0, 0, srcCopy)
	procSelectObject.Call(src, oldSrc)
	procDeleteDC.Call(src)

	w.mu.Lock()
	text, pct, cancelOn, cancelUsed := w.text, w.pct, w.cancelOn, w.cancelUsed
	w.mu.Unlock()

	barTop := w.h - 22
	textY := w.h - 44
	if pct < 0 && w.cfg.Cancel == nil {
		textY = w.h - 38 // no bar: the text moves down a little (with a Cancel button it stays next to it)
	}
	right := w.w - textX
	if w.cfg.Cancel != nil {
		right = w.buttonRect().left - 8
	}
	oldFont, _, _ := procSelectObject.Call(buf, w.font)
	procSetBkMode.Call(buf, transparent)
	const flags = dtSingleLine | dtNoPrefix | dtEndEllipsis
	procSetTextColor.Call(buf, shadowColor)
	drawText(buf, text, rect{textX + 1, textY + 1, right + 1, textY + 19}, flags)
	procSetTextColor.Call(buf, textColor)
	drawText(buf, text, rect{textX, textY, right, textY + 18}, flags)

	if w.cfg.Cancel != nil {
		b := w.buttonRect()
		fill(buf, b, barFill)
		fill(buf, rect{b.left + 1, b.top + 1, b.right - 1, b.bottom - 1}, barEmpty)
		color := uintptr(textColor)
		if !cancelOn || cancelUsed {
			color = grayText
		}
		procSetTextColor.Call(buf, color)
		drawText(buf, "Cancel", b, dtCenter|dtVCenter|dtSingleLine|dtNoPrefix)
	}
	procSelectObject.Call(buf, oldFont)

	if pct >= 0 {
		right := w.w - textX
		fill(buf, rect{textX, barTop, right, barTop + 10}, barBorder)
		fill(buf, rect{textX + 1, barTop + 1, right - 1, barTop + 9}, barEmpty)
		if bw := (right - textX - 2) * int32(min(pct, 100)) / 100; bw > 0 {
			fill(buf, rect{textX + 1, barTop + 1, textX + 1 + bw, barTop + 9}, barFill)
		}
	}

	procBitBlt.Call(hdc, 0, 0, uintptr(w.w), uintptr(w.h), buf, 0, 0, srcCopy)
	procSelectObject.Call(buf, oldBmp)
	procDeleteObject.Call(bmp)
	procDeleteDC.Call(buf)
}

func fill(hdc uintptr, r rect, color uintptr) {
	br, _, _ := procCreateSolidBrush.Call(color)
	procFillRect.Call(hdc, uintptr(unsafe.Pointer(&r)), br)
	procDeleteObject.Call(br)
}

// Say shows the window with text and how far along it is (pct 0-100; -1 for no bar).
func (w *Window) Say(text string, pct int) {
	if w.hwnd == 0 {
		return
	}
	w.mu.Lock()
	changed := text != w.text || pct != w.pct
	w.text, w.pct = text, pct
	first := !w.shown
	w.shown = true
	w.mu.Unlock()
	if first {
		procShowWindowAsync.Call(w.hwnd, swShow)
		procSetForeground.Call(w.hwnd)
	}
	if changed {
		procInvalidateRect.Call(w.hwnd, 0, 0)
	}
}

// Shown is true once the window has been shown.
func (w *Window) Shown() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.shown
}

// SetCancel turns the Cancel button on or off (off while files are being swapped).
func (w *Window) SetCancel(on bool) {
	w.mu.Lock()
	changed := w.cancelOn != on
	w.cancelOn = on
	w.mu.Unlock()
	if changed && w.hwnd != 0 {
		procInvalidateRect.Call(w.hwnd, 0, 0)
	}
}

// Hide takes the window away (the exe keeps watching the client start in the background).
func (w *Window) Hide() {
	w.mu.Lock()
	shown := w.shown
	w.mu.Unlock()
	if w.hwnd != 0 && shown {
		procShowWindowAsync.Call(w.hwnd, 0) // SW_HIDE
	}
}

// HWND is the window handle (0 when there is no window), for message boxes that belong to it.
func (w *Window) HWND() uintptr { return w.hwnd }

// Bytes is a download step for Say: "what 12 of 65 MB".
func (w *Window) Bytes(what string, done, total int64) {
	const mb = 1 << 20
	if total <= 0 {
		w.Say(fmt.Sprintf("%s %d MB", what, done/mb), 0)
		return
	}
	w.Say(fmt.Sprintf("%s %d of %d MB", what, done/mb, (total+mb-1)/mb), int(done*100/total))
}

// MessageBox shows a Windows message box and returns the button pressed (IDOK 1, IDCANCEL 2, IDRETRY 4,
// IDYES 6, IDNO 7). owner may be 0.
func MessageBox(owner uintptr, title, text string, flags uint32) int {
	r, _, _ := procMessageBox.Call(owner, uintptr(unsafe.Pointer(wstr(text))), uintptr(unsafe.Pointer(wstr(title))), uintptr(flags))
	return int(r)
}

// Message box flags.
const (
	MbOK          = 0x0
	MbOKCancel    = 0x1
	MbRetryCancel = 0x5
	MbYesNo       = 0x4
	MbIconError   = 0x10
	MbIconQuest   = 0x20
	MbIconWarning = 0x30
	MbIconInfo    = 0x40
	MbDefButton2  = 0x100
	MbTopMost     = 0x40000
	IDOK          = 1
	IDCancel      = 2
	IDRetry       = 4
	IDYes         = 6
	IDNo          = 7
)

var (
	visMu    sync.Mutex
	visPid   int
	visFound bool
	visCb    uintptr
)

// HasVisibleWindow is true once the process pid shows any window (RuneLite's splash or main window).
func HasVisibleWindow(pid int) bool {
	visMu.Lock()
	defer visMu.Unlock()
	if visCb == 0 { // one callback for all calls: Windows callbacks are a limited resource
		visCb = syscall.NewCallback(func(hwnd, _ uintptr) uintptr {
			var owner uint32
			procGetWindowThreadPid.Call(hwnd, uintptr(unsafe.Pointer(&owner)))
			if int(owner) == visPid {
				if v, _, _ := procIsWindowVisible.Call(hwnd); v != 0 {
					visFound = true
					return 0
				}
			}
			return 1
		})
	}
	visPid, visFound = pid, false
	procEnumWindows.Call(visCb, 0)
	return visFound
}
