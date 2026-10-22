package com.footballref.assist.repository;

import com.footballref.assist.domain.AnnotationTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnnotationTaskRepository extends JpaRepository<AnnotationTask, Long> {
    List<AnnotationTask> findAllByOrderByCreatedAtDesc();
}

