package org.example.dao;

import org.example.entity.AppTask;
import org.example.entity.ScheduledMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduledMessageDAO extends JpaRepository<ScheduledMessage, Long> {
    List<ScheduledMessage> findByChatIdAndText(Long chatId, String text);
}
