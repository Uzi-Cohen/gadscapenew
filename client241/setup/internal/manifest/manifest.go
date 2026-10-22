// Package manifest is the signed release description the setup downloads: manifest.json (what to install) and
// manifest.sig (ed25519 signatures of manifest.json's exact bytes, base64, one per line: the release's own and the
// previous release's, so a manifest.sig uploaded before its manifest.json never makes the old manifest fail). The
// release tool (setup/tools) writes both; the setup checks the signature with the public key built into it before
// it reads anything else.
package manifest

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

// Format is the newest manifest format this code understands. A manifest with a higher format is refused (the
// setup then only looks at "setup" to update itself, see TooNew).
const Format = 1

// File ids.
const (
	Java   = "java"   // Windows x64 Java runtime zip (one top folder) -> <dest>\java
	Client = "client" // the client's portable folder zip (one top folder) -> <dest>\app
	Cache  = "cache"  // the game cache zip (main_file_cache.* at the top) -> home\.runelite\jagexcache\oldschool\LIVE
)

// File is one download.
type File struct {
	ID     string `json:"id"`
	URL    string `json:"url"`    // relative to manifest.json's address, or absolute https
	Size   int64  `json:"size"`   // bytes
	SHA256 string `json:"sha256"` // lower-case hex
	// Unpacked is what the zip's files add up to; the setup refuses a zip that unpacks to more.
	Unpacked int64 `json:"unpacked,omitempty"`
	Entries  int   `json:"entries,omitempty"`
}

// Setup is the newest setup exe; installed setups older than Version replace themselves with it.
type Setup struct {
	Version int `json:"version"`
	File
}

// Index is one cache index's reference table (archive 255 group <ID>): its revision and the CRC-32 of its
// container, as the game client checks them against the server.
type Index struct {
	ID       int    `json:"id"`
	Revision int64  `json:"revision"` // -1: unknown
	CRC      uint32 `json:"crc"`
}

// CacheInfo describes the game cache in the release.
type CacheInfo struct {
	// Version goes up when the release carries a newer cache. A player's cache seeded from an older version is
	// replaced (unless its own contents are newer); one at this version or newer is kept.
	Version int64   `json:"version"`
	Indexes []Index `json:"indexes,omitempty"`
}

// Manifest is manifest.json.
type Manifest struct {
	Format  int    `json:"format"`
	App     string `json:"app"`     // the channel's %LOCALAPPDATA% folder name: GadScape, GadScape241Test
	Channel string `json:"channel"` // release, test
	// Version is the release number. It must go up with every release: a setup never installs a manifest
	// older than the one it installed last (so an old signed manifest cannot be used to downgrade players).
	Version int64     `json:"version"`
	Label   string    `json:"label,omitempty"`
	Created string    `json:"created,omitempty"`
	Server  string    `json:"server,omitempty"` // the server address new installs start with
	Cache   CacheInfo `json:"cache"`
	Setup   *Setup    `json:"setup,omitempty"`
	Files   []File    `json:"files"`
}

var (
	ErrSignature = errors.New("the release's signature is not valid")
	ErrNoKey     = errors.New("no release key is built into this setup")
)

// TooNewError: the manifest has a format this code does not understand.
type TooNewError struct{ Format int }

func (e *TooNewError) Error() string {
	return fmt.Sprintf("the release uses manifest format %d; this setup knows format %d and needs updating", e.Format, Format)
}

// RollbackError: the server offers an older release than the one installed (an old signed manifest, or a cache
// on the way that has not seen the new release yet).
type RollbackError struct{ Offered, Installed int64 }

func (e *RollbackError) Error() string {
	return fmt.Sprintf("the download server offers release %d, older than the installed release %d; not installing it", e.Offered, e.Installed)
}

// BrokenError: the manifest is signed but cannot be used (it does not parse, or is for another app or channel).
type BrokenError struct{ Err error }

func (e *BrokenError) Error() string {
	return "the release on the download server cannot be used: " + e.Err.Error()
}
func (e *BrokenError) Unwrap() error { return e.Err }

// ParsePublicKey reads a base64 ed25519 public key (32 bytes), as written by the release tool's keygen.
func ParsePublicKey(s string) (ed25519.PublicKey, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil, ErrNoKey
	}
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil || len(b) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("bad public key %q (want base64 of %d bytes)", s, ed25519.PublicKeySize)
	}
	return ed25519.PublicKey(b), nil
}

// MaxSignatures is how many signature lines a signature file may have.
const MaxSignatures = 4

// Sign returns the signature file text for manifest bytes. keep are signature lines to add after it (the previous
// release's, see PreviousSignature).
func Sign(priv ed25519.PrivateKey, manifest []byte, keep ...string) []byte {
	lines := []string{base64.StdEncoding.EncodeToString(ed25519.Sign(priv, manifest))}
	seen := map[string]bool{lines[0]: true}
	for _, k := range keep {
		if k = strings.TrimSpace(k); k != "" && !seen[k] && len(lines) < MaxSignatures {
			seen[k] = true
			lines = append(lines, k)
		}
	}
	return []byte(strings.Join(lines, "\n") + "\n")
}

// PreviousSignature is the line of a signature file that signs manifest ("" if none does).
func PreviousSignature(pub ed25519.PublicKey, manifest, sig []byte) string {
	for _, line := range strings.Fields(string(sig)) {
		if raw, err := base64.StdEncoding.DecodeString(line); err == nil && len(raw) == ed25519.SignatureSize &&
			len(pub) == ed25519.PublicKeySize && ed25519.Verify(pub, manifest, raw) {
			return line
		}
	}
	return ""
}

// Verify checks the signature file text sig over the exact manifest bytes: one of its lines must be a valid
// signature of them.
func Verify(pub ed25519.PublicKey, manifest, sig []byte) error {
	if len(pub) != ed25519.PublicKeySize {
		return ErrNoKey
	}
	lines := strings.Fields(string(sig))
	if len(lines) == 0 || len(lines) > MaxSignatures {
		return fmt.Errorf("%w (the signature file has %d lines)", ErrSignature, len(lines))
	}
	sigs := make([][]byte, len(lines))
	for i, line := range lines {
		raw, err := base64.StdEncoding.DecodeString(line)
		if err != nil || len(raw) != ed25519.SignatureSize {
			return fmt.Errorf("%w (signature file is not base64 ed25519 signatures)", ErrSignature)
		}
		sigs[i] = raw
	}
	for _, raw := range sigs {
		if ed25519.Verify(pub, manifest, raw) {
			return nil
		}
	}
	return ErrSignature
}

var hexRe = regexp.MustCompile(`^[0-9a-f]{64}$`)

// Parse reads manifest bytes (whose signature has been checked) and checks that they make sense. Unknown fields
// are ignored (newer releases may add some); a newer format is a *TooNewError, with the manifest still returned
// so its setup entry can be used.
func Parse(data []byte) (*Manifest, error) {
	var m Manifest
	dec := json.NewDecoder(bytes.NewReader(data))
	if err := dec.Decode(&m); err != nil {
		return nil, fmt.Errorf("manifest is not valid JSON: %v", err)
	}
	if m.Format < 1 {
		return nil, fmt.Errorf("manifest has no format number")
	}
	// the setup entry first: a manifest of a newer format is still used for it (TooNewError)
	if m.Setup != nil {
		if err := m.Setup.File.check(); err != nil {
			return nil, err
		}
		if m.Setup.Version <= 0 {
			return nil, errors.New("manifest's setup has no version")
		}
	}
	if m.Format > Format {
		return &m, &TooNewError{m.Format}
	}
	if m.App == "" || m.Channel == "" {
		return nil, errors.New("manifest has no app or channel")
	}
	if m.Version <= 0 {
		return nil, errors.New("manifest has no release version")
	}
	seen := map[string]bool{}
	for _, f := range m.Files {
		if err := f.check(); err != nil {
			return nil, err
		}
		if seen[f.ID] {
			return nil, fmt.Errorf("manifest lists %q twice", f.ID)
		}
		seen[f.ID] = true
	}
	for _, id := range []string{Java, Client, Cache} {
		if !seen[id] {
			return nil, fmt.Errorf("manifest has no %q file", id)
		}
	}
	if m.Server != "" && !validHost(m.Server) {
		return nil, fmt.Errorf("manifest's server %q is not a host name or IP", m.Server)
	}
	return &m, nil
}

func (f File) check() error {
	if f.ID == "" || f.URL == "" {
		return errors.New("manifest has a file without id or url")
	}
	if f.Size <= 0 {
		return fmt.Errorf("manifest file %q has no size", f.ID)
	}
	if !hexRe.MatchString(f.SHA256) {
		return fmt.Errorf("manifest file %q has a bad sha256 %q", f.ID, f.SHA256)
	}
	if f.Unpacked < 0 || f.Entries < 0 {
		return fmt.Errorf("manifest file %q has a negative size", f.ID)
	}
	return nil
}

var hostRe = regexp.MustCompile(`^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$`)

func validHost(h string) bool { return hostRe.MatchString(h) }

// Find returns the file with id, or nil.
func (m *Manifest) Find(id string) *File {
	for i := range m.Files {
		if m.Files[i].ID == id {
			return &m.Files[i]
		}
	}
	return nil
}

// CheckFor checks that the manifest is for this setup (app folder and channel) and is not older than the release
// installed (installed 0: nothing installed yet).
func (m *Manifest) CheckFor(app, channel string, installed int64) error {
	if m.App != app || m.Channel != channel {
		return &BrokenError{fmt.Errorf("the release is for %s (%s), this setup installs %s (%s)", m.App, m.Channel, app, channel)}
	}
	if m.Version < installed {
		return &RollbackError{Offered: m.Version, Installed: installed}
	}
	return nil
}

// CheckURL allows https addresses, and plain http only to 127.0.0.1 when allowLocalHTTP (test builds).
func CheckURL(u *url.URL, allowLocalHTTP bool) error {
	switch u.Scheme {
	case "https":
		if u.Host == "" {
			return fmt.Errorf("address %s has no host", u)
		}
		return nil
	case "http":
		if allowLocalHTTP {
			if ip := net.ParseIP(u.Hostname()); ip != nil && ip.Equal(net.IPv4(127, 0, 0, 1)) {
				return nil
			}
		}
		return fmt.Errorf("address %s is not https (only https is allowed)", u.Redacted())
	}
	return fmt.Errorf("address %s is not https", u.Redacted())
}

// CheckRedirect is an http.Client CheckRedirect that holds redirects to the same rule as the address itself
// (CheckURL): a server or CDN cannot send the setup to plain http.
func CheckRedirect(allowLocalHTTP bool) func(req *http.Request, via []*http.Request) error {
	return func(req *http.Request, via []*http.Request) error {
		if len(via) >= 10 {
			return errors.New("too many redirects")
		}
		if err := CheckURL(req.URL, allowLocalHTTP); err != nil {
			return fmt.Errorf("redirected to a forbidden address: %w", err)
		}
		return nil
	}
}

// Resolve turns a file's url (relative to the manifest's address, or absolute) into an address it may download.
func Resolve(manifestURL *url.URL, ref string, allowLocalHTTP bool) (*url.URL, error) {
	r, err := url.Parse(ref)
	if err != nil {
		return nil, fmt.Errorf("bad file address %q: %v", ref, err)
	}
	u := manifestURL.ResolveReference(r)
	if err := CheckURL(u, allowLocalHTTP); err != nil {
		return nil, err
	}
	return u, nil
}

// SigURL is the signature's address: manifest.sig next to manifest.json (".sig" replaces ".json", or is added).
func SigURL(manifestURL *url.URL) *url.URL {
	u := *manifestURL
	if strings.HasSuffix(u.Path, ".json") {
		u.Path = strings.TrimSuffix(u.Path, ".json") + ".sig"
	} else {
		u.Path += ".sig"
	}
	u.RawPath = ""
	return &u
}

// SHA256Hex is the lower-case hex sha256 of b.
func SHA256Hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
