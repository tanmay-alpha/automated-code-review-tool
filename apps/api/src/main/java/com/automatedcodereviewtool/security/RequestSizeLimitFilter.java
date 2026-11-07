package com.automatedcodereviewtool.security;

import com.automatedcodereviewtool.logging.SecurityEventLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * Rejects requests with an oversized query string, an oversized single
 * header, or a declared {@code Content-Length} above the body limit.
 *
 * <p>Bodies without a {@code Content-Length} (chunked encoding) are bounded
 * while they are read: the request stream throws
 * {@link BodyTooLargeException} once more than the limit has been consumed,
 * so no endpoint can be made to buffer an unbounded body.</p>
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final SecurityEventLogger securityLogger;
    private final long maxBodySize;
    private final int maxQuerySize;
    private final int maxHeaderSize;

    public RequestSizeLimitFilter(SecurityEventLogger securityLogger,
                                  @Value("${app.request.max-body-size:1048576}") long maxBodySize,
                                  @Value("${app.request.max-query-size:4096}") int maxQuerySize,
                                  @Value("${app.request.max-header-size:4096}") int maxHeaderSize) {
        this.securityLogger = securityLogger;
        this.maxBodySize = maxBodySize;
        this.maxQuerySize = maxQuerySize;
        this.maxHeaderSize = maxHeaderSize;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String violation = findViolation(request);
        if (violation != null) {
            String ip = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
            securityLogger.logSecurityViolation("REQUEST_TOO_LARGE", violation, ip);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"REQUEST_LIMIT_EXCEEDED\",\"message\":\"" + violation + "\"}");
            return;
        }
        filterChain.doFilter(new LimitedBodyRequest(request, maxBodySize), response);
    }

    private String findViolation(HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString != null && queryString.getBytes(StandardCharsets.UTF_8).length > maxQuerySize) {
            return "Query string exceeds " + maxQuerySize + " bytes";
        }
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String value = request.getHeader(name);
            if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxHeaderSize) {
                return "A request header exceeds " + maxHeaderSize + " bytes";
            }
        }
        if (request.getContentLengthLong() > maxBodySize) {
            return "Request body exceeds " + maxBodySize + " bytes";
        }
        return null;
    }

    /** Thrown by the request body stream once more than the configured limit is read. */
    public static final class BodyTooLargeException extends IOException {
        public BodyTooLargeException(long limit) {
            super("Request body exceeds " + limit + " bytes");
        }
    }

    /** Exposes the body only through a stream that enforces {@code limit}. */
    static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;
        private BufferedReader reader;

        LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long consumed;

        LimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = delegate.read(buffer, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws BodyTooLargeException {
            consumed += n;
            if (consumed > limit) {
                throw new BodyTooLargeException(limit);
            }
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
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
