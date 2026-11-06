package com.footballref.assist.controller;

import com.footballref.assist.config.JwtAuthFilter;
import com.footballref.assist.service.MediaAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final MediaAccessService media;

    public FileController(MediaAccessService media) {
        this.media = media;
    }

    @PostMapping("/session")
    public ResponseEntity<Void> session(HttpServletRequest request) {
        return cookie(request, request.getHeader(HttpHeaders.AUTHORIZATION).substring(7), Duration.ofHours(8));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> clearSession(HttpServletRequest request) {
        return cookie(request, "", Duration.ZERO);
    }

    private ResponseEntity<Void> cookie(HttpServletRequest request, String value, Duration age) {
        ResponseCookie cookie = ResponseCookie.from(JwtAuthFilter.MEDIA_COOKIE, value)
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict")
                .path("/api/files").maxAge(age).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping
    public ResponseEntity<Resource> file(@RequestParam Map<String, String> parameters,
                                         HttpServletRequest request) throws Exception {
        Path path = media.requireMedia(parameters);
        long length = Files.size(path);
        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null) {
            try {
                var ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1 || length == 0
                        || ranges.get(0).getRangeStart(length) >= length
                        || ranges.get(0).getRangeEnd(length) < ranges.get(0).getRangeStart(length)) {
                    throw new IllegalArgumentException("Unsupported range");
                }
            } catch (IllegalArgumentException invalidRange) {
                return ResponseEntity.status(416).header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
            }
        }
        return ResponseEntity.ok().contentType(media.contentType(path))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(new FileSystemResource(path));
    }
}
