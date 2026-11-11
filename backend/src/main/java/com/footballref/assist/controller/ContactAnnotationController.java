package com.footballref.assist.controller;
import com.footballref.assist.service.ContactAnnotationService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ContactAnnotationController {
    private final ContactAnnotationService service;
    public ContactAnnotationController(ContactAnnotationService service){this.service=service;}
    @GetMapping("/api/events/{id}/annotations")
    public Map<String,Object> detail(@PathVariable Long id){return service.detail(id);}
    @PostMapping("/api/events/{id}/annotations")
    public Map<String,Object> save(@PathVariable Long id,@RequestBody ContactAnnotationService.Request request){return service.save(id,request);}
    @GetMapping("/api/tasks/{id}/contact-annotations")
    public Map<String,Object> export(@PathVariable Long id){return service.export(id);}
}
