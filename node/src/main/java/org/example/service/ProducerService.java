package org.example.service;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;

public interface ProducerService {
    void producerAnswer(SendMessage sendMessage);
    void producerPhAnswer(SendPhoto sendPhoto);
}