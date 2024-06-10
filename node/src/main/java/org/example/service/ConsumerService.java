package org.example.service;

import org.telegram.telegrambots.meta.api.objects.Update;

public interface ConsumerService {
    void consumeCallbackUpdates(Update update);
    void consumeTextMessageUpdates(Update update);
    void consumePhotoMessageUpdates(Update update);
}
