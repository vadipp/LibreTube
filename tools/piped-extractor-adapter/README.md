# Piped download extractor adapter

A separate Go service exposes a Piped-compatible `/downloads/streams/{videoId}`
endpoint enriched with yt-dlp media formats. Normal `/streams/{videoId}` playback
requests remain on the original backend and are rejected by the adapter.
Existing Piped supplies video metadata and continues to own
accounts, browsing, subscriptions and feed updates. Media bytes still pass through
the existing Piped proxy. The adapter never serves media or receives account
credentials. Keep it behind the existing authenticated ingress.

LibreTube must opt into this extension only in its download dialog, download
service and playlist download queue. Instances without the extension return
404 and the app falls back to ordinary Piped streams. Download metadata uses
a separate client with longer timeouts; playback keeps its existing client.
The web frontend and older apps continue using ordinary streams, including
their existing download behavior.

The initial implementation supports non-live AVC/H.264 MP4 video up to 1080p with
one AAC/M4A audio track. It reads bounded samples through the media proxy and
parses the actual MP4 initialization and `sidx` index ranges required by Piped's
DASH clients. Unsupported or rejected formats are omitted. If no valid HD/audio
pair remains, the original response is returned intact. Original combined streams
are retained alongside HD representations. DRM, WebM, AV1, live streams and
proxy deployments requiring a `qhash` signature use the original backend path.

Only exact 11-character video ID paths accept GET and OPTIONS. CORS matches
Piped's existing wildcard policy. No arbitrary extraction URL is accepted.
YouTube signed URLs and raw extractor errors are never logged. HTTP responses
use `no-store`; signed metadata is held only in a bounded in-memory cache.

One extraction runs at a time. Concurrent requests for the same ID share work;
other IDs temporarily fall back. Up to four response builds are allowed, then
503 lets nginx use its original upstream. Cache lifetime is at most ten minutes
and always ends at least two minutes before extracted URL expiration. Failures
are cached for thirty seconds. Limits are configurable within bounded ranges.
There is no media cache. Cold extraction can take tens of seconds; cache hits
avoid extraction. Container restarts discard the metadata cache.

## Validation

```sh
go test -race ./...
```

Unit/HTTP tests cover index parsing, proxy-only URL rewriting, compatibility,
fallback, cache expiry, same-ID request coalescing, concurrency limits, CORS,
bounded responses and extractor timeout/process-group termination.

The image includes an explicit live probe: run its executable with
`verify VIDEO_ID` inside an isolated container with resource limits. It checks
HD metadata, audio, proxy byte ranges and a cache hit, printing only dimensions
and timings. It is not part of ordinary unit tests.

## Packaging and routing

The Dockerfile compiles the Go server and native health check, pins its base
images, and installs yt-dlp 2026.10.26 with its pinned default dependencies,
including the EJS challenge solver. Python modules are installed in the image;
there is no self-extracting executable or RAM-backed Python installation.
Node is retained only as the third-party JavaScript challenge runtime, with a
bounded heap (96 MiB by default). No Python, Go or npm packages are installed
on the host. Image assembly is separate from deployment.

Build on a suitable build host:

```sh
docker build --platform linux/amd64 -t piped-extractor-adapter:prepared .
```

`deploy/compose.example.yml` prepares an unpublished, non-root service with a
read-only root, 384 MiB memory, zero swap, half a CPU and 64 PIDs. A 16 MiB
non-executable tmpfs holds disposable cache files. Replace the example network
and endpoint values before any deployment. Health checks establish process
liveness, not successful extraction.

`deploy/nginx.example.conf` routes only `/downloads/streams/{videoId}` in the existing
internal API virtual host. Other routes and the outer authentication gate remain
as configured. HTTP 502/503/504 from the adapter use the original backend via a
named location. Keep existing frontend same-origin rewriting and ingress CORS
handling. Confirm the existing backend upstream name and Docker DNS alias before
applying. The adapter must be healthy before nginx resolves its name.

Proposed rollout: start the independent service, probe it internally, back up the
API nginx config, add the narrow route, validate nginx, then gracefully reload
only that nginx instance. Rollback restores the original config and gracefully
reloads nginx; the original Piped backend never needs a restart. Remove the
adapter only after routing is restored. All deployment actions require separate
approval. Test real client playback, seeking and complete offline downloads after
switching; bounded server probes alone cannot establish those client behaviors.
