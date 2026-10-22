package cachever

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"os"
	"path/filepath"
	"sort"
)

// RepackResult tells what Repack wrote.
type RepackResult struct {
	Archives int // reference tables written
	Groups   int // groups written
	// Missing: groups a reference table lists that the cache does not have (the game downloads them).
	Missing int
	// Invalid: groups whose data does not match their reference table (left out: the game would throw them away
	// and download them anyway).
	Invalid  int
	Examples []string // a few invalid groups: "archive/group: why"
	Fixed    int      // groups that had a trailer the 241 client does not accept as it was
}

// Repack writes the cache in src to dst (a new or empty folder) the way the 241 client stores it: every group
// that its reference table lists, CRC-checked, with the full 4-byte version trailer, and the reference tables as
// bare containers. Without this the client throws away every group whose version is above 0xFFFF but has only a
// 2-byte trailer (caches packed by older tools) and downloads it from the game server again on the first start.
// Groups are written in archive and group order with no gaps, so the same cache always gives the same bytes.
func Repack(src, dst string) (RepackResult, error) {
	var res RepackResult
	in, err := OpenDisk(src)
	if err != nil {
		return res, err
	}
	defer in.Close()
	tables, ids, err := in.Tables()
	if err != nil {
		return res, err
	}
	if err := os.MkdirAll(dst, 0o755); err != nil {
		return res, err
	}
	if names, _ := Files(dst); len(names) > 0 {
		return res, fmt.Errorf("%s already has a game cache", dst)
	}
	w, err := newDiskWriter(dst)
	if err != nil {
		return res, err
	}
	defer w.abort()
	// reference tables first (archive 255), then every archive's groups
	idx255 := make([]byte, (ids[len(ids)-1]+1)*6)
	for _, a := range ids {
		raw, err := in.Group(255, a)
		if err != nil {
			return res, fmt.Errorf("reference table of archive %d: %v", a, err)
		}
		end, _ := ContainerLen(raw)
		if err := w.group(255, a, raw[:end], idx255); err != nil {
			return res, err
		}
		res.Archives++
	}
	invalid := func(a, g int, why string) {
		res.Invalid++
		if len(res.Examples) < 5 {
			res.Examples = append(res.Examples, fmt.Sprintf("%d/%d: %s", a, g, why))
		}
	}
	for _, a := range ids {
		t := tables[a]
		var idx []byte
		if n := len(t.Groups); n > 0 {
			idx = make([]byte, (t.Groups[n-1]+1)*6)
		}
		for i, g := range t.Groups {
			raw, err := in.Group(a, g)
			if errors.Is(err, ErrNoGroup) {
				res.Missing++
				continue
			}
			if err != nil {
				invalid(a, g, err.Error())
				continue
			}
			end, err := ContainerLen(raw)
			if err != nil {
				invalid(a, g, err.Error())
				continue
			}
			if crc32.ChecksumIEEE(raw[:end]) != t.CRCs[i] {
				invalid(a, g, "CRC differs from the reference table")
				continue
			}
			if !ClientAccepts(raw, t.CRCs[i], t.Versions[i]) {
				res.Fixed++
			}
			stored := make([]byte, end+4)
			copy(stored, raw[:end])
			binary.BigEndian.PutUint32(stored[end:], t.Versions[i])
			if err := w.group(a, g, stored, idx); err != nil {
				return res, err
			}
			res.Groups++
		}
		if err := os.WriteFile(filepath.Join(dst, fmt.Sprintf("main_file_cache.idx%d", a)), idx, 0o644); err != nil {
			return res, err
		}
	}
	if err := os.WriteFile(filepath.Join(dst, "main_file_cache.idx255"), idx255, 0o644); err != nil {
		return res, err
	}
	return res, w.close()
}

type diskWriter struct {
	f    *os.File
	bw   *bufio.Writer
	next int // next free sector
	buf  []byte
}

func newDiskWriter(dir string) (*diskWriter, error) {
	f, err := os.Create(filepath.Join(dir, "main_file_cache.dat2"))
	if err != nil {
		return nil, err
	}
	w := &diskWriter{f: f, bw: bufio.NewWriterSize(f, 1<<20), next: 1, buf: make([]byte, sectorSize)}
	_, err = w.bw.Write(w.buf) // sector 0 is never used
	return w, err
}

// group appends a group's sectors (each a full 520 bytes) and records it in idx.
func (w *diskWriter) group(archive, group int, data []byte, idx []byte) error {
	if len(data) == 0 || len(data) > 0xFFFFFF {
		return fmt.Errorf("group %d/%d is %d bytes", archive, group, len(data))
	}
	hdr := headerSmall
	if group > 0xFFFF {
		hdr = headerBig
	}
	chunk := sectorSize - hdr
	parts := (len(data) + chunk - 1) / chunk
	if parts > maxParts || w.next+parts > maxSector {
		return fmt.Errorf("group %d/%d does not fit the cache format", archive, group)
	}
	e := idx[group*6:]
	e[0], e[1], e[2] = byte(len(data)>>16), byte(len(data)>>8), byte(len(data))
	e[3], e[4], e[5] = byte(w.next>>16), byte(w.next>>8), byte(w.next)
	for part := 0; part < parts; part++ {
		b := w.buf
		clear(b)
		next := 0
		if part < parts-1 {
			next = w.next + 1
		}
		if hdr == headerBig {
			binary.BigEndian.PutUint32(b[0:], uint32(group))
		} else {
			binary.BigEndian.PutUint16(b[0:], uint16(group))
		}
		binary.BigEndian.PutUint16(b[hdr-6:], uint16(part))
		b[hdr-4], b[hdr-3], b[hdr-2] = byte(next>>16), byte(next>>8), byte(next)
		b[hdr-1] = byte(archive)
		copy(b[hdr:], data[part*chunk:])
		if _, err := w.bw.Write(b); err != nil {
			return err
		}
		w.next++
	}
	return nil
}

func (w *diskWriter) close() error {
	if w.f == nil {
		return nil
	}
	err := w.bw.Flush()
	if err == nil {
		err = w.f.Sync()
	}
	if cerr := w.f.Close(); err == nil {
		err = cerr
	}
	w.f = nil
	return err
}

func (w *diskWriter) abort() {
	if w.f != nil {
		w.f.Close()
		w.f = nil
	}
}

// ArchiveCheck is Check's result for one archive.
type ArchiveCheck struct {
	ID, Groups int
	OK         int // groups the client takes as they are
	Invalid    int // groups on disk that the client throws away (and downloads again)
	Missing    int // groups not on disk (downloaded when needed)
}

// CheckResult is what Check found.
type CheckResult struct {
	Archives []ArchiveCheck
	Groups   int
	OK       int
	Invalid  int
	Missing  int
	// TableTrailers: reference tables stored with extra bytes after the container (the client's CRC check over
	// the whole group then fails and it downloads the table again).
	TableTrailers int
}

// Check reads every group the reference tables list and checks it the way the 241 client does when it opens the
// cache (ClientAccepts).
func Check(dir string) (CheckResult, error) {
	var res CheckResult
	d, err := OpenDisk(dir)
	if err != nil {
		return res, err
	}
	defer d.Close()
	tables, ids, err := d.Tables()
	if err != nil {
		return res, err
	}
	sort.Ints(ids)
	for _, a := range ids {
		raw, _ := d.Group(255, a)
		if end, err := ContainerLen(raw); err == nil && end != len(raw) {
			res.TableTrailers++
		}
		t := tables[a]
		ac := ArchiveCheck{ID: a, Groups: len(t.Groups)}
		for i, g := range t.Groups {
			stored, err := d.Group(a, g)
			switch {
			case errors.Is(err, ErrNoGroup):
				ac.Missing++
			case err == nil && ClientAccepts(stored, t.CRCs[i], t.Versions[i]):
				ac.OK++
			default:
				ac.Invalid++
			}
		}
		res.Archives = append(res.Archives, ac)
		res.Groups += ac.Groups
		res.OK += ac.OK
		res.Invalid += ac.Invalid
		res.Missing += ac.Missing
	}
	return res, nil
}
