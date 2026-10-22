package main

import (
	"archive/zip"
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"gadscapeclient/kit"
	"gadscapesetup/internal/cachever"
	"gadscapesetup/internal/manifest"
	"gadscapesetup/internal/pack"
	"gadscapesetup/internal/winres"
)

// channel.properties as written by client241/build.sh
func readProps(path string) (map[string]string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return parseProps(b), nil
}

func parseProps(b []byte) map[string]string {
	m := map[string]string{}
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if k, v, ok := strings.Cut(line, "="); ok {
			m[strings.TrimSpace(k)] = strings.TrimSpace(v)
		}
	}
	return m
}

func openZip(path string) (*zip.ReadCloser, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return nil, fmt.Errorf("%s: %v", path, err)
	}
	return zr, nil
}

// checkJRE: a Windows x64 Java 17+ runtime zip with one top folder.
func checkJRE(path string) (int64, int, error) {
	zr, err := openZip(path)
	if err != nil {
		return 0, 0, err
	}
	defer zr.Close()
	ents, total, err := kit.CheckZip(&zr.Reader, kit.ExtractOptions{StripTop: true})
	if err != nil {
		return 0, 0, fmt.Errorf("%s: %v", path, err)
	}
	var javaw, release *zip.File
	for _, e := range ents {
		switch e.Path {
		case "bin/javaw.exe":
			javaw = e.File
		case "release":
			release = e.File
		}
	}
	if javaw == nil {
		return 0, 0, fmt.Errorf("%s has no <top>/bin/javaw.exe (not a Windows JRE zip?)", path)
	}
	if release != nil {
		if rc, err := release.Open(); err == nil {
			b, _ := io.ReadAll(io.LimitReader(rc, 64<<10))
			rc.Close()
			if m := regexp.MustCompile(`JAVA_VERSION="(\d+)`).FindSubmatch(b); m != nil {
				if v, _ := strconv.Atoi(string(m[1])); v < 17 {
					return 0, 0, fmt.Errorf("%s is Java %d; RuneLite 1.13.3 needs 17 or newer", path, v)
				}
			}
		}
	}
	return total, len(ents), nil
}

// checkClient: the client's portable folder zip, built for channel.
func checkClient(path, channel string) (int64, int, error) {
	zr, err := openZip(path)
	if err != nil {
		return 0, 0, err
	}
	defer zr.Close()
	ents, total, err := kit.CheckZip(&zr.Reader, kit.ExtractOptions{StripTop: true})
	if err != nil {
		return 0, 0, fmt.Errorf("%s: %v", path, err)
	}
	var launcher *zip.File
	hasArgs, hasWin := false, false
	for _, e := range ents {
		switch {
		case e.Path == "jvm-args.txt":
			hasArgs = true
		case e.Path == "lib/gadscape-launcher.jar":
			launcher = e.File
		case strings.HasPrefix(e.Path, "lib-win/"):
			hasWin = true
		}
	}
	if !hasArgs || launcher == nil || !hasWin {
		return 0, 0, fmt.Errorf("%s is not a GadScape client folder zip (needs jvm-args.txt, lib/gadscape-launcher.jar, lib-win/)", path)
	}
	// the channel baked into the launcher must be the release's: never ship the test client as a release
	rc, err := launcher.Open()
	if err != nil {
		return 0, 0, err
	}
	jar, err := io.ReadAll(io.LimitReader(rc, 64<<20))
	rc.Close()
	if err != nil {
		return 0, 0, err
	}
	jr, err := zip.NewReader(bytes.NewReader(jar), int64(len(jar)))
	if err != nil {
		return 0, 0, fmt.Errorf("gadscape-launcher.jar in %s: %v", path, err)
	}
	got := "test" // the launcher's own rule: no channel file = test build
	for _, f := range jr.File {
		if f.Name == "gadscape/launcher/channel.properties" {
			if r, err := f.Open(); err == nil {
				b, _ := io.ReadAll(io.LimitReader(r, 64<<10))
				r.Close()
				if c := parseProps(b)["channel"]; c != "" {
					got = c
				}
			}
		}
	}
	if got != channel {
		return 0, 0, fmt.Errorf("%s is a %q channel client, the release is %q", path, got, channel)
	}
	return total, len(ents), nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, dst)
}

// addFile copies src into out/files/<id>-<sha16><ext> (unless it is there already) and returns its entry.
func addFile(out, id, src, ext, baseURL string) (manifest.File, error) {
	sum, size, err := pack.HashFile(src)
	if err != nil {
		return manifest.File{}, err
	}
	name := id + "-" + sum[:16] + ext
	dst := filepath.Join(out, "files", name)
	if have, n, err := pack.HashFile(dst); err != nil || have != sum || n != size {
		if err := copyFile(src, dst); err != nil {
			return manifest.File{}, err
		}
	}
	u := "files/" + name
	if baseURL != "" {
		u = strings.TrimSuffix(baseURL, "/") + "/" + u
	}
	return manifest.File{ID: id, URL: u, Size: size, SHA256: sum}, nil
}

func cmdBuild(args []string) error {
	fs := flag.NewFlagSet("build", flag.ExitOnError)
	dist := fs.String("dist", "", "client build output folder")
	clientZip := fs.String("client-zip", "", "client zip (instead of the one in --dist)")
	channel := fs.String("channel", "release", "release or test")
	jre := fs.String("jre-zip", "", "Windows x64 JRE zip")
	cacheDir := fs.String("cache-dir", "", "game cache folder")
	setupExe := fs.String("setup-exe", "", "GadScape-Setup.exe")
	keyFile := fs.String("key", "", "private key file")
	out := fs.String("out", "", "release folder")
	version := fs.Int64("version", 0, "release number")
	label := fs.String("label", "", "release label")
	server := fs.String("server", "", "server address for new installs")
	cacheVersion := fs.Int64("cache-version", 0, "cache version")
	baseURL := fs.String("base-url", "", "absolute base address for the files")
	app := fs.String("app", "", "install folder name (default: from --dist's channel properties)")
	fs.Parse(args)
	if *jre == "" || *cacheDir == "" || *keyFile == "" || *out == "" || (*dist == "" && *clientZip == "") {
		return errors.New("build needs --jre-zip, --cache-dir, --key, --out and --dist (or --client-zip)")
	}
	priv, err := readPrivateKey(*keyFile)
	if err != nil {
		return err
	}
	pub := priv.Public().(ed25519.PublicKey)
	pubB64 := base64.StdEncoding.EncodeToString(pub)
	if *baseURL != "" {
		u, err := url.Parse(*baseURL)
		if err != nil || manifest.CheckURL(u, false) != nil {
			return fmt.Errorf("--base-url %q must be an https address", *baseURL)
		}
	}

	props := map[string]string{}
	if *dist != "" {
		props, err = readProps(filepath.Join(*dist, "channel-"+*channel+".properties"))
		if err != nil {
			return fmt.Errorf("%v (is --dist a client241/build.sh %s build?)", err, *channel)
		}
		if *clientZip == "" {
			name := "GadScape"
			if *channel != "release" {
				name = "GadScape-241-Test"
			}
			if t := props["exe.server_file"]; strings.HasSuffix(t, "-server.txt") {
				name = strings.TrimSuffix(t, "-server.txt")
			}
			*clientZip = filepath.Join(*dist, name+".zip")
		}
	}
	if *app == "" {
		*app = props["home.windows"]
	}
	if *app == "" {
		return errors.New("--app is needed without --dist (GadScape for release, GadScape241Test for test)")
	}
	if *server == "" {
		*server = props["default.host"]
	}

	// previous release in --out
	var prev *manifest.Manifest
	prevSig := "" // the previous manifest's signature, kept in the new manifest.sig (see the upload note below)
	if b, err := os.ReadFile(filepath.Join(*out, "manifest.json")); err == nil {
		sig, err := os.ReadFile(filepath.Join(*out, "manifest.sig"))
		if err != nil {
			return fmt.Errorf("%s has manifest.json but no manifest.sig", *out)
		}
		if err := manifest.Verify(pub, b, sig); err != nil {
			return fmt.Errorf("the release in %s is not signed with this key (%v); use an empty --out folder", *out, err)
		}
		if prev, err = manifest.Parse(b); err != nil {
			return fmt.Errorf("previous release in %s: %v", *out, err)
		}
		prevSig = manifest.PreviousSignature(pub, b, sig)
		fmt.Printf("previous release in %s: %d (%s)\n", *out, prev.Version, prev.Label)
	}
	now := time.Now().UTC()
	if *version == 0 {
		*version, _ = strconv.ParseInt(now.Format("200601021504"), 10, 64)
		if prev != nil && *version <= prev.Version {
			*version = prev.Version + 1
		}
	}
	if prev != nil && *version <= prev.Version {
		return fmt.Errorf("--version %d is not higher than the previous release %d (installed setups refuse older releases)", *version, prev.Version)
	}
	if *label == "" {
		*label = now.Format("2006-01-02") + " (" + strconv.FormatInt(*version, 10) + ")"
	}

	fmt.Println("checking", *jre)
	jreUnpacked, jreEntries, err := checkJRE(*jre)
	if err != nil {
		return err
	}
	fmt.Println("checking", *clientZip)
	clUnpacked, clEntries, err := checkClient(*clientZip, *channel)
	if err != nil {
		return err
	}
	fmt.Println("checking", *cacheDir)
	indexes, err := cachever.Read(*cacheDir)
	if err != nil {
		return fmt.Errorf("%s: %v", *cacheDir, err)
	}
	for _, ix := range indexes {
		if ix.Revision < 0 {
			return fmt.Errorf("%s: index %d's reference table cannot be read", *cacheDir, ix.ID)
		}
	}
	var stamp manifest.Stamp
	if *setupExe != "" {
		b, err := os.ReadFile(*setupExe)
		if err != nil {
			return err
		}
		if stamp, err = manifest.FindStamp(b); err != nil {
			return fmt.Errorf("%s: %v", *setupExe, err)
		}
		if stamp.Key != pubB64 {
			return fmt.Errorf("%s was built with another public key (%q) than this private key's (%s): it could not check this release", *setupExe, stamp.Key, pubB64)
		}
		if stamp.Channel != *channel || stamp.App != *app {
			return fmt.Errorf("%s is a %s/%s setup, the release is %s/%s", *setupExe, stamp.Channel, stamp.App, *channel, *app)
		}
		if prev != nil && prev.Setup != nil && stamp.Version < prev.Setup.Version {
			return fmt.Errorf("%s is setup version %d, older than the previous release's %d", *setupExe, stamp.Version, prev.Setup.Version)
		}
	} else {
		fmt.Println("note: no --setup-exe: installed launchers will not update themselves with this release")
	}

	if err := os.MkdirAll(filepath.Join(*out, "files"), 0o755); err != nil {
		return err
	}
	m := &manifest.Manifest{Format: manifest.Format, App: *app, Channel: *channel, Version: *version, Label: *label,
		Created: now.Format(time.RFC3339), Server: *server}
	jf, err := addFile(*out, manifest.Java, *jre, ".zip", *baseURL)
	if err != nil {
		return err
	}
	jf.Unpacked, jf.Entries = jreUnpacked, jreEntries
	cf, err := addFile(*out, manifest.Client, *clientZip, ".zip", *baseURL)
	if err != nil {
		return err
	}
	cf.Unpacked, cf.Entries = clUnpacked, clEntries

	// the cache as the 241 client stores it (4-byte version trailers): a cache packed by other tools would be
	// half thrown away and downloaded from the game server again on every new install's first start
	fmt.Println("repacking the cache the way the 241 client stores it")
	repacked := filepath.Join(*out, ".cache-repack.tmp")
	os.RemoveAll(repacked)
	defer os.RemoveAll(repacked)
	rp, err := cachever.Repack(*cacheDir, repacked)
	if err != nil {
		return fmt.Errorf("repacking %s: %v", *cacheDir, err)
	}
	fmt.Printf("  %d reference tables, %d groups (%d with a version trailer the client would not have accepted)\n", rp.Archives, rp.Groups, rp.Fixed)
	if rp.Missing > 0 {
		fmt.Printf("  note: %d groups the reference tables list are not in the cache (the game downloads them)\n", rp.Missing)
	}
	if rp.Invalid > 0 {
		fmt.Printf("  WARNING: %d groups do not match their reference table and were left out (the game downloads them from\n"+
			"  the server, which must have good copies), for example %s\n", rp.Invalid, strings.Join(rp.Examples, "; "))
	}
	if chk, err := cachever.Check(repacked); err != nil || chk.Invalid > 0 || chk.TableTrailers > 0 || chk.OK != rp.Groups {
		return fmt.Errorf("internal error: the repacked cache does not check out (%+v, %v)", chk, err)
	}
	if again, err := cachever.Read(repacked); err != nil || cachever.Compare(again, indexes) != cachever.Same || len(again) != len(indexes) {
		return fmt.Errorf("internal error: repacking changed the cache's reference tables (%v)", err)
	}
	names, err := cachever.Files(repacked)
	if err != nil {
		return err
	}
	fmt.Printf("zipping the cache (%d files)\n", len(names))
	tmpZip := filepath.Join(*out, "files", ".cache.zip.tmp")
	res, err := pack.ZipFiles(tmpZip, repacked, names)
	os.RemoveAll(repacked)
	if err != nil {
		os.Remove(tmpZip)
		return err
	}
	cachef, err := addFile(*out, manifest.Cache, tmpZip, ".zip", *baseURL)
	os.Remove(tmpZip)
	if err != nil {
		return err
	}
	cachef.Unpacked, cachef.Entries = res.Unpacked, res.Entries
	m.Files = []manifest.File{jf, cf, cachef}

	m.Cache = manifest.CacheInfo{Version: *version, Indexes: indexes}
	if prev != nil {
		if pc := prev.Find(manifest.Cache); pc != nil && pc.SHA256 == cachef.SHA256 {
			m.Cache.Version = prev.Cache.Version // same cache: players' caches are not seeded again
		}
	}
	if *cacheVersion > 0 {
		m.Cache.Version = *cacheVersion
	}
	if *setupExe != "" {
		sf, err := addFile(*out, "setup", *setupExe, ".exe", *baseURL)
		if err != nil {
			return err
		}
		m.Setup = &manifest.Setup{Version: stamp.Version, File: sf}
		// a copy under its own name for the website's download link
		if err := copyFile(*setupExe, filepath.Join(*out, filepath.Base(*setupExe))); err != nil {
			return err
		}
	}

	body, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	body = append(body, '\n')
	if _, err := manifest.Parse(body); err != nil {
		return fmt.Errorf("internal error, manifest does not parse: %v", err)
	}
	// manifest.sig signs this manifest and the previous one: uploaded before manifest.json, it never makes the
	// manifest still on the server fail
	sig := manifest.Sign(priv, body, prevSig)
	// signature first, manifest last: a half-written folder never has a new manifest with an old signature
	if err := os.WriteFile(filepath.Join(*out, "manifest.sig.tmp"), sig, 0o644); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(*out, "manifest.json.tmp"), body, 0o644); err != nil {
		return err
	}
	if err := os.Rename(filepath.Join(*out, "manifest.sig.tmp"), filepath.Join(*out, "manifest.sig")); err != nil {
		return err
	}
	if err := os.Rename(filepath.Join(*out, "manifest.json.tmp"), filepath.Join(*out, "manifest.json")); err != nil {
		return err
	}
	// files no release lists any more
	keep := map[string]bool{}
	for _, f := range m.Files {
		keep[filepath.Base(f.URL)] = true
	}
	if m.Setup != nil {
		keep[filepath.Base(m.Setup.URL)] = true
	}
	ents, _ := os.ReadDir(filepath.Join(*out, "files"))
	for _, e := range ents {
		if !keep[e.Name()] {
			os.Remove(filepath.Join(*out, "files", e.Name()))
			fmt.Println("removed old", "files/"+e.Name())
		}
	}

	fmt.Printf("\nrelease %d (%s), %s channel, app folder %s, server %s\n", m.Version, m.Label, m.Channel, m.App, m.Server)
	for _, f := range append(m.Files, setupFiles(m)...) {
		changed := ""
		if prev != nil {
			if p := prev.Find(f.ID); f.ID != "setup" && (p == nil || p.SHA256 != f.SHA256) {
				changed = "  (changed: players download it)"
			} else if f.ID == "setup" && (prev.Setup == nil || prev.Setup.SHA256 != f.SHA256) {
				changed = "  (changed: launchers update themselves)"
			}
		}
		fmt.Printf("  %-7s %9.1f MB  %s%s\n", f.ID, float64(f.Size)/(1<<20), f.URL, changed)
	}
	fmt.Printf("  cache version %d, %d indexes\n", m.Cache.Version, len(m.Cache.Indexes))
	if m.Setup != nil {
		strs, fv, err := winres.ReadVersion(mustRead(*setupExe))
		if err == nil {
			fmt.Printf("  setup version %d (file version %d.%d.%d.%d, %s)\n", m.Setup.Version, fv[0], fv[1], fv[2], fv[3], strs["FileDescription"])
		}
	}
	fmt.Printf("\nUpload everything in %s to the address the setup was built with: files/ first, then manifest.sig\n"+
		"(it also signs the manifest that is still online), then manifest.json. Then check what players get:\n"+
		"gadscape-release verify --url <manifest address> --pub <public key>\n", *out)
	return nil
}

func setupFiles(m *manifest.Manifest) []manifest.File {
	if m.Setup == nil {
		return nil
	}
	return []manifest.File{m.Setup.File}
}

func mustRead(p string) []byte {
	b, _ := os.ReadFile(p)
	return b
}
