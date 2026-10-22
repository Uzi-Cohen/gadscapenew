package main

import (
	"errors"
	"flag"
	"fmt"

	"gadscapesetup/internal/cachever"
)

// cachecheck: checks a game cache folder the way the 241 client does when it opens it.
func cmdCacheCheck(args []string) error {
	fs := flag.NewFlagSet("cachecheck", flag.ExitOnError)
	dir := fs.String("dir", "", "game cache folder")
	quiet := fs.Bool("quiet", false, "only the total")
	fs.Parse(args)
	if *dir == "" {
		return errors.New("cachecheck needs --dir")
	}
	res, err := cachever.Check(*dir)
	if err != nil {
		return err
	}
	if !*quiet {
		for _, a := range res.Archives {
			fmt.Printf("archive %3d: %6d groups, %6d ok, %6d invalid, %6d missing\n", a.ID, a.Groups, a.OK, a.Invalid, a.Missing)
		}
	}
	fmt.Printf("%d groups: %d ok, %d invalid (the client downloads them again), %d missing; %d reference tables with extra bytes\n",
		res.Groups, res.OK, res.Invalid, res.Missing, res.TableTrailers)
	if res.Invalid > 0 || res.TableTrailers > 0 {
		return fmt.Errorf("%d groups and %d reference tables would be downloaded again by the 241 client (gadscape-release build repacks the cache it ships)", res.Invalid, res.TableTrailers)
	}
	return nil
}
