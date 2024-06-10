package org.example.service.impl;

import lombok.extern.log4j.Log4j;
import org.example.dao.AppTaskDAO;
import org.example.dao.AppUserDAO;
import org.example.entity.*;
import org.example.enums.UserState;
import org.example.exceptions.UploadFileException;
import org.example.service.FileService;
import org.example.service.MainService;
import org.example.service.ProducerService;
import org.example.service.enums.ServiceCommand;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.sql.Time;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.example.enums.UserState.*;
import static org.example.service.enums.ServiceCommand.*;

@Service
@Log4j
public class MainServiceImpl implements MainService {
    private final ProducerService producerService;
    private final AppUserDAO appUserDAO;
    private final FileService fileService;
    private final AppTaskDAO appTaskDAO;

    public MainServiceImpl(ProducerService producerService, AppUserDAO appUserDAO, FileService fileService, AppTaskDAO appTaskDAO) {
        this.producerService = producerService;
        this.appUserDAO = appUserDAO;
        this.fileService = fileService;
        this.appTaskDAO = appTaskDAO;
    }

    private String help() {
        return "Список доступных команд:\n"
                + "/edit - просмотр/редактировние задач;\n"
                + "/registration - регистрация пользователя;\n"
                + "/cancel - отмена выполнения текущей команды;\n";
    }

    @Override
    public void processCallback(Update update) {
        CallbackQuery callbackQuery = update.getCallbackQuery();
        String callbackData = callbackQuery.getData();
        Message message = callbackQuery.getMessage();
        Long chatId = message.getChatId();
        User userFrom = update.getCallbackQuery().getFrom();
        AppUser appUser = findOrSaveAppUser(userFrom);
        UserState userState = appUser.getState();

        if (callbackData.startsWith("MONTH_")) {
            String[] parts = callbackData.substring(6).split("_");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            appUser.setState(WAITING_FOR_DAY);
            appUserDAO.save(appUser);
            producerService.producerAnswer(sendDayKeyboard(chatId, year, month));
        } else if (callbackData.startsWith("DAY_")) {
                // Сохраняем выбранную дату
                fileService.processTaskData(update, userState.toString());
                appUser.setState(WAITING_FOR_REMIND);
                appUserDAO.save(appUser);
                producerService.producerAnswer(sendRemindKeyboard(chatId));
        } else {
            switch (callbackData) {
                case "EDIT_1":
                    sendUserPh(chatId, userFrom);
                    break;
                case "EDIT_2":
                    appUser.setState(EDIT_PHOTO_STATE);
                    appUserDAO.save(appUser);
                    sendAnswer("Отправьте вашу фотографию", chatId);
                    break;
                case "EDIT_3":
                    appUser.setState(EDIT_REMIND_STATE);
                    appUserDAO.save(appUser);
                    sendAnswer("Введите номер задачи по порядку добавления/изменения напоминания", chatId);
                    break;
                case "EDIT_4":
                    appUser.setState(WAITING_FOR_NAME);
                    appUserDAO.save(appUser);
                    sendAnswer("Введите название вашей задачи", chatId);
                    break;
                case "EDIT_5":
                    appUser.setState(DELETE_TASKS_STATE);
                    appUserDAO.save(appUser);
                    sendAnswer("Введите номер задачи по порядку для её удаления", chatId);
                    break;
                case "TO_REMIND":
                    fileService.processTaskData(update, userState.toString());
                    appUser.setState(WAITING_FOR_REMIND_TIME);
                    appUserDAO.save(appUser);
                    sendAnswer("Введите время напоминания (например: 14:30)", chatId);
                    break;
                case "NOT_TO_REMIND":
                    fileService.processTaskData(update, userState.toString());
                    appUser.setState(BASIC_STATE);
                    appUserDAO.save(appUser);
                    sendAnswer("Задача была добавлена", chatId);
                    break;
                case "CUR_YEAR":
                    appUser.setState(WAITING_FOR_MONTH);
                    appUserDAO.save(appUser);
                    producerService.producerAnswer(sendMonthKeyboard(chatId, LocalDate.now().getYear()));
                    break;
                case "N_YEAR":
                    appUser.setState(WAITING_FOR_MONTH);
                    appUserDAO.save(appUser);
                    producerService.producerAnswer(sendMonthKeyboard(chatId, LocalDate.now().getYear()+1));
                    break;
            }
        }
    }

    private String processServiceCommand(String cmd, Update update) {
        ServiceCommand serviceCommand = fromValue(cmd);
        if (HELP.equals(serviceCommand)) {
            return help();
        } else if (START.equals(serviceCommand)) {
            return "Приветствую! Чтобы посмотреть список доступных команд введите /help";
        } else if (EDIT.equals(serviceCommand)) {
            sendEditTaskMessage(update);
            return "";
        } else {
            return "Неизвестная команда! Чтобы посмотреть список доступных команд введите /help";
        }
    }

    private static String editTasksText() {
        return "1 - Просмотр задач;\n"
                + "2 - Добавление/изменение фото вывода задач;\n"
                + "3 - Добавление/изменение напоминания для задачи;\n"
                + "4 - Добавление задачи;\n"
                + "5 - Удаление задачи;\n";
    }

    @Override
    public void processTextMessage(Update update) {
        Message curMessage = update.getMessage();
        Long chatId = curMessage.getChatId();
        AppUser appUser = findOrSaveAppUser(curMessage.getFrom());
        UserState userState = appUser.getState();
        String text = curMessage.getText();
        String output = "";

        ServiceCommand serviceCommand = fromValue(text);
        if (CANCEL.equals(serviceCommand)) {
            output = cancelProcess(appUser);
        } else if (BASIC_STATE.equals(userState)) {
            output = processServiceCommand(text, update);
        } else if (EDIT_PHOTO_STATE.equals(userState)) {
            output = "Отправьте своё фото, либо /cancel";
        } else if (WAITING_FOR_NAME.equals(userState)) {
            if (text.length() < 31) {
                if (appTaskDAO.findByName(text).isEmpty()) {
                    fileService.processTaskData(update, userState.toString());
                    appUser.setState(WAITING_FOR_YEAR);
                    appUserDAO.save(appUser);
                    producerService.producerAnswer(sendYearKeyboard(chatId));
                } else {
                    output = "Задача с таким именем уже существует";
                }
            } else {
                output = "Имя не может быть длинее 60 символов";
            }
        } else if (WAITING_FOR_YEAR.equals(userState)) {
            output = "Выберите год, либо нажмите /cancel";
        } else if (WAITING_FOR_MONTH.equals(userState)) {
            output = "Выберите месяц, либо нажмите /cancel";
        } else if (WAITING_FOR_DAY.equals(userState)) {
            output = "Выберите день, либо нажмите /cancel";
        } else if (WAITING_FOR_REMIND.equals(userState)) {
            output = "Ответьте, нужно ли напоминание, либо нажмите /cancel (задача не будет добавлена)";
        } else if (WAITING_FOR_REMIND_TIME.equals(userState)) {
            try {
                Time time = processTaskTime(text);
                if (time != null) {
                    fileService.processTaskData(update, String.valueOf(appUser.getState()));
                    output = "Задача была добавлена";
                    sendEditTaskMessage(update);
                    appUser.setState(BASIC_STATE);
                    appUserDAO.save(appUser);
                } else {
                    output = "Вы неправильно ввели время";
                }
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        } else if (DELETE_TASKS_STATE.equals(userState)) {
            int taskIndex = fileService.findTask(curMessage);
            appUser.setState(BASIC_STATE);
            appUserDAO.save(appUser);
            if (taskIndex == -1) {
                output = "Этой задачи нет";
            } else {
                fileService.deleteTask(curMessage, taskIndex);
                output = "Задача была успешно удалена";
            }
            sendEditTaskMessage(update);
        } else if (EDIT_REMIND_STATE.equals(userState)) {
            int taskIndex = fileService.findTask(curMessage);
            if (taskIndex == -1) {
                output = "Этой задачи нет";
            } else {
                output = "Введите время напоминания (например: 10:20)";
                fileService.setTempTask(curMessage, taskIndex);
                fileService.deleteTask(curMessage, taskIndex);
                appUser.setState(WAITING_FOR_CHANGE_REMIND);
                appUserDAO.save(appUser);
            }
        } else if (WAITING_FOR_CHANGE_REMIND.equals(userState)) {
            try {
                Time time = processTaskTime(text);
                if (time != null) {
                    fileService.changeTaskRemind(curMessage);
                    output = "Напоминание было изменено";
                    sendEditTaskMessage(update);
                    appUser.setState(BASIC_STATE);
                    appUserDAO.save(appUser);
                } else {
                    output = "Вы неправильно ввели время";
                }
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        } else {
            log.error("Unknown user state: " + userState);
            output = "Неизвестная ошибка! Введите /cancel и попробуйте снова!";
        }

        sendAnswer(output, chatId);
    }

    @Override
    public void processPhotoMessage(Update update) {
        AppUser appUser = findOrSaveAppUser(update.getMessage().getFrom());
        Long chatId = update.getMessage().getChatId();

        UserState userState = appUser.getState();

        String answer;
        if (EDIT_PHOTO_STATE.equals(userState)) {
            try {
                fileService.processPhoto(update.getMessage());
                answer = "Фото успешно добавлено для вашей задачи!";
                sendAnswer(answer, chatId);
                sendEditTaskMessage(update);
            } catch (UploadFileException ex) {
                log.error(ex);
                String error = "К сожалению, загрузка фото не удалась. Повторите попытку позже.";
                sendAnswer(error, chatId);
            }
            appUser.setState(BASIC_STATE);
            appUserDAO.save(appUser);
        } else {
            answer = "Для того чтобы изменить фото задач, введите /edit";
            sendAnswer(answer, chatId);
        }
    }

    public Time processTaskTime(String timeString) throws ParseException {
        // Регулярное выражение для проверки формата времени HH:mm
        String timePattern = "^([01]?\\d|2[0-3]):[0-5]\\d$";
        Pattern pattern = Pattern.compile(timePattern);
        Matcher matcher = pattern.matcher(timeString);
        if (!matcher.matches()) {
            return null;
        }

        // Если время соответствует формату, преобразуем его в объект Time
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        long ms = sdf.parse(timeString).getTime();
        return new Time(ms);
    }

    public void sendUserPh(Long chatId, User userFrom) {
        String fileId = fileService.getPhotoByUserPhotoId(userFrom.getId());
        if (!fileId.isEmpty()) {
            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(chatId.toString());
            sendPhoto.setPhoto(new InputFile(fileId));
            sendPhoto.setCaption(sendListTasks(userFrom.getId()));

            producerService.producerPhAnswer(sendPhoto);
        } else {
            sendAnswer(sendListTasks(userFrom.getId()), chatId);
        }
    }

    private String sendListTasks(Long userId) {
        List<AppTask> tasks = fileService.getTasksByUserId(userId);
        tasks.sort(Comparator.comparing(AppTask::getDate).thenComparing(AppTask::getRemind));
        if (!tasks.isEmpty()) {
            StringBuilder responseText = new StringBuilder("Ваши задачи:\n");
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
            int count = 1;
            for (AppTask task : tasks) {
                responseText.append(count).append(". Задача: ").append(task.getName()).append("\n")
                        .append("Дата: ").append(dateFormat.format((task.getDate()))).append("\n");
                if (task.isToRemind()) {
                    responseText.append("Время напоминания: ").append(timeFormat.format(task.getRemind())).append("\n\n");
                } else {
                    responseText.append("\n");
                }
                count++;
            }
            return responseText.toString();
        } else {
            return "Вы ещё не добавили задачи";
        }
    }

    private void sendAnswer(String output, Long chatId) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(output);
        producerService.producerAnswer(sendMessage);
    }

    public void sendEditTaskMessage(Update update) {
        Long chatId = update.getMessage().getChatId();
        SendMessage message = createEditTaskMessage(chatId);
        producerService.producerAnswer(message);
        sendAnswer(editTasksText(), chatId);
    }

    public SendMessage createEditTaskMessage(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Выберите действие:");

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> rowInline1 = new ArrayList<>();
        rowInline1.add(InlineKeyboardButton.builder().text("1").callbackData("EDIT_1").build());
        rowInline1.add(InlineKeyboardButton.builder().text("2").callbackData("EDIT_2").build());
        rowInline1.add(InlineKeyboardButton.builder().text("3").callbackData("EDIT_3").build());
        rowInline1.add(InlineKeyboardButton.builder().text("4").callbackData("EDIT_4").build());
        rowInline1.add(InlineKeyboardButton.builder().text("5").callbackData("EDIT_5").build());

        rowsInline.add(rowInline1);

        inlineKeyboardMarkup.setKeyboard(rowsInline);
        message.setReplyMarkup(inlineKeyboardMarkup);

        return message;
    }

    private SendMessage sendYearKeyboard(Long chatId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton buttonCurYear = new InlineKeyboardButton();
        buttonCurYear.setText(String.valueOf(LocalDate.now().getYear()));
        buttonCurYear.setCallbackData("CUR_YEAR");
        row.add(buttonCurYear);
        InlineKeyboardButton buttonNYear = new InlineKeyboardButton();
        buttonNYear.setText(String.valueOf(LocalDate.now().getYear()+1));
        buttonNYear.setCallbackData("N_YEAR");
        row.add(buttonNYear);
        rows.add(row);

        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Выберите год");
        inlineKeyboardMarkup.setKeyboard(rows);
        message.setReplyMarkup(inlineKeyboardMarkup);
        return message;
    }

    private SendMessage sendMonthKeyboard(Long chatId, int year) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int currentYear = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();
        for (int month = 1; month <= 12; month++) {
            if (year == currentYear && month < currentMonth) {
                InlineKeyboardButton emptyButton = new InlineKeyboardButton();
                emptyButton.setText(" ");
                emptyButton.setCallbackData("EMPTY");
                // Каждые 7 кнопок добавляем новый ряд
                if (month % 4 == 1) {
                    rows.add(new ArrayList<>());
                }
                rows.get(rows.size() - 1).add(emptyButton);
            } else {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(String.format("%d", month));
            button.setCallbackData("MONTH_" + year + "_" + month);
            // Каждые 7 кнопок добавляем новый ряд
            if (month % 4 == 1) {
                rows.add(new ArrayList<>());
            }
            rows.get(rows.size() - 1).add(button);
            }
        }
        
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Выберите месяц:");
        inlineKeyboardMarkup.setKeyboard(rows);
        message.setReplyMarkup(inlineKeyboardMarkup);

        return message;
    }

    private SendMessage sendDayKeyboard(Long chatId, int year, int month) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        int daysInMonth = YearMonth.of(year, month).lengthOfMonth();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int currentYear = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();
        int currentDay = LocalDate.now().getDayOfMonth();
        // Создаем кнопки для каждого дня месяца
        for (int day = 1; day <= daysInMonth; day++) {
            if (year == currentYear && month == currentMonth && day < currentDay) {
                InlineKeyboardButton emptyButton = new InlineKeyboardButton();
                emptyButton.setText(" ");
                emptyButton.setCallbackData("EMPTY");
                // Каждые 7 кнопок добавляем новый ряд
                if (day % 7 == 1) {
                    rows.add(new ArrayList<>());
                }
                rows.get(rows.size() - 1).add(emptyButton);
            } else {
                InlineKeyboardButton button = new InlineKeyboardButton();
                button.setText(String.format("%2d", day));
                button.setCallbackData("DAY_" + year + "_" + month + "_" + day);
                // Каждые 7 кнопок добавляем новый ряд
                if (day % 7 == 1) {
                    rows.add(new ArrayList<>());
                }
                rows.get(rows.size() - 1).add(button);
            }
        }

        // Добавляем пустые кнопки в последний ряд, если их меньше 7
        List<InlineKeyboardButton> lastRow = rows.get(rows.size() - 1);
        while (lastRow.size() < 7) {
            InlineKeyboardButton emptyButton = new InlineKeyboardButton();
            emptyButton.setText(" ");
            emptyButton.setCallbackData("EMPTY");
            lastRow.add(emptyButton);
        }


        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Выберите день:");
        inlineKeyboardMarkup.setKeyboard(rows);
        message.setReplyMarkup(inlineKeyboardMarkup);

        return message;
    }

    private SendMessage sendRemindKeyboard(Long chatId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton buttonYes = new InlineKeyboardButton();
        buttonYes.setText("Да");
        buttonYes.setCallbackData("TO_REMIND");
        row.add(buttonYes);
        InlineKeyboardButton buttonNo = new InlineKeyboardButton();
        buttonNo.setText("Нет");
        buttonNo.setCallbackData("NOT_TO_REMIND");
        row.add(buttonNo);
        rows.add(row);

        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Нужно ли вам напоминание в Telegram?");
        inlineKeyboardMarkup.setKeyboard(rows);
        message.setReplyMarkup(inlineKeyboardMarkup);
        return message;
    }

    private String cancelProcess(AppUser appUser) {
        if (WAITING_FOR_CHANGE_REMIND.equals(appUser.getState())) {
            return "Сначала измените время напоминания";
        } else {
            appUser.setState(BASIC_STATE);
            appUserDAO.save(appUser);
            return "Команда отменена!";
        }
    }

    private AppUser findOrSaveAppUser(User telegramUser) {
        AppUser persistentAppUser = appUserDAO.findAppUserByTelegramUserId(telegramUser.getId());
        if (persistentAppUser == null) {
            AppUser transientAppUser = AppUser.builder()
                    .telegramUserId(telegramUser.getId())
                    .username(telegramUser.getUserName())
                    .firstName(telegramUser.getFirstName())
                    .lastName(telegramUser.getLastName())
                    .isActive(true)
                    .state(BASIC_STATE)
                    .build();
            return appUserDAO.save(transientAppUser);
        }
        return persistentAppUser;
    }
}