package com.footballref.assist.repository;

import com.footballref.assist.domain.TaskAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, String> {
    boolean existsByTaskIdAndActiveTrue(Long taskId);
    long countByTaskIdAndActiveTrue(Long taskId);
    List<TaskAttempt> findByTaskIdOrderByCreatedAtAsc(Long taskId);
    List<TaskAttempt> findByActiveTrue();
}
