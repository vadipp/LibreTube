package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func indexedMP4() []byte {
	box := func(kind string, body []byte) []byte {
		b := make([]byte, 8+len(body))
		binary.BigEndian.PutUint32(b, uint32(len(b)))
		copy(b[4:], kind)
		copy(b[8:], body)
		return b
	}
	s := make([]byte, 36)
	binary.BigEndian.PutUint32(s[8:], 1000)
	binary.BigEndian.PutUint16(s[22:], 1)
	binary.BigEndian.PutUint32(s[24:], 2000)
	return append(append(box("ftyp", make([]byte, 8)), box("moov", make([]byte, 8))...), box("sidx", s)...)
}
func fixtureInfo() extraction {
	media := func(id int) string {
		return "https://rr1.googlevideo.com/videoplayback?itag=" + strconv.Itoa(id) + "&expire=" + strconv.FormatInt(time.Now().Add(time.Hour).Unix(), 10) + "&sig=fixture"
	}
	return extraction{Duration: 60, Formats: []format{{URL: media(137), ID: "137", Ext: "mp4", Protocol: "https", VCodec: "avc1.640028", ACodec: "none", Height: 1080, Width: 1920, FPS: 30, TBR: 3000, Size: 24000000}, {URL: media(140), ID: "140", Ext: "m4a", Protocol: "https", VCodec: "none", ACodec: "mp4a.40.2", ABR: 128, Language: "en"}}}
}
func fixtureOriginal() document {
	return document{"title": rawJSON("Example"), "duration": rawJSON(60), "proxyUrl": rawJSON("https://proxy.example.org"), "relatedStreams": rawJSON([]string{"fixture"}), "videoStreams": rawJSON([]map[string]any{{"url": "https://proxy.example.org/videoplayback?itag=18", "videoOnly": false, "height": 360, "format": "MPEG_4", "quality": "360p"}}), "audioStreams": rawJSON([]any{})}
}
func fixtureAdapter() *adapter {
	a := newAdapter(config{MaxHeight: 1080, Entries: 16, Heap: 96, TTL: time.Minute, RequestTimeout: time.Second, ExtractTimeout: time.Second})
	a.backend = func(context.Context, string) (document, error) { return fixtureOriginal(), nil }
	a.extract = func(context.Context, string) (extraction, error) { return fixtureInfo(), nil }
	a.probe = func(context.Context, string, string, int64, int64) ([]byte, error) { return indexedMP4(), nil }
	return a
}
func TestMP4Ranges(t *testing.T) {
	b := indexedMP4()
	r, e := mp4Ranges(b)
	if e != nil || r != (ranges{0, 31, 32, 75}) {
		t.Fatalf("ranges %v %v", r, e)
	}
	for _, bad := range [][]byte{b[:60], []byte("<html>login</html>"), append([]byte(nil), b...)} {
		if len(bad) == 76 {
			binary.BigEndian.PutUint32(bad[48:], 0)
		}
		if _, e := mp4Ranges(bad); e == nil {
			t.Fatal("accepted invalid index")
		}
	}
}
func TestProxyURL(t *testing.T) {
	raw, e := proxyURL(fixtureInfo().Formats[0].URL, "https://proxy.example.org")
	if e != nil {
		t.Fatal(e)
	}
	u, _ := url.Parse(raw)
	if u.Host != "proxy.example.org" || u.Query().Get("host") != "rr1.googlevideo.com" || u.Query().Get("sig") != "fixture" {
		t.Fatal("lost proxy metadata")
	}
	for _, bad := range []string{"http://rr1.googlevideo.com/videoplayback", "https://googlevideo.com.evil.example/videoplayback", "https://127.0.0.1/videoplayback", "https://rr1.googlevideo.com/other"} {
		if _, e := proxyURL(bad, "https://proxy.example.org"); e == nil {
			t.Fatal("accepted invalid media URL")
		}
	}
}
func TestEnrichedMetadataAndDuplicateQuality(t *testing.T) {
	a := fixtureAdapter()
	a.extract = func(context.Context, string) (extraction, error) {
		x := fixtureInfo()
		f := x.Formats[0]
		f.Height = 360
		f.Width = 640
		x.Formats = append(x.Formats, f)
		return x, nil
	}
	r, e := a.streams(context.Background(), "ABCDEFGHIJK")
	if e != nil || r.Kind != "enriched" {
		t.Fatalf("%s %v", r.Kind, e)
	}
	var videos []stream
	var audio []stream
	_ = json.Unmarshal(r.Data["videoStreams"], &videos)
	_ = json.Unmarshal(r.Data["audioStreams"], &audio)
	if len(videos) != 2 || videos[0].Height != 1080 || videos[0].IndexEnd != 75 || len(audio) != 1 || audio[0].Mime != "audio/mp4" {
		t.Fatal("incorrect mapped representations")
	}
	if !reflect.DeepEqual(r.Data["relatedStreams"], fixtureOriginal()["relatedStreams"]) {
		t.Fatal("lost original metadata")
	}
	if videos[1].VideoOnly {
		t.Fatal("combined fallback removed")
	}
}
func TestCoalescingAndExpiry(t *testing.T) {
	a := fixtureAdapter()
	a.Config.TTL = 10 * time.Millisecond
	var calls atomic.Int32
	a.extract = func(context.Context, string) (extraction, error) {
		calls.Add(1)
		time.Sleep(30 * time.Millisecond)
		return fixtureInfo(), nil
	}
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if r, e := a.streams(context.Background(), "ABCDEFGHIJK"); e != nil || r.Kind != "enriched" {
				t.Error("concurrent extraction failed")
			}
		}()
	}
	wg.Wait()
	a.streams(context.Background(), "ABCDEFGHIJK")
	if calls.Load() != 1 {
		t.Fatal("not coalesced/cached")
	}
	time.Sleep(20 * time.Millisecond)
	a.streams(context.Background(), "ABCDEFGHIJK")
	if calls.Load() != 2 {
		t.Fatal("stale cache retained")
	}
}
func TestFallbacks(t *testing.T) {
	for _, kind := range []string{"failure", "index", "audio", "expired", "live", "qhash"} {
		t.Run(kind, func(t *testing.T) {
			a := fixtureAdapter()
			a.extract = func(context.Context, string) (extraction, error) {
				x := fixtureInfo()
				switch kind {
				case "failure":
					return x, errors.New("private detail")
				case "audio":
					x.Formats = x.Formats[:1]
				case "expired":
					for i := range x.Formats {
						x.Formats[i].URL = "https://rr1.googlevideo.com/videoplayback?expire=1&itag=137"
					}
				case "live":
					x.Live = true
				}
				return x, nil
			}
			if kind == "index" {
				a.probe = func(context.Context, string, string, int64, int64) ([]byte, error) { return []byte("invalid"), nil }
			}
			if kind == "qhash" {
				a.backend = func(context.Context, string) (document, error) {
					d := fixtureOriginal()
					d["videoStreams"] = rawJSON([]map[string]any{{"url": "https://proxy.example.org/videoplayback?qhash=fixture"}})
					return d, nil
				}
			}
			original, _ := a.backend(context.Background(), "ABCDEFGHIJK")
			r, e := a.streams(context.Background(), "ABCDEFGHIJK")
			if e != nil || r.Kind != "fallback" || !reflect.DeepEqual(r.Data, original) {
				t.Fatal("fallback changed original")
			}
		})
	}
}
func TestOneExtraction(t *testing.T) {
	a := fixtureAdapter()
	started, release := make(chan struct{}), make(chan struct{})
	a.extract = func(context.Context, string) (extraction, error) {
		close(started)
		<-release
		return fixtureInfo(), nil
	}
	done := make(chan struct{})
	go func() { defer close(done); a.streams(context.Background(), "ABCDEFGHIJK") }()
	<-started
	r, e := a.streams(context.Background(), "LMNOPQRSTUV")
	close(release)
	<-done
	if e != nil || r.Kind != "fallback" {
		t.Fatal("extra extractor started")
	}
}
func TestCompatibleFormats(t *testing.T) {
	x := fixtureInfo()
	for _, kind := range []string{"large", "codec", "drm"} {
		f := x.Formats[0]
		switch kind {
		case "large":
			f.Height = 2160
		case "codec":
			f.VCodec = "av01.0"
		case "drm":
			f.DRM = true
		}
		x.Formats = append(x.Formats, f)
	}
	v, a, e := selectFormats(x, 1080)
	if e != nil || len(v) != 1 || len(a) != 1 {
		t.Fatal("invalid format filtering")
	}
}
func TestHTTPRoutes(t *testing.T) {
	a := fixtureAdapter()
	a.backend = func(context.Context, string) (document, error) { return nil, errors.New("private detail") }
	for _, test := range []struct {
		method, path string
		code         int
	}{{"GET", "/healthz", 200}, {"GET", "/streams/ABCDEFGHIJK", 404}, {"GET", "/login", 404}, {"GET", "/downloads/streams/short", 404}, {"GET", "/downloads/streams/%2Fwatch?v=ABCDEFGHIJK", 404}, {"GET", "/downloads/streams/ABCDEFGHIJK?url=anything", 404}, {"POST", "/downloads/streams/ABCDEFGHIJK", 405}, {"OPTIONS", "/downloads/streams/ABCDEFGHIJK", 200}, {"GET", "/downloads/streams/ABCDEFGHIJK", 503}} {
		w := httptest.NewRecorder()
		a.ServeHTTP(w, httptest.NewRequest(test.method, test.path, nil))
		if w.Code != test.code || strings.Contains(w.Body.String(), "private detail") {
			t.Fatalf("route %s: %d", test.path, w.Code)
		}
		if w.Header().Get("Access-Control-Allow-Origin") != "*" {
			t.Fatal("missing CORS")
		}
	}
}
func TestBackendAndRangeBounds(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/streams/ABCDEFGHIJK":
			if r.Host != "api.example.org" {
				t.Error("incorrect backend host")
			}
			json.NewEncoder(w).Encode(fixtureOriginal())
		case "/redirect":
			w.Header().Set("Location", "/valid")
			w.WriteHeader(302)
		case "/login":
			w.Header().Set("Content-Type", "text/html")
			w.Write([]byte("login"))
		default:
			w.Header().Set("Content-Type", "video/mp4")
			w.Header().Set("Content-Range", "bytes 0-7/8")
			w.WriteHeader(206)
			w.Write([]byte("12345678"))
		}
	}))
	defer s.Close()
	a := fixtureAdapter()
	a.Config.Backend = s.URL
	a.Config.BackendHost = "api.example.org"
	if _, e := a.getBackend(context.Background(), "ABCDEFGHIJK"); e != nil {
		t.Fatal(e)
	}
	if b, e := readRange(context.Background(), s.URL+"/valid", "", 0, 7); e != nil || len(b) != 8 {
		t.Fatal("valid range rejected")
	}
	for _, path := range []string{"/valid", "/redirect", "/login"} {
		if _, e := readRange(context.Background(), s.URL+path, "", 0, 3); e == nil {
			t.Fatal("unsafe/oversize range accepted")
		}
	}
}
func TestExtractorTimeout(t *testing.T) {
	dir := t.TempDir()
	exe := filepath.Join(dir, "sleep.sh")
	if e := os.WriteFile(exe, []byte("#!/bin/sh\nsleep 60\n"), 0700); e != nil {
		t.Fatal(e)
	}
	a := fixtureAdapter()
	a.Config.Extractor = exe
	a.Config.ExtractTimeout = 50 * time.Millisecond
	started := time.Now()
	if _, e := a.runExtractor(context.Background(), "ABCDEFGHIJK"); e == nil {
		t.Fatal("timeout missing")
	}
	if time.Since(started) > 3*time.Second {
		t.Fatal("child processes not killed")
	}
	a.Config.Extractor = filepath.Join(dir, "missing")
	if _, e := a.runExtractor(context.Background(), "ABCDEFGHIJK"); e == nil {
		t.Fatal("missing executable accepted")
	}
}
