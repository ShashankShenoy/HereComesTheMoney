package com.moneybags.privacy.common;

import java.util.List;

/** Stable paging envelope shared by all list endpoints and the frontend. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
