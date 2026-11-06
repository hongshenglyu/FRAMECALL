package com.footballref.assist.repository;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.VideoAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AnalysisTask t where t.id = :id")
    Optional<AnalysisTask> lockById(@Param("id") Long id);
    List<AnalysisTask> findByVideoOrderByCreatedAtDesc(VideoAsset video);
    List<AnalysisTask> findAllByOrderByCreatedAtDesc();
}

