package com.dch.smartrecruters.service;

import java.util.UUID;

/**
 * Another worker holds a fresh claim on the event (e.g. a consumer that lost its partition but
 * is still finishing). Not processed here; Kafka retries it later, when the other worker
 * has either completed it (then it is ignored) or failed (then it is reclaimed).
 */
public class DeltaEventInProgressException extends RuntimeException {

    public DeltaEventInProgressException(UUID eventId) {
        super("Delta event " + eventId + " is being processed by another worker");
    }
}
