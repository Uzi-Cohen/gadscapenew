package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"time"
)

// serve: a static file server for a release folder, for local tests only (plain http, logs every request with its
// Range header, optional speed limit so the setup's window can be watched).
func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	dir := fs.String("dir", "", "release folder")
	addr := fs.String("addr", "127.0.0.1:8090", "listen address")
	logPath := fs.String("log", "", "request log file (default: standard output)")
	rate := fs.Int64("rate", 0, "bytes per second per download (0: no limit)")
	fs.Parse(args)
	if *dir == "" {
		return fmt.Errorf("serve needs --dir")
	}
	var w io.Writer = os.Stdout
	if *logPath != "" {
		f, err := os.OpenFile(*logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
		if err != nil {
			return err
		}
		defer f.Close()
		w = f
	}
	lg := log.New(w, "", log.LstdFlags|log.Lmicroseconds)
	files := http.FileServer(http.Dir(*dir))
	h := http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		cw := &countingWriter{ResponseWriter: rw, rate: *rate, start: time.Now()}
		files.ServeHTTP(cw, r)
		lg.Printf("%s %s range=%q status=%d bytes=%d", r.Method, r.URL.RequestURI(), r.Header.Get("Range"), cw.status, cw.n)
	})
	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		return err
	}
	lg.Printf("serving %s on http://%s/", *dir, ln.Addr())
	fmt.Printf("serving %s on http://%s/\n", *dir, ln.Addr())
	return http.Serve(ln, h)
}

type countingWriter struct {
	http.ResponseWriter
	status int
	n      int64
	rate   int64
	start  time.Time
}

func (c *countingWriter) WriteHeader(code int) {
	c.status = code
	c.ResponseWriter.WriteHeader(code)
}

func (c *countingWriter) Write(b []byte) (int, error) {
	if c.status == 0 {
		c.status = 200
	}
	if c.rate <= 0 {
		k, err := c.ResponseWriter.Write(b)
		c.n += int64(k)
		return k, err
	}
	written := 0
	for written < len(b) {
		chunk := len(b) - written
		if chunk > 32<<10 {
			chunk = 32 << 10
		}
		k, err := c.ResponseWriter.Write(b[written : written+chunk])
		written += k
		c.n += int64(k)
		if err != nil {
			return written, err
		}
		if f, ok := c.ResponseWriter.(http.Flusher); ok {
			f.Flush()
		}
		// sleep until the average speed is back under the limit
		due := time.Duration(float64(c.n) / float64(c.rate) * float64(time.Second))
		if d := due - time.Since(c.start); d > 0 {
			time.Sleep(d)
		}
	}
	return written, nil
}
