package com.dch.smartrecruters.client.sap;

import java.util.List;

/**
 * One page of SAP candidates, ordered by candidate id.
 * Deliberately independent of Spring Data pagination types.
 */
public record SapCandidatePage(
        List<SapCandidate> items,
        int page,
        int size,
        boolean hasNext
) {

    public SapCandidatePage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public int nextPage() {
        return page + 1;
    }
}
