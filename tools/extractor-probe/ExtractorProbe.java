package probe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.TreeSet;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

public final class ExtractorProbe {
    static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    static String sample(String url) {
        if (url == null) return "null";
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Range", "bytes=0-65535").GET().build();
            var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                int count = body.readNBytes(65536).length;
                return "{\"status\":" + response.statusCode() + ",\"bytes\":" + count + "}";
            }
        } catch (Exception error) {
            return "{\"error\":\"" + error.getClass().getSimpleName() + "\"}";
        }
    }

    public static void main(String[] args) {
        NewPipe.init(new Downloader() {
            @Override public Response execute(Request request) throws IOException {
                try {
                    var builder = HttpRequest.newBuilder(URI.create(request.url()))
                        .timeout(Duration.ofSeconds(30));
                    request.headers().forEach((k, values) -> values.forEach(v -> builder.header(k, v)));
                    var data = request.dataToSend();
                    builder.method(request.httpMethod(), data == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(data));
                    var response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (var body = response.body()) {
                        var bytes = body.readNBytes(8 * 1024 * 1024 + 1);
                        if (bytes.length > 8 * 1024 * 1024) throw new IOException("Response exceeds test limit");
                        return new Response(response.statusCode(), "", response.headers().map(),
                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8), response.uri().toString());
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", error);
                }
            }
        });
        for (var id : args) {
            try {
                var extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=" + id);
                extractor.fetchPage();
                var videos = new java.util.ArrayList<>(extractor.getVideoStreams());
                videos.addAll(extractor.getVideoOnlyStreams());
                var audio = extractor.getAudioStreams();
                var heights = new TreeSet<Integer>();
                for (var video : videos) heights.add(video.getHeight());
                var best = videos.stream().filter(v -> v.isUrl() && v.getHeight() >= 720)
                    .min(java.util.Comparator.comparingInt(v -> v.getHeight()));
                System.out.println("{\"extractor\":\"NewPipeExtractor\",\"id\":\"" + id
                    + "\",\"heights\":" + heights + ",\"video_tracks\":" + videos.size()
                    + ",\"audio_tracks\":" + audio.size()
                    + ",\"video_sample\":" + sample(best.map(v -> v.getContent()).orElse(null))
                    + ",\"audio_sample\":" + sample(audio.isEmpty() ? null : audio.get(0).getUrl()) + "}");
            } catch (Exception error) {
                System.out.println("{\"extractor\":\"NewPipeExtractor\",\"id\":\"" + id
                    + "\",\"error\":\"" + error.getClass().getSimpleName() + "\"}");
            }
        }
    }
}
