package main

import (
	"errors"
	"flag"
	"fmt"
	"os"

	"gadscapesetup/internal/manifest"
	"gadscapesetup/internal/winres"
)

// inspect: what a setup exe was built with (version, channel, install folder, public key) and its version
// information.
func cmdInspect(args []string) error {
	fs := flag.NewFlagSet("inspect", flag.ExitOnError)
	exe := fs.String("setup-exe", "", "setup exe")
	fs.Parse(args)
	if *exe == "" {
		return errors.New("inspect needs --setup-exe FILE")
	}
	b, err := os.ReadFile(*exe)
	if err != nil {
		return err
	}
	st, err := manifest.FindStamp(b)
	if err != nil {
		return err
	}
	key := st.Key
	if key == "" {
		key = "NONE (this setup refuses to install)"
	}
	fmt.Printf("setup version     %d, %s channel, installs to %%LOCALAPPDATA%%\\%s\npublic key        %s\n", st.Version, st.Channel, st.App, key)
	strs, fv, err := winres.ReadVersion(b)
	if err != nil {
		return fmt.Errorf("version information: %v", err)
	}
	fmt.Printf("file version      %d.%d.%d.%d\n", fv[0], fv[1], fv[2], fv[3])
	for _, k := range []string{"ProductName", "FileDescription", "CompanyName", "OriginalFilename", "ProductVersion"} {
		fmt.Printf("%-17s %s\n", k, strs[k])
	}
	return nil
}
