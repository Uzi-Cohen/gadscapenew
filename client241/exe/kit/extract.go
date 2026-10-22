// Package kit is the code GadScape's Windows programs share: the one-file client exe (client241/exe) and the
// setup/launcher (client241/setup). It has the loading window, safe zip extraction, the java command line that
// starts the client and the crash report. Portable parts (this file, client.go) are tested on Linux.
package kit

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
)

// ExtractOptions limits what ExtractZip accepts.
type ExtractOptions struct {
	// StripTop drops the one top-level folder of the zip (a JRE zip "jdk-21.../bin/..." -> "bin/...").
	// Every entry must then be inside that same folder.
	StripTop bool
	// MaxBytes is the most the unpacked files may add up to (0: DefaultMaxBytes). Checked against the zip's
	// headers before anything is written and again while writing, so a zip that lies about sizes stops too.
	MaxBytes int64
	// MaxFiles is the most entries the zip may have (0: DefaultMaxFiles).
	MaxFiles int
	// Progress, if set, is called with the number of bytes written so far.
	Progress func(done int64)
}

const (
	DefaultMaxBytes = 8 << 30
	DefaultMaxFiles = 200000
)

// ZipEntry is one file of a checked zip.
type ZipEntry struct {
	File *zip.File
	Path string // cleaned relative path, '/' separated, top folder removed when StripTop
	Dir  bool
}

// CheckZip checks every entry of a zip without writing anything: names (no "..", no absolute paths or drive
// letters, no backslashes, colons, control characters or Windows device names), types (regular files and folders
// only: no symlinks or other special files), duplicates (also ones that differ only in upper/lower case, which are
// the same file on Windows), and the size and count limits. It returns the entries to extract and their total size.
func CheckZip(zr *zip.Reader, opt ExtractOptions) ([]ZipEntry, int64, error) {
	maxBytes, maxFiles := opt.MaxBytes, opt.MaxFiles
	if maxBytes <= 0 {
		maxBytes = DefaultMaxBytes
	}
	if maxFiles <= 0 {
		maxFiles = DefaultMaxFiles
	}
	if len(zr.File) > maxFiles {
		return nil, 0, fmt.Errorf("zip has %d entries, more than the limit of %d", len(zr.File), maxFiles)
	}
	var out []ZipEntry
	var total int64
	top := ""
	seen := map[string]bool{}  // lower-case path -> is a dir
	files := map[string]bool{} // lower-case paths of files (a file may not also be a folder)
	for _, f := range zr.File {
		p, isDir, err := CleanZipName(f.Name)
		if err != nil {
			return nil, 0, err
		}
		mode := f.Mode()
		if mode&os.ModeSymlink != 0 {
			return nil, 0, fmt.Errorf("zip entry %q is a symbolic link", f.Name)
		}
		if mode&(os.ModeNamedPipe|os.ModeSocket|os.ModeDevice|os.ModeCharDevice|os.ModeIrregular) != 0 {
			return nil, 0, fmt.Errorf("zip entry %q is not a plain file or folder", f.Name)
		}
		if mode.IsDir() {
			isDir = true
		}
		if f.Flags&0x1 != 0 {
			return nil, 0, fmt.Errorf("zip entry %q is encrypted", f.Name)
		}
		if opt.StripTop {
			first, rest, _ := strings.Cut(p, "/")
			if top == "" {
				top = first
			} else if first != top {
				return nil, 0, fmt.Errorf("zip has more than one top-level folder (%q and %q)", top, first)
			}
			if rest == "" {
				if !isDir {
					return nil, 0, fmt.Errorf("zip entry %q is a file outside the top-level folder", f.Name)
				}
				continue // the top folder itself
			}
			p = rest
		}
		key := strings.ToLower(p)
		if prev, dup := seen[key]; dup {
			if prev && isDir {
				continue // a folder listed twice is harmless
			}
			return nil, 0, fmt.Errorf("zip has %q twice (names that differ only in case are the same file on Windows)", p)
		}
		// no entry may sit "inside" a file
		for d := path.Dir(key); d != "."; d = path.Dir(d) {
			if files[d] {
				return nil, 0, fmt.Errorf("zip entry %q is inside %q, which is a file", p, d)
			}
		}
		if !isDir {
			if files[key] || seen[key] {
				return nil, 0, fmt.Errorf("zip has %q twice", p)
			}
			files[key] = true
			if f.UncompressedSize64 > uint64(maxBytes) || total+int64(f.UncompressedSize64) > maxBytes {
				return nil, 0, fmt.Errorf("zip unpacks to more than the limit of %d MB", maxBytes>>20)
			}
			total += int64(f.UncompressedSize64)
		}
		seen[key] = isDir
		out = append(out, ZipEntry{File: f, Path: p, Dir: isDir})
	}
	// a folder entry with the same name as a file
	for k, isDir := range seen {
		if isDir && files[k] {
			return nil, 0, fmt.Errorf("zip has %q as both a file and a folder", k)
		}
	}
	if opt.StripTop && top == "" {
		return nil, 0, errors.New("zip is empty")
	}
	return out, total, nil
}

var reserved = map[string]bool{"con": true, "prn": true, "aux": true, "nul": true, "conin$": true, "conout$": true}

func init() {
	for i := 0; i <= 9; i++ {
		reserved[fmt.Sprintf("com%d", i)] = true
		reserved[fmt.Sprintf("lpt%d", i)] = true
	}
	for _, s := range []string{"¹", "²", "³"} {
		reserved["com"+s] = true
		reserved["lpt"+s] = true
	}
}

// CleanZipName checks one zip entry name and returns it cleaned ('/' separated, no trailing slash) and whether it
// names a folder (trailing slash). It refuses anything that could land outside the folder it is unpacked into, or
// that Windows would read differently from Linux.
func CleanZipName(name string) (string, bool, error) {
	bad := func(why string) (string, bool, error) {
		return "", false, fmt.Errorf("unsafe zip entry %q: %s", name, why)
	}
	if name == "" {
		return bad("empty name")
	}
	if strings.ContainsRune(name, '\\') {
		return bad("backslash in the name")
	}
	if strings.HasPrefix(name, "/") {
		return bad("absolute path")
	}
	if strings.ContainsRune(name, ':') {
		return bad("drive letter or colon in the name")
	}
	isDir := strings.HasSuffix(name, "/")
	trimmed := strings.TrimSuffix(name, "/")
	if trimmed == "" {
		return bad("empty name")
	}
	parts := strings.Split(trimmed, "/")
	for _, c := range parts {
		switch {
		case c == "":
			return bad("empty path element")
		case c == "." || c == "..":
			return bad("relative path element " + c)
		case strings.HasSuffix(c, ".") || strings.HasSuffix(c, " "):
			return bad("element ends with a dot or space")
		case strings.ContainsAny(c, "<>\"|?*"):
			return bad("character Windows does not allow")
		}
		for _, r := range c {
			if r < 0x20 || r == 0x7f {
				return bad("control character")
			}
		}
		base := strings.ToLower(c)
		if i := strings.IndexByte(base, '.'); i >= 0 {
			base = base[:i]
		}
		if reserved[strings.TrimRight(base, " ")] {
			return bad("Windows device name " + c)
		}
	}
	return trimmed, isDir, nil
}

// ExtractZipFile unpacks the zip file at zipPath into dest (see ExtractZip).
func ExtractZipFile(ctx context.Context, zipPath, dest string, opt ExtractOptions) error {
	f, err := os.Open(zipPath)
	if err != nil {
		return err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return err
	}
	return ExtractZip(ctx, f, st.Size(), dest, opt)
}

// ExtractZip unpacks a zip into dest after checking all of it with CheckZip. Files are created new (an existing
// file of the same name is an error), so dest should be a fresh folder. Sizes are enforced while writing as well
// (Go's zip reader also checks each file's CRC and length).
func ExtractZip(ctx context.Context, r io.ReaderAt, size int64, dest string, opt ExtractOptions) error {
	zr, err := zip.NewReader(r, size)
	if err != nil {
		return err
	}
	entries, total, err := CheckZip(zr, opt)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return err
	}
	root, err := filepath.Abs(dest)
	if err != nil {
		return err
	}
	// folders first (sorted), then files in zip order
	sort.SliceStable(entries, func(i, j int) bool { return entries[i].Dir && !entries[j].Dir })
	var done int64
	for _, e := range entries {
		if ctx != nil {
			if err := ctx.Err(); err != nil {
				return err
			}
		}
		p := filepath.Join(root, filepath.FromSlash(e.Path))
		if rel, err := filepath.Rel(root, p); err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
			return fmt.Errorf("unsafe zip entry %q", e.File.Name)
		}
		if e.Dir {
			if err := os.MkdirAll(p, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			return err
		}
		n, err := extractOne(ctx, e.File, p, total-done, func(n int64) {
			if opt.Progress != nil {
				opt.Progress(done + n)
			}
		})
		done += n
		if err != nil {
			return fmt.Errorf("unpacking %s: %w", e.Path, err)
		}
	}
	if opt.Progress != nil {
		opt.Progress(done)
	}
	return nil
}

func extractOne(ctx context.Context, f *zip.File, p string, budget int64, progress func(int64)) (int64, error) {
	in, err := f.Open()
	if err != nil {
		return 0, err
	}
	defer in.Close()
	perm := os.FileMode(0o644)
	if f.Mode()&0o111 != 0 {
		perm = 0o755
	}
	out, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_EXCL, perm)
	if err != nil {
		return 0, err
	}
	limit := int64(f.UncompressedSize64)
	if limit > budget {
		limit = budget
	}
	var n int64
	buf := make([]byte, 256<<10)
	for {
		if ctx != nil && ctx.Err() != nil {
			out.Close()
			return n, ctx.Err()
		}
		k, rerr := in.Read(buf)
		if k > 0 {
			if n+int64(k) > limit {
				out.Close()
				return n, errors.New("more data than the zip says")
			}
			if _, werr := out.Write(buf[:k]); werr != nil {
				out.Close()
				return n, werr
			}
			n += int64(k)
			progress(n)
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			out.Close()
			return n, rerr
		}
	}
	if err := out.Close(); err != nil {
		return n, err
	}
	if !f.Modified.IsZero() {
		os.Chtimes(p, f.Modified, f.Modified)
	}
	return n, nil
}
