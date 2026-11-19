# Optional Piped download metadata extension

The separate [Piped extractor adapter](https://github.com/vadipp/Piped/tree/master/tools/piped-extractor-adapter)
is maintained and built in the Piped fork. Server source, image packaging and
routing examples belong there.

LibreTube uses the optional `/downloads/streams/{videoId}` endpoint only for
download metadata. Normal playback remains on the standard Piped endpoint.
Instances without the extension return 404 and the client falls back to ordinary
Piped streams. Instance endpoints and authentication are configured by the user.
