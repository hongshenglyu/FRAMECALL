package com.footballref.assist.controller;

import com.footballref.assist.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/internal")
public class InternalController {
    private final TaskService taskService;
    private final String internalToken;

    public InternalController(TaskService taskService, @Value("${app.internal-token}") String internalToken) {
        this.taskService = taskService;
        this.internalToken = internalToken;
    }

    @PostMapping("/tasks/{taskId}/progress")
    public Map<String, Object> progress(@PathVariable Long taskId, @RequestBody ProgressRequest request, HttpServletRequest httpRequest) {
        String token = httpRequest.getHeader("X-Internal-Token");
        if (!internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal token");
        }
        taskService.updateProgress(taskId, request.stage(), request.progressPercent());
        return Map.of("ok", true);
    }

    public record ProgressRequest(String stage, int progressPercent, String message) {
    }
}

