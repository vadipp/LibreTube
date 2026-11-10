# Server extraction experiment

This branch is an isolated diagnostic experiment, not an app release or a backend
deployment. Its CI workflow replaces Android builds on this branch only. Do not
merge that workflow over the normal application CI.

The workflow compares yt-dlp 2026.10.26 with NewPipeExtractor v0.27.0 against three
public sample videos. It reports format heights, separate audio availability and
bounded media samples. A successful workflow means the experiment executed;
inspect each report for extraction and HTTP failures before drawing conclusions.
Network location matters: a hosted runner result does not establish that the same
requests will succeed from a deployment's address.

Reports omit signed media URLs, request headers, token values and raw exceptions.
No account credentials or signing secrets are required. The Java probe artifact
is executable with Java 17 or newer, allowing the identical extractor build to be
tested separately from another network without rebuilding there. It deliberately
uses a simple JDK HTTP downloader; failure may reflect that integration as well as
the extractor. No token provider is configured in this baseline.

Run the Python probe in an environment with the pinned yt-dlp package and Node 22:

```sh
python probe.py QvW61K2s0tA 99rI-nOCajM dQw4w9WgXcQ
```

Run the built Java probe:

```sh
java -Xmx256m -jar extractor-probe.jar QvW61K2s0tA 99rI-nOCajM dQw4w9WgXcQ
```

When testing on a production host, use an isolated temporary container with hard
memory/CPU/PID/time limits, zero swap and no production volumes or credentials.
Only small media samples are downloaded. These probes do not establish full
playback, seek, expiry renewal or complete offline download correctness.
