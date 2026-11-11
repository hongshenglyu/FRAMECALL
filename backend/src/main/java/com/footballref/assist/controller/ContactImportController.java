package com.footballref.assist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.service.ContactImportService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

@RestController
public class ContactImportController {
    private final ContactImportService importer;
    private final ObjectMapper mapper;

    public ContactImportController(ContactImportService importer, ObjectMapper mapper) {
        this.importer = importer; this.mapper = mapper;
    }

    @PostMapping(value="/api/videos/contact-import", consumes="multipart/form-data")
    public Map<String,Object> importClips(@RequestPart("manifest") MultipartFile manifest,
                                          @RequestPart("media") List<MultipartFile> media) throws Exception {
        return importer.importBundle(mapper.readTree(manifest.getInputStream()), media);
    }
}
