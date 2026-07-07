package com.example.vintedbot.dto;

/**
 * Where to deliver a message: a chat, optionally a forum topic thread inside it.
 */
public record SendTarget(Long chatId, Long messageThreadId) {

    public static SendTarget chat(Long chatId) {
        return new SendTarget(chatId, null);
    }

    public static SendTarget topic(Long chatId, Long messageThreadId) {
        return new SendTarget(chatId, messageThreadId);
    }
}
