package com.springtime.bigquery.web;

import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.JobException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpstreamErrorMapperTest {

    private final UpstreamErrorMapper mapper = new UpstreamErrorMapper();

    @Test
    void mapsJobReasonsByPrefixAndSuffix() {
        assertThat(map("jobRateLimitExceeded").code()).isEqualTo(ErrorCode.UPSTREAM_RATE_LIMITED);
        assertThat(map("jobQuotaExceeded").code()).isEqualTo(ErrorCode.UPSTREAM_RATE_LIMITED);
        assertThat(map("jobBackendError").code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(map("jobInternalError").code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(map("jobTimeout").code()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT);
        assertThat(map("invalidQuery").code()).isEqualTo(ErrorCode.UPSTREAM_REJECTED);
    }

    @Test
    void collapsesWhitespaceInClientVisibleDetail() {
        var mapped = mapper.map(new BigQueryException(400, "line one\nline two\tmore"));

        assertThat(mapped.detail()).doesNotContain("\n").doesNotContain("\t");
    }

    @Test
    void mapsUnknownJobReasonToUpstreamError() {
        assertThat(map("somethingNew").code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
    }

    private UpstreamErrorMapper.MappedError map(String reason) {
        JobException exception = mock(JobException.class);
        when(exception.getErrors()).thenReturn(List.of(new BigQueryError(reason, "US", "detail")));
        return mapper.map(exception);
    }
}
