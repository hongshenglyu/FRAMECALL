package com.footballref.assist.controller;

import com.footballref.assist.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/internal")
public class InternalController {
    private final TaskService taskService;

    public InternalController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/tasks/{taskId}/progress")
    public Map<String, Object> progress(@PathVariable Long taskId, @Valid @RequestBody ProgressRequest request,
                                      jakarta.servlet.http.HttpServletRequest http) {
        taskService.updateProgress(taskId, (String) http.getAttribute("analysisAttemptId"), request.stage(), request.progressPercent());
        return Map.of("ok", true);
    }

    public record ProgressRequest(@NotNull @Pattern(regexp = "EXTRACTING|DETECTING|TRACKING|EVENT_ANALYSIS|COMPLETED") String stage,
                                  @Min(0) @Max(100) int progressPercent, String message) {
    }
}
