package com.springtime.bigquery.model.query;

import com.springtime.bigquery.error.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Opaque offset pagination tokens shared by every list endpoint.
 *
 * <p>A token is the base64url encoding of a non-negative decimal offset. Tokens are opaque to
 * clients, bounded in length and value, and never interpolated into SQL: callers are expected to
 * bind the decoded offset as a query parameter.</p>
 */
public final class PageToken {

    /** Hard ceiling on the raw token; anything longer is rejected before decoding. */
    public static final int MAX_TOKEN_LENGTH = 512;

    /** Hard ceiling on the decoded offset so a forged token cannot force an unbounded scan. */
    public static final int MAX_OFFSET = 1_000_000;

    private static final Pattern OFFSET = Pattern.compile("[0-9]{1,10}");

    private PageToken() {
    }

    /** Returns the offset encoded in {@code token}, or {@code 0} when no token was supplied. */
    public static int decodeOffset(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw invalid();
        }
        String decoded;
        try {
            decoded = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (!OFFSET.matcher(decoded).matches()) {
            throw invalid();
        }
        int offset;
        try {
            offset = Integer.parseInt(decoded);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw invalid();
        }
        return offset;
    }

    /** Encodes a non-negative offset as an opaque token. */
    public static String encodeOffset(int offset) {
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new InvalidRequestException("Pagination offset exceeds the supported maximum");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Integer.toString(offset).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Trims a {@code limit + 1} fetch to the first page and sets {@code nextPageToken} only when
     * the extra row proves another page exists. This is the single place that decides whether a
     * list response is truncated.
     */
    public static <T> PageResponse<T> page(List<T> fetched, int offset, int limit) {
        List<T> rows = fetched == null ? List.of() : fetched;
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? rows.subList(0, limit) : rows;
        // Stop issuing tokens at the offset ceiling instead of throwing on a token the server
        // itself produced; the bounded window is exhausted, not the client's request invalid.
        int nextOffset = offset + limit;
        String nextPageToken = hasMore && nextOffset <= MAX_OFFSET ? encodeOffset(nextOffset) : null;
        return new PageResponse<>(items, nextPageToken, limit);
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException(
                "pageToken is malformed; use the nextPageToken from a previous response");
    }
}
