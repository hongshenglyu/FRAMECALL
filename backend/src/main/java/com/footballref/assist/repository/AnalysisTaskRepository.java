package com.footballref.assist.repository;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.VideoAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    List<AnalysisTask> findByVideoOrderByCreatedAtDesc(VideoAsset video);
    List<AnalysisTask> findAllByOrderByCreatedAtDesc();
}

