package com.footballref.assist.repository;

import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.VideoAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoAssetRepository extends JpaRepository<VideoAsset, Long> {
    List<VideoAsset> findByOwnerOrAssignedToOrderByCreatedAtDesc(UserAccount owner, UserAccount assignedTo);
}

