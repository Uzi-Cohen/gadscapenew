module gadscapeinstaller

go 1.21

require gadscapeclient v0.0.0

// the shared Windows code (the loading window, message boxes, the log) lives with the one-file exe
replace gadscapeclient => ../exe
