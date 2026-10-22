// Package cachever reads what version a game cache folder (main_file_cache.dat2 + .idx files, Jagex's JS5 disk
// format) is at: for every index, the revision and CRC-32 of its reference table (archive 255). These are the
// numbers the game client compares with the server to decide what to download, so they also tell whether a
// player's cache is the same as, older or newer than the one in a release.
package cachever

import (
	"bytes"
	"compress/bzip2"
	"compress/gzip"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"os"
	"path/filepath"
	"sort"

	"gadscapesetup/internal/manifest"
)

const (
	sectorSize   = 520
	headerSmall  = 8
	maxGroupSize = 64 << 20
)

// Files are the names of a cache's own files in a folder: main_file_cache.dat2 and main_file_cache.idx<N>.
func Files(dir string) ([]string, error) {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var out []string
	for _, e := range ents {
		if e.Type().IsRegular() && IsCacheFile(e.Name()) {
			out = append(out, e.Name())
		}
	}
	sort.Strings(out)
	return out, nil
}

// IsCacheFile is true for main_file_cache.dat2 and main_file_cache.idx<0-255>.
func IsCacheFile(name string) bool {
	if name == "main_file_cache.dat2" {
		return true
	}
	const p = "main_file_cache.idx"
	if len(name) <= len(p) || len(name) > len(p)+3 || name[:len(p)] != p {
		return false
	}
	n := 0
	for _, c := range name[len(p):] {
		if c < '0' || c > '9' {
			return false
		}
		n = n*10 + int(c-'0')
	}
	return n <= 255
}

// Read returns the reference table of every index listed in the cache's idx255 (sorted by index).
// An index whose table cannot be read gets Revision -1 and CRC 0.
func Read(dir string) ([]manifest.Index, error) {
	dat, err := os.Open(filepath.Join(dir, "main_file_cache.dat2"))
	if err != nil {
		return nil, err
	}
	defer dat.Close()
	idx, err := os.ReadFile(filepath.Join(dir, "main_file_cache.idx255"))
	if err != nil {
		return nil, err
	}
	if len(idx)%6 != 0 || len(idx) == 0 {
		return nil, fmt.Errorf("main_file_cache.idx255 has %d bytes (not a cache index)", len(idx))
	}
	var out []manifest.Index
	for i := 0; i < len(idx)/6 && i < 255; i++ {
		e := idx[i*6:]
		size := int(e[0])<<16 | int(e[1])<<8 | int(e[2])
		sector := int64(e[3])<<16 | int64(e[4])<<8 | int64(e[5])
		if size == 0 || sector == 0 {
			continue // no such index
		}
		ix := manifest.Index{ID: i, Revision: -1}
		if data, err := readSectors(dat, 255, i, size, sector); err == nil {
			ix.CRC, ix.Revision = tableInfo(data)
		}
		out = append(out, ix)
	}
	if len(out) == 0 {
		return nil, errors.New("cache has no indexes")
	}
	return out, nil
}

// tableInfo is the CRC-32 of a reference table's container (without the 2-byte version some caches append) and
// the revision inside it (-1 when it cannot be read: unknown compression or format).
func tableInfo(c []byte) (uint32, int64) {
	if len(c) < 5 {
		return 0, -1
	}
	comp := c[0]
	clen := int(binary.BigEndian.Uint32(c[1:]))
	end := 5 + clen
	if comp != 0 {
		end += 4
	}
	if clen < 0 || end > len(c) {
		return crc32.ChecksumIEEE(c), -1
	}
	crc := crc32.ChecksumIEEE(c[:end])
	var data []byte
	switch comp {
	case 0:
		data = c[5:end]
	case 1, 2:
		ulen := int(binary.BigEndian.Uint32(c[5:]))
		if ulen < 0 || ulen > maxGroupSize {
			return crc, -1
		}
		var r io.Reader
		if comp == 1 { // Jagex leaves out bzip2's "BZh1" header
			r = bzip2.NewReader(io.MultiReader(bytes.NewReader([]byte("BZh1")), bytes.NewReader(c[9:end])))
		} else {
			z, err := gzip.NewReader(bytes.NewReader(c[9:end]))
			if err != nil {
				return crc, -1
			}
			r = z
		}
		// only the first 5 bytes are needed: format and revision
		head := make([]byte, 5)
		n, _ := io.ReadFull(r, head)
		data = head[:n]
	default:
		return crc, -1
	}
	if len(data) < 1 {
		return crc, -1
	}
	format := data[0]
	if format < 6 {
		return crc, 0 // older formats carry no revision
	}
	if len(data) < 5 {
		return crc, -1
	}
	return crc, int64(binary.BigEndian.Uint32(data[1:5]))
}

// Relation is how a player's cache compares with a release's.
type Relation int

const (
	Same    Relation = iota // every index has the release's table
	Older                   // no index is newer and at least one is older or missing
	Newer                   // at least one index has a higher revision than the release
	Differs                 // tables differ but the revisions do not say which is newer
)

func (r Relation) String() string {
	return [...]string{"same", "older", "newer", "different"}[r]
}

// Compare compares a player's cache (local) with the release's (rel), index by index. An index the player's
// cache lacks or cannot read counts as older; one with a higher revision makes the whole cache newer (it is then
// never replaced); indexes the release does not have are not looked at.
func Compare(local, rel []manifest.Index) Relation {
	have := map[int]manifest.Index{}
	for _, ix := range local {
		have[ix.ID] = ix
	}
	same, older, newer := true, false, false
	for _, r := range rel {
		l, ok := have[r.ID]
		if !ok || l.Revision < 0 {
			same, older = false, true
			continue
		}
		if l.CRC == r.CRC && l.Revision == r.Revision {
			continue
		}
		same = false
		if r.Revision < 0 {
			continue // the release's revision is unknown: no order
		}
		if l.Revision > r.Revision {
			newer = true
		} else if l.Revision < r.Revision {
			older = true
		}
	}
	switch {
	case newer:
		return Newer
	case same:
		return Same
	case older:
		return Older
	}
	return Differs
}
