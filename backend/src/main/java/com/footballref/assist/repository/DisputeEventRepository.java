package com.footballref.assist.repository;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.DisputeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeEventRepository extends JpaRepository<DisputeEvent, Long> {
    List<DisputeEvent> findByTaskOrderByTimestampMsAsc(AnalysisTask task);
    void deleteByTask(AnalysisTask task);
}

