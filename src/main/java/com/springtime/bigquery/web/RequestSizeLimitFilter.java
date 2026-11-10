package com.springtime.bigquery.web;

import com.springtime.bigquery.config.AppHttpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Rejects oversized JSON bodies before they reach the application.
 *
 * <p>Uses the declared Content-Length as a fast path and counts bytes for chunked/undeclared
 * bodies, so a caller cannot bypass the limit with {@code Transfer-Encoding: chunked}.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final AppHttpProperties properties;
    private final ProblemResponseWriter problemWriter;

    public RequestSizeLimitFilter(AppHttpProperties properties, ProblemResponseWriter problemWriter) {
        this.properties = properties;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long limit = properties.maxRequestBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > limit) {
            reject(request, response, limit);
            return;
        }

        HttpServletRequest effective = contentLength < 0
                ? new LimitedRequestWrapper(request, limit)
                : request;
        try {
            chain.doFilter(effective, response);
        } catch (PayloadTooLargeException exception) {
            if (response.isCommitted()) {
                throw exception;
            }
            reject(request, response, limit);
        } catch (IOException | ServletException | RuntimeException exception) {
            PayloadTooLargeException tooLarge = find(exception);
            if (tooLarge == null) {
                throw exception;
            }
            if (response.isCommitted()) {
                throw tooLarge;
            }
            reject(request, response, limit);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long limit)
            throws IOException {
        problemWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE,
                "Request body must not exceed %d bytes".formatted(limit));
    }

    private static PayloadTooLargeException find(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PayloadTooLargeException tooLarge) {
                return tooLarge;
            }
            current = current.getCause();
        }
        return null;
    }

    private static final class PayloadTooLargeException extends RuntimeException {
        private PayloadTooLargeException() {
            super("Request body exceeds the configured limit", null, false, false);
        }
    }

    private static final class LimitedRequestWrapper extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream limitedInputStream;

        private LimitedRequestWrapper(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitedInputStream == null) {
                limitedInputStream = new LimitedServletInputStream(super.getInputStream(), limit);
            }
            return limitedInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        private LimitedServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next != -1 && ++count > limit) {
                throw new PayloadTooLargeException();
            }
            return next;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                count += read;
                if (count > limit) {
                    throw new PayloadTooLargeException();
                }
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
