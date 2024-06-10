package org.example.dao;

import org.example.entity.AppTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppTaskDAO extends JpaRepository<AppTask, Long> {
    List<AppTask> findByUserId(Long userId);
    List<AppTask> findByName(String name);
}