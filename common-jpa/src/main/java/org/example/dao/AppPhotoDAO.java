package org.example.dao;

import org.example.entity.AppPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppPhotoDAO extends JpaRepository<AppPhoto, Long> {
    Optional<AppPhoto> findByUserPhotoId(Long userPhotoId);
}
