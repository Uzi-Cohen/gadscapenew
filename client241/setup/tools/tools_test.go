package main

import (
	"archive/zip"
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gadscapesetup/internal/cachever/cachetest"
	"gadscapesetup/internal/manifest"
)

func zipFile(t *testing.T, path string, files map[string][]byte) {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, data := range files {
		w, _ := zw.Create(name)
		w.Write(data)
	}
	zw.Close()
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
}

func launcherJar(channel string) []byte {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, _ := zw.Create("gadscape/launcher/channel.properties")
	w.Write([]byte("channel=" + channel + "\n"))
	zw.Close()
	return buf.Bytes()
}

type fixture struct {
	dir, key, pub, jre, dist, cache, out string
}

func setup(t *testing.T) fixture {
	d := t.TempDir()
	f := fixture{dir: d, jre: filepath.Join(d, "jre.zip"), dist: filepath.Join(d, "dist"), cache: filepath.Join(d, "cache"), out: filepath.Join(d, "release")}
	if err := cmdKeygen([]string{"--out", filepath.Join(d, "keys"), "--name", "k"}); err != nil {
		t.Fatal(err)
	}
	f.key, f.pub = filepath.Join(d, "keys", "k.key"), filepath.Join(d, "keys", "k.pub")
	if st, _ := os.Stat(f.key); st.Mode().Perm() != 0o600 {
		t.Errorf("private key mode %v", st.Mode())
	}
	if err := cmdKeygen([]string{"--out", filepath.Join(d, "keys"), "--name", "k"}); err == nil {
		t.Error("keygen overwrote a key")
	}
	zipFile(t, f.jre, map[string][]byte{"jdk-21-jre/bin/javaw.exe": []byte("MZ"), "jdk-21-jre/release": []byte(`JAVA_VERSION="21.0.12"`)})
	os.MkdirAll(f.dist, 0o755)
	os.WriteFile(filepath.Join(f.dist, "channel-test.properties"), []byte("channel=test\ntitle=GadScape (test)\ndefault.host=127.0.0.1\nhome.windows=GadScape241Test\nexe.server_file=GadScape-241-Test-server.txt\n"), 0o644)
	f.client(t, "test", "1")
	cachetest.Write(f.cache, map[int]int64{0: 10, 2: 20}, 0)
	os.WriteFile(filepath.Join(f.cache, "idmap.json"), []byte("{}"), 0o644) // not a cache file: left out
	return f
}

func (f fixture) client(t *testing.T, channel, v string) {
	zipFile(t, filepath.Join(f.dist, "GadScape-241-Test.zip"), map[string][]byte{
		"GadScape-241-Test/jvm-args.txt": []byte("-Xmx768m"), "GadScape-241-Test/lib/gadscape-launcher.jar": launcherJar(channel),
		"GadScape-241-Test/lib-win/x.jar": []byte(v)})
}

func (f fixture) build(extra ...string) error {
	return cmdBuild(append([]string{"--channel", "test", "--dist", f.dist, "--jre-zip", f.jre, "--cache-dir", f.cache,
		"--key", f.key, "--out", f.out}, extra...))
}

func (f fixture) manifest(t *testing.T) *manifest.Manifest {
	b, err := os.ReadFile(filepath.Join(f.out, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	m, err := manifest.Parse(b)
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func TestBuildVerifyAndNextRelease(t *testing.T) {
	f := setup(t)
	if err := f.build("--version", "100"); err != nil {
		t.Fatal(err)
	}
	if err := cmdVerify([]string{"--dir", f.out, "--pub", f.pub}); err != nil {
		t.Fatalf("verify: %v", err)
	}
	m1 := f.manifest(t)
	if m1.App != "GadScape241Test" || m1.Server != "127.0.0.1" || m1.Version != 100 || m1.Cache.Version != 100 || len(m1.Cache.Indexes) != 2 {
		t.Errorf("manifest %+v", m1)
	}
	if c := m1.Find(manifest.Cache); c.Entries != 4 { // dat2, idx0, idx2, idx255 (not idmap.json)
		t.Errorf("cache zip has %d entries", c.Entries)
	}
	for _, x := range m1.Files {
		if strings.Contains(x.URL, "://") || !strings.HasPrefix(x.URL, "files/") {
			t.Errorf("url %s", x.URL)
		}
	}

	// the same release number again, or a lower one: refused
	if err := f.build("--version", "100"); err == nil {
		t.Error("same version accepted")
	}
	// next release with a new client: the cache keeps its version (same bytes), the old client file goes
	f.client(t, "test", "2")
	if err := f.build("--version", "101"); err != nil {
		t.Fatal(err)
	}
	m2 := f.manifest(t)
	if m2.Cache.Version != 100 || m2.Find(manifest.Cache).SHA256 != m1.Find(manifest.Cache).SHA256 {
		t.Errorf("unchanged cache got version %d", m2.Cache.Version)
	}
	if m2.Find(manifest.Client).SHA256 == m1.Find(manifest.Client).SHA256 {
		t.Error("client did not change")
	}
	if _, err := os.Stat(filepath.Join(f.out, m1.Find(manifest.Client).URL)); err == nil {
		t.Error("old client file kept")
	}
	// a changed cache gets the release's number
	cachetest.Write(f.cache, map[int]int64{0: 11, 2: 20}, 0)
	if err := f.build("--version", "102"); err != nil {
		t.Fatal(err)
	}
	if m3 := f.manifest(t); m3.Cache.Version != 102 {
		t.Errorf("changed cache version %d", m3.Cache.Version)
	}

	// a tampered manifest fails verify
	p := filepath.Join(f.out, "manifest.json")
	b, _ := os.ReadFile(p)
	os.WriteFile(p, bytes.Replace(b, []byte(`"127.0.0.1"`), []byte(`"evil.example"`), 1), 0o644)
	if err := cmdVerify([]string{"--dir", f.out, "--pub", f.pub}); err == nil {
		t.Error("tampered manifest verified")
	}
	// and a build into it refuses to treat it as the previous release
	if err := f.build("--version", "103"); err == nil {
		t.Error("build on top of a tampered release")
	}
}

func TestBuildRefusesWrongInputs(t *testing.T) {
	f := setup(t)
	// a release-channel client in a test release
	f.client(t, "release", "1")
	if err := f.build(); err == nil || !strings.Contains(err.Error(), "channel") {
		t.Errorf("wrong channel client: %v", err)
	}
	f.client(t, "test", "1")
	// a setup exe built with another key
	other, _, _ := ed25519.GenerateKey(nil)
	exe := filepath.Join(f.dir, "GadScape-241-Test-Setup.exe")
	os.WriteFile(exe, []byte("MZ..."+manifest.Stamp{Version: 1, Channel: "test", App: "GadScape241Test", Key: base64.StdEncoding.EncodeToString(other)}.String()), 0o644)
	if err := f.build("--setup-exe", exe); err == nil || !strings.Contains(err.Error(), "another public key") {
		t.Errorf("setup with another key: %v", err)
	}
	// a setup without a key
	os.WriteFile(exe, []byte("MZ..."+manifest.Stamp{Version: 1, Channel: "test", App: "GadScape241Test"}.String()), 0o644)
	if err := f.build("--setup-exe", exe); err == nil {
		t.Error("keyless setup accepted")
	}
	// a JRE zip without javaw.exe
	zipFile(t, f.jre, map[string][]byte{"jdk/bin/java": []byte("ELF")})
	if err := f.build(); err == nil || !strings.Contains(err.Error(), "javaw.exe") {
		t.Errorf("non-Windows JRE: %v", err)
	}
}

func TestKeyFiles(t *testing.T) {
	f := setup(t)
	priv, err := readPrivateKey(f.key)
	if err != nil {
		t.Fatal(err)
	}
	pub, err := readPublicKey(f.pub)
	if err != nil || !pub.Equal(priv.Public()) {
		t.Fatalf("key pair: %v", err)
	}
	if _, err := readPublicKey(f.key); err == nil {
		t.Error("private key file read as a public key")
	}
	// manifest signed by the tool verifies with the public key, JSON round trip
	b, _ := json.Marshal(map[string]int{"format": 1})
	if err := manifest.Verify(pub, b, manifest.Sign(priv, b)); err != nil {
		t.Error(err)
	}
}
