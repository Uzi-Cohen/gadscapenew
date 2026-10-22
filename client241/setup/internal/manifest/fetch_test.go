package manifest

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

// releaseServer serves manifest.json and manifest.sig; next (if set) is called before every request.
type releaseServer struct {
	mu       sync.Mutex
	body     []byte
	sig      []byte
	queries  []string
	requests int
	next     func(path string)
}

func (s *releaseServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.requests++
	s.queries = append(s.queries, r.URL.RawQuery)
	if s.next != nil {
		s.next(r.URL.Path)
	}
	switch r.URL.Path {
	case "/rel/manifest.json":
		w.Write(s.body)
	case "/rel/manifest.sig":
		w.Write(s.sig)
	default:
		http.NotFound(w, r)
	}
}

func get(c *http.Client) Getter {
	return func(ctx context.Context, u string, max int64) ([]byte, error) {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		resp, err := c.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			return nil, errors.New(resp.Status)
		}
		return io.ReadAll(io.LimitReader(resp.Body, max))
	}
}

func TestFetch(t *testing.T) {
	defer func(d time.Duration) { RetryDelay = d }(RetryDelay)
	RetryDelay = 10 * time.Millisecond
	pub, priv := keys(t)
	m1, m2 := sample(), sample()
	m2.Version++
	b1, _ := json.Marshal(m1)
	b2, _ := json.Marshal(m2)
	rs := &releaseServer{body: b1, sig: Sign(priv, b1)}
	srv := httptest.NewServer(rs)
	defer srv.Close()
	mu, _ := url.Parse(srv.URL + "/rel/manifest.json")
	ctx := context.Background()

	m, body, err := Fetch(ctx, get(srv.Client()), mu, pub, t.Logf)
	if err != nil || m.Version != m1.Version || string(body) != string(b1) {
		t.Fatalf("good release: %v", err)
	}
	if len(rs.queries) != 2 || !strings.HasPrefix(rs.queries[0], "r=") || rs.queries[0] == rs.queries[1] {
		t.Errorf("no cache-busting queries: %q", rs.queries)
	}

	// a new manifest.sig next to the old manifest.json (the upload order): the old manifest still verifies
	rs.sig = Sign(priv, b2, PreviousSignature(pub, b1, rs.sig))
	if m, _, err := Fetch(ctx, get(srv.Client()), mu, pub, t.Logf); err != nil || m.Version != m1.Version {
		t.Errorf("old manifest with the new signature file: %v", err)
	}

	// a stale signature (cached) on the first try, the right one on the second
	rs.body, rs.requests = b2, 0
	stale := Sign(priv, b1)
	good := Sign(priv, b2)
	rs.sig = stale
	rs.next = func(path string) {
		if path == "/rel/manifest.sig" && rs.requests > 2 {
			rs.sig = good
		}
	}
	if m, _, err := Fetch(ctx, get(srv.Client()), mu, pub, t.Logf); err != nil || m.Version != m2.Version || rs.requests != 4 {
		t.Errorf("mismatch then match: %v after %d requests", err, rs.requests)
	}

	// a mismatch that lasts: ErrSignature after two tries
	rs.next, rs.sig, rs.requests = nil, stale, 0
	if _, _, err := Fetch(ctx, get(srv.Client()), mu, pub, t.Logf); !errors.Is(err, ErrSignature) || rs.requests != 4 {
		t.Errorf("lasting mismatch: %v after %d requests", err, rs.requests)
	}

	// signed but broken, and signed for a newer format
	rs.body = []byte(`{"format":1,"app":"","channel":"release"}`)
	rs.sig = Sign(priv, rs.body)
	var broken *BrokenError
	if _, _, err := Fetch(ctx, get(srv.Client()), mu, pub, nil); !errors.As(err, &broken) {
		t.Errorf("broken manifest: %v", err)
	}
	rs.body = []byte(`{"format":9,"setup":{"version":3,"id":"setup","url":"s.exe","size":1,"sha256":"` + strings.Repeat("ab", 32) + `"}}`)
	rs.sig = Sign(priv, rs.body)
	var tooNew *TooNewError
	if m, _, err := Fetch(ctx, get(srv.Client()), mu, pub, nil); !errors.As(err, &tooNew) || m == nil || m.Setup.Version != 3 {
		t.Errorf("newer format: %v", err)
	}

	// no connection
	srv.Close()
	if _, _, err := Fetch(ctx, get(srv.Client()), mu, pub, nil); err == nil || errors.Is(err, ErrSignature) {
		t.Errorf("server down: %v", err)
	}
}

func TestSignatureFileWithPrevious(t *testing.T) {
	pub, priv := keys(t)
	old, cur := []byte(`{"v":1}`), []byte(`{"v":2}`)
	oldSig := Sign(priv, old)
	prev := PreviousSignature(pub, old, oldSig)
	if prev == "" || PreviousSignature(pub, cur, oldSig) != "" {
		t.Fatal("PreviousSignature")
	}
	sig := Sign(priv, cur, prev, prev, "")
	if n := len(strings.Fields(string(sig))); n != 2 {
		t.Errorf("%d signature lines", n)
	}
	for _, b := range [][]byte{old, cur} {
		if err := Verify(pub, b, sig); err != nil {
			t.Errorf("%s: %v", b, err)
		}
	}
	if err := Verify(pub, []byte(`{"v":3}`), sig); !errors.Is(err, ErrSignature) {
		t.Errorf("third manifest: %v", err)
	}
	// another key's line does not help, garbage lines and too many lines are refused
	_, other := keys(t)
	if err := Verify(pub, cur, Sign(other, cur, PreviousSignature(pub, old, oldSig))); !errors.Is(err, ErrSignature) {
		t.Errorf("other key: %v", err)
	}
	if err := Verify(pub, cur, append(Sign(priv, cur), []byte("junk!\n")...)); !errors.Is(err, ErrSignature) {
		t.Errorf("junk line: %v", err)
	}
	many := strings.Repeat(strings.TrimSpace(string(Sign(priv, cur)))+"\n", MaxSignatures+1)
	if err := Verify(pub, cur, []byte(many)); !errors.Is(err, ErrSignature) {
		t.Errorf("too many lines: %v", err)
	}
}

func TestTooNewManifestChecksSetup(t *testing.T) {
	// a newer format whose setup entry is unusable is an error, not a TooNewError with a setup to use
	for _, setup := range []string{
		`{"version":9,"id":"setup","url":"s.exe","size":0,"sha256":"abc"}`,
		`{"version":0,"id":"setup","url":"s.exe","size":1,"sha256":"` + strings.Repeat("ab", 32) + `"}`,
	} {
		_, err := Parse([]byte(`{"format":2,"app":"GadScape","channel":"release","version":5,"setup":` + setup + `,"files":[]}`))
		var tooNew *TooNewError
		if err == nil || errors.As(err, &tooNew) {
			t.Errorf("setup %s: %v", setup, err)
		}
	}
}

func TestCheckForErrors(t *testing.T) {
	m := sample()
	var broken *BrokenError
	if err := m.CheckFor("GadScape", "test", 0); !errors.As(err, &broken) {
		t.Errorf("wrong channel: %v", err)
	}
	var rb *RollbackError
	if err := m.CheckFor("GadScape", "release", m.Version+1); !errors.As(err, &rb) || errors.As(err, &broken) {
		t.Errorf("rollback: %v", err)
	}
}

func TestRedirectsStayHTTPS(t *testing.T) {
	plain := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("plain")) }))
	defer plain.Close()
	var secure *httptest.Server
	secure = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/to-http":
			http.Redirect(w, r, plain.URL+"/x", http.StatusFound)
		case "/to-https":
			http.Redirect(w, r, secure.URL+"/ok", http.StatusFound)
		case "/loop":
			http.Redirect(w, r, secure.URL+"/loop", http.StatusFound)
		default:
			w.Write([]byte("secure"))
		}
	}))
	defer secure.Close()
	tr := secure.Client().Transport.(*http.Transport).Clone()
	tr.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	c := &http.Client{Transport: tr, CheckRedirect: CheckRedirect(false)}
	if b, err := get(c)(context.Background(), secure.URL+"/to-http", 100); err == nil {
		t.Errorf("redirect to plain http followed: %q", b)
	}
	if b, err := get(c)(context.Background(), secure.URL+"/to-https", 100); err != nil || string(b) != "secure" {
		t.Errorf("https redirect: %q %v", b, err)
	}
	if _, err := get(c)(context.Background(), secure.URL+"/loop", 100); err == nil {
		t.Error("redirect loop followed")
	}
	// test builds: plain http to 127.0.0.1 only
	local := &http.Client{Transport: tr, CheckRedirect: CheckRedirect(true)}
	if b, err := get(local)(context.Background(), secure.URL+"/to-http", 100); err != nil || string(b) != "plain" {
		t.Errorf("test build, redirect to http://127.0.0.1: %q %v", b, err)
	}
}
