package com.footballref.assist.controller;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.DisputeEvent;
import com.footballref.assist.repository.AnalysisTaskRepository;
import com.footballref.assist.repository.DisputeEventRepository;
import com.footballref.assist.service.CurrentUserService;
import com.footballref.assist.service.ReportService;
import com.footballref.assist.service.ResourceAccessService;
import com.footballref.assist.service.TaskService;
import com.footballref.assist.service.TaskHistoryService;
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
    private final ResourceAccessService resourceAccessService;
    private final TaskHistoryService history;

    public TaskController(
            AnalysisTaskRepository tasks,
            DisputeEventRepository events,
            TaskService taskService,
            ReportService reportService,
            CurrentUserService currentUserService,
            ResourceAccessService resourceAccessService, TaskHistoryService history
    ) {
        this.tasks = tasks;
        this.events = events;
        this.taskService = taskService;
        this.reportService = reportService;
        this.currentUserService = currentUserService;
        this.resourceAccessService = resourceAccessService;
        this.history = history;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        var current = currentUserService.requireUser();
        return tasks.findAllByOrderByCreatedAtDesc().stream()
                .filter(task -> (includeArchived || task.getArchivedAt()==null)
                        && resourceAccessService.canAccessVideo(task.getVideo(),current))
                .map(TaskController::toTaskMap)
                .toList();
    }

    @GetMapping("/{taskId}")
    public Map<String, Object> detail(@PathVariable Long taskId,
                                    @RequestParam(defaultValue = "current") String scope,
                                    @RequestParam(required = false) String attemptId) {
        var view=history.view(taskId,scope,attemptId);
        Map<String, Object> response = toTaskMap(view.task());
        response.put("events", view.events().stream().map(this::historyEventMap).toList());
        response.put("eventSelection",view.label());
        response.put("attempts",view.attempts().stream().map(attempt->{
            Map<String,Object> map=new LinkedHashMap<>();map.put("id",attempt.getId());map.put("status",attempt.getStatus());
            map.put("active",attempt.isActive());map.put("createdAt",attempt.getCreatedAt());map.put("finishedAt",attempt.getFinishedAt());
            return map;
        }).toList());
        return response;
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long taskId) {
        resourceAccessService.requireTask(taskId);
        var status = taskService.requestCancel(taskId);
        return ResponseEntity.status(status).body(toTaskMap(tasks.findById(taskId).orElseThrow()));
    }

    @PostMapping("/{taskId}/retry")
    public Map<String, Object> retry(@PathVariable Long taskId) {
        resourceAccessService.requireTask(taskId);
        taskService.retry(taskId);
        return toTaskMap(tasks.findById(taskId).orElseThrow());
    }

    @DeleteMapping("/{taskId}")
    public Map<String, Object> delete(@PathVariable Long taskId) {
        AnalysisTask task=history.archive(taskId,false);
        return Map.of("deleted",true,"archived",true,"taskId",task.getId(),"archivedAt",task.getArchivedAt());
    }

    @PostMapping("/{taskId}/restore")
    public Map<String,Object> restore(@PathVariable Long taskId) {
        return toTaskMap(history.archive(taskId,true));
    }

    @GetMapping("/{taskId}/events")
    public List<Map<String, Object>> events(@PathVariable Long taskId,
                                          @RequestParam(defaultValue = "current") String scope,
                                          @RequestParam(required = false) String attemptId) {
        return history.view(taskId,scope,attemptId).events().stream().map(this::historyEventMap).toList();
    }

    @GetMapping("/{taskId}/report")
    public ResponseEntity<Resource> report(@PathVariable Long taskId,
                                           @RequestParam(defaultValue = "current") String scope,
                                           @RequestParam(required = false) String attemptId) throws Exception {
        var view=history.view(taskId,scope,attemptId);
        Resource resource = new org.springframework.core.io.ByteArrayResource(reportService.generatePdf(view.task(),view.events(),view.label()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=referee-review-report.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }

    public static Map<String, Object> toTaskMap(AnalysisTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("attemptId", task.getCurrentAttemptId());
        map.put("archivedAt", task.getArchivedAt());
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

    private Map<String,Object> historyEventMap(DisputeEvent event) {
        Map<String,Object> map=EventController.toEventMap(event);
        map.put("attemptAssociation",history.association(event));return map;
    }
}
