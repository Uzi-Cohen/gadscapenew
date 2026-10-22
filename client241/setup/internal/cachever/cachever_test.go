package cachever

import (
	"os"
	"path/filepath"
	"testing"

	"gadscapesetup/internal/cachever/cachetest"
	"gadscapesetup/internal/manifest"
)

func TestReadAndCompare(t *testing.T) {
	dir := t.TempDir()
	rel, old, newer, salted := filepath.Join(dir, "rel"), filepath.Join(dir, "old"), filepath.Join(dir, "new"), filepath.Join(dir, "salt")
	cachetest.Write(rel, map[int]int64{0: 100, 2: 200, 24: 5}, 0)
	cachetest.Write(old, map[int]int64{0: 90, 2: 200}, 0)            // index 0 older, 24 missing
	cachetest.Write(newer, map[int]int64{0: 100, 2: 201, 24: 5}, 0)  // index 2 newer
	cachetest.Write(salted, map[int]int64{0: 100, 2: 200, 24: 5}, 9) // same revisions, other bytes
	r, err := Read(rel)
	if err != nil || len(r) != 3 || r[0].Revision != 100 || r[1].Revision != 200 || r[2].ID != 24 {
		t.Fatalf("read %+v %v", r, err)
	}
	read := func(d string) []manifest.Index {
		ix, err := Read(d)
		if err != nil {
			t.Fatal(err)
		}
		return ix
	}
	cases := []struct {
		dir  string
		want Relation
	}{{rel, Same}, {old, Older}, {newer, Newer}, {salted, Differs}}
	for _, c := range cases {
		if got := Compare(read(c.dir), r); got != c.want {
			t.Errorf("%s: %v, want %v", filepath.Base(c.dir), got, c.want)
		}
	}
	// newer wins over older: one index newer, another older -> never replaced
	mixed := filepath.Join(dir, "mixed")
	cachetest.Write(mixed, map[int]int64{0: 50, 2: 300, 24: 5}, 0)
	if got := Compare(read(mixed), r); got != Newer {
		t.Errorf("mixed: %v", got)
	}
	// an unreadable index counts as older
	if got := Compare([]manifest.Index{{ID: 0, Revision: -1}, {ID: 2, Revision: 200, CRC: r[1].CRC}, r[2]}, r); got != Older {
		t.Errorf("unreadable: %v", got)
	}
}

func TestReadBroken(t *testing.T) {
	dir := t.TempDir()
	if _, err := Read(dir); err == nil {
		t.Error("empty folder read")
	}
	cachetest.Write(dir, map[int]int64{0: 1}, 0)
	os.WriteFile(filepath.Join(dir, "main_file_cache.idx255"), []byte{1, 2, 3}, 0o644)
	if _, err := Read(dir); err == nil {
		t.Error("broken idx255 read")
	}
	// a dat2 that ends early: the index is listed with an unknown revision
	cachetest.Write(dir, map[int]int64{0: 1}, 0)
	os.WriteFile(filepath.Join(dir, "main_file_cache.dat2"), make([]byte, 600), 0o644)
	ix, err := Read(dir)
	if err != nil || len(ix) != 1 || ix[0].Revision != -1 {
		t.Errorf("truncated dat2: %+v %v", ix, err)
	}
}

func TestIsCacheFile(t *testing.T) {
	for name, want := range map[string]bool{"main_file_cache.dat2": true, "main_file_cache.idx0": true, "main_file_cache.idx255": true,
		"main_file_cache.idx256": false, "main_file_cache.idx": false, "main_file_cache.idx1a": false, "preferences2.dat": false,
		"main_file_cache.dat": false, "random.dat": false} {
		if IsCacheFile(name) != want {
			t.Errorf("%s: %v", name, !want)
		}
	}
}
