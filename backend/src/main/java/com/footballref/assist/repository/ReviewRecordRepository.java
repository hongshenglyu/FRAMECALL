package com.footballref.assist.repository;

import com.footballref.assist.domain.DisputeEvent;
import com.footballref.assist.domain.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, Long> {
    List<ReviewRecord> findByEventOrderByRevisionDesc(DisputeEvent event);
    long countByEvent(DisputeEvent event);
}

