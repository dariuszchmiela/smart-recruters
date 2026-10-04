package com.dch.srstub;

import java.util.List;

public record CandidatePage(
        List<StoredCandidate> items,
        int page,
        int size,
        boolean hasNext
) {
}
