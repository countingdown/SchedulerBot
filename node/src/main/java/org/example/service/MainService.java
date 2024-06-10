package org.example.service;

import org.telegram.telegrambots.meta.api.objects.Update;

public interface MainService {
    void processCallback(Update update);
    void processTextMessage(Update update);
    void processPhotoMessage(Update update);
}