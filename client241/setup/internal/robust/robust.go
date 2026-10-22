// Package robust renames and deletes files the way Windows needs it: a file or folder that another program has
// open for a moment (Windows Defender scanning a java.exe or zip that was just written, the search indexer, a
// program that has only just exited) cannot be renamed or deleted until it lets go, so these calls retry for a few
// seconds on "access denied" and "sharing violation" (as Go's own toolchain does, cmd/internal/robustio).
package robust

import (
	"os"
	"time"
)

// Timeout is how long a call keeps retrying.
var Timeout = 10 * time.Second

var (
	sleep       = time.Sleep
	isEphemeral = ephemeral // the errors worth retrying (see robust_windows.go)
)

func retry(op func() error) error {
	start := time.Now()
	delay := 20 * time.Millisecond
	for {
		err := op()
		if err == nil || !isEphemeral(err) || time.Since(start) >= Timeout {
			return err
		}
		sleep(delay)
		if delay < 640*time.Millisecond {
			delay *= 2
		}
	}
}

// Rename is os.Rename, retried while the file is busy.
func Rename(oldpath, newpath string) error {
	return retry(func() error { return os.Rename(oldpath, newpath) })
}

// RemoveAll is os.RemoveAll, retried while a file in it is busy.
func RemoveAll(path string) error {
	return retry(func() error { return os.RemoveAll(path) })
}

// Remove is os.Remove, retried while the file is busy.
func Remove(path string) error {
	return retry(func() error { return os.Remove(path) })
}
