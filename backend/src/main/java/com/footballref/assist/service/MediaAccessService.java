package com.footballref.assist.service;

import com.footballref.assist.domain.DisputeEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MediaAccessService {
    private final StorageService storage;
    private final ResourceAccessService access;

    public MediaAccessService(StorageService storage, ResourceAccessService access) {
        this.storage = storage;
        this.access = access;
    }

    public Path requireMedia(Map<String, String> parameters) {
        try {
            if (parameters.keySet().equals(Set.of("videoId"))) {
                return requireStoredFile(access.requireVideo(Long.valueOf(parameters.get("videoId"))).getOriginalPath());
            }
            if (parameters.keySet().equals(Set.of("eventId", "kind"))) {
                return eventFile(access.requireEvent(Long.valueOf(parameters.get("eventId"))), parameters.get("kind"));
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed IDs and paths have the same response as inaccessible resources.
        }
        throw notFound();
    }

    public Path eventFile(DisputeEvent event, String kind) {
        String raw = switch (kind) {
            case "keyframe" -> event.getKeyframePath();
            case "clip" -> event.getClipPath();
            default -> throw notFound();
        };
        Path path = requireStoredFile(raw);
        Path taskRoot = storage.root().resolve("tasks/task-" + event.getTask().getId()).normalize();
        if (!path.startsWith(taskRoot)
                && !path.equals(requireStoredFile(event.getTask().getVideo().getOriginalPath()))) {
            throw notFound();
        }
        return path;
    }

    public Path requireStoredFile(String raw) {
        Path path = controlledPath(raw);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw notFound();
        contentType(path);
        return path;
    }

    // Call only after resource authorization, including the existing authorized PDF route.
    public Path controlledPath(String raw) {
        try {
            if (raw == null || raw.isBlank() || raw.indexOf('%') >= 0 || raw.indexOf('\0') >= 0) throw notFound();
            String value = raw.replace('\\', '/');
            if (value.startsWith("//") || value.contains("~")) throw notFound();
            String withoutDrive = value.replaceFirst("^[A-Za-z]:/", "");
            if (withoutDrive.contains(":")) throw notFound();
            for (String segment : withoutDrive.split("/")) {
                if (segment.equals(".") || segment.equals("..") || segment.endsWith(".") || segment.endsWith(" ")) {
                    throw notFound();
                }
            }
            Path root = storage.root().toAbsolutePath().normalize();
            Path candidate;
            if (value.startsWith("storage/")) candidate = root.resolve(value.substring(8));
            else if (value.startsWith("/storage/")) candidate = root.resolve(value.substring(9));
            else {
                candidate = Path.of(raw);
                if (!candidate.isAbsolute()) candidate = root.resolve(candidate);
            }
            candidate = candidate.toAbsolutePath().normalize();
            if (!candidate.startsWith(root)) throw notFound();
            Path current = root;
            rejectLink(current);
            for (Path segment : root.relativize(candidate)) {
                current = current.resolve(segment);
                rejectLink(current);
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root.toRealPath())) throw notFound();
            return real;
        } catch (IOException | IllegalArgumentException ignored) {
            throw notFound();
        }
    }

    private void rejectLink(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) throw notFound();
    }

    public MediaType contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4")) return MediaType.valueOf("video/mp4");
        if (name.endsWith(".webm")) return MediaType.valueOf("video/webm");
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (name.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (name.endsWith(".webp")) return MediaType.valueOf("image/webp");
        throw notFound();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
    }
}
