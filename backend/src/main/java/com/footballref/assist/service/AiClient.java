package com.footballref.assist.service;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.enums.EventType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

@Service
public class AiClient {
    private final RestTemplate client;
    private final RestTemplate cancellationClient;
    private final String aiServiceUrl;
    private final String serviceToken;
    private final String callbackBase;
    private final StorageService storage;
    private final InternalCallbackService callbacks;

    public AiClient(@Value("${app.ai-service-url}") String aiServiceUrl,
                    @Value("${app.ai-service-token:}") String serviceToken,
                    @Value("${app.internal-callback-base-url:}") String callbackBase,
                    StorageService storage, InternalCallbackService callbacks) {
        this(aiServiceUrl, serviceToken, callbackBase, storage, callbacks, 2 * 60 * 60 * 1000);
    }

    public AiClient(@Value("${app.ai-service-url}") String aiServiceUrl,
                    @Value("${app.ai-service-token:}") String serviceToken,
                    @Value("${app.internal-callback-base-url:}") String callbackBase,
                    StorageService storage, InternalCallbackService callbacks,
                    @Value("${app.analysis.read-timeout-ms:7200000}") int readTimeout) {
        this(aiServiceUrl, serviceToken, callbackBase, storage, callbacks, readTimeout, 5000);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiClient(@Value("${app.ai-service-url}") String aiServiceUrl,
                    @Value("${app.ai-service-token:}") String serviceToken,
                    @Value("${app.internal-callback-base-url:}") String callbackBase,
                    StorageService storage, InternalCallbackService callbacks,
                    @Value("${app.analysis.read-timeout-ms:7200000}") int readTimeout,
                    @Value("${app.analysis.cancel-timeout-ms:5000}") int cancelTimeout) {
        if (readTimeout < 50 || readTimeout > 7200000) throw new IllegalArgumentException("Invalid AI read timeout");
        if (cancelTimeout < 50 || cancelTimeout > 30000) throw new IllegalArgumentException("Invalid AI cancel timeout");
        this.aiServiceUrl = aiServiceUrl;
        this.serviceToken = serviceToken;
        this.callbackBase = callbackBase;
        this.storage = storage;
        this.callbacks = callbacks;
        this.client = new RestTemplate(requestFactory(readTimeout));
        this.cancellationClient = new RestTemplate(requestFactory(cancelTimeout));
    }

    private static SimpleClientHttpRequestFactory requestFactory(int readTimeout) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
            @Override
            protected java.net.HttpURLConnection openConnection(java.net.URL url, java.net.Proxy proxy) throws IOException {
                return super.openConnection(url, java.net.Proxy.NO_PROXY);
            }
        };
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(AnalysisTask task, String legacyCallbackUrl) {
        // The legacy Java argument is never a destination; configuration is the sole authority.
        String callback = origin(callbackBase) + "/api/internal/tasks/" + task.getId() + "/progress";
        Path output = checkedPath(task.getOutputDir());
        Path expected = storage.root().resolve("tasks/task-" + task.getId()).normalize();
        if (task.getCurrentAttemptId() != null) {
            expected = expected.resolve("attempts").resolve(java.util.UUID.fromString(task.getCurrentAttemptId()).toString());
        }
        if (!output.equals(expected)) {
            throw new IllegalArgumentException("Unsafe AI output directory");
        }
        Path video = checkedPath(task.getVideo().getOriginalPath());
        if (!video.startsWith(storage.root().resolve("uploads")) || !Files.isRegularFile(video, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Unsafe AI input video");
        }
        String token = callbacks.issue(task.getId(), task.getCurrentAttemptId());
        try {
            Map<String, Object> payload = Map.of("taskId", task.getId(), "videoPath", video.toString(),
                    "outputDir", output.toString(), "sensitivity", task.getSensitivity().name(),
                    "enabledEvents", List.of(EventType.FOUL.name(), EventType.HANDBALL.name(), EventType.OFFSIDE.name()),
                    "callbackUrl", callback);
            HttpHeaders headers = headers(); headers.set("X-Callback-Token", token);
            var response = client.exchange(origin(aiServiceUrl) + "/internal/analyze", HttpMethod.POST,
                    new HttpEntity<>(payload, headers), Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("AI service rejected request");
            }
            return response.getBody();
        } catch (org.springframework.web.client.ResourceAccessException failure) {
            throw new UncertainExecutionException();
        } catch (RestClientException failure) {
            throw new IllegalStateException("AI service request failed");
        } finally { callbacks.revoke(task.getId(), token); }
    }

    public static class UncertainExecutionException extends IllegalStateException {
        public UncertainExecutionException() { super("AI execution outcome unknown"); }
    }

    public void cancel(Long taskId) {
        try {
            var response = cancellationClient.exchange(origin(aiServiceUrl) + "/internal/tasks/" + taskId + "/cancel",
                    HttpMethod.POST, new HttpEntity<>(Map.of(), headers()), Void.class);
            if (!response.getStatusCode().is2xxSuccessful()) throw new CancellationRejectedException();
        } catch (org.springframework.web.client.HttpStatusCodeException rejected) {
            throw new CancellationRejectedException();
        } catch (RestClientException failure) {
            throw new UncertainExecutionException();
        }
    }

    public static class CancellationRejectedException extends IllegalStateException {
        public CancellationRejectedException() { super("AI cancellation rejected"); }
    }

    private HttpHeaders headers() {
        if (serviceToken == null || serviceToken.length() < 32) throw new IllegalStateException("AI service identity is not configured");
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-AI-Service-Token", serviceToken); return headers;
    }

    static String origin(String raw) {
        try {
            URI uri = URI.create(raw); String host = uri.getHost();
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw new IllegalArgumentException();
            if (host.equals("localhost")) host = "127.0.0.1";
            if (host.startsWith("[") && host.endsWith("]")) {
                if (!host.substring(1, host.length() - 1).matches("[0-9a-fA-F:]+")) throw new IllegalArgumentException();
            } else {
                String[] parts = host.split("\\.");
                if (parts.length != 4) throw new IllegalArgumentException();
                for (String part : parts) if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) throw new IllegalArgumentException();
            }
            int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            return uri.getScheme() + "://" + host + ":" + port;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Internal service origin is not configured safely");
        }
    }

    private Path checkedPath(String raw) {
        try {
            if (raw == null || raw.contains("%") || raw.indexOf(0) >= 0 || raw.contains("~") || raw.startsWith("\\\\")) throw new IllegalArgumentException();
            String value = raw.replace('\\', '/').replaceFirst("^[A-Za-z]:/", "");
            if (value.contains(":")) throw new IllegalArgumentException();
            for (String part : value.split("/")) if (part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) throw new IllegalArgumentException();
            Path path = Path.of(raw).toAbsolutePath().normalize(); Path root = storage.root();
            if (!path.startsWith(root)) throw new IllegalArgumentException();
            Path current = root;
            for (Path segment : root.relativize(path)) {
                current = current.resolve(segment);
                BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) throw new IllegalArgumentException();
            }
            if (!path.toRealPath().startsWith(root.toRealPath())) throw new IllegalArgumentException();
            return path;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unsafe AI storage path");
        }
    }
}
