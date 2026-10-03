package com.dch.srstub;

public enum FailureMode {
    /**
     * Respond 503 Service Unavailable before anything is stored.
     */
    UNAVAILABLE,
    /**
     * Store the candidate, then respond 503 - simulates a lost response.
     */
    UNAVAILABLE_AFTER_SAVE,
    /**
     * Store the candidate, then sleep before responding - client read timeout after a successful write.
     */
    DELAY_AFTER_SAVE
}
