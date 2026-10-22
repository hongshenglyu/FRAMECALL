package com.footballref.assist.controller;

import com.footballref.assist.service.StorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final StorageService storageService;

    public FileController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping
    public ResponseEntity<Resource> file(@RequestParam String path) throws Exception {
        Path requested = storageService.resolveStoragePath(path);
        if (!requested.startsWith(storageService.root())) {
            throw new ResponseStatusException(FORBIDDEN, "File is outside storage root");
        }
        if (!Files.exists(requested)) {
            throw new ResponseStatusException(NOT_FOUND, "File not found");
        }
        String contentType = Files.probeContentType(requested);
        MediaType mediaType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        return ResponseEntity.ok().contentType(mediaType).body(new FileSystemResource(requested));
    }
}
