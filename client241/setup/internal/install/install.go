// Package install puts a release on disk in the layout the one-file GadScape.exe uses (%LOCALAPPDATA%\GadScape:
// java\, app\, home\, the server address file) and keeps it up to date: it works out what changed since the
// release installed last (Plan), downloads only that, unpacks each part into a staging folder and swaps it in
// with a rename (Apply), and seeds the game cache only when the player's is missing or older.
package install

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"gadscapeclient/kit"
	"gadscapesetup/internal/cachever"
	"gadscapesetup/internal/fetch"
	"gadscapesetup/internal/manifest"
	"gadscapesetup/internal/robust"
)

const (
	StateFile   = "setup-state.json" // in Dest: what the setup installed
	MarkerFile  = "installed.txt"    // in Dest: shared with the one-file exe (it writes its build id there)
	CacheMarker = "gadscape-cache-version.txt"
	updateDir   = ".update"   // staging: <id>.new, <id>.old
	downloadDir = "downloads" // finished and partial downloads (.part)
)

// State is setup-state.json.
type State struct {
	Release int64             `json:"release"`
	Label   string            `json:"label,omitempty"`
	Files   map[string]string `json:"files"`            // file id -> sha256 of the zip that is installed
	Marker  string            `json:"marker,omitempty"` // what installed.txt held after the setup installed
	// ServerSHA is the sha256 of the server address file as the setup last wrote it: a file that still has it was
	// not edited by the player and may follow the release's server address.
	ServerSHA    string `json:"server_sha,omitempty"`
	SetupVersion int    `json:"setup_version,omitempty"` // version of the launcher exe in Dest
	Shortcuts    bool   `json:"shortcuts,omitempty"`     // shortcuts were made once (a deleted one is not made again)
}

// LoadState reads Dest\setup-state.json; a missing file is an empty state.
func LoadState(dest string) (State, error) {
	st := State{Files: map[string]string{}}
	b, err := os.ReadFile(filepath.Join(dest, StateFile))
	if errors.Is(err, os.ErrNotExist) {
		return st, nil
	}
	if err != nil {
		return st, err
	}
	if err := json.Unmarshal(b, &st); err != nil {
		return State{Files: map[string]string{}}, fmt.Errorf("%s is damaged: %v", StateFile, err)
	}
	if st.Files == nil {
		st.Files = map[string]string{}
	}
	return st, nil
}

// SaveState writes the state through a temporary file, so a crash never leaves half a file.
func SaveState(dest string, st State) error {
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	return WriteFileAtomic(filepath.Join(dest, StateFile), b)
}

// WriteFileAtomic writes path through path.tmp and a rename.
func WriteFileAtomic(path string, b []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	if err := robust.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

// Step is one release file to download and install.
type Step struct {
	File   manifest.File
	Reason string
}

// Plan is what an update has to do.
type Plan struct {
	Steps       []Step
	CacheReason string // why the cache is (or is not) seeded
	CacheSame   bool   // the player's cache is the release's: only the marker needs writing
}

// Empty is true when nothing has to be downloaded.
func (p Plan) Empty() bool { return len(p.Steps) == 0 }

// Has tells whether the plan downloads file id.
func (p Plan) Has(id string) bool {
	for _, s := range p.Steps {
		if s.File.ID == id {
			return true
		}
	}
	return false
}

func exists(p string) bool { _, err := os.Stat(p); return err == nil }

// componentOK checks that an installed part looks complete.
func componentOK(c kit.Client, id string) bool {
	switch id {
	case manifest.Java:
		return exists(c.Javaw())
	case manifest.Client:
		return exists(filepath.Join(c.App(), "jvm-args.txt")) && exists(filepath.Join(c.App(), "lib"))
	}
	return true
}

// Installed is true when Java and the client are in place as the setup left them (the client can be started
// without downloading anything).
func Installed(c kit.Client, st State) bool {
	if st.Release == 0 || !componentOK(c, manifest.Java) || !componentOK(c, manifest.Client) {
		return false
	}
	b, _ := os.ReadFile(filepath.Join(c.Dest, MarkerFile))
	return st.Marker != "" && strings.TrimSpace(string(b)) == st.Marker
}

// MakePlan compares the manifest with what is installed. Java and the client are downloaded when their sha256
// differs from the installed one, when their folder is incomplete, or when installed.txt shows that the one-file
// GadScape.exe has put its own copy there since. The cache follows DecideCache.
func MakePlan(m *manifest.Manifest, st State, c kit.Client) Plan {
	var p Plan
	b, _ := os.ReadFile(filepath.Join(c.Dest, MarkerFile))
	markerOK := st.Marker != "" && strings.TrimSpace(string(b)) == st.Marker
	for _, id := range []string{manifest.Java, manifest.Client} {
		f := m.Find(id)
		reason := ""
		switch {
		case st.Files[id] == "":
			reason = "not installed yet"
		case st.Files[id] != f.SHA256:
			reason = "new version"
		case !componentOK(c, id):
			reason = "files missing"
		case !markerOK:
			reason = "replaced by another " + c.AppName + " program"
		}
		if reason != "" {
			p.Steps = append(p.Steps, Step{File: *f, Reason: reason})
		}
	}
	seed, same, why := DecideCache(c.CacheDir(), m.Cache)
	p.CacheReason, p.CacheSame = why, same
	if seed {
		p.Steps = append(p.Steps, Step{File: *m.Find(manifest.Cache), Reason: why})
	}
	return p
}

// ReadCacheMarker returns the release cache version the setup last seeded into dir (ok false: none).
func ReadCacheMarker(dir string) (int64, bool) {
	b, err := os.ReadFile(filepath.Join(dir, CacheMarker))
	if err != nil {
		return 0, false
	}
	for _, line := range strings.Split(string(b), "\n") {
		if v, ok := strings.CutPrefix(strings.TrimSpace(line), "version="); ok {
			if n, err := strconv.ParseInt(v, 10, 64); err == nil {
				return n, true
			}
		}
	}
	return 0, false
}

// WriteCacheMarker records the release cache version a cache folder is at.
func WriteCacheMarker(dir string, version int64) error {
	return WriteFileAtomic(filepath.Join(dir, CacheMarker), []byte(fmt.Sprintf(
		"# written by the GadScape setup: the release cache version this game cache was seeded from or matches\nversion=%d\n", version)))
}

// DecideCache decides whether the release's cache goes into the game's cache folder dir:
//
//   - no cache there (no main_file_cache.dat2 / .idx255): yes
//   - the cache there cannot be read: yes (the game would have to download it all anyway)
//   - the release lists its reference tables: compare them. Older: yes. Same: no (same is true). Newer: never.
//     Different but not ordered: only when the setup itself seeded it from an older release cache version.
//   - no tables in the release: only when the setup seeded it from an older release cache version.
//
// A cache the setup knows nothing about is only replaced when it is provably older, so a player's cache that the
// game brought up to date from the server is never thrown away for an older one.
func DecideCache(dir string, rel manifest.CacheInfo) (seed, same bool, why string) {
	if !exists(filepath.Join(dir, "main_file_cache.dat2")) || !exists(filepath.Join(dir, "main_file_cache.idx255")) {
		return true, false, "no game cache yet"
	}
	marker, hasMarker := ReadCacheMarker(dir)
	markerOlder := hasMarker && marker < rel.Version
	local, err := cachever.Read(dir)
	if err != nil {
		return true, false, fmt.Sprintf("the game cache cannot be read (%v)", err)
	}
	if len(rel.Indexes) == 0 {
		if markerOlder {
			return true, false, fmt.Sprintf("game cache was seeded from cache version %d, the release has %d", marker, rel.Version)
		}
		return false, false, "game cache kept (release lists no cache tables)"
	}
	switch r := cachever.Compare(local, rel.Indexes); r {
	case cachever.Same:
		return false, true, "game cache is the release's"
	case cachever.Newer:
		return false, false, "game cache is newer than the release's: kept"
	case cachever.Older:
		return true, false, "game cache is older than the release's"
	default:
		if markerOlder {
			return true, false, fmt.Sprintf("game cache differs and was seeded from cache version %d, the release has %d", marker, rel.Version)
		}
		return false, false, "game cache differs from the release's but is not older: kept"
	}
}

// Installer applies a plan.
type Installer struct {
	Client         kit.Client
	DL             *fetch.Downloader
	ManifestURL    *url.URL
	AllowLocalHTTP bool
	Log            func(format string, a ...any)
	// Status shows progress (may be nil).
	Status func(text string, pct int)
	// Cancelable turns the Cancel button on and off (may be nil): off while folders are swapped.
	Cancelable func(bool)
	// FreeSpace returns the free bytes on the drive of a path (may be nil: not checked).
	FreeSpace func(path string) (int64, error)
}

func (in *Installer) logf(format string, a ...any) {
	if in.Log != nil {
		in.Log(format, a...)
	}
}

func (in *Installer) status(text string, pct int) {
	if in.Status != nil {
		in.Status(text, pct)
	}
}

func (in *Installer) cancelable(on bool) {
	if in.Cancelable != nil {
		in.Cancelable(on)
	}
}

// Names shown to the player.
func Title(id string) string {
	switch id {
	case manifest.Java:
		return "Java"
	case manifest.Client:
		return "the client"
	case manifest.Cache:
		return "the game cache"
	}
	return id
}

// DownloadPath is where a release file is downloaded to.
func (in *Installer) DownloadPath(f manifest.File) string {
	return filepath.Join(in.Client.Dest, downloadDir, f.ID+"-"+f.SHA256[:16]+".zip")
}

// Recover undoes a swap a crash or power cut interrupted (a folder moved to .update\<x>.old with no new one in
// place) and clears the staging folder. It needs no connection, so the setup calls it before it looks at what is
// installed; calling it again does nothing.
func Recover(c kit.Client, log func(string, ...any)) {
	up := filepath.Join(c.Dest, updateDir)
	if !exists(up) {
		return
	}
	for _, d := range []struct{ old, target string }{{"java.old", c.Java()}, {"app.old", c.App()}} {
		old := filepath.Join(up, d.old)
		if exists(old) && !exists(d.target) {
			if err := robust.Rename(old, d.target); err == nil && log != nil {
				log("recovered %s from an interrupted update", d.target)
			}
		}
	}
	oldCache := filepath.Join(up, "cache.old")
	if exists(oldCache) && !exists(filepath.Join(c.CacheDir(), "main_file_cache.dat2")) {
		if names, _ := cachever.Files(oldCache); len(names) > 0 {
			os.MkdirAll(c.CacheDir(), 0o755)
			for _, n := range names {
				robust.Rename(filepath.Join(oldCache, n), filepath.Join(c.CacheDir(), n))
			}
			if log != nil {
				log("recovered the game cache from an interrupted update")
			}
		}
	}
	robust.RemoveAll(up)
}

// Apply downloads every file of the plan (all of them first, so a failed download changes nothing), then unpacks
// and swaps them in one by one, saving the state after each. st is updated in place.
func (in *Installer) Apply(ctx context.Context, m *manifest.Manifest, p Plan, st *State) error {
	c := in.Client
	if err := os.MkdirAll(filepath.Join(c.Dest, downloadDir), 0o755); err != nil {
		return err
	}
	Recover(c, in.Log)
	var total, unpacked int64
	for _, s := range p.Steps {
		total += s.File.Size
		unpacked += s.File.Unpacked
	}
	if in.FreeSpace != nil {
		need := unpacked + 200<<20
		for _, s := range p.Steps {
			if !fetch.FileMatches(in.DownloadPath(s.File), s.File.Size, s.File.SHA256) {
				need += s.File.Size
			}
		}
		if free, err := in.FreeSpace(c.Dest); err == nil && free < need {
			return fmt.Errorf("not enough free disk space: %d MB needed on the drive of %s, %d MB free", need>>20, c.Dest, free>>20)
		}
	}
	// 1. downloads
	var done int64
	for _, s := range p.Steps {
		u, err := manifest.Resolve(in.ManifestURL, s.File.URL, in.AllowLocalHTTP)
		if err != nil {
			return err
		}
		in.logf("download %s (%s): %s, %d bytes", s.File.ID, s.Reason, u.Redacted(), s.File.Size)
		base := done
		title := "Downloading " + Title(s.File.ID)
		err = in.DL.Download(ctx, u.String(), in.DownloadPath(s.File), s.File.Size, s.File.SHA256, func(n int64) {
			const mb = 1 << 20
			in.status(fmt.Sprintf("%s %d of %d MB", title, n/mb, (s.File.Size+mb-1)/mb), pct(base+n, total))
		})
		if err != nil {
			return fmt.Errorf("downloading %s: %w", Title(s.File.ID), err)
		}
		done += s.File.Size
	}
	// 2. unpack and swap in
	up := filepath.Join(c.Dest, updateDir)
	for _, s := range p.Steps {
		if err := in.installOne(ctx, m, s, up, st); err != nil {
			return err
		}
		os.Remove(in.DownloadPath(s.File))
	}
	if p.CacheSame && !p.Has(manifest.Cache) {
		WriteCacheMarker(c.CacheDir(), m.Cache.Version)
	}
	robust.RemoveAll(up)
	return nil
}

func pct(done, total int64) int {
	if total <= 0 {
		return 0
	}
	return int(done * 100 / total)
}

func (in *Installer) installOne(ctx context.Context, m *manifest.Manifest, s Step, up string, st *State) error {
	c := in.Client
	f := s.File
	zipPath := in.DownloadPath(f)
	newDir := filepath.Join(up, f.ID+".new")
	robust.RemoveAll(newDir)
	limit := f.Unpacked
	if limit <= 0 {
		limit = kit.DefaultMaxBytes
	}
	opt := kit.ExtractOptions{
		StripTop: f.ID != manifest.Cache,
		MaxBytes: limit,
		Progress: func(n int64) {
			in.status("Installing "+Title(f.ID)+"...", pct(n, limit))
		},
	}
	in.status("Installing "+Title(f.ID)+"...", 0)
	if err := kit.ExtractZipFile(ctx, zipPath, newDir, opt); err != nil {
		os.RemoveAll(newDir)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return fmt.Errorf("unpacking %s: %w", Title(f.ID), err)
	}
	in.cancelable(false)
	defer in.cancelable(true)
	switch f.ID {
	case manifest.Java:
		if !exists(filepath.Join(newDir, "bin", "javaw.exe")) {
			return errors.New("the Java download has no bin\\javaw.exe")
		}
		if err := SwapDir(c.Java(), newDir, filepath.Join(up, "java.old")); err != nil {
			return err
		}
	case manifest.Client:
		if !exists(filepath.Join(newDir, "jvm-args.txt")) || !exists(filepath.Join(newDir, "lib")) {
			return errors.New("the client download has no jvm-args.txt or lib folder")
		}
		if err := SwapDir(c.App(), newDir, filepath.Join(up, "app.old")); err != nil {
			return err
		}
	case manifest.Cache:
		if err := SeedCache(c.CacheDir(), newDir, filepath.Join(up, "cache.old")); err != nil {
			return err
		}
		if err := WriteCacheMarker(c.CacheDir(), m.Cache.Version); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unknown release file %q", f.ID)
	}
	in.logf("installed %s (%s)", f.ID, f.SHA256)
	st.Files[f.ID] = f.SHA256
	if f.ID == manifest.Java || f.ID == manifest.Client {
		// the one-file exe reinstalls when installed.txt is not its own build id; this id tells the setup that
		// the folders are still the ones it put there
		marker := fmt.Sprintf("setup-%d-%s-%s", m.Version, short(st.Files[manifest.Java]), short(st.Files[manifest.Client]))
		if err := os.WriteFile(filepath.Join(c.Dest, MarkerFile), []byte(marker), 0o644); err != nil {
			return err
		}
		st.Marker = marker
	}
	return SaveState(c.Dest, *st)
}

func short(s string) string {
	if len(s) > 12 {
		return s[:12]
	}
	return s
}

// ErrInUse: a folder could not be moved because a program has files in it open.
var ErrInUse = errors.New("files are in use")

// SwapDir replaces folder target with newDir: target is moved to oldDir, newDir to target, and oldDir deleted.
// If the second move fails the old folder is put back, so target is always either the old or the new folder.
// Every rename is retried for a few seconds while Windows reports the folder busy (robust).
func SwapDir(target, newDir, oldDir string) error {
	robust.RemoveAll(oldDir)
	hadOld := exists(target)
	if hadOld {
		if err := robust.Rename(target, oldDir); err != nil {
			return fmt.Errorf("%w: couldn't move %s out of the way (is %s still open?): %v", ErrInUse, target, filepath.Base(target), err)
		}
	}
	if err := robust.Rename(newDir, target); err != nil {
		if hadOld {
			robust.Rename(oldDir, target)
		}
		return fmt.Errorf("couldn't move the new %s into place: %v", filepath.Base(target), err)
	}
	robust.RemoveAll(oldDir)
	return nil
}

// SeedCache replaces the game cache files (main_file_cache.*) in live with the ones in newDir. The old cache
// files are moved aside first and put back if anything fails; every other file in live (the game's
// preferences2.dat and anything else) is left alone.
func SeedCache(live, newDir, oldDir string) error {
	names, err := cachever.Files(newDir)
	if err != nil {
		return err
	}
	if !contains(names, "main_file_cache.dat2") || !contains(names, "main_file_cache.idx255") {
		return errors.New("the game cache download has no main_file_cache.dat2 or main_file_cache.idx255")
	}
	if all, _ := os.ReadDir(newDir); len(all) != len(names) {
		return errors.New("the game cache download has files that are not cache files")
	}
	if err := os.MkdirAll(live, 0o755); err != nil {
		return err
	}
	robust.RemoveAll(oldDir)
	if err := os.MkdirAll(oldDir, 0o755); err != nil {
		return err
	}
	oldNames, err := cachever.Files(live)
	if err != nil {
		return err
	}
	var movedOld, movedNew []string
	undo := func() {
		for _, n := range movedNew {
			robust.Remove(filepath.Join(live, n))
		}
		for _, n := range movedOld {
			robust.Rename(filepath.Join(oldDir, n), filepath.Join(live, n))
		}
	}
	for _, n := range oldNames {
		if err := robust.Rename(filepath.Join(live, n), filepath.Join(oldDir, n)); err != nil {
			undo()
			return fmt.Errorf("%w: couldn't move the old game cache aside (is the client still open?): %v", ErrInUse, err)
		}
		movedOld = append(movedOld, n)
	}
	for _, n := range names {
		if err := robust.Rename(filepath.Join(newDir, n), filepath.Join(live, n)); err != nil {
			undo()
			return fmt.Errorf("couldn't put the new game cache in place: %v", err)
		}
		movedNew = append(movedNew, n)
	}
	robust.RemoveAll(oldDir)
	return nil
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// EnsureServerFile writes the server address file in Dest when it is missing, or when it still holds exactly what
// the setup wrote last and the release now names another server. A file the player edited is never changed.
func EnsureServerFile(c kit.Client, name, host string, st *State) (string, error) {
	p := filepath.Join(c.Dest, name)
	want := kit.ServerFileText(c.AppName, host)
	cur, err := os.ReadFile(p)
	if err == nil {
		if string(cur) == want {
			st.ServerSHA = hashText(want)
			return p, nil
		}
		if st.ServerSHA == "" || hashText(string(cur)) != st.ServerSHA {
			return p, nil // the player's own address
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return p, err
	}
	if err := WriteFileAtomic(p, []byte(want)); err != nil {
		return p, err
	}
	st.ServerSHA = hashText(want)
	return p, nil
}

func hashText(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

// CleanDownloads deletes finished downloads and parts that the manifest no longer lists.
func CleanDownloads(c kit.Client, m *manifest.Manifest, keepParts bool) {
	dir := filepath.Join(c.Dest, downloadDir)
	ents, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	keep := map[string]bool{}
	if keepParts {
		for _, f := range m.Files {
			keep[f.ID+"-"+f.SHA256[:16]+".zip.part"] = true
		}
	}
	for _, e := range ents {
		if !keep[e.Name()] {
			os.RemoveAll(filepath.Join(dir, e.Name()))
		}
	}
}
