// Package pack writes deterministic zip files: the same input files always give the same bytes (sorted names,
// fixed time stamps and permissions), so a release whose game cache did not change keeps the same sha256 and
// players do not download it again.
package pack

import (
	"archive/zip"
	"compress/flate"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// Stamp is the time every entry gets.
var Stamp = time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)

// Result describes a written zip.
type Result struct {
	Size     int64
	SHA256   string
	Unpacked int64
	Entries  int
}

// ZipFiles writes the named files of dir (flat, no folders) into out, deflated, in name order.
func ZipFiles(out, dir string, names []string) (Result, error) {
	names = append([]string{}, names...)
	sort.Strings(names)
	f, err := os.Create(out)
	if err != nil {
		return Result{}, err
	}
	zw := zip.NewWriter(f)
	zw.RegisterCompressor(zip.Deflate, func(w io.Writer) (io.WriteCloser, error) {
		return flate.NewWriter(w, flate.DefaultCompression)
	})
	var res Result
	for _, n := range names {
		src, err := os.Open(filepath.Join(dir, n))
		if err != nil {
			f.Close()
			return Result{}, err
		}
		h := &zip.FileHeader{Name: n, Method: zip.Deflate, Modified: Stamp}
		h.SetMode(0o644)
		w, err := zw.CreateHeader(h)
		if err == nil {
			var k int64
			k, err = io.Copy(w, src)
			res.Unpacked += k
		}
		src.Close()
		if err != nil {
			f.Close()
			return Result{}, err
		}
		res.Entries++
	}
	if err := zw.Close(); err != nil {
		f.Close()
		return Result{}, err
	}
	if err := f.Close(); err != nil {
		return Result{}, err
	}
	sum, size, err := HashFile(out)
	res.SHA256, res.Size = sum, size
	return res, err
}

// HashFile is the sha256 (lower-case hex) and size of a file.
func HashFile(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}
