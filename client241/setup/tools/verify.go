package main

import (
	"archive/zip"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gadscapeclient/kit"
	"gadscapesetup/internal/cachever"
	"gadscapesetup/internal/fetch"
	"gadscapesetup/internal/manifest"
	"gadscapesetup/internal/winres"
)

func cmdVerify(args []string) error {
	fs := flag.NewFlagSet("verify", flag.ExitOnError)
	dir := fs.String("dir", "", "release folder")
	addr := fs.String("url", "", "manifest.json address of an uploaded release")
	pubFile := fs.String("pub", "", "public key file")
	keyFile := fs.String("key", "", "private key file (its public half is used)")
	quick := fs.Bool("quick", false, "skip unpacking the cache to compare its tables")
	localHTTP := fs.Bool("allow-local-http", false, "allow http://127.0.0.1 (testing)")
	fs.Parse(args)
	var pub ed25519.PublicKey
	var err error
	switch {
	case *pubFile != "":
		pub, err = readPublicKey(*pubFile)
	case *keyFile != "":
		var priv ed25519.PrivateKey
		if priv, err = readPrivateKey(*keyFile); err == nil {
			pub = priv.Public().(ed25519.PublicKey)
		}
	default:
		err = errors.New("verify needs --pub or --key")
	}
	if err != nil {
		return err
	}
	if (*dir == "") == (*addr == "") {
		return errors.New("verify needs --dir or --url")
	}
	var get func(string) (string, func(), error) // file url -> local path, cleanup
	var body, sig []byte
	var base *url.URL
	fetched := false
	if *dir != "" {
		if body, err = os.ReadFile(filepath.Join(*dir, "manifest.json")); err != nil {
			return err
		}
		if sig, err = os.ReadFile(filepath.Join(*dir, "manifest.sig")); err != nil {
			return err
		}
		base, _ = url.Parse("https://release.invalid/manifest.json")
		get = func(u string) (string, func(), error) {
			if strings.Contains(u, "://") {
				return "", nil, fmt.Errorf("absolute address %s: check it with --url", u)
			}
			return filepath.Join(*dir, filepath.FromSlash(u)), func() {}, nil
		}
	} else {
		if base, err = url.Parse(*addr); err != nil {
			return err
		}
		if err := manifest.CheckURL(base, *localHTTP); err != nil {
			return err
		}
		// the same client rules as the setup: redirects only to addresses the setup would take
		client := &http.Client{CheckRedirect: manifest.CheckRedirect(*localHTTP)}
		dl := &fetch.Downloader{Client: client, UserAgent: "gadscape-release-verify"}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		// fetched the way the setup does it (no cached copies, a mismatching pair fetched again once)
		if _, body, err = manifest.Fetch(ctx, dl.Get, base, pub, func(f string, a ...any) { fmt.Printf("  note "+f+"\n", a...) }); body == nil {
			return fmt.Errorf("manifest: %v", err)
		}
		fetched = true // its signature is checked
		tmp, err := os.MkdirTemp("", "gadscape-verify-")
		if err != nil {
			return err
		}
		defer os.RemoveAll(tmp)
		get = func(u string) (string, func(), error) {
			full, err := manifest.Resolve(base, u, *localHTTP)
			if err != nil {
				return "", nil, err
			}
			p := filepath.Join(tmp, "f")
			resp, err := client.Get(full.String())
			if err != nil {
				return "", nil, err
			}
			defer resp.Body.Close()
			if resp.StatusCode != 200 {
				return "", nil, fmt.Errorf("%s: HTTP %d", full, resp.StatusCode)
			}
			f, err := os.Create(p)
			if err != nil {
				return "", nil, err
			}
			_, err = io.Copy(f, resp.Body)
			f.Close()
			return p, func() { os.Remove(p) }, err
		}
	}
	problems := 0
	bad := func(format string, a ...any) {
		problems++
		fmt.Printf("  BAD  "+format+"\n", a...)
	}
	ok := func(format string, a ...any) { fmt.Printf("  ok   "+format+"\n", a...) }

	if !fetched {
		if err := manifest.Verify(pub, body, sig); err != nil {
			return fmt.Errorf("signature: %v", err)
		}
	}
	ok("signature (key %s)", base64.StdEncoding.EncodeToString(pub))
	m, err := manifest.Parse(body)
	if err != nil {
		return err
	}
	ok("manifest: release %d (%s), %s channel, app %s, server %q, cache version %d", m.Version, m.Label, m.Channel, m.App, m.Server, m.Cache.Version)
	all := append([]manifest.File{}, m.Files...)
	if m.Setup != nil {
		all = append(all, m.Setup.File)
	}
	for _, f := range all {
		if _, err := manifest.Resolve(base, f.URL, *localHTTP); err != nil {
			bad("%s: %v", f.ID, err)
			continue
		}
		p, cleanup, err := get(f.URL)
		if err != nil {
			bad("%s: %v", f.ID, err)
			continue
		}
		func() {
			defer cleanup()
			st, err := os.Stat(p)
			if err != nil {
				bad("%s: %v", f.ID, err)
				return
			}
			if st.Size() != f.Size {
				bad("%s: %d bytes, manifest says %d", f.ID, st.Size(), f.Size)
				return
			}
			sum, _ := fetch.HashFile(p)
			if sum != f.SHA256 {
				bad("%s: sha256 %s, manifest says %s", f.ID, sum, f.SHA256)
				return
			}
			ok("%-6s %s  %d bytes  sha256 %s", f.ID, f.URL, f.Size, f.SHA256[:16])
			if f.ID == "setup" {
				checkSetupExe(p, m, pub, ok, bad)
				return
			}
			checkZipFile(p, f, m, *quick, ok, bad)
		}()
	}
	if problems > 0 {
		return fmt.Errorf("%d problem(s)", problems)
	}
	fmt.Println("release OK")
	return nil
}

func checkSetupExe(p string, m *manifest.Manifest, pub ed25519.PublicKey, ok, bad func(string, ...any)) {
	b, err := os.ReadFile(p)
	if err != nil {
		bad("setup: %v", err)
		return
	}
	st, err := manifest.FindStamp(b)
	if err != nil {
		bad("setup: %v", err)
		return
	}
	switch {
	case st.Key != base64.StdEncoding.EncodeToString(pub):
		bad("setup: built with key %q, not this release's", st.Key)
	case st.Version != m.Setup.Version:
		bad("setup: version %d, manifest says %d", st.Version, m.Setup.Version)
	case st.Channel != m.Channel || st.App != m.App:
		bad("setup: %s/%s, manifest is %s/%s", st.Channel, st.App, m.Channel, m.App)
	default:
		ok("setup: version %d, %s channel, same key", st.Version, st.Channel)
	}
	if strs, fv, err := winres.ReadVersion(b); err != nil {
		bad("setup: version resource: %v", err)
	} else {
		ok("setup: %s %s, file version %d.%d.%d.%d", strs["CompanyName"], strs["FileDescription"], fv[0], fv[1], fv[2], fv[3])
	}
}

func checkZipFile(p string, f manifest.File, m *manifest.Manifest, quick bool, ok, bad func(string, ...any)) {
	zr, err := zip.OpenReader(p)
	if err != nil {
		bad("%s: %v", f.ID, err)
		return
	}
	defer zr.Close()
	limit := f.Unpacked
	if limit <= 0 {
		limit = kit.DefaultMaxBytes
	}
	ents, total, err := kit.CheckZip(&zr.Reader, kit.ExtractOptions{StripTop: f.ID != manifest.Cache, MaxBytes: limit})
	if err != nil {
		bad("%s: %v", f.ID, err)
		return
	}
	ok("%-6s zip: %d entries, %d bytes unpacked, names safe", f.ID, len(ents), total)
	if f.ID != manifest.Cache {
		return
	}
	for _, e := range ents {
		if e.Dir || !cachever.IsCacheFile(e.Path) {
			bad("cache: %q is not a cache file", e.Path)
			return
		}
	}
	if quick {
		return
	}
	tmp, err := os.MkdirTemp("", "gadscape-verify-cache-")
	if err != nil {
		bad("cache: %v", err)
		return
	}
	defer os.RemoveAll(tmp)
	if err := kit.ExtractZipFile(context.Background(), p, tmp, kit.ExtractOptions{MaxBytes: limit}); err != nil {
		bad("cache: %v", err)
		return
	}
	ix, err := cachever.Read(tmp)
	if err != nil {
		bad("cache: %v", err)
		return
	}
	if r := cachever.Compare(ix, m.Cache.Indexes); r != cachever.Same || len(ix) != len(m.Cache.Indexes) {
		bad("cache: tables differ from the manifest's (%v)", r)
		return
	}
	h := sha256.New()
	for _, x := range ix {
		fmt.Fprintf(h, "%d:%d:%d;", x.ID, x.Revision, x.CRC)
	}
	ok("cache: %d reference tables match the manifest (%s)", len(ix), hex.EncodeToString(h.Sum(nil))[:12])
	chk, err := cachever.Check(tmp)
	switch {
	case err != nil:
		bad("cache: %v", err)
	case chk.Invalid > 0 || chk.TableTrailers > 0:
		bad("cache: %d of %d groups (and %d reference tables) would be thrown away by the 241 client and downloaded again (build the release with this gadscape-release: it repacks the cache)", chk.Invalid, chk.Groups, chk.TableTrailers)
	default:
		ok("cache: all %d groups on disk pass the 241 client's check (%d not in the cache)", chk.OK, chk.Missing)
	}
}
