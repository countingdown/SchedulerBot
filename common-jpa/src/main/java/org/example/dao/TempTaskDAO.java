package org.example.dao;

import org.example.entity.AppTask;
import org.example.entity.TempTaskData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TempTaskDAO extends JpaRepository<TempTaskData, Long> {
    Optional<TempTaskData> findByUserId(Long userId);
}
