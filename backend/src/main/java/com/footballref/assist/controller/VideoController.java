package com.footballref.assist.controller;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.VideoAsset;
import com.footballref.assist.domain.enums.Role;
import com.footballref.assist.domain.enums.Sensitivity;
import com.footballref.assist.repository.AnalysisTaskRepository;
import com.footballref.assist.repository.UserAccountRepository;
import com.footballref.assist.repository.VideoAssetRepository;
import com.footballref.assist.service.CurrentUserService;
import com.footballref.assist.service.StorageService;
import com.footballref.assist.service.TaskService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
public class VideoController {
    private final VideoAssetRepository videos;
    private final UserAccountRepository users;
    private final AnalysisTaskRepository tasks;
    private final StorageService storageService;
    private final TaskService taskService;
    private final CurrentUserService currentUserService;

    public VideoController(
            VideoAssetRepository videos,
            UserAccountRepository users,
            AnalysisTaskRepository tasks,
            StorageService storageService,
            TaskService taskService,
            CurrentUserService currentUserService
    ) {
        this.videos = videos;
        this.users = users;
        this.tasks = tasks;
        this.storageService = storageService;
        this.taskService = taskService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        UserAccount current = currentUserService.requireUser();
        List<VideoAsset> list = current.getRole() == Role.ADMIN
                ? videos.findAll()
                : videos.findByOwnerOrAssignedToOrderByCreatedAtDesc(current, current);
        return list.stream().map(this::toMap).toList();
    }

    @PostMapping
    public Map<String, Object> upload(
            @RequestParam MultipartFile file,
            @RequestParam String title,
            @RequestParam String competition,
            @RequestParam String teams,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime matchTime,
            @RequestParam(required = false) Long assignedToUserId,
            @RequestParam(defaultValue = "BALANCED") Sensitivity sensitivity
    ) throws Exception {
        UserAccount current = currentUserService.requireUser();
        Path storedPath = storageService.storeUpload(file);

        VideoAsset video = new VideoAsset();
        video.setTitle(title);
        video.setCompetition(competition);
        video.setTeams(teams);
        video.setMatchTime(matchTime);
        video.setOriginalPath(storedPath.toString());
        video.setOwner(current);
        if (assignedToUserId != null) {
            video.setAssignedTo(users.findById(assignedToUserId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned user not found")));
        }
        VideoAsset savedVideo = videos.save(video);

        AnalysisTask task = new AnalysisTask();
        task.setVideo(savedVideo);
        task.setSensitivity(sensitivity);
        AnalysisTask savedTask = taskService.createTask(task);
        taskService.runTask(savedTask.getId());
        return Map.of("video", toMap(savedVideo), "task", TaskController.toTaskMap(savedTask));
    }

    private Map<String, Object> toMap(VideoAsset video) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", video.getId());
        map.put("title", video.getTitle());
        map.put("competition", video.getCompetition());
        map.put("teams", video.getTeams());
        map.put("matchTime", video.getMatchTime());
        map.put("originalPath", video.getOriginalPath());
        map.put("owner", video.getOwner().getDisplayName());
        map.put("assignedTo", video.getAssignedTo() == null ? "" : video.getAssignedTo().getDisplayName());
        map.put("createdAt", video.getCreatedAt());
        return map;
    }
}
