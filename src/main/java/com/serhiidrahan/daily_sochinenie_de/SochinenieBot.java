package com.serhiidrahan.daily_sochinenie_de;

import com.serhiidrahan.daily_sochinenie_de.entity.Assignment;
import com.serhiidrahan.daily_sochinenie_de.entity.User;
import com.serhiidrahan.daily_sochinenie_de.enums.AssignmentState;
import com.serhiidrahan.daily_sochinenie_de.service.AssignmentService;
import com.serhiidrahan.daily_sochinenie_de.service.ChatGPTService;
import com.serhiidrahan.daily_sochinenie_de.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.AfterBotRegistration;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Component
public class SochinenieBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SochinenieBot.class);

    private TelegramClient telegramClient;
    private final UserService userService;
    private final AssignmentService assignmentService;
    private final ChatGPTService chatGPTService;
    private final String botToken;

    public SochinenieBot(UserService userService, AssignmentService assignmentService, ChatGPTService chatGPTService,
                         @Value("${telegrambot.token}") String botToken) {
        this.userService = userService;
        this.assignmentService = assignmentService;
        this.chatGPTService = chatGPTService;
        this.botToken = botToken;
        telegramClient = new OkHttpTelegramClient(getBotToken());
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleTextMessage(update.getMessage());
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
        }
    }

    private void handleTextMessage(Message incomingMessage) {
        long chatId = incomingMessage.getChatId();
        String incomingMessageText = incomingMessage.getText().trim();
        Long telegramUserId = incomingMessage.getFrom().getId();
        String telegramUsername = incomingMessage.getFrom().getUserName();

        boolean userExists = userService.userExists(telegramUserId);
        User user = userService.getUser(telegramUserId, telegramUsername, chatId);

        if (!userExists || incomingMessageText.equalsIgnoreCase("/start")) {
            sendMessage(chatId, "Hi! I'm now going to give you your first assignment!");
            Assignment firstAssignment = assignmentService.assignNewTopic(user);
            sendAssignment(chatId, firstAssignment);
            return;
        }

        if (incomingMessageText.equalsIgnoreCase("new")) {
            assignNewAssignment(chatId, user);
            return;
        }

        Assignment currentAssignment = assignmentService.getCurrentActiveAssignment(user);
        assignmentService.changeAssignmentState(currentAssignment, AssignmentState.SUBMITTED);

        String feedback = chatGPTService.getFeedback(incomingMessageText);
        sendMessageWithButton(chatId, feedback + "\n\nWould you like a new assignment?", "I'm done, give me another", "new_assignment");
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        Long telegramUserId = callbackQuery.getFrom().getId();
        String telegramUsername = callbackQuery.getFrom().getUserName();
        User user = userService.getUser(telegramUserId, telegramUsername, chatId);

        if (callbackData.equals("new_assignment")) {
            assignNewAssignment(chatId, user);
        }
    }

    private void assignNewAssignment(Long chatId, User user) {
        Assignment currentAssignment = assignmentService.getCurrentActiveAssignment(user);

        if (currentAssignment.getState() == AssignmentState.SUBMITTED) {
            sendMessage(chatId, "Ok, here's your new assignment.");
            assignmentService.changeAssignmentState(currentAssignment, AssignmentState.DONE);
        } else {
            sendMessage(chatId, "I understand you don't want this one. Let's assign you another.");
            assignmentService.changeAssignmentState(currentAssignment, AssignmentState.CANCELLED);
        }

        Assignment newAssignment = assignmentService.assignNewTopic(user);
        sendAssignment(chatId, newAssignment);
    }

    private void sendAssignment(Long chatId, Assignment assignment) {
        String assignmentText = String.format("📌 *Your Assignment:*\n*%s*\n\n%s",
                assignment.getTopic().getTopicDe(),
                assignment.getTopic().getDescriptionDe());

        sendMessageWithButton(chatId, assignmentText, "I want another one", "new_assignment");
    }

    private void sendMessage(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown") // Allow bold formatting
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            LOGGER.error("Error sending message: {}", e.getMessage(), e);
        }
    }

    private void sendMessageWithButton(Long chatId, String text, String buttonText, String callbackData) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(InlineKeyboardButton.builder()
                        .text(buttonText)
                        .callbackData(callbackData)
                        .build())))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            LOGGER.error("Error sending message: {}", e.getMessage(), e);
        }
    }

    @AfterBotRegistration
    public void afterRegistration(BotSession botSession) {
        System.out.println("Registered bot running state is: " + botSession.isRunning());
    }
}
