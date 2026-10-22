// Package winres writes the Windows resources of the setup exe (icon, application manifest, version information)
// as a COFF object (.syso) that the Go linker puts into the exe, and reads version information back from an exe.
// (github.com/akavel/rsrc, used for the client exe, has no version information; this has no dependencies.)
package winres

import (
	"bytes"
	"debug/pe"
	"encoding/binary"
	"errors"
	"fmt"
	"sort"
	"unicode/utf16"
)

const (
	rtIcon      = 3
	rtGroupIcon = 14
	rtVersion   = 16
	rtManifest  = 24
	langEnUS    = 0x0409
)

// VersionInfo is the exe's version resource (what Explorer shows under Properties > Details).
type VersionInfo struct {
	FileVersion    [4]uint16
	ProductVersion [4]uint16
	Strings        map[string]string // CompanyName, FileDescription, FileVersion, ProductName, ...
}

type resource struct {
	typ, id uint32
	data    []byte
}

// Build returns a .syso (COFF, amd64) with the icons of an .ico file (group 1), the manifest (id 1) and the
// version information (id 1).
func Build(ico, manifestXML []byte, vi VersionInfo) ([]byte, error) {
	var res []resource
	if len(ico) > 0 {
		icons, group, err := splitIco(ico, 2)
		if err != nil {
			return nil, err
		}
		for i, img := range icons {
			res = append(res, resource{rtIcon, uint32(2 + i), img})
		}
		res = append(res, resource{rtGroupIcon, 1, group})
	}
	if len(manifestXML) > 0 {
		res = append(res, resource{rtManifest, 1, manifestXML})
	}
	res = append(res, resource{rtVersion, 1, versionResource(vi)})
	return coff(res), nil
}

// splitIco reads an .ico file into its images and an RT_GROUP_ICON whose entries point at ids firstID, firstID+1...
func splitIco(ico []byte, firstID uint16) ([][]byte, []byte, error) {
	if len(ico) < 6 || binary.LittleEndian.Uint16(ico[0:]) != 0 || binary.LittleEndian.Uint16(ico[2:]) != 1 {
		return nil, nil, errors.New("not an .ico file")
	}
	n := int(binary.LittleEndian.Uint16(ico[4:]))
	if n == 0 || len(ico) < 6+16*n {
		return nil, nil, errors.New(".ico file has no images")
	}
	var images [][]byte
	var group bytes.Buffer
	binary.Write(&group, binary.LittleEndian, [3]uint16{0, 1, uint16(n)})
	for i := 0; i < n; i++ {
		e := ico[6+16*i:]
		size := binary.LittleEndian.Uint32(e[8:])
		off := binary.LittleEndian.Uint32(e[12:])
		if uint64(off)+uint64(size) > uint64(len(ico)) {
			return nil, nil, fmt.Errorf(".ico image %d is outside the file", i)
		}
		images = append(images, ico[off:off+size])
		group.Write(e[:12])                                          // width, height, colours, reserved, planes, bit count, bytes
		binary.Write(&group, binary.LittleEndian, firstID+uint16(i)) // resource id instead of the file offset
	}
	return images, group.Bytes(), nil
}

func utf16z(s string) []byte {
	u := utf16.Encode([]rune(s))
	b := make([]byte, 2*len(u)+2)
	for i, c := range u {
		binary.LittleEndian.PutUint16(b[2*i:], c)
	}
	return b
}

func pad4(b *bytes.Buffer) {
	for b.Len()%4 != 0 {
		b.WriteByte(0)
	}
}

// node is one VS_VERSIONINFO block: wLength, wValueLength, wType, key, padding, value, padding, children.
func node(key string, value []byte, valueLen uint16, text bool, children ...[]byte) []byte {
	var b bytes.Buffer
	b.Write(make([]byte, 6))
	b.Write(utf16z(key))
	pad4(&b)
	b.Write(value)
	for _, c := range children {
		pad4(&b)
		b.Write(c)
	}
	out := b.Bytes()
	typ := uint16(0)
	if text {
		typ = 1
	}
	binary.LittleEndian.PutUint16(out[0:], uint16(len(out)))
	binary.LittleEndian.PutUint16(out[2:], valueLen)
	binary.LittleEndian.PutUint16(out[4:], typ)
	return out
}

func versionResource(vi VersionInfo) []byte {
	ms := func(v [4]uint16) (uint32, uint32) {
		return uint32(v[0])<<16 | uint32(v[1]), uint32(v[2])<<16 | uint32(v[3])
	}
	fms, fls := ms(vi.FileVersion)
	pms, pls := ms(vi.ProductVersion)
	fixed := make([]byte, 52)
	for i, v := range []uint32{0xFEEF04BD, 0x00010000, fms, fls, pms, pls, 0x3F, 0, 0x00040004, 1, 0, 0, 0} {
		binary.LittleEndian.PutUint32(fixed[4*i:], v)
	}
	var keys []string
	for k := range vi.Strings {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var strs [][]byte
	for _, k := range keys {
		v := utf16z(vi.Strings[k])
		strs = append(strs, node(k, v, uint16(len(v)/2), true))
	}
	table := node("040904B0", nil, 0, true, strs...)
	sfi := node("StringFileInfo", nil, 0, true, table)
	trans := make([]byte, 4)
	binary.LittleEndian.PutUint16(trans[0:], langEnUS)
	binary.LittleEndian.PutUint16(trans[2:], 1200)
	vfi := node("VarFileInfo", nil, 0, true, node("Translation", trans, 4, false))
	return node("VS_VERSION_INFO", fixed, 52, false, sfi, vfi)
}

// coff lays the resources out as a .rsrc section: directory tables (type, id, language), data entries, data.
func coff(res []resource) []byte {
	sort.Slice(res, func(i, j int) bool {
		if res[i].typ != res[j].typ {
			return res[i].typ < res[j].typ
		}
		return res[i].id < res[j].id
	})
	var types []uint32
	byType := map[uint32][]int{}
	for i, r := range res {
		if len(byType[r.typ]) == 0 {
			types = append(types, r.typ)
		}
		byType[r.typ] = append(byType[r.typ], i)
	}
	dirSize := func(n int) int { return 16 + 8*n }
	// offsets
	off := dirSize(len(types))
	typeDir := map[uint32]int{}
	for _, t := range types {
		typeDir[t] = off
		off += dirSize(len(byType[t]))
	}
	langDir := make([]int, len(res))
	for _, t := range types {
		for _, i := range byType[t] {
			langDir[i] = off
			off += dirSize(1)
		}
	}
	dataEntry := make([]int, len(res))
	for _, t := range types {
		for _, i := range byType[t] {
			dataEntry[i] = off
			off += 16
		}
	}
	dataOff := make([]int, len(res))
	for _, t := range types {
		for _, i := range byType[t] {
			off = (off + 7) &^ 7
			dataOff[i] = off
			off += len(res[i].data)
		}
	}
	raw := make([]byte, (off+7)&^7)
	le := binary.LittleEndian
	writeDir := func(at, n int) {
		le.PutUint16(raw[at+14:], uint16(n)) // NumberOfIdEntries
	}
	writeDir(0, len(types))
	for k, t := range types {
		e := 16 + 8*k
		le.PutUint32(raw[e:], t)
		le.PutUint32(raw[e+4:], 0x80000000|uint32(typeDir[t]))
		writeDir(typeDir[t], len(byType[t]))
		for j, i := range byType[t] {
			e := typeDir[t] + 16 + 8*j
			le.PutUint32(raw[e:], res[i].id)
			le.PutUint32(raw[e+4:], 0x80000000|uint32(langDir[i]))
			writeDir(langDir[i], 1)
			le.PutUint32(raw[langDir[i]+16:], langEnUS)
			le.PutUint32(raw[langDir[i]+20:], uint32(dataEntry[i])) // a leaf: no high bit
			le.PutUint32(raw[dataEntry[i]:], uint32(dataOff[i]))    // relocated to an RVA by the linker
			le.PutUint32(raw[dataEntry[i]+4:], uint32(len(res[i].data)))
			copy(raw[dataOff[i]:], res[i].data)
		}
	}
	const headers = 20 + 40
	relocAt := headers + len(raw)
	symAt := relocAt + 10*len(res)
	var out bytes.Buffer
	binary.Write(&out, le, pe.FileHeader{
		Machine: pe.IMAGE_FILE_MACHINE_AMD64, NumberOfSections: 1, PointerToSymbolTable: uint32(symAt),
		NumberOfSymbols: 1, Characteristics: 0x0104,
	})
	binary.Write(&out, le, pe.SectionHeader32{
		Name: [8]uint8{'.', 'r', 's', 'r', 'c'}, SizeOfRawData: uint32(len(raw)), PointerToRawData: headers,
		PointerToRelocations: uint32(relocAt), NumberOfRelocations: uint16(len(res)), Characteristics: 0x40000040,
	})
	out.Write(raw)
	for i := range res {
		binary.Write(&out, le, struct {
			VirtualAddress uint32
			SymbolIndex    uint32
			Type           uint16
		}{uint32(dataEntry[i]), 0, 3}) // IMAGE_REL_AMD64_ADDR32NB against the section symbol
	}
	binary.Write(&out, le, struct {
		Name          [8]byte
		Value         uint32
		SectionNumber int16
		Type          uint16
		StorageClass  uint8
		AuxCount      uint8
	}{Name: [8]byte{'.', 'r', 's', 'r', 'c'}, SectionNumber: 1, StorageClass: 3})
	binary.Write(&out, le, uint32(4)) // empty string table
	return out.Bytes()
}

// ReadVersion reads the version strings and file version of a PE exe (or a .syso).
func ReadVersion(file []byte) (map[string]string, [4]uint16, error) {
	var fv [4]uint16
	f, err := pe.NewFile(bytes.NewReader(file))
	if err != nil {
		return nil, fv, err
	}
	sec := f.Section(".rsrc")
	if sec == nil {
		return nil, fv, errors.New("no .rsrc section")
	}
	raw, err := sec.Data()
	if err != nil {
		return nil, fv, err
	}
	rva := sec.VirtualAddress
	le := binary.LittleEndian
	entries := func(at uint32) [][2]uint32 {
		if int(at)+16 > len(raw) {
			return nil
		}
		n := int(le.Uint16(raw[at+12:])) + int(le.Uint16(raw[at+14:]))
		var out [][2]uint32
		for i := 0; i < n && int(at)+16+8*i+8 <= len(raw); i++ {
			e := at + 16 + uint32(8*i)
			out = append(out, [2]uint32{le.Uint32(raw[e:]), le.Uint32(raw[e+4:])})
		}
		return out
	}
	var data []byte
	for _, t := range entries(0) {
		if t[0] != rtVersion || t[1]&0x80000000 == 0 {
			continue
		}
		for _, id := range entries(t[1] &^ 0x80000000) {
			for _, lang := range entries(id[1] &^ 0x80000000) {
				de := lang[1]
				if int(de)+8 > len(raw) {
					continue
				}
				addr, size := le.Uint32(raw[de:]), le.Uint32(raw[de+4:])
				if f.OptionalHeader != nil { // an exe: an RVA (a .syso has offsets inside the section)
					addr -= rva
				}
				if uint64(addr)+uint64(size) <= uint64(len(raw)) {
					data = raw[addr : addr+size]
				}
			}
		}
	}
	if data == nil {
		return nil, fv, errors.New("no version resource")
	}
	return parseVersion(data)
}

func parseVersion(b []byte) (map[string]string, [4]uint16, error) {
	var fv [4]uint16
	le := binary.LittleEndian
	type blk struct {
		key      string
		value    []byte
		children []byte
	}
	read := func(b []byte) (blk, int, bool) {
		if len(b) < 6 {
			return blk{}, 0, false
		}
		l, vl, typ := int(le.Uint16(b[0:])), int(le.Uint16(b[2:])), le.Uint16(b[4:])
		if l < 6 || l > len(b) {
			return blk{}, 0, false
		}
		b = b[:l]
		i := 6
		var key []uint16
		for i+1 < len(b) {
			c := le.Uint16(b[i:])
			i += 2
			if c == 0 {
				break
			}
			key = append(key, c)
		}
		i = (i + 3) &^ 3
		vbytes := vl
		if typ == 1 {
			vbytes = vl * 2
		}
		if i+vbytes > len(b) {
			vbytes = len(b) - i
		}
		if vbytes < 0 {
			vbytes = 0
		}
		v := b[i : i+vbytes]
		i += vbytes
		i = (i + 3) &^ 3
		var ch []byte
		if i < len(b) {
			ch = b[i:]
		}
		return blk{string(utf16.Decode(key)), v, ch}, (l + 3) &^ 3, true
	}
	root, _, ok := read(b)
	if !ok || root.key != "VS_VERSION_INFO" || len(root.value) < 52 {
		return nil, fv, errors.New("bad version resource")
	}
	fv = [4]uint16{le.Uint16(root.value[10:]), le.Uint16(root.value[8:]), le.Uint16(root.value[14:]), le.Uint16(root.value[12:])}
	strs := map[string]string{}
	for rest := root.children; len(rest) > 0; {
		c, n, ok := read(rest)
		if !ok {
			break
		}
		if c.key == "StringFileInfo" {
			for tr := c.children; len(tr) > 0; {
				t, n, ok := read(tr)
				if !ok {
					break
				}
				for sr := t.children; len(sr) > 0; {
					s, n, ok := read(sr)
					if !ok {
						break
					}
					var u []uint16
					for i := 0; i+1 < len(s.value); i += 2 {
						if c := le.Uint16(s.value[i:]); c != 0 {
							u = append(u, c)
						}
					}
					strs[s.key] = string(utf16.Decode(u))
					if n >= len(sr) {
						break
					}
					sr = sr[n:]
				}
				if n >= len(tr) {
					break
				}
				tr = tr[n:]
			}
		}
		if n >= len(rest) {
			break
		}
		rest = rest[n:]
	}
	return strs, fv, nil
}
