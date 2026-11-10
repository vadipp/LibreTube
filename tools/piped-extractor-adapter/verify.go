package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"time"
)

// verify runs only when explicitly requested inside a resource-limited test container.
// Reports contain dimensions and timings, never signed URLs or credentials.
func verify(id string) error {
	if !route.MatchString("/downloads/streams/" + id) {
		return errors.New("invalid video ID")
	}
	c, e := settings()
	if e != nil {
		return e
	}
	client := http.Client{Timeout: 125 * time.Second}
	started := time.Now()
	response, e := client.Get("http://127.0.0.1:" + c.Port + "/downloads/streams/" + id)
	if e != nil {
		return errors.New("adapter request failed")
	}
	defer response.Body.Close()
	var data struct {
		Video []stream `json:"videoStreams"`
		Audio []stream `json:"audioStreams"`
	}
	if response.StatusCode != 200 || response.Header.Get("X-Extractor-Source") != "enriched" || json.NewDecoder(response.Body).Decode(&data) != nil {
		return errors.New("HD extraction unavailable")
	}
	cold := time.Since(started)
	max := 0
	var highest stream
	keys := map[string]bool{}
	for _, s := range data.Video {
		key := s.Format + ":" + s.Quality
		if keys[key] {
			return errors.New("ambiguous download representation")
		}
		keys[key] = true
		if s.Height > max {
			max = s.Height
			highest = s
		}
	}
	if max < 720 || len(data.Audio) == 0 {
		return errors.New("missing HD/audio")
	}
	for _, s := range []stream{highest, data.Audio[0]} {
		if s.IndexEnd <= s.IndexStart {
			return errors.New("missing indexes")
		}
		u, e := url.Parse(s.URL)
		if e != nil {
			return errors.New("invalid media URL")
		}
		if c.Probe != "" {
			p, _ := url.Parse(c.Probe)
			u.Scheme = p.Scheme
			u.Host = p.Host
		}
		if b, e := readRange(context.Background(), u.String(), c.ProbeHost, 65536, 65599); e != nil || len(b) != 64 {
			return errors.New("media range verification failed")
		}
	}
	warmStart := time.Now()
	cached, e := client.Get("http://127.0.0.1:" + c.Port + "/downloads/streams/" + id)
	if e != nil {
		return errors.New("cache request failed")
	}
	cached.Body.Close()
	if cached.Header.Get("X-Extractor-Source") != "enriched" {
		return errors.New("cache response failed")
	}
	fmt.Fprintf(os.Stdout, "{\"height\":%d,\"audioTracks\":%d,\"coldSeconds\":%.3f,\"warmSeconds\":%.3f,\"mediaRangesVerified\":true}\n", max, len(data.Audio), cold.Seconds(), time.Since(warmStart).Seconds())
	return nil
}
