package cachever

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"hash/crc32"
	"os"
	"path/filepath"
	"testing"
)

func container(data []byte, gz bool) []byte {
	if !gz {
		c := append([]byte{0, 0, 0, 0, 0}, data...)
		binary.BigEndian.PutUint32(c[1:], uint32(len(data)))
		return c
	}
	var z bytes.Buffer
	w := gzip.NewWriter(&z)
	w.Write(data)
	w.Close()
	c := make([]byte, 9, 9+z.Len())
	c[0] = 2
	binary.BigEndian.PutUint32(c[1:], uint32(z.Len()))
	binary.BigEndian.PutUint32(c[5:], uint32(len(data)))
	return append(c, z.Bytes()...)
}

type tgroup struct {
	id      int
	version uint32
	c       []byte // container
	trailer []byte // stored after the container
	crc     uint32 // in the table (0: the container's)
	missing bool
}

func table(format int, flags byte, gs []tgroup) []byte {
	var b bytes.Buffer
	be := func(v any) { binary.Write(&b, binary.BigEndian, v) }
	b.WriteByte(byte(format))
	if format >= 6 {
		be(uint32(1234))
	}
	b.WriteByte(flags)
	count := func(n int) {
		if format >= 7 && n > 0x7FFF {
			be(uint32(n) | 0x80000000)
		} else {
			be(uint16(n))
		}
	}
	count(len(gs))
	last := 0
	for _, g := range gs {
		count(g.id - last)
		last = g.id
	}
	n := len(gs)
	if flags&1 != 0 {
		b.Write(make([]byte, 4*n))
	}
	for _, g := range gs {
		crc := g.crc
		if crc == 0 {
			crc = crc32.ChecksumIEEE(g.c)
		}
		be(crc)
	}
	if flags&8 != 0 {
		b.Write(make([]byte, 4*n))
	}
	if flags&2 != 0 {
		b.Write(make([]byte, 64*n))
	}
	if flags&4 != 0 {
		b.Write(make([]byte, 8*n))
	}
	for _, g := range gs {
		be(g.version)
	}
	for range gs {
		be(uint16(1)) // file counts (not read by ParseTable)
	}
	return b.Bytes()
}

func trailer2(v uint32) []byte { return []byte{byte(v >> 8), byte(v)} }
func trailer4(v uint32) []byte { return binary.BigEndian.AppendUint32(nil, v) }

// writeCache writes a cache the way older tools do (any trailers given), archive -> groups.
func writeCache(t *testing.T, dir string, archives map[int][]tgroup, flags map[int]byte, format map[int]int) {
	t.Helper()
	os.MkdirAll(dir, 0o755)
	w, err := newDiskWriter(dir)
	if err != nil {
		t.Fatal(err)
	}
	idx255 := make([]byte, 3*6)
	for a := 0; a < 3; a++ {
		gs, ok := archives[a]
		if !ok {
			continue
		}
		if err := w.group(255, a, container(table(format[a], flags[a], gs), a == 0), idx255); err != nil {
			t.Fatal(err)
		}
		idx := make([]byte, (gs[len(gs)-1].id+1)*6)
		for _, g := range gs {
			if g.missing {
				continue
			}
			if err := w.group(a, g.id, append(append([]byte{}, g.c...), g.trailer...), idx); err != nil {
				t.Fatal(err)
			}
		}
		os.WriteFile(filepath.Join(dir, "main_file_cache.idx"+itoa(a)), idx, 0o644)
	}
	os.WriteFile(filepath.Join(dir, "main_file_cache.idx255"), idx255, 0o644)
	if err := w.close(); err != nil {
		t.Fatal(err)
	}
}

func itoa(i int) string { return string(rune('0' + i)) }

func TestRepackGivesTheClientEveryGroup(t *testing.T) {
	d := t.TempDir()
	src, dst, again := filepath.Join(d, "src"), filepath.Join(d, "dst"), filepath.Join(d, "again")
	big := bytes.Repeat([]byte("model data "), 150) // several sectors
	c0, c1, c2 := container([]byte("small"), false), container(big, true), container([]byte("far group"), false)
	c3, c4 := container([]byte("archive 2 group 3"), false), container([]byte("archive 2 group 4"), false)
	broken := append([]byte{}, c4...)
	broken[len(broken)-1] ^= 0xFF
	archives := map[int][]tgroup{
		0: { // format 7, sizes + uncompressed CRCs, a group id above 0xFFFF (10-byte sector headers)
			{id: 0, version: 5, c: c0, trailer: trailer2(5)},                 // fine as it is
			{id: 1, version: 0x12345, c: c1, trailer: trailer2(0x12345)},     // 2-byte trailer of a big version
			{id: 70000, version: 0x20000, c: c2, trailer: trailer2(0x20000)}, // same, far group
		},
		2: { // format 6, names
			{id: 3, version: 0x10001, c: c3, trailer: trailer4(0x10001)}, // fine as it is
			{id: 4, version: 7, c: broken, crc: crc32.ChecksumIEEE(c4), trailer: trailer2(7)},
			{id: 9, version: 1, c: c3, missing: true},
		},
	}
	writeCache(t, src, archives, map[int]byte{0: 0xC, 2: 1}, map[int]int{0: 7, 2: 6})

	before, err := Check(src)
	if err != nil {
		t.Fatal(err)
	}
	if before.Groups != 6 || before.OK != 2 || before.Invalid != 3 || before.Missing != 1 {
		t.Fatalf("source check %+v", before)
	}

	res, err := Repack(src, dst)
	if err != nil {
		t.Fatal(err)
	}
	if res.Archives != 2 || res.Groups != 4 || res.Invalid != 1 || res.Missing != 1 || res.Fixed != 2 || len(res.Examples) != 1 {
		t.Errorf("repack %+v", res)
	}
	after, err := Check(dst)
	if err != nil {
		t.Fatal(err)
	}
	if after.OK != 4 || after.Invalid != 0 || after.Missing != 2 || after.TableTrailers != 0 {
		t.Errorf("repacked check %+v", after)
	}
	// the tables (what the setup compares) are unchanged, every group keeps its container and gets the full version
	a, _ := Read(src)
	b, _ := Read(dst)
	if Compare(b, a) != Same || len(a) != len(b) {
		t.Errorf("tables changed: %+v %+v", a, b)
	}
	disk, _ := OpenDisk(dst)
	defer disk.Close()
	for arch, gs := range archives {
		for _, g := range gs {
			got, err := disk.Group(arch, g.id)
			if g.missing || g.id == 4 {
				if err != ErrNoGroup {
					t.Errorf("%d/%d should be left out: %v", arch, g.id, err)
				}
				continue
			}
			want := append(append([]byte{}, g.c...), trailer4(g.version)...)
			if err != nil || !bytes.Equal(got, want) {
				t.Errorf("%d/%d: %v", arch, g.id, err)
			}
		}
	}
	// same input, same bytes
	if _, err := Repack(src, again); err != nil {
		t.Fatal(err)
	}
	names, _ := Files(dst)
	if len(names) != 4 { // dat2, idx0, idx2, idx255
		t.Errorf("files %v", names)
	}
	for _, n := range names {
		x, _ := os.ReadFile(filepath.Join(dst, n))
		y, _ := os.ReadFile(filepath.Join(again, n))
		if !bytes.Equal(x, y) {
			t.Errorf("%s differs between two repacks", n)
		}
	}
	// never over an existing cache
	if _, err := Repack(src, dst); err == nil {
		t.Error("repacked over an existing cache")
	}
}

func TestClientAccepts(t *testing.T) {
	c := container([]byte("data"), false)
	crc := crc32.ChecksumIEEE(c)
	for _, x := range []struct {
		trailer []byte
		version uint32
		want    bool
	}{
		{trailer4(0x12345), 0x12345, true},
		{trailer2(0x2345), 0x12345, false}, // 2 bytes cannot hold it: the client compares the full version
		{trailer2(0x2345), 0x2345, true},
		{trailer4(0x2345), 0x2345, true},
		{trailer4(6), 5, false},
		{nil, 0, false},
	} {
		if got := ClientAccepts(append(append([]byte{}, c...), x.trailer...), crc, x.version); got != x.want {
			t.Errorf("trailer %x version %#x: %v", x.trailer, x.version, got)
		}
	}
	if ClientAccepts(append(append([]byte{}, c...), trailer4(5)...), crc+1, 5) {
		t.Error("wrong CRC accepted")
	}
}

func TestParseTableErrors(t *testing.T) {
	good := table(6, 0, []tgroup{{id: 1, c: []byte{1}}})
	if _, err := ParseTable(container(good, false)); err != nil {
		t.Fatal(err)
	}
	bad := map[string][]byte{
		"format 4":      append([]byte{4}, good[1:]...),
		"unknown flags": append(append([]byte{}, good[:5]...), append([]byte{0x30}, good[6:]...)...),
		"ends early":    good[:len(good)-4],
	}
	for name, b := range bad {
		if _, err := ParseTable(container(b, false)); err == nil {
			t.Errorf("%s accepted", name)
		}
	}
	if _, err := ParseTable([]byte{9, 0, 0, 0, 1, 0}); err == nil {
		t.Error("unknown compression accepted")
	}
}
