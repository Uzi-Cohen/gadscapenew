// gadscape-release: makes and checks GadScape releases for GadScape-Setup.exe (runs on Linux, macOS or Windows).
//
//	gadscape-release keygen --out DIR                      make the release signing key pair (once)
//	gadscape-release pubkey --key FILE                     print the public key of a private key file
//	gadscape-release build  --dist DIR --jre-zip FILE --cache-dir DIR --setup-exe FILE --key FILE --out DIR
//	gadscape-release verify --dir DIR --pub FILE           check a release folder (or --url URL: the uploaded one)
//	gadscape-release inspect --setup-exe FILE              show a setup exe's version, channel and key
//	gadscape-release serve  --dir DIR [--addr 127.0.0.1:8090]   serve a release folder locally (testing)
//	gadscape-release cachecheck --dir DIR                  check a game cache the way the 241 client does
//
// See client241/README.md ("Setup and updates") for the whole procedure.
package main

import (
	"fmt"
	"os"
)

func usage() {
	fmt.Fprint(os.Stderr, `gadscape-release: make and check GadScape releases for GadScape-Setup.exe

  keygen --out DIR [--name gadscape-release]
        Make the release signing key pair: DIR/<name>.key (PRIVATE: keep it secret and backed up, never upload or
        commit it) and DIR/<name>.pub (public: goes into the setup with build.sh --setup --setup-key).
  pubkey --key FILE
        Print the public key (base64) of a private key file.
  build --dist DIR --jre-zip FILE --cache-dir DIR --key FILE --out DIR [--setup-exe FILE] [options]
        Make (or update) a release folder: files/ (Java, client and cache zips, setup exe), manifest.json and
        manifest.sig, and a copy of the setup exe for your download link.
          --dist DIR          client241/build.sh's --out folder (GadScape.zip, channel-<channel>.properties)
          --client-zip FILE   the client zip instead of the one in --dist
          --channel NAME      release (default) or test
          --jre-zip FILE      Windows x64 Java 17+ JRE zip (one top folder with bin/javaw.exe)
          --cache-dir DIR     the game cache folder (main_file_cache.dat2 + .idx files; other files are left out)
          --setup-exe FILE    GadScape-Setup.exe from build.sh --setup (signed with the same key pair)
          --key FILE          the private key from keygen
          --out DIR           the release folder; a release already there is the previous one
          --version N         release number (default: the time, yyyymmddHHMM); must be higher than the previous
          --label TEXT        shown in Apps & features (default: the date)
          --server HOST       server address for new installs (default: the channel's default server)
          --cache-version N   default: the previous one if the cache did not change, else the release number
          --base-url URL      write absolute file addresses under URL (default: relative to manifest.json)
  verify (--dir DIR | --url URL) (--pub FILE | --key FILE) [--quick]
        Check the signature, every file's size and sha256, the zips (safe names, sizes), the cache tables and the
        setup exe's key and version. --url checks an uploaded release over the internet (read only).
  inspect --setup-exe FILE
        Show what a setup exe was built with: version, channel, install folder, public key, version information.
  serve --dir DIR [--addr 127.0.0.1:8090] [--log FILE] [--rate BYTES_PER_SECOND]
        Serve a release folder over plain http for local tests (setups built with --setup-local-http).
  cachecheck --dir DIR [--quiet]
        Check a game cache folder the way the 241 client does when it starts: every group the reference tables
        list must have their CRC and version, or the client downloads it from the game server again.
`)
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	args := os.Args[2:]
	switch os.Args[1] {
	case "keygen":
		err = cmdKeygen(args)
	case "pubkey":
		err = cmdPubkey(args)
	case "build":
		err = cmdBuild(args)
	case "verify":
		err = cmdVerify(args)
	case "serve":
		err = cmdServe(args)
	case "inspect":
		err = cmdInspect(args)
	case "cachecheck":
		err = cmdCacheCheck(args)
	case "syso": // for setup/build.sh
		err = cmdSyso(args)
	case "-h", "--help", "help":
		usage()
		return
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}
