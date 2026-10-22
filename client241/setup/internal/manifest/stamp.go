package manifest

import (
	"bytes"
	"errors"
	"fmt"
	"regexp"
	"strconv"
)

// Stamp is the line build.sh puts into every setup exe (main.stamp, -ldflags -X), so the release tool can read
// a setup's version, channel and public key from the exe file without running it, and a setup can check a new
// setup exe before it replaces itself with it:
//
//	GSSTAMP1 version=<n> channel=<channel> app=<folder> key=<base64 public key or "none"> GSEND
type Stamp struct {
	Version int
	Channel string
	App     string
	Key     string // base64 public key; "" = none (the setup refuses to install)
}

func (s Stamp) String() string {
	k := s.Key
	if k == "" {
		k = "none"
	}
	return fmt.Sprintf("GSSTAMP1 version=%d channel=%s app=%s key=%s GSEND", s.Version, s.Channel, s.App, k)
}

var stampRe = regexp.MustCompile(`^GSSTAMP1 version=([0-9]{1,9}) channel=([a-z0-9-]{1,32}) app=([A-Za-z0-9._-]{1,64}) key=([A-Za-z0-9+/=]{1,64}) GSEND`)

// ParseStamp reads one stamp line.
func ParseStamp(s string) (Stamp, error) {
	m := stampRe.FindStringSubmatch(s)
	if m == nil {
		return Stamp{}, fmt.Errorf("not a setup stamp: %q", s)
	}
	v, _ := strconv.Atoi(m[1])
	st := Stamp{Version: v, Channel: m[2], App: m[3], Key: m[4]}
	if st.Key == "none" {
		st.Key = ""
	}
	return st, nil
}

// FindStamp finds the one stamp in an exe file's bytes.
func FindStamp(exe []byte) (Stamp, error) {
	prefix := []byte("GSSTAMP1 version=")
	var found []Stamp
	for off := 0; ; {
		i := bytes.Index(exe[off:], prefix)
		if i < 0 {
			break
		}
		start := off + i
		end := start + 256
		if end > len(exe) {
			end = len(exe)
		}
		if st, err := ParseStamp(string(exe[start:end])); err == nil {
			found = append(found, st)
		}
		off = start + len(prefix)
	}
	switch len(found) {
	case 0:
		return Stamp{}, errors.New("no GadScape setup stamp in the file (not a GadScape setup exe, or built without client241/build.sh --setup)")
	case 1:
		return found[0], nil
	}
	return Stamp{}, fmt.Errorf("%d setup stamps in the file", len(found))
}
