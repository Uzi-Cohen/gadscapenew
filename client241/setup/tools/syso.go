package main

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"strconv"

	"gadscapesetup/internal/winres"
)

// syso (used by setup/build.sh): writes the setup exe's Windows resources (icon, manifest, version information).
func cmdSyso(args []string) error {
	fs := flag.NewFlagSet("syso", flag.ExitOnError)
	ico := fs.String("ico", "", "icon file")
	man := fs.String("manifest", "", "application manifest")
	version := fs.Int("version", 0, "setup version")
	title := fs.String("title", "GadScape", "product name")
	exe := fs.String("exe", "GadScape-Setup.exe", "original file name")
	out := fs.String("out", "", "output .syso")
	fs.Parse(args)
	if *ico == "" || *man == "" || *out == "" || *version <= 0 || *version > 65535 {
		return errors.New("syso needs --ico, --manifest, --out and --version 1..65535")
	}
	icoData, err := os.ReadFile(*ico)
	if err != nil {
		return err
	}
	manData, err := os.ReadFile(*man)
	if err != nil {
		return err
	}
	v := strconv.Itoa(*version)
	vi := winres.VersionInfo{
		FileVersion:    [4]uint16{1, uint16(*version), 0, 0},
		ProductVersion: [4]uint16{1, uint16(*version), 0, 0},
		Strings: map[string]string{
			"CompanyName":      "GadScape",
			"FileDescription":  *title + " Setup",
			"FileVersion":      "1." + v + ".0.0",
			"InternalName":     "gadscape-setup",
			"LegalCopyright":   "GadScape",
			"OriginalFilename": *exe,
			"ProductName":      *title,
			"ProductVersion":   "1." + v,
		},
	}
	b, err := winres.Build(icoData, manData, vi)
	if err != nil {
		return err
	}
	if err := os.WriteFile(*out, b, 0o644); err != nil {
		return err
	}
	fmt.Printf("wrote %s (%d bytes)\n", *out, len(b))
	return nil
}
