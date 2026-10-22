package manifest

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"time"
)

// Getter downloads a small file whole, at most max bytes (fetch.Downloader.Get).
type Getter func(ctx context.Context, url string, max int64) ([]byte, error)

// RetryDelay is how long Fetch waits before it fetches a manifest and signature that do not match again.
var RetryDelay = 1500 * time.Millisecond

// Fetch downloads manifest.json and manifest.sig from murl, checks the signature with pub and parses the manifest.
//
// Both requests carry a random query (when murl has none), so caches between the setup and the host (CDNs,
// proxies) cannot hand out a manifest of one release and a signature of another; a pair that still does not match
// is fetched once more after RetryDelay (the two files are uploaded one after the other). Errors: ErrSignature
// (wrapped), *TooNewError (with the manifest, whose setup entry is checked), *BrokenError (signed but not usable),
// or the download error.
func Fetch(ctx context.Context, get Getter, murl *url.URL, pub ed25519.PublicKey, logf func(string, ...any)) (*Manifest, []byte, error) {
	var sigErr error
	for try := 1; try <= 2; try++ {
		if try > 1 {
			if logf != nil {
				logf("manifest and signature do not match (%v): fetching both again in %s", sigErr, RetryDelay)
			}
			t := time.NewTimer(RetryDelay)
			select {
			case <-ctx.Done():
				t.Stop()
				return nil, nil, sigErr
			case <-t.C:
			}
		}
		body, err := get(ctx, cacheBust(murl).String(), 4<<20)
		if err != nil {
			return nil, nil, firstErr(sigErr, ctx, err)
		}
		sig, err := get(ctx, cacheBust(SigURL(murl)).String(), 4096)
		if err != nil {
			return nil, nil, firstErr(sigErr, ctx, fmt.Errorf("release signature: %w", err))
		}
		if err := Verify(pub, body, sig); err != nil {
			if !errors.Is(err, ErrSignature) {
				return nil, nil, err
			}
			sigErr = err
			continue
		}
		m, err := Parse(body)
		var tooNew *TooNewError
		if err != nil && !errors.As(err, &tooNew) {
			return nil, body, &BrokenError{err}
		}
		return m, body, err
	}
	return nil, nil, sigErr
}

// firstErr: when the second try runs out of time, the signature mismatch is the error that tells what happened.
func firstErr(sigErr error, ctx context.Context, err error) error {
	if sigErr != nil && ctx.Err() != nil {
		return sigErr
	}
	return err
}

// cacheBust adds ?r=<random> to an address without a query.
func cacheBust(u *url.URL) *url.URL {
	if u.RawQuery != "" {
		return u
	}
	var b [8]byte
	rand.Read(b[:])
	c := *u
	c.RawQuery = "r=" + hex.EncodeToString(b[:])
	return &c
}
