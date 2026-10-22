package robust

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"testing"
	"time"
)

var errBusy = errors.New("busy")

func TestRetryUntilFree(t *testing.T) {
	defer func(e func(error) bool, s func(time.Duration)) { isEphemeral, sleep = e, s }(isEphemeral, sleep)
	isEphemeral = func(err error) bool { return errors.Is(err, errBusy) }
	var slept time.Duration
	sleep = func(d time.Duration) { slept += d }
	tries := 0
	err := retry(func() error {
		if tries++; tries < 4 {
			return errBusy
		}
		return nil
	})
	if err != nil || tries != 4 || slept == 0 {
		t.Errorf("err %v after %d tries, slept %v", err, tries, slept)
	}
	// other errors are not retried
	tries = 0
	if err := retry(func() error { tries++; return fs.ErrNotExist }); !errors.Is(err, fs.ErrNotExist) || tries != 1 {
		t.Errorf("not-exist retried: %d tries, %v", tries, err)
	}
	// a file that stays busy: gives up after Timeout
	defer func(old time.Duration) { Timeout = old }(Timeout)
	Timeout = 50 * time.Millisecond
	sleep = time.Sleep
	start := time.Now()
	if err := retry(func() error { return errBusy }); !errors.Is(err, errBusy) || time.Since(start) < Timeout {
		t.Errorf("stayed busy: %v after %v", err, time.Since(start))
	}
}

func TestCalls(t *testing.T) {
	d := t.TempDir()
	a, b := filepath.Join(d, "a"), filepath.Join(d, "b")
	os.MkdirAll(filepath.Join(a, "x"), 0o755)
	if err := Rename(a, b); err != nil {
		t.Fatal(err)
	}
	if err := RemoveAll(b); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(a, nil, 0o644)
	if err := Remove(a); err != nil {
		t.Fatal(err)
	}
	if err := Remove(a); !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("removing a missing file: %v", err)
	}
}
