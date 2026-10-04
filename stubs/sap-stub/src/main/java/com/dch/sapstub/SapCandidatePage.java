package com.dch.sapstub;

import java.util.List;

public record SapCandidatePage(
        List<SapCandidate> items,
        int page,
        int size,
        boolean hasNext
) {
}
