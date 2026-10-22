package pack

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestDeterministic(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "main_file_cache.dat2"), []byte("dat data"), 0o600)
	os.WriteFile(filepath.Join(dir, "main_file_cache.idx255"), []byte("idx"), 0o755)
	a, err := ZipFiles(filepath.Join(dir, "a.zip"), dir, []string{"main_file_cache.idx255", "main_file_cache.dat2"})
	if err != nil {
		t.Fatal(err)
	}
	// other file times and order: same bytes
	later := time.Now().Add(time.Hour)
	os.Chtimes(filepath.Join(dir, "main_file_cache.dat2"), later, later)
	b, err := ZipFiles(filepath.Join(dir, "b.zip"), dir, []string{"main_file_cache.dat2", "main_file_cache.idx255"})
	if err != nil {
		t.Fatal(err)
	}
	if a != b || a.Entries != 2 || a.Unpacked != 11 {
		t.Fatalf("%+v vs %+v", a, b)
	}
	zr, err := zip.OpenReader(filepath.Join(dir, "a.zip"))
	if err != nil {
		t.Fatal(err)
	}
	defer zr.Close()
	if zr.File[0].Name != "main_file_cache.dat2" || !zr.File[0].Modified.Equal(Stamp) || zr.File[1].Mode().Perm() != 0o644 {
		t.Errorf("entries %s %v %v", zr.File[0].Name, zr.File[0].Modified, zr.File[1].Mode())
	}
}
