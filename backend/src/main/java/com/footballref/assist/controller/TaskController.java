package com.footballref.assist.controller;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.DisputeEvent;
import com.footballref.assist.domain.enums.Role;
import com.footballref.assist.repository.AnalysisTaskRepository;
import com.footballref.assist.repository.DisputeEventRepository;
import com.footballref.assist.service.CurrentUserService;
import com.footballref.assist.service.ReportService;
import com.footballref.assist.service.TaskService;
import jakarta.transaction.Transactional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final AnalysisTaskRepository tasks;
    private final DisputeEventRepository events;
    private final TaskService taskService;
    private final ReportService reportService;
    private final CurrentUserService currentUserService;

    public TaskController(
            AnalysisTaskRepository tasks,
            DisputeEventRepository events,
            TaskService taskService,
            ReportService reportService,
            CurrentUserService currentUserService
    ) {
        this.tasks = tasks;
        this.events = events;
        this.taskService = taskService;
        this.reportService = reportService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        var current = currentUserService.requireUser();
        return tasks.findAllByOrderByCreatedAtDesc().stream()
                .filter(task -> current.getRole() == Role.ADMIN
                        || task.getVideo().getOwner().getId().equals(current.getId())
                        || (task.getVideo().getAssignedTo() != null && task.getVideo().getAssignedTo().getId().equals(current.getId())))
                .map(TaskController::toTaskMap)
                .toList();
    }

    @GetMapping("/{taskId}")
    public Map<String, Object> detail(@PathVariable Long taskId) {
        AnalysisTask task = tasks.findById(taskId).orElseThrow();
        Map<String, Object> response = toTaskMap(task);
        response.put("events", events.findByTaskOrderByTimestampMsAsc(task).stream().map(EventController::toEventMap).toList());
        return response;
    }

    @PostMapping("/{taskId}/cancel")
    public Map<String, Object> cancel(@PathVariable Long taskId) {
        taskService.requestCancel(taskId);
        return toTaskMap(tasks.findById(taskId).orElseThrow());
    }

    @PostMapping("/{taskId}/retry")
    public Map<String, Object> retry(@PathVariable Long taskId) {
        taskService.retry(taskId);
        return toTaskMap(tasks.findById(taskId).orElseThrow());
    }

    @DeleteMapping("/{taskId}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long taskId) {
        AnalysisTask task = tasks.findById(taskId).orElseThrow();
        events.deleteByTask(task);
        tasks.delete(task);
        return Map.of("deleted", true);
    }

    @GetMapping("/{taskId}/events")
    public List<Map<String, Object>> events(@PathVariable Long taskId) {
        AnalysisTask task = tasks.findById(taskId).orElseThrow();
        return events.findByTaskOrderByTimestampMsAsc(task).stream().map(EventController::toEventMap).toList();
    }

    @GetMapping("/{taskId}/report")
    public ResponseEntity<Resource> report(@PathVariable Long taskId) throws Exception {
        AnalysisTask task = tasks.findById(taskId).orElseThrow();
        Path report = reportService.generatePdf(task);
        Resource resource = new FileSystemResource(report);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=referee-review-report.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }

    public static Map<String, Object> toTaskMap(AnalysisTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("videoId", task.getVideo().getId());
        map.put("videoTitle", task.getVideo().getTitle());
        map.put("status", task.getStatus());
        map.put("stage", task.getStage());
        map.put("progressPercent", task.getProgressPercent());
        map.put("sensitivity", task.getSensitivity());
        map.put("outputDir", task.getOutputDir());
        map.put("resultJsonPath", task.getResultJsonPath());
        map.put("errorMessage", task.getErrorMessage());
        map.put("createdAt", task.getCreatedAt());
        map.put("updatedAt", task.getUpdatedAt());
        return map;
    }
}
