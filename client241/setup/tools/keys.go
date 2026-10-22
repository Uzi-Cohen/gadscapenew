package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gadscapesetup/internal/manifest"
)

const (
	privPrefix = "private-key="
	pubPrefix  = "public-key="
)

func cmdKeygen(args []string) error {
	fs := flag.NewFlagSet("keygen", flag.ExitOnError)
	out := fs.String("out", "", "folder for the key files")
	name := fs.String("name", "gadscape-release", "file name (without .key/.pub)")
	fs.Parse(args)
	if *out == "" {
		return errors.New("keygen needs --out DIR")
	}
	if err := os.MkdirAll(*out, 0o700); err != nil {
		return err
	}
	privPath := filepath.Join(*out, *name+".key")
	pubPath := filepath.Join(*out, *name+".pub")
	for _, p := range []string{privPath, pubPath} {
		if _, err := os.Stat(p); err == nil {
			return fmt.Errorf("%s already exists; not overwriting a key (every installed setup trusts the old one)", p)
		}
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	privText := "# GadScape release signing key - PRIVATE.\n" +
		"# Anyone with this file can publish releases that every installed GadScape accepts. Keep it secret and\n" +
		"# backed up (losing it means a new setup for everyone); never upload it, never commit it to git.\n" +
		privPrefix + base64.StdEncoding.EncodeToString(priv.Seed()) + "\n"
	pubText := "# GadScape release public key: client241/build.sh --setup --setup-key " + pubPath + "\n" +
		pubPrefix + base64.StdEncoding.EncodeToString(pub) + "\n"
	f, err := os.OpenFile(privPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.WriteString(privText); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.WriteFile(pubPath, []byte(pubText), 0o644); err != nil {
		return err
	}
	fmt.Printf("private key: %s  (keep secret)\npublic key:  %s\n             %s\n", privPath, pubPath, base64.StdEncoding.EncodeToString(pub))
	return nil
}

// keyLine returns the value of "prefix..." in a key file, or a bare base64 line.
func keyLine(path, prefix string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	var bare string
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if v, ok := strings.CutPrefix(line, prefix); ok {
			return strings.TrimSpace(v), nil
		}
		if !strings.Contains(strings.TrimRight(line, "="), "=") { // base64 only has '=' at the end
			bare = line
		}
	}
	if bare != "" {
		return bare, nil
	}
	return "", fmt.Errorf("%s has no %s line", path, prefix)
}

func readPrivateKey(path string) (ed25519.PrivateKey, error) {
	v, err := keyLine(path, privPrefix)
	if err != nil {
		return nil, err
	}
	seed, err := base64.StdEncoding.DecodeString(v)
	if err != nil || len(seed) != ed25519.SeedSize {
		return nil, fmt.Errorf("%s is not a GadScape private key file", path)
	}
	return ed25519.NewKeyFromSeed(seed), nil
}

func readPublicKey(path string) (ed25519.PublicKey, error) {
	v, err := keyLine(path, pubPrefix)
	if err != nil {
		return nil, err
	}
	return manifest.ParsePublicKey(v)
}

func cmdPubkey(args []string) error {
	fs := flag.NewFlagSet("pubkey", flag.ExitOnError)
	key := fs.String("key", "", "private key file")
	fs.Parse(args)
	priv, err := readPrivateKey(*key)
	if err != nil {
		return err
	}
	fmt.Println(base64.StdEncoding.EncodeToString(priv.Public().(ed25519.PublicKey)))
	return nil
}
