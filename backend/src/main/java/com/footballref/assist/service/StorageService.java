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

    public Path attemptDir(Long taskId, String attemptId) throws IOException {
        if (taskId == null || taskId <= 0 || !java.util.UUID.fromString(attemptId).toString().equals(attemptId)) {
            throw new IllegalArgumentException("Invalid analysis attempt");
        }
        Path target = root.resolve("tasks/task-" + taskId + "/attempts/" + attemptId);
        Path current = root;
        checkDirectory(current);
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            try { Files.createDirectory(current); }
            catch (java.nio.file.FileAlreadyExistsException exists) { /* Check the existing entry below. */ }
            checkDirectory(current);
        }
        return target;
    }

    private void checkDirectory(Path path) throws IOException {
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || !path.toRealPath().startsWith(root.toRealPath())) throw new IOException("Unsafe attempt directory");
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
