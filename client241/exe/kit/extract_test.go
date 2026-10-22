package kit

import (
	"archive/zip"
	"bytes"
	"compress/flate"
	"context"
	"hash/crc32"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type entry struct {
	name string
	data string
	mode fs.FileMode // 0: plain file
}

func makeZip(t *testing.T, entries ...entry) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, e := range entries {
		h := &zip.FileHeader{Name: e.name, Method: zip.Deflate}
		if e.mode != 0 {
			h.SetMode(e.mode)
		}
		w, err := zw.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		w.Write([]byte(e.data))
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func extract(t *testing.T, data []byte, opt ExtractOptions) (string, error) {
	t.Helper()
	dest := filepath.Join(t.TempDir(), "out")
	return dest, ExtractZip(context.Background(), bytes.NewReader(data), int64(len(data)), dest, opt)
}

func TestExtractGood(t *testing.T) {
	z := makeZip(t, entry{name: "jdk-21/"}, entry{name: "jdk-21/bin/javaw.exe", data: "MZ"},
		entry{name: "jdk-21/release", data: `JAVA_VERSION="21"`}, entry{name: "jdk-21/lib/a/b.txt", data: "hello"})
	dest, err := extract(t, z, ExtractOptions{StripTop: true})
	if err != nil {
		t.Fatal(err)
	}
	for p, want := range map[string]string{"bin/javaw.exe": "MZ", "release": `JAVA_VERSION="21"`, "lib/a/b.txt": "hello"} {
		got, err := os.ReadFile(filepath.Join(dest, filepath.FromSlash(p)))
		if err != nil || string(got) != want {
			t.Errorf("%s: %q %v", p, got, err)
		}
	}
	if _, err := os.Stat(filepath.Join(dest, "jdk-21")); err == nil {
		t.Error("top folder was not stripped")
	}
}

func TestExtractNoStrip(t *testing.T) {
	z := makeZip(t, entry{name: "main_file_cache.dat2", data: "dat"}, entry{name: "main_file_cache.idx255", data: "idx"})
	dest, err := extract(t, z, ExtractOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(dest, "main_file_cache.idx255")); string(b) != "idx" {
		t.Fatalf("got %q", b)
	}
}

func TestZipSlipAndAbsolutePaths(t *testing.T) {
	bad := []string{
		"../evil.txt",
		"a/../../evil.txt",
		"a/./b.txt",
		"/etc/passwd",
		"/abs/file",
		"C:/Windows/evil.dll",
		"c:evil.txt",
		`a\..\..\evil.txt`,
		`\\server\share\x`,
		"a/b:stream",
		"a//b",
		"a/con.txt",
		"NUL",
		"a/lpt1",
		"a/trailing.",
		"a/trailing ",
		"a/what?.txt",
		"a/\x01ctl",
		"",
	}
	for _, name := range bad {
		if _, _, err := CleanZipName(name); err == nil {
			t.Errorf("CleanZipName(%q) accepted an unsafe name", name)
		}
		if name == "" {
			continue
		}
		parent := t.TempDir()
		dest := filepath.Join(parent, "x", "out")
		z := makeZip(t, entry{name: "ok.txt", data: "ok"}, entry{name: name, data: "evil"})
		err := ExtractZip(context.Background(), bytes.NewReader(z), int64(len(z)), dest, ExtractOptions{})
		if err == nil {
			t.Errorf("zip with %q was extracted", name)
		}
		// nothing at all is written when a zip fails the check
		if _, err := os.Stat(dest); err == nil {
			t.Errorf("zip with %q: something was written to %s", name, dest)
		}
		if _, err := os.Stat(filepath.Join(parent, "evil.txt")); err == nil {
			t.Errorf("zip with %q wrote outside the folder", name)
		}
	}
	for _, name := range []string{"a.txt", "dir/", "lib/x-1.0.jar", "jdk-21.0.12.1+1-jre/bin/javaw.exe", "a/console.txt", "a/b.c.d"} {
		if _, _, err := CleanZipName(name); err != nil {
			t.Errorf("CleanZipName(%q) refused a good name: %v", name, err)
		}
	}
}

func TestSymlinkRejected(t *testing.T) {
	z := makeZip(t, entry{name: "top/link", data: "/etc/passwd", mode: fs.ModeSymlink | 0o777})
	if _, err := extract(t, z, ExtractOptions{StripTop: true}); err == nil || !strings.Contains(err.Error(), "symbolic link") {
		t.Fatalf("symlink accepted: %v", err)
	}
	z = makeZip(t, entry{name: "pipe", mode: fs.ModeNamedPipe | 0o644})
	if _, err := extract(t, z, ExtractOptions{}); err == nil {
		t.Fatal("named pipe accepted")
	}
}

func TestDuplicatesAndConflicts(t *testing.T) {
	cases := [][]entry{
		{{name: "a.txt", data: "1"}, {name: "a.txt", data: "2"}},
		{{name: "Lib/A.jar", data: "1"}, {name: "lib/a.jar", data: "2"}}, // same file on Windows
		{{name: "a", data: "file"}, {name: "a/b", data: "inside a file"}},
		{{name: "a/", data: ""}, {name: "a", data: "file"}},
	}
	for i, c := range cases {
		if _, err := extract(t, makeZip(t, c...), ExtractOptions{}); err == nil {
			t.Errorf("case %d: accepted", i)
		}
	}
	// a folder listed twice is fine
	if _, err := extract(t, makeZip(t, entry{name: "d/"}, entry{name: "d/"}, entry{name: "d/x", data: "x"}), ExtractOptions{}); err != nil {
		t.Errorf("folder twice: %v", err)
	}
}

func TestStripTopRules(t *testing.T) {
	if _, err := extract(t, makeZip(t, entry{name: "a/x", data: "1"}, entry{name: "b/y", data: "2"}), ExtractOptions{StripTop: true}); err == nil {
		t.Error("two top folders accepted")
	}
	if _, err := extract(t, makeZip(t, entry{name: "loose.txt", data: "1"}), ExtractOptions{StripTop: true}); err == nil {
		t.Error("file outside the top folder accepted")
	}
}

func TestSizeLimits(t *testing.T) {
	big := strings.Repeat("A", 1<<20)
	z := makeZip(t, entry{name: "a", data: big}, entry{name: "b", data: big})
	if _, err := extract(t, z, ExtractOptions{MaxBytes: 1<<21 - 1}); err == nil {
		t.Error("over the byte limit accepted")
	}
	if _, err := extract(t, z, ExtractOptions{MaxBytes: 1 << 21}); err != nil {
		t.Errorf("at the byte limit: %v", err)
	}
	if _, err := extract(t, makeZip(t, entry{name: "1", data: "1"}, entry{name: "2", data: "2"}, entry{name: "3", data: "3"}), ExtractOptions{MaxFiles: 2}); err == nil {
		t.Error("over the file count limit accepted")
	}
}

// a zip whose header says a small size but whose data unpacks to much more (a "zip bomb" that lies)
func TestLyingSizeStops(t *testing.T) {
	data := bytes.Repeat([]byte("B"), 4<<20)
	var comp bytes.Buffer
	fw, _ := flate.NewWriter(&comp, flate.BestCompression)
	fw.Write(data)
	fw.Close()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, err := zw.CreateRaw(&zip.FileHeader{Name: "bomb", Method: zip.Deflate, CRC32: crc32.ChecksumIEEE(data),
		CompressedSize64: uint64(comp.Len()), UncompressedSize64: 1000})
	if err != nil {
		t.Fatal(err)
	}
	w.Write(comp.Bytes())
	zw.Close()
	z := buf.Bytes()
	dest, err := extract(t, z, ExtractOptions{MaxBytes: 1 << 20})
	if err == nil {
		t.Fatal("lying zip extracted")
	}
	if st, e := os.Stat(filepath.Join(dest, "bomb")); e == nil && st.Size() > 1000 {
		t.Errorf("wrote %d bytes, more than the header's 1000", st.Size())
	}
}

func TestCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	z := makeZip(t, entry{name: "a", data: "1"})
	dest := filepath.Join(t.TempDir(), "o")
	if err := ExtractZip(ctx, bytes.NewReader(z), int64(len(z)), dest, ExtractOptions{}); err == nil {
		t.Fatal("cancelled extract finished")
	}
}

func TestJavaArgs(t *testing.T) {
	c := Client{AppName: "GadScape", Dest: `C:\Users\p\AppData\Local\GadScape`}
	args := strings.Join(c.JavaArgs(`C:\x\GadScape-server.txt`), " ")
	for _, want := range []string{"gadscape.launcher.Launcher", "--host-file", "--home", "-Duser.home="} {
		if !strings.Contains(args, want) {
			t.Errorf("args lack %s: %s", want, args)
		}
	}
	env := CleanEnv([]string{"PATH=x", "JX_SESSION_ID=1", "jx_access_token=2", "HOME=y"})
	if len(env) != 2 {
		t.Errorf("CleanEnv kept %v", env)
	}
}
