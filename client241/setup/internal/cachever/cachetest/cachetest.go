// Package cachetest writes small fake game caches (JS5 disk format) for tests.
package cachetest

import (
	"encoding/binary"
	"os"
	"path/filepath"
	"sort"
)

// Write makes main_file_cache.dat2 and .idx files in dir with one reference table per index in revs (index ->
// revision, format 6, uncompressed). salt changes the tables' bytes (and so their CRC) without changing revisions.
func Write(dir string, revs map[int]int64, salt byte) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	var ids []int
	for id := range revs {
		ids = append(ids, id)
	}
	sort.Ints(ids)
	max := 0
	for _, id := range ids {
		if id > max {
			max = id
		}
	}
	dat := make([]byte, 520) // sector 0 is never used
	idx255 := make([]byte, (max+1)*6)
	for _, id := range ids {
		table := []byte{6, 0, 0, 0, 0, 0, salt, 0, 0} // format 6, revision, flags, 0 groups...
		binary.BigEndian.PutUint32(table[1:], uint32(revs[id]))
		c := append([]byte{0, 0, 0, 0, 0}, table...)
		binary.BigEndian.PutUint32(c[1:], uint32(len(table)))
		c = append(c, 0, 1) // the 2-byte version trailer some caches keep
		sector := len(dat) / 520
		for part := 0; len(c) > 0 || part == 0; part++ {
			n := len(c)
			if n > 512 {
				n = 512
			}
			s := make([]byte, 520)
			binary.BigEndian.PutUint16(s[0:], uint16(id))
			binary.BigEndian.PutUint16(s[2:], uint16(part))
			next := 0
			if len(c) > n {
				next = len(dat)/520 + 1
			}
			s[4], s[5], s[6] = byte(next>>16), byte(next>>8), byte(next)
			s[7] = 255
			copy(s[8:], c[:n])
			dat = append(dat, s...)
			c = c[n:]
		}
		size := 5 + 9 + 2
		e := idx255[id*6:]
		e[0], e[1], e[2] = byte(size>>16), byte(size>>8), byte(size)
		e[3], e[4], e[5] = byte(sector>>16), byte(sector>>8), byte(sector)
	}
	if err := os.WriteFile(filepath.Join(dir, "main_file_cache.dat2"), dat, 0o644); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, "main_file_cache.idx255"), idx255, 0o644); err != nil {
		return err
	}
	for _, id := range ids {
		name := filepath.Join(dir, "main_file_cache.idx"+itoa(id))
		if err := os.WriteFile(name, []byte{byte(id), salt}, 0o644); err != nil {
			return err
		}
	}
	return nil
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var b []byte
	for ; i > 0; i /= 10 {
		b = append([]byte{byte('0' + i%10)}, b...)
	}
	return string(b)
}
