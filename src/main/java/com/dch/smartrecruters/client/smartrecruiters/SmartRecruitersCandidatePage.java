package com.dch.smartrecruters.client.smartrecruiters;

import java.util.List;

/**
 * One page of target candidates, ordered by externalId.
 * Deliberately independent of Spring Data pagination types.
 */
public record SmartRecruitersCandidatePage(
        List<SmartRecruitersCandidate> items,
        int page,
        int size,
        boolean hasNext
) {

    public SmartRecruitersCandidatePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
