package com.footballref.assist.repository;
import com.footballref.assist.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ContactAnnotationRevisionRepository extends JpaRepository<ContactAnnotationRevision,Long> {
    List<ContactAnnotationRevision> findByEventOrderByIdDesc(DisputeEvent event);
}
