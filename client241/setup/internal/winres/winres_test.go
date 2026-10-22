package winres

import (
	"bytes"
	"debug/pe"
	"encoding/binary"
	"os"
	"testing"
)

func TestBuildAndRead(t *testing.T) {
	ico, err := os.ReadFile("../../../exe/icon.ico")
	if err != nil {
		t.Fatal(err)
	}
	vi := VersionInfo{FileVersion: [4]uint16{1, 2, 3, 4}, ProductVersion: [4]uint16{1, 2, 0, 0},
		Strings: map[string]string{"CompanyName": "GadScape", "FileDescription": "GadScape Setup", "ProductName": "GadScape", "FileVersion": "1.2.3.4"}}
	syso, err := Build(ico, []byte("<assembly/>"), vi)
	if err != nil {
		t.Fatal(err)
	}
	f, err := pe.NewFile(bytes.NewReader(syso))
	if err != nil {
		t.Fatal(err)
	}
	sec := f.Section(".rsrc")
	if sec == nil || f.FileHeader.Machine != pe.IMAGE_FILE_MACHINE_AMD64 || len(f.Symbols) != 1 {
		t.Fatalf("bad coff: %+v", f.FileHeader)
	}
	if int(sec.NumberOfRelocations) != len(sec.Relocs) || len(sec.Relocs) < 4 {
		t.Errorf("relocations %d", len(sec.Relocs))
	}
	strs, fv, err := ReadVersion(syso)
	if err != nil {
		t.Fatal(err)
	}
	if fv != vi.FileVersion {
		t.Errorf("file version %v", fv)
	}
	for k, v := range vi.Strings {
		if strs[k] != v {
			t.Errorf("%s = %q, want %q", k, strs[k], v)
		}
	}
	// the icon group points at the icon resources
	icons, group, err := splitIco(ico, 2)
	if err != nil || len(icons) != int(binary.LittleEndian.Uint16(ico[4:])) || len(group) != 6+14*len(icons) {
		t.Errorf("ico: %d images, group %d bytes, %v", len(icons), len(group), err)
	}
	if _, _, err := splitIco([]byte("not an icon"), 1); err == nil {
		t.Error("bad ico accepted")
	}
}
