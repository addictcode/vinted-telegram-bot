package com.example.vintedbot.service;

public class VintedParseException extends RuntimeException {

    public enum Reason {
        INVALID_URL,
        NOT_FOUND,
        BLOCKED,          // Cloudflare / anti-bot challenge
        TIMEOUT,
        UNKNOWN
    }

    private final Reason reason;

    public VintedParseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public VintedParseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
