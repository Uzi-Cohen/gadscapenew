//go:build !windows

package robust

// ephemeral: other systems let programs rename and delete open files.
func ephemeral(error) bool { return false }
