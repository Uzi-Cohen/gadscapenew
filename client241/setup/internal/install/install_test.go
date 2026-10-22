package install

import (
	"archive/zip"
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"gadscapeclient/kit"
	"gadscapesetup/internal/cachever"
	"gadscapesetup/internal/cachever/cachetest"
	"gadscapesetup/internal/fetch"
	"gadscapesetup/internal/manifest"
	"gadscapesetup/internal/pack"
)

// release is a fake release served over HTTP.
type release struct {
	t     *testing.T
	dir   string // served folder
	srv   *httptest.Server
	mu    sync.Mutex
	log   []string // paths requested
	m     *manifest.Manifest
	cache string // the cache folder the cache zip was made from
}

func newRelease(t *testing.T) *release {
	r := &release{t: t, dir: t.TempDir()}
	fs := http.FileServer(http.Dir(r.dir))
	r.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		r.mu.Lock()
		r.log = append(r.log, q.URL.Path)
		r.mu.Unlock()
		fs.ServeHTTP(w, q)
	}))
	t.Cleanup(r.srv.Close)
	r.m = &manifest.Manifest{Format: 1, App: "GadScape", Channel: "release", Version: 1, Cache: manifest.CacheInfo{Version: 1}}
	return r
}

func (r *release) requests() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := append([]string{}, r.log...)
	r.log = nil
	return out
}

func writeZip(t *testing.T, path string, files map[string]string) {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, data := range files {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		w.Write([]byte(data))
	}
	zw.Close()
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
}

// set puts a zip for id into the release (replacing the old one) with its manifest entry
func (r *release) set(id string, files map[string]string) {
	name := id + "-" + time.Now().Format("150405.000000000") + ".zip"
	p := filepath.Join(r.dir, name)
	writeZip(r.t, p, files)
	r.setFile(id, name)
}

func (r *release) setFile(id, name string) {
	p := filepath.Join(r.dir, name)
	sum, size, err := pack.HashFile(p)
	if err != nil {
		r.t.Fatal(err)
	}
	var unpacked int64
	zr, _ := zip.OpenReader(p)
	for _, f := range zr.File {
		unpacked += int64(f.UncompressedSize64)
	}
	zr.Close()
	f := manifest.File{ID: id, URL: name, Size: size, SHA256: sum, Unpacked: unpacked}
	for i := range r.m.Files {
		if r.m.Files[i].ID == id {
			r.m.Files[i] = f
			return
		}
	}
	r.m.Files = append(r.m.Files, f)
}

// setCache makes the release's cache from fake reference tables
func (r *release) setCache(revs map[int]int64, salt byte, version int64) {
	dir := filepath.Join(r.t.TempDir(), "cache")
	if err := cachetest.Write(dir, revs, salt); err != nil {
		r.t.Fatal(err)
	}
	names, _ := cachever.Files(dir)
	name := "cache-" + time.Now().Format("150405.000000000") + ".zip"
	if _, err := pack.ZipFiles(filepath.Join(r.dir, name), dir, names); err != nil {
		r.t.Fatal(err)
	}
	ix, err := cachever.Read(dir)
	if err != nil {
		r.t.Fatal(err)
	}
	r.m.Cache = manifest.CacheInfo{Version: version, Indexes: ix}
	r.cache = dir
	r.setFile(manifest.Cache, name)
}

func javaZip(v string) map[string]string {
	return map[string]string{"jdk-21-jre/bin/javaw.exe": "MZ java " + v, "jdk-21-jre/release": `JAVA_VERSION="21"`}
}

func clientZip(v string) map[string]string {
	return map[string]string{"GadScape/jvm-args.txt": "-Xmx768m", "GadScape/lib/gadscape-launcher.jar": "launcher " + v,
		"GadScape/lib-win/x.jar": "win"}
}

func (r *release) installer(dest string) *Installer {
	mu, _ := url.Parse(r.srv.URL + "/manifest.json")
	return &Installer{
		Client:         kit.Client{AppName: "GadScape", Dest: dest},
		DL:             &fetch.Downloader{Backoff: func(int) time.Duration { return 0 }},
		ManifestURL:    mu,
		AllowLocalHTTP: true,
		Log:            r.t.Logf,
	}
}

func run(t *testing.T, r *release, dest string, st *State) Plan {
	t.Helper()
	in := r.installer(dest)
	p := MakePlan(r.m, *st, in.Client)
	if err := in.Apply(context.Background(), r.m, p, st); err != nil {
		t.Fatalf("apply: %v", err)
	}
	st.Release = r.m.Version
	SaveState(dest, *st)
	return p
}

func ids(p Plan) string {
	var s []string
	for _, x := range p.Steps {
		s = append(s, x.File.ID)
	}
	return strings.Join(s, ",")
}

func downloaded(reqs []string) string {
	var s []string
	for _, q := range reqs {
		s = append(s, strings.SplitN(strings.TrimPrefix(q, "/"), "-", 2)[0])
	}
	return strings.Join(s, ",")
}

func sameFiles(t *testing.T, a, b string) {
	t.Helper()
	names, _ := cachever.Files(a)
	if len(names) == 0 {
		t.Fatalf("no cache files in %s", a)
	}
	for _, n := range names {
		x, _ := os.ReadFile(filepath.Join(a, n))
		y, err := os.ReadFile(filepath.Join(b, n))
		if err != nil || !bytes.Equal(x, y) {
			t.Errorf("%s differs (%v)", n, err)
		}
	}
	other, _ := cachever.Files(b)
	if len(other) != len(names) {
		t.Errorf("%s has %d cache files, want %d", b, len(other), len(names))
	}
}

func TestInstallUpdateDiffing(t *testing.T) {
	r := newRelease(t)
	r.set(manifest.Java, javaZip("1"))
	r.set(manifest.Client, clientZip("1"))
	r.setCache(map[int]int64{0: 100, 2: 100}, 0, 1)
	dest := filepath.Join(t.TempDir(), "GadScape")
	c := kit.Client{AppName: "GadScape", Dest: dest}
	st, _ := LoadState(dest)

	// first install: everything
	if p := run(t, r, dest, &st); ids(p) != "java,client,cache" {
		t.Fatalf("first plan %s", ids(p))
	}
	if got := downloaded(r.requests()); got != "java,client,cache" {
		t.Errorf("downloads %s", got)
	}
	if b, _ := os.ReadFile(c.Javaw()); string(b) != "MZ java 1" {
		t.Errorf("javaw %q", b)
	}
	if !exists(filepath.Join(c.App(), "lib", "gadscape-launcher.jar")) {
		t.Error("client not installed")
	}
	sameFiles(t, r.cache, c.CacheDir())
	if v, ok := ReadCacheMarker(c.CacheDir()); !ok || v != 1 {
		t.Errorf("cache marker %d %v", v, ok)
	}
	if !Installed(c, st) {
		t.Error("Installed false after install")
	}
	if exists(filepath.Join(dest, updateDir)) {
		t.Error("staging folder left")
	}

	// second run: nothing to do, nothing downloaded
	st, _ = LoadState(dest)
	if p := run(t, r, dest, &st); !p.Empty() {
		t.Fatalf("second plan %s", ids(p))
	}
	if got := r.requests(); len(got) != 0 {
		t.Errorf("second run downloaded %v", got)
	}

	// a new client: only the client
	r.set(manifest.Client, clientZip("2"))
	r.m.Version = 2
	if p := run(t, r, dest, &st); ids(p) != "client" {
		t.Fatalf("client update plan %s", ids(p))
	}
	if got := downloaded(r.requests()); got != "client" {
		t.Errorf("downloads %s", got)
	}
	if b, _ := os.ReadFile(filepath.Join(c.App(), "lib", "gadscape-launcher.jar")); string(b) != "launcher 2" {
		t.Errorf("client not updated: %q", b)
	}
	if b, _ := os.ReadFile(c.Javaw()); string(b) != "MZ java 1" {
		t.Error("java changed")
	}

	// the player deleted Java: only Java
	os.RemoveAll(c.Java())
	if p := MakePlan(r.m, st, c); ids(p) != "java" || p.Steps[0].Reason != "files missing" {
		t.Errorf("missing java plan %s %+v", ids(p), p.Steps)
	}
	run(t, r, dest, &st)
	r.requests()

	// the one-file GadScape.exe reinstalled its own copy (installed.txt = its build id): Java and client again
	os.WriteFile(filepath.Join(dest, MarkerFile), []byte("241-0123456789abcdef"), 0o644)
	if p := MakePlan(r.m, st, c); ids(p) != "java,client" {
		t.Errorf("after the one-file exe: %s", ids(p))
	}
	if Installed(c, st) {
		t.Error("Installed true with a foreign installed.txt")
	}
}

func TestCacheSeedingRules(t *testing.T) {
	relRevs := map[int]int64{0: 100, 2: 200, 24: 5}
	type tc struct {
		name   string
		local  func(dir string) // makes the player's cache (nil: none)
		marker int64            // 0: no marker
		seed   bool
	}
	cases := []tc{
		{name: "missing", seed: true},
		{name: "empty folder", local: func(d string) { os.MkdirAll(d, 0o755) }, seed: true},
		{name: "older", local: func(d string) { cachetest.Write(d, map[int]int64{0: 90, 2: 200}, 0) }, seed: true},
		{name: "same", local: func(d string) { cachetest.Write(d, relRevs, 0) }, seed: false},
		{name: "newer (game updated it from the server)", local: func(d string) { cachetest.Write(d, map[int]int64{0: 100, 2: 250, 24: 5}, 0) }, seed: false},
		{name: "newer, seeded from an older cache version", local: func(d string) { cachetest.Write(d, map[int]int64{0: 101, 2: 200, 24: 5}, 0) }, marker: 1, seed: false},
		{name: "differs, unknown origin", local: func(d string) { cachetest.Write(d, relRevs, 7) }, seed: false},
		{name: "differs, seeded from an older cache version", local: func(d string) { cachetest.Write(d, relRevs, 7) }, marker: 1, seed: true},
		{name: "differs, seeded from this cache version", local: func(d string) { cachetest.Write(d, relRevs, 7) }, marker: 2, seed: false},
		{name: "unreadable", local: func(d string) {
			cachetest.Write(d, relRevs, 0)
			os.WriteFile(filepath.Join(d, "main_file_cache.idx255"), []byte{1}, 0o644)
		}, seed: true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := newRelease(t)
			r.set(manifest.Java, javaZip("1"))
			r.set(manifest.Client, clientZip("1"))
			r.setCache(relRevs, 0, 2)
			dest := filepath.Join(t.TempDir(), "GadScape")
			cl := kit.Client{AppName: "GadScape", Dest: dest}
			live := cl.CacheDir()
			if c.local != nil {
				c.local(live)
			}
			if c.marker > 0 {
				WriteCacheMarker(live, c.marker)
			}
			// the game's own files next to the cache, and RuneLite's files, must survive
			os.MkdirAll(live, 0o755)
			os.WriteFile(filepath.Join(live, "preferences2.dat"), []byte("player prefs"), 0o644)
			rl := filepath.Join(cl.Home(), ".runelite", "settings.properties")
			os.WriteFile(rl, []byte("runelite"), 0o644)
			before := snapshot(live)

			st, _ := LoadState(dest)
			p := MakePlan(r.m, st, cl)
			if p.Has(manifest.Cache) != c.seed {
				t.Fatalf("seed=%v, want %v (%s)", p.Has(manifest.Cache), c.seed, p.CacheReason)
			}
			in := r.installer(dest)
			if err := in.Apply(context.Background(), r.m, p, &st); err != nil {
				t.Fatal(err)
			}
			if c.seed {
				sameFiles(t, r.cache, live)
				if v, _ := ReadCacheMarker(live); v != 2 {
					t.Errorf("marker %d", v)
				}
			} else {
				after := snapshot(live)
				delete(after, CacheMarker)
				delete(before, CacheMarker)
				for k, v := range before {
					if after[k] != v {
						t.Errorf("%s changed although the cache was kept", k)
					}
				}
			}
			if b, _ := os.ReadFile(filepath.Join(live, "preferences2.dat")); string(b) != "player prefs" {
				t.Error("preferences2.dat changed")
			}
			if b, _ := os.ReadFile(rl); string(b) != "runelite" {
				t.Error("RuneLite settings changed")
			}
			if strings.Contains(downloaded(r.requests()), "cache") != c.seed {
				t.Error("cache downloaded although it is not seeded (or the other way round)")
			}
		})
	}
}

func snapshot(dir string) map[string]string {
	out := map[string]string{}
	ents, _ := os.ReadDir(dir)
	for _, e := range ents {
		b, _ := os.ReadFile(filepath.Join(dir, e.Name()))
		out[e.Name()] = string(b)
	}
	return out
}

func TestSeedReplacesStaleIndexFiles(t *testing.T) {
	r := newRelease(t)
	r.set(manifest.Java, javaZip("1"))
	r.set(manifest.Client, clientZip("1"))
	r.setCache(map[int]int64{0: 100}, 0, 1)
	dest := filepath.Join(t.TempDir(), "GadScape")
	cl := kit.Client{AppName: "GadScape", Dest: dest}
	cachetest.Write(cl.CacheDir(), map[int]int64{0: 50, 16: 1}, 0) // old cache with an index the release lacks
	st, _ := LoadState(dest)
	run(t, r, dest, &st)
	if exists(filepath.Join(cl.CacheDir(), "main_file_cache.idx16")) {
		t.Error("the old cache's idx16 was left next to the new dat2")
	}
	sameFiles(t, r.cache, cl.CacheDir())
}

func TestUnsafeOrOversizedZipChangesNothing(t *testing.T) {
	r := newRelease(t)
	r.set(manifest.Java, javaZip("1"))
	r.set(manifest.Client, clientZip("1"))
	r.setCache(map[int]int64{0: 1}, 0, 1)
	dest := filepath.Join(t.TempDir(), "GadScape")
	st, _ := LoadState(dest)
	run(t, r, dest, &st)
	cl := kit.Client{AppName: "GadScape", Dest: dest}

	// a client zip that tries to write outside its folder (signed by mistake): refused, old client kept
	evil := clientZip("2")
	evil["GadScape/../../evil.txt"] = "x"
	r.set(manifest.Client, evil)
	in := r.installer(dest)
	p := MakePlan(r.m, st, cl)
	if err := in.Apply(context.Background(), r.m, p, &st); err == nil || !strings.Contains(err.Error(), "unsafe") {
		t.Fatalf("zip-slip client installed: %v", err)
	}
	if b, _ := os.ReadFile(filepath.Join(cl.App(), "lib", "gadscape-launcher.jar")); string(b) != "launcher 1" {
		t.Error("old client damaged")
	}
	if exists(filepath.Join(dest, "..", "evil.txt")) || exists(filepath.Join(filepath.Dir(dest), "evil.txt")) {
		t.Error("file written outside")
	}

	// a zip that unpacks to more than the manifest says
	r.set(manifest.Client, clientZip("3"))
	r.m.Find(manifest.Client).Unpacked = 10
	p = MakePlan(r.m, st, cl)
	if err := in.Apply(context.Background(), r.m, p, &st); err == nil {
		t.Fatal("oversized zip installed")
	}
	if b, _ := os.ReadFile(filepath.Join(cl.App(), "lib", "gadscape-launcher.jar")); string(b) != "launcher 1" {
		t.Error("old client damaged")
	}
}

func TestSwapDirKeepsOldOnFailure(t *testing.T) {
	dir := t.TempDir()
	target, old := filepath.Join(dir, "app"), filepath.Join(dir, ".update", "app.old")
	os.MkdirAll(target, 0o755)
	os.WriteFile(filepath.Join(target, "x"), []byte("old"), 0o644)
	os.MkdirAll(filepath.Dir(old), 0o755)
	if err := SwapDir(target, filepath.Join(dir, "does-not-exist"), old); err == nil {
		t.Fatal("swap with a missing new folder succeeded")
	}
	if b, _ := os.ReadFile(filepath.Join(target, "x")); string(b) != "old" {
		t.Error("old folder not put back")
	}
}

func TestRecoverInterruptedSwap(t *testing.T) {
	dest := t.TempDir()
	c := kit.Client{Dest: dest}
	old := filepath.Join(dest, updateDir, "java.old")
	os.MkdirAll(filepath.Join(old, "bin"), 0o755)
	os.WriteFile(filepath.Join(old, "bin", "javaw.exe"), []byte("MZ"), 0o644)
	Recover(c, t.Logf)
	if !exists(c.Javaw()) || exists(filepath.Join(dest, updateDir)) {
		t.Error("not recovered")
	}
}

func TestServerFile(t *testing.T) {
	dest := t.TempDir()
	c := kit.Client{AppName: "GadScape", Dest: dest}
	var st State
	p, err := EnsureServerFile(c, "GadScape-server.txt", "gadscape.duckdns.org", &st)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); !strings.Contains(string(b), "\r\ngadscape.duckdns.org\r\n") {
		t.Fatalf("server file %q", b)
	}
	// the release moves the server: an unedited file follows
	EnsureServerFile(c, "GadScape-server.txt", "play.gadscape.example", &st)
	if b, _ := os.ReadFile(p); !strings.Contains(string(b), "play.gadscape.example") {
		t.Error("unedited file did not follow the release")
	}
	// the player's own address is never changed
	os.WriteFile(p, []byte("192.168.1.20\r\n"), 0o644)
	EnsureServerFile(c, "GadScape-server.txt", "other.example", &st)
	if b, _ := os.ReadFile(p); string(b) != "192.168.1.20\r\n" {
		t.Errorf("player's address overwritten: %q", b)
	}
	// a file that existed before the setup (no record of writing it) is kept too
	var fresh State
	EnsureServerFile(c, "GadScape-server.txt", "x.example", &fresh)
	if b, _ := os.ReadFile(p); string(b) != "192.168.1.20\r\n" {
		t.Error("existing file overwritten")
	}
}

func TestStateRoundTrip(t *testing.T) {
	dest := t.TempDir()
	st, err := LoadState(dest)
	if err != nil || st.Release != 0 || st.Files == nil {
		t.Fatalf("empty state %+v %v", st, err)
	}
	st.Release, st.Files["java"] = 5, "abc"
	SaveState(dest, st)
	got, _ := LoadState(dest)
	if got.Release != 5 || got.Files["java"] != "abc" {
		t.Errorf("%+v", got)
	}
	os.WriteFile(filepath.Join(dest, StateFile), []byte("{broken"), 0o644)
	if got, err := LoadState(dest); err == nil || got.Files == nil {
		t.Error("broken state not reported")
	}
}
