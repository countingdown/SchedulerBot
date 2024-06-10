package org.example.service.impl;

import lombok.extern.log4j.Log4j;
import org.example.dao.AppPhotoDAO;
import org.example.dao.AppTaskDAO;
import org.example.dao.ScheduledMessageDAO;
import org.example.dao.TempTaskDAO;
import org.example.entity.AppPhoto;
import org.example.entity.AppTask;
import org.example.entity.ScheduledMessage;
import org.example.entity.TempTaskData;
import org.example.exceptions.UploadFileException;
import org.example.service.FileService;
import org.example.service.ProducerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.sql.Time;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ScheduledFuture;

@Log4j
@Service
public class FileServiceImpl implements FileService {
    private final TempTaskDAO tempTaskDAO;
    @Value("${token}")
    private String token;
    @Value("${service.file_info.uri}")
    private String fileInfoUri;
    private final ProducerService producerService;
    private final AppTaskDAO appTaskDAO;
    private final AppPhotoDAO appPhotoDAO;
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
    private final TaskScheduler taskScheduler;
    private final ScheduledMessageDAO scheduledMessageDAO;

    public FileServiceImpl(AppTaskDAO appTaskDAO, AppPhotoDAO appPhotoDAO, TempTaskDAO tempTaskDAO, ProducerService producerService, TaskScheduler taskScheduler, ScheduledMessageDAO scheduledMessageDAO) {
            this.appTaskDAO = appTaskDAO;
            this.appPhotoDAO = appPhotoDAO;
        this.tempTaskDAO = tempTaskDAO;
        this.producerService = producerService;
        this.taskScheduler = taskScheduler;
        this.scheduledMessageDAO = scheduledMessageDAO;
    }

    public void scheduleMessage(Long chatId, String text, Date targetD, Time targetTime) {
        LocalDate localDate = targetD.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalTime localTime = targetTime.toLocalTime();
        LocalDateTime localDateTime = LocalDateTime.of(localDate, localTime);
        Date targetDate = Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());

        ScheduledMessage scheduledMessage = ScheduledMessage.builder()
                .chatId(chatId)
                .text(text)
                .date(targetDate)
                .build();

        ScheduledMessage schedMessage = scheduledMessageDAO.save(scheduledMessage);

        ScheduledFuture<?> scheduledFuture = taskScheduler.schedule(() -> sendAnswer(text, chatId), targetDate);

        scheduledTasks.put(schedMessage.getId(), scheduledFuture);
    }

    public void cancelScheduledMessage(Long scheduledMessageId) {
        ScheduledFuture<?> scheduledFuture = scheduledTasks.get(scheduledMessageId);
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);

            ScheduledMessage scheduledMessage = scheduledMessageDAO.findById(scheduledMessageId)
                    .orElseThrow(() -> new RuntimeException("Scheduled message not found"));
            scheduledMessageDAO.delete(scheduledMessage);
        }
    }

    private void sendAnswer(String output, Long chatId) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(output);
        producerService.producerAnswer(sendMessage);
    }

    @Override
    public void processTaskData(Update update, String curState) {
        Message telegramMessage = update.getMessage();
        if (telegramMessage == null) {
            telegramMessage = update.getCallbackQuery().getMessage();
        }
        Long userId = telegramMessage.getFrom().getId();
        Optional<TempTaskData> tempTaskData = tempTaskDAO.findByUserId(telegramMessage.getChatId());

        if  (tempTaskData.isEmpty()) {
            // Начало нового процесса ввода
            TempTaskData tempData = new TempTaskData();
            tempData.setUserId(userId);
            tempTaskDAO.save(tempData);
        }
        //noinspection OptionalGetWithoutIsPresent
        TempTaskData tempData = tempTaskData.get();
        // Проверяем текущее состояние и обрабатываем данные соответственно
        // Например, если текущее состояние "WAITING_FOR_NAME", сохраняем название задачи
        if ("WAITING_FOR_NAME".equals(curState)) {
            tempData.setName(telegramMessage.getText());
            tempTaskDAO.save(tempData);
        } else if ("WAITING_FOR_DAY".equals(curState)) {
            try {
                String[] parts = update.getCallbackQuery().getData().substring(4).split("_");
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                int day = Integer.parseInt(parts[2]);
                // Обрабатываем выбранную дату
                String selectedDate = String.format("%04d-%02d-%02d", year, month, day);
                Date date = new SimpleDateFormat("yyyy-MM-dd").parse(selectedDate);
                tempData.setDate(date);
                tempTaskDAO.save(tempData);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        } else if ("WAITING_FOR_REMIND".equals(curState)) {
            String call = update.getCallbackQuery().getData();
            boolean toRemind = call.equals("TO_REMIND");
            tempData.setToRemind(toRemind);
            tempTaskDAO.save(tempData);
            if (!toRemind) {
                // Сохраняем окончательную задачу
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
                Time timeNull;
                try {
                    timeNull = new Time(sdf.parse("00:00").getTime());
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
                AppTask appTask = buildTask(tempData.getUserId(), tempData.getName(), tempData.getDate(), tempData.isToRemind(), timeNull);
                appTaskDAO.save(appTask);
                tempTaskData.ifPresent(tempTaskDAO::delete);
            }
        } else if ("WAITING_FOR_REMIND_TIME".equals(curState)) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
            Time remindTime;
            try {
                remindTime = new Time(sdf.parse(telegramMessage.getText()).getTime());
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
            tempData.setRemind(remindTime);
            // Добавление напоминания
            Long chatId = update.getMessage().getChatId();
            scheduleMessage(chatId, getRemindText(tempData), tempData.getDate(), tempData.getRemind());

            // Сохраняем окончательную задачу
            AppTask appTask = buildTask(tempData.getUserId(), tempData.getName(), tempData.getDate(), tempData.isToRemind(), tempData.getRemind());
            appTaskDAO.save(appTask);
            tempTaskData.ifPresent(tempTaskDAO::delete);
        }
    }

    private static String getRemindText(TempTaskData tempData) {
        return "Вы просили напомнить Вам о задаче " + tempData.getName() + "\n\nСамое время к ней приступить!";
    }

    @Override
    public void processPhoto(Message telegramMessage) {
        Long userFromId = telegramMessage.getFrom().getId();
        Optional<AppPhoto> existingPhoto = appPhotoDAO.findByUserPhotoId(userFromId);

        int photoSizeCount = telegramMessage.getPhoto().size();
        int photoIndex = photoSizeCount > 1 ? telegramMessage.getPhoto().size() - 1 : 0;
        PhotoSize telegramPhoto = telegramMessage.getPhoto().get(photoIndex);
        String fileId = telegramPhoto.getFileId();
        ResponseEntity<String> response = getFilePath(fileId);
        if (response.getStatusCode() == HttpStatus.OK) {
            if (existingPhoto.isPresent()) {
                AppPhoto appPhoto = existingPhoto.get();
                appPhoto.setTelegramFileId(telegramPhoto.getFileId());
                appPhoto.setFileSize(telegramPhoto.getFileSize());
                appPhotoDAO.save(appPhoto);
            } else {
                AppPhoto transientAppPhoto = buildTransientAppPhoto(userFromId, telegramPhoto);
                appPhotoDAO.save(transientAppPhoto);
            }
        } else {
            throw new UploadFileException("Bad response from telegram service: " + response);
        }
    }

    @Override
    public String getPhotoByUserPhotoId(Long userPhotoId) {
        AppPhoto appPhoto = appPhotoDAO.findByUserPhotoId(userPhotoId).orElse(null);
        if (appPhoto == null) {
            return "";
        }
        return appPhoto.getTelegramFileId();
    }

    @Override
    public List<AppTask> getTasksByUserId(Long userId) {
        return appTaskDAO.findByUserId(userId);
    }

    @Override
    public int findTask(Message telegramMessage) {
        Long userFromId = telegramMessage.getFrom().getId();
        String text = telegramMessage.getText();
        try {
            int taskIndex = Integer.parseInt(text) - 1;
            List<AppTask> tasks = getTasksByUserId(userFromId);

            if (taskIndex >= 0 && taskIndex < tasks.size()) {
                return taskIndex;
            } else {
                log.debug("Task with index " + (taskIndex + 1) + " not found.");
                return -1;
            }
        } catch (NumberFormatException e) {
            log.debug("Invalid task index.");
            return -1;
        }
    }

    @Override
    public void setTempTask(Message telegramMessage, int taskIndex) {
        Long userId = telegramMessage.getFrom().getId();

        List<AppTask> tasks = getTasksByUserId(userId);
        tasks.sort(Comparator.comparing(AppTask::getDate).thenComparing(AppTask::getRemind));
        AppTask taskToSet = tasks.get(taskIndex);

        TempTaskData tempData = new TempTaskData();
        tempData.setUserId(userId);
        tempData.setName(taskToSet.getName());
        tempData.setDate(taskToSet.getDate());
        tempData.setToRemind(true);
        tempTaskDAO.save(tempData);
    }

    @Override
    public void changeTaskRemind(Message telegramMessage) {
        Optional<TempTaskData> tempTaskData = tempTaskDAO.findByUserId(telegramMessage.getChatId());
        if (tempTaskData.isEmpty()) {
            throw new RuntimeException("No temp task data found for user");
        }

        TempTaskData tempData = tempTaskData.get();

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        Time remindTime;
        try {
            remindTime = new Time(sdf.parse(telegramMessage.getText()).getTime());
            tempData.setRemind(remindTime);

            // Добавление напоминания
            Long chatId = telegramMessage.getChatId();
            String outputRemind = getRemindText(tempData);

            Date remindDateTime = combineDateAndTime(tempData.getDate(), remindTime);

            if (remindDateTime.after(new Date())) {
                scheduleMessage(chatId, outputRemind, remindDateTime, tempData.getRemind());
            }

            AppTask appTask = buildTask(tempData.getUserId(), tempData.getName(), tempData.getDate(), tempData.isToRemind(), remindTime);
            appTaskDAO.save(appTask);
            tempTaskData.ifPresent(tempTaskDAO::delete);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private Date combineDateAndTime(Date date, Time time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        Calendar timeCal = Calendar.getInstance();
        timeCal.setTime(time);

        calendar.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY));
        calendar.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE));
        calendar.set(Calendar.SECOND, timeCal.get(Calendar.SECOND));
        calendar.set(Calendar.MILLISECOND, timeCal.get(Calendar.MILLISECOND));

        return calendar.getTime();
    }

    @Override
    public void deleteTask(Message telegramMessage, int taskIndex) {
        Long userId = telegramMessage.getFrom().getId();

        List<AppTask> tasks = getTasksByUserId(userId);
        tasks.sort(Comparator.comparing(AppTask::getDate).thenComparing(AppTask::getRemind));
        AppTask taskToDelete = tasks.get(taskIndex);
        // Удаление напоминания
        Long chatId = telegramMessage.getChatId();
        String outputRemind = "Вы просили напомнить Вам о задаче " + taskToDelete.getName() + "\n\nСамое время к ней приступить!";
        if (!scheduledMessageDAO.findByChatIdAndText(chatId, outputRemind).isEmpty()) {
            ScheduledMessage messagebyChatIdAndText = scheduledMessageDAO.findByChatIdAndText(chatId, outputRemind).get(0);
            cancelScheduledMessage(messagebyChatIdAndText.getId());
        }

        appTaskDAO.delete(taskToDelete);
    }

    public AppTask buildTask(Long userId, String name, Date date, boolean toRemind, Time remind) {
        return AppTask.builder()
                .userId(userId)
                .name(name)
                .date(date)
                .toRemind(toRemind)
                .remind(remind)
                .build();
    }

    private AppPhoto buildTransientAppPhoto(Long userFromId, PhotoSize telegramPhoto) {
        return AppPhoto.builder()
                .userPhotoId(userFromId)
                .telegramFileId(telegramPhoto.getFileId())
                .fileSize(telegramPhoto.getFileSize())
                .build();
    }

    private ResponseEntity<String> getFilePath(String fileId) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<String> request = new HttpEntity<>(headers);
        return restTemplate.exchange(
                fileInfoUri,
                HttpMethod.GET,
                request,
                String.class,
                token, fileId
        );
    }
}
