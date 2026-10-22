package fetch

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"
)

func payload(n int) ([]byte, string) {
	b := make([]byte, n)
	rand.New(rand.NewSource(int64(n))).Read(b)
	h := sha256.Sum256(b)
	return b, hex.EncodeToString(h[:])
}

type server struct {
	*httptest.Server
	mu     sync.Mutex
	ranges []string // Range header of every request ("" = none)
}

func (s *server) log(r *http.Request) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ranges = append(s.ranges, r.Header.Get("Range"))
	return len(s.ranges)
}

func (s *server) requests() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string{}, s.ranges...)
}

func newServer(t *testing.T, h func(s *server, n int, w http.ResponseWriter, r *http.Request)) *server {
	s := &server{}
	s.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h(s, s.log(r), w, r)
	}))
	t.Cleanup(s.Close)
	return s
}

func fast() *Downloader {
	return &Downloader{Backoff: func(int) time.Duration { return 0 }, IdleTimeout: 2 * time.Second}
}

// serve content with Range support (http.ServeContent)
func serveRange(data []byte) func(*server, int, http.ResponseWriter, *http.Request) {
	return func(_ *server, _ int, w http.ResponseWriter, r *http.Request) {
		http.ServeContent(w, r, "f", time.Time{}, bytes.NewReader(data))
	}
}

func TestDownloadOK(t *testing.T) {
	data, sum := payload(300_000)
	s := newServer(t, serveRange(data))
	dest := filepath.Join(t.TempDir(), "f.zip")
	var last int64
	if err := fast().Download(context.Background(), s.URL+"/f.zip", dest, int64(len(data)), sum, func(n int64) { last = n }); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(dest)
	if !bytes.Equal(got, data) || last != int64(len(data)) {
		t.Fatalf("content/progress wrong (%d)", last)
	}
	if _, err := os.Stat(dest + ".part"); err == nil {
		t.Error("part file left behind")
	}
	// already there: no request at all
	n := len(s.requests())
	if err := fast().Download(context.Background(), s.URL+"/f.zip", dest, int64(len(data)), sum, nil); err != nil {
		t.Fatal(err)
	}
	if len(s.requests()) != n {
		t.Error("downloaded a file that was already there")
	}
}

// the connection drops halfway: the next try asks for the rest only
func TestTruncatedDownloadResumes(t *testing.T) {
	data, sum := payload(1 << 20)
	half := 400_000
	s := newServer(t, func(s *server, n int, w http.ResponseWriter, r *http.Request) {
		if n == 1 {
			w.Header().Set("Content-Length", strconv.Itoa(len(data)))
			w.WriteHeader(200)
			w.Write(data[:half])
			w.(http.Flusher).Flush()
			panic(http.ErrAbortHandler) // cut the connection
		}
		http.ServeContent(w, r, "f", time.Time{}, bytes.NewReader(data))
	})
	dest := filepath.Join(t.TempDir(), "f.zip")
	if err := fast().Download(context.Background(), s.URL+"/f", dest, int64(len(data)), sum, nil); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(dest)
	if !bytes.Equal(got, data) {
		t.Fatal("content differs")
	}
	req := s.requests()
	if len(req) != 2 || req[0] != "" || req[1] != "bytes="+strconv.Itoa(half)+"-" {
		t.Fatalf("requests %q, want a resume at %d", req, half)
	}
}

// a part file left by an earlier run (the setup was closed mid-download) is continued
func TestResumeFromEarlierRun(t *testing.T) {
	data, sum := payload(500_000)
	s := newServer(t, serveRange(data))
	dest := filepath.Join(t.TempDir(), "f.zip")
	os.WriteFile(dest+".part", data[:123_456], 0o644)
	if err := fast().Download(context.Background(), s.URL+"/f", dest, int64(len(data)), sum, nil); err != nil {
		t.Fatal(err)
	}
	if req := s.requests(); len(req) != 1 || req[0] != "bytes=123456-" {
		t.Fatalf("requests %q", req)
	}
	if got, _ := os.ReadFile(dest); !bytes.Equal(got, data) {
		t.Fatal("content differs")
	}
}

// a server without Range support sends everything again: the part is restarted, not appended to
func TestServerWithoutRanges(t *testing.T) {
	data, sum := payload(200_000)
	s := newServer(t, func(_ *server, _ int, w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(len(data)))
		w.Write(data)
	})
	dest := filepath.Join(t.TempDir(), "f.zip")
	os.WriteFile(dest+".part", data[:50_000], 0o644)
	if err := fast().Download(context.Background(), s.URL+"/f", dest, int64(len(data)), sum, nil); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(dest); !bytes.Equal(got, data) {
		t.Fatal("content differs")
	}
}

// the server has another file of the same size: refused, nothing kept under the real name
func TestHashMismatch(t *testing.T) {
	data, _ := payload(100_000)
	_, otherSum := payload(100_001)
	s := newServer(t, serveRange(data))
	dest := filepath.Join(t.TempDir(), "f.zip")
	err := fast().Download(context.Background(), s.URL+"/f", dest, int64(len(data)), otherSum, nil)
	var he *HashError
	if !errors.As(err, &he) {
		t.Fatalf("got %v, want a hash error", err)
	}
	if _, err := os.Stat(dest); err == nil {
		t.Error("bad file got the real name")
	}
	if _, err := os.Stat(dest + ".part"); err == nil {
		t.Error("bad part kept")
	}
	if n := len(s.requests()); n != 2 {
		t.Errorf("%d requests, want 2 (one fresh retry after a hash mismatch)", n)
	}
}

// a damaged part file from an earlier run: the hash fails once, the fresh retry succeeds
func TestDamagedPartRecovers(t *testing.T) {
	data, sum := payload(300_000)
	s := newServer(t, serveRange(data))
	dest := filepath.Join(t.TempDir(), "f.zip")
	bad := append([]byte{}, data[:100_000]...)
	bad[10] ^= 0xff
	os.WriteFile(dest+".part", bad, 0o644)
	if err := fast().Download(context.Background(), s.URL+"/f", dest, int64(len(data)), sum, nil); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(dest); !bytes.Equal(got, data) {
		t.Fatal("content differs")
	}
}

func TestSizeMismatchAndNotFound(t *testing.T) {
	data, sum := payload(1000)
	s := newServer(t, serveRange(data))
	dest := filepath.Join(t.TempDir(), "f.zip")
	var se *SizeError
	if err := fast().Download(context.Background(), s.URL+"/f", dest, 999, sum, nil); !errors.As(err, &se) {
		t.Errorf("bigger file: %v", err)
	}
	nf := newServer(t, func(_ *server, _ int, w http.ResponseWriter, r *http.Request) { http.NotFound(w, r) })
	err := fast().Download(context.Background(), nf.URL+"/f", dest, 1000, sum, nil)
	var he *HTTPError
	if !errors.As(err, &he) || he.Status != 404 {
		t.Errorf("404: %v", err)
	}
	if n := len(nf.requests()); n != 1 {
		t.Errorf("404 was tried %d times", n)
	}
	// server errors are retried
	var calls int
	flaky := newServer(t, func(_ *server, n int, w http.ResponseWriter, r *http.Request) {
		calls = n
		if n < 3 {
			http.Error(w, "busy", 503)
			return
		}
		http.ServeContent(w, r, "f", time.Time{}, bytes.NewReader(data))
	})
	if err := fast().Download(context.Background(), flaky.URL+"/f", filepath.Join(t.TempDir(), "g"), 1000, sum, nil); err != nil || calls != 3 {
		t.Errorf("503 twice then OK: %v after %d calls", err, calls)
	}
}

// a server that stops sending is given up on after the idle time and the download continues on the next try
func TestIdleTimeout(t *testing.T) {
	data, sum := payload(200_000)
	s := newServer(t, func(_ *server, n int, w http.ResponseWriter, r *http.Request) {
		if n == 1 {
			w.Header().Set("Content-Length", strconv.Itoa(len(data)))
			w.Write(data[:1000])
			w.(http.Flusher).Flush()
			select {
			case <-r.Context().Done():
			case <-time.After(10 * time.Second):
			}
			return
		}
		http.ServeContent(w, r, "f", time.Time{}, bytes.NewReader(data))
	})
	d := fast()
	d.IdleTimeout = 300 * time.Millisecond
	start := time.Now()
	if err := d.Download(context.Background(), s.URL+"/f", filepath.Join(t.TempDir(), "f"), int64(len(data)), sum, nil); err != nil {
		t.Fatal(err)
	}
	if time.Since(start) > 5*time.Second {
		t.Error("idle timeout did not fire")
	}
	if req := s.requests(); len(req) != 2 || req[1] != "bytes=1000-" {
		t.Errorf("requests %q", req)
	}
}

func TestCancelKeepsPart(t *testing.T) {
	data, sum := payload(2 << 20)
	ctx, cancel := context.WithCancel(context.Background())
	s := newServer(t, func(_ *server, n int, w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(len(data)))
		w.Write(data[:1<<20])
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	})
	dest := filepath.Join(t.TempDir(), "f")
	cancelAt := func(n int64) { // the player presses Cancel once half a megabyte is in
		if n >= 500_000 {
			cancel()
		}
	}
	if err := fast().Download(ctx, s.URL+"/f", dest, int64(len(data)), sum, cancelAt); !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
	if st, err := os.Stat(dest + ".part"); err != nil || st.Size() == 0 {
		t.Errorf("part file not kept for later: %v", err)
	}
}

func TestGet(t *testing.T) {
	s := newServer(t, func(_ *server, _ int, w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Cache-Control") != "no-cache" {
			http.Error(w, "want no-cache", 400)
			return
		}
		w.Write([]byte(`{"format":1}`))
	})
	b, err := fast().Get(context.Background(), s.URL+"/manifest.json", 1<<20)
	if err != nil || string(b) != `{"format":1}` {
		t.Fatalf("%q %v", b, err)
	}
	if _, err := fast().Get(context.Background(), s.URL+"/manifest.json", 5); err == nil {
		t.Error("over the size limit accepted")
	}
}

func TestParseContentRange(t *testing.T) {
	for _, c := range []struct {
		in           string
		start, total int64
		ok           bool
	}{
		{"bytes 100-199/200", 100, 200, true},
		{"bytes 0-9/*", 0, -1, true},
		{"bytes */200", 0, 0, false},
		{"items 1-2/3", 0, 0, false},
	} {
		s, tot, ok := parseContentRange(c.in)
		if ok != c.ok || (ok && (s != c.start || tot != c.total)) {
			t.Errorf("%q: %d %d %v", c.in, s, tot, ok)
		}
	}
}
