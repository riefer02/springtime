package com.springtime.bigquery.model.query;

import com.springtime.bigquery.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageTokenTest {

    @Test
    void roundTripsOffsets() {
        assertThat(PageToken.decodeOffset(PageToken.encodeOffset(0))).isZero();
        assertThat(PageToken.decodeOffset(PageToken.encodeOffset(250))).isEqualTo(250);
        assertThat(PageToken.decodeOffset(
                PageToken.encodeOffset(PageToken.MAX_OFFSET))).isEqualTo(PageToken.MAX_OFFSET);
    }

    @Test
    void treatsAMissingTokenAsTheFirstPage() {
        assertThat(PageToken.decodeOffset(null)).isZero();
        assertThat(PageToken.decodeOffset("")).isZero();
        assertThat(PageToken.decodeOffset("   ")).isZero();
    }

    @Test
    void tokensAreBase64UrlAndOpaque() {
        String token = PageToken.encodeOffset(42);

        assertThat(token).doesNotContain("+", "/", "=");
        assertThat(new String(Base64.getUrlDecoder().decode(token))).isEqualTo("42");
    }

    @Test
    void rejectsMalformedTokens() {
        assertThatThrownBy(() -> PageToken.decodeOffset("not-a-token"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageToken.decodeOffset(PageToken.encodeOffset(1) + "!!"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageToken.decodeOffset(encodedAscii("abc")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageToken.decodeOffset(encodedAscii("-1")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageToken.decodeOffset(encodedAscii("1.5")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageToken.decodeOffset(encodedAscii("99999999999999999999")))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void stopsIssuingTokensAtTheOffsetCeilingInsteadOfReturningTheLastPageForever() {
        PageResponse<String> page = PageToken.page(
                List.of("a", "b"), PageToken.MAX_OFFSET, 1);

        assertThat(page.items()).containsExactly("a");
        assertThat(page.nextPageToken()).isNull();
    }

    @Test
    void rejectsOffsetsAboveTheBound() {
        String tooLarge = encodedAscii(Integer.toString(PageToken.MAX_OFFSET + 1));

        assertThatThrownBy(() -> PageToken.decodeOffset(tooLarge))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageToken.encodeOffset(PageToken.MAX_OFFSET + 1))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageToken.encodeOffset(-1))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsOverlongTokensBeforeDecoding() {
        String overlong = "A".repeat(PageToken.MAX_TOKEN_LENGTH + 1);

        assertThatThrownBy(() -> PageToken.decodeOffset(overlong))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void buildsAPageWithATokenOnlyWhenAnotherPageExists() {
        PageResponse<String> first = PageToken.page(List.of("a", "b"), 0, 1);

        assertThat(first.items()).containsExactly("a");
        assertThat(first.pageSize()).isEqualTo(1);
        assertThat(PageToken.decodeOffset(first.nextPageToken())).isEqualTo(1);

        PageResponse<String> last = PageToken.page(List.of("b"), 1, 1);

        assertThat(last.items()).containsExactly("b");
        assertThat(last.nextPageToken()).isNull();

        PageResponse<String> empty = PageToken.page(List.of(), 0, 10);

        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextPageToken()).isNull();
    }

    private static String encodedAscii(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
