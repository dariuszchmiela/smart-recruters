package com.dch.smartrecruters.state;

import java.util.List;

public record ReconciliationItemPage(
        List<ReconciliationItem> items,
        int page,
        int size,
        boolean hasNext
) {
}
