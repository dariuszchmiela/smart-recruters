package com.dch.smartrecruters.client;

public enum FailureType {
    /**
     * The same request may succeed later (timeout, I/O problem, 429, 502, 503, 504).
     */
    TRANSIENT,
    /**
     * Repeating the same request will not help (400, 401, 403, 404, other errors).
     */
    PERMANENT
}
