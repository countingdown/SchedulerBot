package org.example.service;

import org.telegram.telegrambots.meta.api.objects.Message;
import org.example.entity.AppTask;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Date;
import java.util.List;

public interface FileService {

    void processTaskData(Update update, String curState);

    void processPhoto(Message telegramMessage);
    String getPhotoByUserPhotoId(Long userPhotoId);
    List<AppTask> getTasksByUserId(Long userId);

    int findTask(Message telegramMessage);
    void changeTaskRemind(Message telegramMessage);
    void deleteTask(Message telegramMessage, int taskIndex);
    void setTempTask(Message curMessage, int taskIndex);
}