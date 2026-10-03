package com.dch.sapstub;

public enum FailureMode {
    /**
     * Respond 503 Service Unavailable without looking up the candidate.
     */
    UNAVAILABLE,
    /**
     * Sleep for the configured delay, then respond normally.
     */
    DELAY
}
