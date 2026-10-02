package com.reservation.booking_system.exception;

import lombok.Getter;

@Getter
public class ConflictException extends RuntimeException {

    private final String reason;

    public ConflictException(String message, String reason) {
        super(message);
        this.reason = reason;
    }
}