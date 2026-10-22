package manifest

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/url"
	"strings"
	"testing"
)

func sample() *Manifest {
	h := strings.Repeat("ab", 32)
	return &Manifest{
		Format: 1, App: "GadScape", Channel: "release", Version: 202610221200, Label: "test",
		Cache: CacheInfo{Version: 3},
		Files: []File{
			{ID: Java, URL: "files/java.zip", Size: 10, SHA256: h},
			{ID: Client, URL: "files/client.zip", Size: 10, SHA256: h},
			{ID: Cache, URL: "files/cache.zip", Size: 10, SHA256: h},
		},
	}
}

func keys(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	return pub, priv
}

func TestSignatureAcceptReject(t *testing.T) {
	pub, priv := keys(t)
	data, _ := json.Marshal(sample())
	sig := Sign(priv, data)
	if err := Verify(pub, data, sig); err != nil {
		t.Fatalf("good signature refused: %v", err)
	}
	// one byte changed in the manifest
	tampered := append([]byte{}, data...)
	tampered[len(tampered)/2] ^= 1
	if err := Verify(pub, tampered, sig); !errors.Is(err, ErrSignature) {
		t.Errorf("tampered manifest accepted: %v", err)
	}
	// whitespace added (same JSON meaning, different bytes)
	if err := Verify(pub, append(data, ' '), sig); err == nil {
		t.Error("manifest with an extra byte accepted")
	}
	// another key's signature
	_, other := keys(t)
	if err := Verify(pub, data, Sign(other, data)); !errors.Is(err, ErrSignature) {
		t.Errorf("other key's signature accepted: %v", err)
	}
	// garbage and empty signature files
	for _, s := range []string{"", "not base64!", base64.StdEncoding.EncodeToString([]byte("short"))} {
		if err := Verify(pub, data, []byte(s)); err == nil {
			t.Errorf("signature %q accepted", s)
		}
	}
	// no key built in: fail closed
	if err := Verify(nil, data, sig); !errors.Is(err, ErrNoKey) {
		t.Errorf("no key: %v", err)
	}
	if _, err := ParsePublicKey(""); !errors.Is(err, ErrNoKey) {
		t.Errorf("empty key: %v", err)
	}
	if _, err := ParsePublicKey("abc"); err == nil {
		t.Error("bad key accepted")
	}
	k, err := ParsePublicKey(base64.StdEncoding.EncodeToString(pub))
	if err != nil || !k.Equal(pub) {
		t.Errorf("key round trip: %v", err)
	}
}

func TestParseChecks(t *testing.T) {
	good, _ := json.Marshal(sample())
	if _, err := Parse(good); err != nil {
		t.Fatalf("good manifest: %v", err)
	}
	bad := []func(m *Manifest){
		func(m *Manifest) { m.Format = 0 },
		func(m *Manifest) { m.App = "" },
		func(m *Manifest) { m.Version = 0 },
		func(m *Manifest) { m.Files = m.Files[:2] },                                 // no cache
		func(m *Manifest) { m.Files = append(m.Files, m.Files[0]) },                 // java twice
		func(m *Manifest) { m.Files[0].SHA256 = "xyz" },                             // bad hash
		func(m *Manifest) { m.Files[0].SHA256 = strings.Repeat("AB", 32) },          // upper case
		func(m *Manifest) { m.Files[1].Size = 0 },                                   // no size
		func(m *Manifest) { m.Server = "evil host; rm -rf" },                        // not a host
		func(m *Manifest) { m.Setup = &Setup{Version: 0, File: m.Files[0]} },        // setup without version
		func(m *Manifest) { m.Setup = &Setup{Version: 2, File: File{ID: "setup"}} }, // setup without url
	}
	for i, f := range bad {
		m := sample()
		f(m)
		b, _ := json.Marshal(m)
		if _, err := Parse(b); err == nil {
			t.Errorf("bad manifest %d accepted", i)
		}
	}
	if _, err := Parse([]byte("{not json")); err == nil {
		t.Error("broken JSON accepted")
	}
	// unknown fields from a newer release are ignored
	m := map[string]any{}
	json.Unmarshal(good, &m)
	m["future_field"] = 1
	b, _ := json.Marshal(m)
	if _, err := Parse(b); err != nil {
		t.Errorf("unknown field: %v", err)
	}
}

func TestVersionHandling(t *testing.T) {
	// newer format: refused, but the manifest comes back so the setup can update itself
	m := sample()
	m.Format = Format + 1
	m.Setup = &Setup{Version: 7, File: File{ID: "setup", URL: "GadScape-Setup.exe", Size: 5, SHA256: strings.Repeat("cd", 32)}}
	b, _ := json.Marshal(m)
	got, err := Parse(b)
	var tooNew *TooNewError
	if !errors.As(err, &tooNew) || got == nil || got.Setup == nil || got.Setup.Version != 7 {
		t.Fatalf("newer format: %v %+v", err, got)
	}

	cur := sample()
	if err := cur.CheckFor("GadScape", "release", 0); err != nil {
		t.Errorf("first install: %v", err)
	}
	if err := cur.CheckFor("GadScape", "release", cur.Version); err != nil {
		t.Errorf("same release again: %v", err)
	}
	if err := cur.CheckFor("GadScape", "release", cur.Version-1); err != nil {
		t.Errorf("newer release: %v", err)
	}
	var rb *RollbackError
	if err := cur.CheckFor("GadScape", "release", cur.Version+1); !errors.As(err, &rb) {
		t.Errorf("older release than installed accepted: %v", err)
	}
	if err := cur.CheckFor("GadScape241Test", "test", 0); err == nil {
		t.Error("release manifest accepted by the test setup")
	}
	if err := cur.CheckFor("GadScape", "test", 0); err == nil {
		t.Error("wrong channel accepted")
	}
}

func TestURLs(t *testing.T) {
	base, _ := url.Parse("https://cdn.example.org/gadscape/release/manifest.json")
	u, err := Resolve(base, "files/client-ab.zip", false)
	if err != nil || u.String() != "https://cdn.example.org/gadscape/release/files/client-ab.zip" {
		t.Errorf("relative: %v %v", u, err)
	}
	if u, err := Resolve(base, "https://other.example.org/x.zip", false); err != nil || u.Host != "other.example.org" {
		t.Errorf("absolute: %v %v", u, err)
	}
	if _, err := Resolve(base, "http://other.example.org/x.zip", false); err == nil {
		t.Error("http file accepted")
	}
	if _, err := Resolve(base, "file:///C:/x.zip", false); err == nil {
		t.Error("file: accepted")
	}
	local, _ := url.Parse("http://127.0.0.1:8090/rel/manifest.json")
	if err := CheckURL(local, false); err == nil {
		t.Error("http to 127.0.0.1 accepted without the test flag")
	}
	if err := CheckURL(local, true); err != nil {
		t.Errorf("http to 127.0.0.1 with the test flag: %v", err)
	}
	for _, s := range []string{"http://192.168.1.5/m.json", "http://localhost/m.json", "http://127.0.0.2/m.json", "http://example.org/m.json"} {
		u, _ := url.Parse(s)
		if err := CheckURL(u, true); err == nil {
			t.Errorf("%s accepted with the test flag", s)
		}
	}
	if s := SigURL(base).String(); s != "https://cdn.example.org/gadscape/release/manifest.sig" {
		t.Errorf("sig url %s", s)
	}
}

func TestStamp(t *testing.T) {
	st := Stamp{Version: 3, Channel: "release", App: "GadScape", Key: base64.StdEncoding.EncodeToString(make([]byte, 32))}
	exe := append([]byte("MZ...code...GSSTAMP1 version=%d channel=%s...GSSTAMP1 version=(\\d+)..."), []byte(st.String())...)
	exe = append(exe, 0, 1, 2)
	got, err := FindStamp(exe)
	if err != nil || got != st {
		t.Fatalf("stamp: %+v %v", got, err)
	}
	none := Stamp{Version: 1, Channel: "test", App: "GadScape241Test"}
	if got, err := FindStamp([]byte(none.String())); err != nil || got.Key != "" {
		t.Errorf("keyless stamp: %+v %v", got, err)
	}
	if _, err := FindStamp([]byte("MZ no stamp")); err == nil {
		t.Error("no stamp found one")
	}
	if _, err := FindStamp([]byte(st.String() + st.String())); err == nil {
		t.Error("two stamps accepted")
	}
}
