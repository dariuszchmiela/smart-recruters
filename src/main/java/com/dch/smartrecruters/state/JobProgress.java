package com.dch.smartrecruters.state;

/**
 * Counter increments for one processed page.
 */
public record JobProgress(
        long processed,
        long succeeded,
        long skipped,
        long failed
) {
}
