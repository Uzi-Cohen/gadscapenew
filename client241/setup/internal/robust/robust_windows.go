package robust

import (
	"errors"
	"syscall"
)

// ephemeral: ERROR_ACCESS_DENIED (a file in use, or one being deleted), ERROR_SHARING_VIOLATION,
// ERROR_LOCK_VIOLATION.
func ephemeral(err error) bool {
	var errno syscall.Errno
	if errors.As(err, &errno) {
		switch errno {
		case 5, 32, 33:
			return true
		}
	}
	return false
}
