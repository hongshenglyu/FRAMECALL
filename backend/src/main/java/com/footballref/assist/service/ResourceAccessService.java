package com.footballref.assist.service;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.DisputeEvent;
import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.VideoAsset;
import com.footballref.assist.domain.enums.Role;
import com.footballref.assist.repository.AnalysisTaskRepository;
import com.footballref.assist.repository.DisputeEventRepository;
import com.footballref.assist.repository.VideoAssetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceAccessService {
    private final AnalysisTaskRepository tasks;
    private final DisputeEventRepository events;
    private final CurrentUserService currentUserService;
    private final VideoAssetRepository videos;

    public ResourceAccessService(AnalysisTaskRepository tasks, DisputeEventRepository events,
                                 CurrentUserService currentUserService, VideoAssetRepository videos) {
        this.tasks = tasks;
        this.events = events;
        this.currentUserService = currentUserService;
        this.videos = videos;
    }

    public VideoAsset requireVideo(Long videoId) {
        UserAccount current = currentUserService.requireUser();
        VideoAsset video = videos.findById(videoId).orElseThrow(ResourceAccessService::notFound);
        if (!canAccessVideo(video, current)) {
            throw notFound();
        }
        return video;
    }

    public AnalysisTask requireTask(Long taskId) {
        UserAccount current = currentUserService.requireUser();
        AnalysisTask task = tasks.findById(taskId).orElseThrow(ResourceAccessService::notFound);
        if (!canAccessTask(task, current)) {
            throw notFound();
        }
        return task;
    }

    public DisputeEvent requireEvent(Long eventId) {
        UserAccount current = currentUserService.requireUser();
        DisputeEvent event = events.findById(eventId).orElseThrow(ResourceAccessService::notFound);
        if (!canAccessTask(event.getTask(), current)) {
            throw notFound();
        }
        return event;
    }

    public boolean canAccessTask(AnalysisTask task, UserAccount current) {
        return task != null && task.getArchivedAt() == null && canAccessVideo(task.getVideo(), current);
    }

    public boolean canAccessVideo(VideoAsset video, UserAccount current) {
        if (current == null || current.getId() == null || video == null
                || video.getOwner() == null || video.getOwner().getId() == null) {
            return false;
        }
        return current.getRole() == Role.ADMIN
                || current.getId().equals(video.getOwner().getId())
                || (video.getAssignedTo() != null && current.getId().equals(video.getAssignedTo().getId()));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
    }
}
