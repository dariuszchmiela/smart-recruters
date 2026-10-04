package com.dch.smartrecruters.service;

/**
 * Processing this event again cannot succeed (invalid event, candidate fails validation,
 * permanent source/target error). Kafka must not retry it; it goes straight to the dead letter topic.
 */
public class PermanentDeltaEventException extends RuntimeException {

    public PermanentDeltaEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public PermanentDeltaEventException(String message) {
        super(message);
    }
}
