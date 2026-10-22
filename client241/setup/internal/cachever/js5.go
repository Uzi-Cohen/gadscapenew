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
)

// The JS5 disk format, as the 241 client reads and writes it:
//
//   - main_file_cache.idx<A>: 6 bytes per group of archive A: size (3 bytes), first sector (3 bytes). Size or
//     sector 0: the group is not on disk.
//   - main_file_cache.dat2: 520-byte sectors. Header: group (2 bytes, or 4 bytes for groups above 0xFFFF), part
//     number (2), next sector (3), archive (1); the rest is data. Sector 0 is never used.
//   - a group is its container (compression, length, [uncompressed length], data) followed by a version trailer.
//     The 241 client writes the full 32-bit version (4 bytes) and accepts a group when the CRC-32 of the bytes
//     before the trailer and the trailer equal the CRC and version in the archive's reference table; it also
//     tries an old 2-byte trailer, but compares it with the full version, so a 2-byte trailer only works for
//     versions up to 0xFFFF.
//   - the reference tables (archive 255, group A = archive A's table) are stored as the bare container: the
//     client checks the CRC-32 of the whole group against the server's master index.

const (
	headerBig = 10 // sector header for groups above 0xFFFF
	maxParts  = 0x10000
	maxSector = 0xFFFFFF
)

// Table is the part of a reference table the cache layout needs: every group's id, CRC-32 and version.
type Table struct {
	Format   int
	Revision int64 // -1: format 5 (no revision)
	Groups   []int
	CRCs     []uint32
	Versions []uint32
}

// ContainerLen is the length of the container at the start of a stored group (without a version trailer).
func ContainerLen(b []byte) (int, error) {
	if len(b) < 5 {
		return 0, fmt.Errorf("%d bytes is too short for a container", len(b))
	}
	n := int64(binary.BigEndian.Uint32(b[1:]))
	end := 5 + n
	if b[0] != 0 {
		end += 4
	}
	if n > maxGroupSize || end > int64(len(b)) {
		return 0, fmt.Errorf("container says %d bytes, the group has %d", end, len(b))
	}
	return int(end), nil
}

// Unpack returns the data of a container (no compression, bzip2 or gzip).
func Unpack(c []byte) ([]byte, error) {
	end, err := ContainerLen(c)
	if err != nil {
		return nil, err
	}
	switch c[0] {
	case 0:
		return c[5:end], nil
	case 1, 2:
		ulen := int64(binary.BigEndian.Uint32(c[5:]))
		if ulen > maxGroupSize {
			return nil, fmt.Errorf("container unpacks to %d bytes", ulen)
		}
		var r io.Reader
		if c[0] == 1 { // Jagex leaves out bzip2's "BZh1" header
			r = bzip2.NewReader(io.MultiReader(bytes.NewReader([]byte("BZh1")), bytes.NewReader(c[9:end])))
		} else {
			z, err := gzip.NewReader(bytes.NewReader(c[9:end]))
			if err != nil {
				return nil, err
			}
			r = z
		}
		out := make([]byte, ulen)
		if _, err := io.ReadFull(r, out); err != nil {
			return nil, fmt.Errorf("unpacking: %v", err)
		}
		return out, nil
	}
	return nil, fmt.Errorf("unknown compression %d", c[0])
}

// ParseTable reads a reference table container (field order as in the client and the server's jagcached: ids,
// name hashes (flag 1), CRCs, uncompressed CRCs (flag 8), whirlpool digests (flag 2), sizes (flag 4), versions).
func ParseTable(container []byte) (*Table, error) {
	data, err := Unpack(container)
	if err != nil {
		return nil, err
	}
	r := &reader{b: data}
	t := &Table{Format: int(r.u8()), Revision: -1}
	if t.Format < 5 || t.Format > 7 {
		return nil, fmt.Errorf("reference table format %d", t.Format)
	}
	if t.Format >= 6 {
		t.Revision = int64(r.u32())
	}
	flags := r.u8()
	if flags&^0xF != 0 {
		return nil, fmt.Errorf("unknown reference table flags %#x", flags)
	}
	count := r.count(t.Format)
	if r.err != nil || count < 0 || count*4 > len(data) {
		return nil, fmt.Errorf("reference table with %d groups in %d bytes", count, len(data))
	}
	t.Groups = make([]int, count)
	last := 0
	for i := range t.Groups {
		last += r.count(t.Format)
		t.Groups[i] = last
	}
	if flags&1 != 0 {
		r.skip(4 * count)
	}
	t.CRCs = make([]uint32, count)
	for i := range t.CRCs {
		t.CRCs[i] = r.u32()
	}
	if flags&8 != 0 {
		r.skip(4 * count)
	}
	if flags&2 != 0 {
		r.skip(64 * count)
	}
	if flags&4 != 0 {
		r.skip(8 * count)
	}
	t.Versions = make([]uint32, count)
	for i := range t.Versions {
		t.Versions[i] = r.u32()
	}
	if r.err != nil {
		return nil, fmt.Errorf("reference table ends early")
	}
	for i := 1; i < count; i++ {
		if t.Groups[i] <= t.Groups[i-1] {
			return nil, fmt.Errorf("reference table group ids are not ascending at %d", t.Groups[i])
		}
	}
	return t, nil
}

type reader struct {
	b   []byte
	p   int
	err error
}

func (r *reader) take(n int) []byte {
	if r.err != nil || n < 0 || r.p+n > len(r.b) {
		r.err = io.ErrUnexpectedEOF
		return make([]byte, n)
	}
	s := r.b[r.p : r.p+n]
	r.p += n
	return s
}

func (r *reader) u8() byte       { return r.take(1)[0] }
func (r *reader) u16() int       { return int(binary.BigEndian.Uint16(r.take(2))) }
func (r *reader) u32() uint32    { return binary.BigEndian.Uint32(r.take(4)) }
func (r *reader) skip(n int)     { r.take(n) }
func (r *reader) peekHigh() bool { return r.err == nil && r.p < len(r.b) && r.b[r.p]&0x80 != 0 }

// count reads a group count or id delta: 2 bytes, or (format 7) a "big smart" (2 bytes, or 4 with the top bit set).
func (r *reader) count(format int) int {
	if format >= 7 && r.peekHigh() {
		return int(r.u32() & 0x7FFFFFFF)
	}
	return r.u16()
}

// Disk reads groups from a cache folder.
type Disk struct {
	dir string
	dat *os.File
	n   int64 // sectors in dat2
	idx map[int][]byte
}

// OpenDisk opens the cache in dir for reading.
func OpenDisk(dir string) (*Disk, error) {
	dat, err := os.Open(filepath.Join(dir, "main_file_cache.dat2"))
	if err != nil {
		return nil, err
	}
	st, err := dat.Stat()
	if err != nil {
		dat.Close()
		return nil, err
	}
	return &Disk{dir: dir, dat: dat, n: (st.Size() + sectorSize - 1) / sectorSize, idx: map[int][]byte{}}, nil
}

// Close closes the dat2 file.
func (d *Disk) Close() error { return d.dat.Close() }

// HasIndex tells whether main_file_cache.idx<archive> exists.
func (d *Disk) HasIndex(archive int) bool {
	_, err := os.Stat(filepath.Join(d.dir, fmt.Sprintf("main_file_cache.idx%d", archive)))
	return err == nil
}

func (d *Disk) index(archive int) []byte {
	b, ok := d.idx[archive]
	if !ok {
		b, _ = os.ReadFile(filepath.Join(d.dir, fmt.Sprintf("main_file_cache.idx%d", archive)))
		d.idx[archive] = b
	}
	return b
}

// ErrNoGroup: the group is not on disk.
var ErrNoGroup = errors.New("group is not in the cache")

// Group returns the stored bytes of a group (container and trailer), or ErrNoGroup.
func (d *Disk) Group(archive, group int) ([]byte, error) {
	idx := d.index(archive)
	if group < 0 || int64(group)*6+6 > int64(len(idx)) {
		return nil, ErrNoGroup
	}
	e := idx[group*6:]
	size := int(e[0])<<16 | int(e[1])<<8 | int(e[2])
	sector := int64(e[3])<<16 | int64(e[4])<<8 | int64(e[5])
	if size == 0 || sector == 0 || sector >= d.n {
		return nil, ErrNoGroup
	}
	return readSectors(d.dat, archive, group, size, sector)
}

// readSectors follows the sector chain of a group (8-byte headers, 10-byte for groups above 0xFFFF).
func readSectors(dat io.ReaderAt, archive, group, size int, sector int64) ([]byte, error) {
	if size > maxGroupSize {
		return nil, fmt.Errorf("group %d/%d is %d bytes", archive, group, size)
	}
	hdr := headerSmall
	if group > 0xFFFF {
		hdr = headerBig
	}
	out := make([]byte, 0, size)
	buf := make([]byte, sectorSize)
	for part := 0; len(out) < size; part++ {
		if sector <= 0 {
			return nil, fmt.Errorf("group %d/%d ends early", archive, group)
		}
		n, err := dat.ReadAt(buf, sector*sectorSize)
		if n < hdr {
			if err == nil {
				err = io.ErrUnexpectedEOF
			}
			return nil, fmt.Errorf("group %d/%d: %v", archive, group, err)
		}
		var g int
		if hdr == headerBig {
			g = int(binary.BigEndian.Uint32(buf[0:]))
		} else {
			g = int(binary.BigEndian.Uint16(buf[0:]))
		}
		p := int(binary.BigEndian.Uint16(buf[hdr-6:]))
		next := int64(buf[hdr-4])<<16 | int64(buf[hdr-3])<<8 | int64(buf[hdr-2])
		ix := int(buf[hdr-1])
		if g != group || p != part || ix != archive { // the client compares the full part number too
			return nil, fmt.Errorf("group %d/%d: sector %d belongs to %d/%d part %d", archive, group, sector, ix, g, p)
		}
		take := size - len(out)
		if take > sectorSize-hdr {
			take = sectorSize - hdr
		}
		if hdr+take > n {
			return nil, fmt.Errorf("group %d/%d: %v", archive, group, io.ErrUnexpectedEOF)
		}
		out = append(out, buf[hdr:hdr+take]...)
		sector = next
	}
	return out, nil
}

// Tables reads the reference table of every archive listed in idx255 (archive -> table). An archive whose table
// cannot be read is an error.
func (d *Disk) Tables() (map[int]*Table, []int, error) {
	idx := d.index(255)
	if len(idx) == 0 || len(idx)%6 != 0 {
		return nil, nil, fmt.Errorf("main_file_cache.idx255 has %d bytes (not a cache index)", len(idx))
	}
	tables := map[int]*Table{}
	var ids []int
	for a := 0; a < len(idx)/6 && a < 255; a++ {
		raw, err := d.Group(255, a)
		if errors.Is(err, ErrNoGroup) {
			continue
		}
		if err != nil {
			return nil, nil, fmt.Errorf("reference table of archive %d: %v", a, err)
		}
		t, err := ParseTable(raw)
		if err != nil {
			return nil, nil, fmt.Errorf("reference table of archive %d: %v", a, err)
		}
		tables[a] = t
		ids = append(ids, a)
	}
	if len(ids) == 0 {
		return nil, nil, errors.New("cache has no reference tables")
	}
	return tables, ids, nil
}

// ClientAccepts tells whether the 241 client takes a stored group as it is (its CRC and version trailer match the
// reference table), or throws it away and downloads the group from the server again.
func ClientAccepts(stored []byte, crc, version uint32) bool {
	n := len(stored)
	if n > 4 && crc32.ChecksumIEEE(stored[:n-4]) == crc && binary.BigEndian.Uint32(stored[n-4:]) == version {
		return true
	}
	return n > 2 && crc32.ChecksumIEEE(stored[:n-2]) == crc && uint32(binary.BigEndian.Uint16(stored[n-2:])) == version
}
