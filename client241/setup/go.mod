module gadscapesetup

go 1.21

require gadscapeclient v0.0.0

// the shared Windows/launcher code lives with the one-file exe (client241/exe/kit)
replace gadscapeclient => ../exe
