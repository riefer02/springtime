package com.springtime.bigquery.model.query;

import java.util.List;

/** A single page of catalog results. */
public record PageResponse<T>(
        List<T> items,
        String nextPageToken,
        int pageSize
) {
    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
