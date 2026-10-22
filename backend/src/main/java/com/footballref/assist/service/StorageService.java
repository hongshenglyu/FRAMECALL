package com.footballref.assist.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

@Service
public class StorageService {
    private final Path root;

    public StorageService(@Value("${app.storage-root}") String storageRoot) throws IOException {
        this.root = Path.of(storageRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("uploads"));
        Files.createDirectories(root.resolve("tasks"));
        Files.createDirectories(root.resolve("annotations"));
    }

    public Path storeUpload(MultipartFile file) throws IOException {
        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "video.mp4" : file.getOriginalFilename());
        String safeName = Instant.now().toEpochMilli() + "_" + originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path destination = root.resolve("uploads").resolve(safeName);
        file.transferTo(destination);
        return destination;
    }

    public Path taskDir(Long taskId) throws IOException {
        Path path = root.resolve("tasks").resolve("task-" + taskId);
        Files.createDirectories(path);
        return path;
    }

    public Path annotationDir() throws IOException {
        Path path = root.resolve("annotations");
        Files.createDirectories(path);
        return path;
    }

    public Path resolveStoragePath(String rawPath) {
        Path requested = Path.of(rawPath).toAbsolutePath().normalize();
        if (requested.startsWith(root)) {
            return requested;
        }

        String normalized = rawPath.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        int marker = lower.lastIndexOf("/storage/");
        if (marker >= 0) {
            String relative = normalized.substring(marker + "/storage/".length());
            return root.resolve(relative).normalize();
        }
        if (lower.startsWith("storage/")) {
            return root.resolve(normalized.substring("storage/".length())).normalize();
        }
        return requested;
    }

    public String normalizeStoragePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        return resolveStoragePath(rawPath).toString();
    }

    public Path root() {
        return root;
    }
}
