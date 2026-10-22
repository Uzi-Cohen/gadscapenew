// Package fetch downloads release files over HTTP(S): small files whole (the manifest), big ones into a ".part"
// file that is resumed with a Range request after a broken connection, retried with back-off, size-limited and
// checked against the sha256 from the signed manifest before it gets its real name.
package fetch

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"gadscapesetup/internal/robust"
)

// Downloader holds the settings; the zero value works (5 tries, 30 s without data = broken connection).
type Downloader struct {
	Client      *http.Client
	Attempts    int
	Backoff     func(attempt int) time.Duration // wait before try number attempt (2, 3, ...)
	IdleTimeout time.Duration
	UserAgent   string
	Log         func(format string, a ...any)
}

// HTTPError is a server answer other than the file.
type HTTPError struct {
	URL    string
	Status int
}

func (e *HTTPError) Error() string {
	return fmt.Sprintf("the server answered %d %s for %s", e.Status, http.StatusText(e.Status), e.URL)
}

// HashError: the downloaded file is not the one the manifest lists.
type HashError struct {
	URL       string
	Want, Got string
}

func (e *HashError) Error() string {
	return fmt.Sprintf("the file from %s does not match the release (sha256 %s, expected %s)", e.URL, e.Got, e.Want)
}

// SizeError: the server's file has another size than the manifest says.
type SizeError struct {
	URL       string
	Want, Got int64
}

func (e *SizeError) Error() string {
	return fmt.Sprintf("the file at %s has %d bytes, the release says %d", e.URL, e.Got, e.Want)
}

var errIdle = errors.New("no data for too long")

func (d *Downloader) logf(format string, a ...any) {
	if d.Log != nil {
		d.Log(format, a...)
	}
}

func (d *Downloader) client() *http.Client {
	if d.Client != nil {
		return d.Client
	}
	return http.DefaultClient
}

func (d *Downloader) attempts() int {
	if d.Attempts > 0 {
		return d.Attempts
	}
	return 5
}

func (d *Downloader) idle() time.Duration {
	if d.IdleTimeout > 0 {
		return d.IdleTimeout
	}
	return 30 * time.Second
}

func (d *Downloader) backoff(attempt int) time.Duration {
	if d.Backoff != nil {
		return d.Backoff(attempt)
	}
	s := 1 << (attempt - 2) // 1, 2, 4, 8 s
	if s > 15 {
		s = 15
	}
	return time.Duration(s) * time.Second
}

func sleep(ctx context.Context, t time.Duration) error {
	if t <= 0 {
		return ctx.Err()
	}
	tm := time.NewTimer(t)
	defer tm.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-tm.C:
		return nil
	}
}

// permanent errors are not retried: the server said the file is not there, or it is the wrong file.
func permanent(err error) bool {
	var he *HTTPError
	if errors.As(err, &he) {
		return he.Status >= 400 && he.Status < 500 && he.Status != 408 && he.Status != 429
	}
	var se *SizeError
	return errors.As(err, &se)
}

func (d *Downloader) newRequest(ctx context.Context, u string) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	if d.UserAgent != "" {
		req.Header.Set("User-Agent", d.UserAgent)
	}
	return req, nil
}

// Get downloads a small file whole (at most max bytes), asking caches for a fresh copy. ctx sets the time limit.
func (d *Downloader) Get(ctx context.Context, u string, max int64) ([]byte, error) {
	var last error
	for attempt := 1; attempt <= 2; attempt++ {
		if attempt > 1 {
			if err := sleep(ctx, 500*time.Millisecond); err != nil {
				return nil, last
			}
		}
		req, err := d.newRequest(ctx, u)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Cache-Control", "no-cache")
		resp, err := d.client().Do(req)
		if err != nil {
			last = err
			continue
		}
		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			last = &HTTPError{URL: u, Status: resp.StatusCode}
			if permanent(last) {
				return nil, last
			}
			continue
		}
		b, err := io.ReadAll(io.LimitReader(resp.Body, max+1))
		resp.Body.Close()
		if err != nil {
			last = err
			continue
		}
		if int64(len(b)) > max {
			return nil, fmt.Errorf("%s is bigger than %d bytes", u, max)
		}
		return b, nil
	}
	return nil, last
}

// FileMatches is true when path exists with this size and sha256.
func FileMatches(path string, size int64, sha string) bool {
	st, err := os.Stat(path)
	if err != nil || !st.Mode().IsRegular() || st.Size() != size {
		return false
	}
	got, err := HashFile(path)
	return err == nil && got == sha
}

// HashFile is the lower-case hex sha256 of a file.
func HashFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// Download gets u into dest: it must be size bytes with this sha256 (lower-case hex). Bytes go to dest+".part"
// first; a broken download is continued from where it stopped (here, or in a later run), with a fresh start when
// the server does not do ranges or the part turns out to be damaged. dest gets its name only once the hash
// matches. progress (may be nil) is called with the bytes on disk so far.
func (d *Downloader) Download(ctx context.Context, u, dest string, size int64, sha string, progress func(done int64)) error {
	if progress == nil {
		progress = func(int64) {}
	}
	if FileMatches(dest, size, sha) {
		progress(size)
		return nil
	}
	part := dest + ".part"
	var last error
	hashFails := 0
	for attempt := 1; attempt <= d.attempts(); attempt++ {
		if attempt > 1 {
			wait := d.backoff(attempt)
			d.logf("download %s: try %d in %s (%v)", u, attempt, wait, last)
			if err := sleep(ctx, wait); err != nil {
				return err
			}
		}
		err := d.attempt(ctx, u, part, size, progress)
		if err == nil {
			got, herr := HashFile(part)
			if herr != nil {
				return herr
			}
			if got == sha {
				os.Remove(dest)
				// retried: a virus scanner may still be reading the file it has just seen written
				if err := robust.Rename(part, dest); err != nil {
					return err
				}
				return nil
			}
			// a damaged part file (or a bad copy on the server): start again from nothing, once
			os.Remove(part)
			last = &HashError{URL: u, Want: sha, Got: got}
			d.logf("download %s: %v", u, last)
			if hashFails++; hashFails >= 2 {
				return last
			}
			continue
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		last = err
		if permanent(err) {
			break
		}
	}
	return fmt.Errorf("couldn't download %s: %w", u, last)
}

// attempt continues the part file once. A nil error means the part file has size bytes.
func (d *Downloader) attempt(ctx context.Context, u, part string, size int64, progress func(int64)) error {
	f, err := os.OpenFile(part, os.O_RDWR|os.O_CREATE, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return err
	}
	have := st.Size()
	if have > size {
		if err := f.Truncate(0); err != nil {
			return err
		}
		have = 0
	}
	if have == size {
		return nil
	}
	actx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	req, err := d.newRequest(actx, u)
	if err != nil {
		return err
	}
	if have > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", have))
	}
	idle := time.AfterFunc(d.idle(), func() { cancel(errIdle) })
	defer idle.Stop()
	resp, err := d.client().Do(req)
	if err != nil {
		if c := context.Cause(actx); c == errIdle {
			return errIdle
		}
		return err
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusPartialContent:
		start, total, ok := parseContentRange(resp.Header.Get("Content-Range"))
		if !ok || start != have {
			// not the piece asked for: start over next time
			f.Truncate(0)
			return fmt.Errorf("server sent the wrong part of %s (%q)", u, resp.Header.Get("Content-Range"))
		}
		if total >= 0 && total != size {
			return &SizeError{URL: u, Want: size, Got: total}
		}
	case http.StatusOK:
		if have > 0 {
			d.logf("download %s: server does not resume, starting over", u)
			if err := f.Truncate(0); err != nil {
				return err
			}
			have = 0
		}
		if resp.ContentLength >= 0 && resp.ContentLength != size {
			return &SizeError{URL: u, Want: size, Got: resp.ContentLength}
		}
	case http.StatusRequestedRangeNotSatisfiable:
		f.Truncate(0)
		return fmt.Errorf("server refused to resume %s (416); starting over", u)
	default:
		return &HTTPError{URL: u, Status: resp.StatusCode}
	}
	if _, err := f.Seek(have, io.SeekStart); err != nil {
		return err
	}
	if have > 0 {
		d.logf("download %s: resuming at %d of %d bytes", u, have, size)
	}
	progress(have)
	buf := make([]byte, 256<<10)
	for have < size {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			idle.Reset(d.idle())
			if have+int64(n) > size {
				f.Truncate(0)
				return &SizeError{URL: u, Want: size, Got: have + int64(n)}
			}
			if _, err := f.Write(buf[:n]); err != nil {
				return err
			}
			have += int64(n)
			progress(have)
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			if context.Cause(actx) == errIdle {
				return errIdle
			}
			return rerr
		}
	}
	if have < size {
		return io.ErrUnexpectedEOF
	}
	// anything after the expected end means the server's file is bigger than the release says
	var one [1]byte
	if n, _ := resp.Body.Read(one[:]); n > 0 {
		f.Truncate(0)
		return &SizeError{URL: u, Want: size, Got: size + 1}
	}
	return f.Sync()
}

// parseContentRange reads "bytes <start>-<end>/<total or *>"; total is -1 when unknown.
func parseContentRange(s string) (start, total int64, ok bool) {
	s = strings.TrimSpace(s)
	if !strings.HasPrefix(s, "bytes ") {
		return 0, 0, false
	}
	rng, tot, found := strings.Cut(strings.TrimPrefix(s, "bytes "), "/")
	if !found {
		return 0, 0, false
	}
	a, _, found := strings.Cut(rng, "-")
	if !found {
		return 0, 0, false
	}
	start, err := strconv.ParseInt(a, 10, 64)
	if err != nil {
		return 0, 0, false
	}
	total = -1
	if tot != "*" {
		if total, err = strconv.ParseInt(tot, 10, 64); err != nil {
			return 0, 0, false
		}
	}
	return start, total, true
}
