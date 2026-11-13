package com.example.aftersales.security;

import io.lettuce.core.RedisCommandTimeoutException;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisAvailabilityFilterTest {

    private final RedisAvailabilityFilter filter = new RedisAvailabilityFilter(new ObjectMapper());

    @Test
    void translatesWrappedRedisFailureAndDiscardsBufferedBody() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (request, currentResponse) -> {
            currentResponse.getWriter().write("partial success must not be returned");
            throw new ServletException(new RedisConnectionFailureException("test unavailable"));
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("AUTH_SERVICE_UNAVAILABLE")
                .doesNotContain("partial success");
    }

    @Test
    void resetsOutputStreamModeAndContentLengthBeforeWriting503() throws Exception {
        var response = new MockHttpServletResponse();
        var strict = new StrictResponse(response);
        filter.doFilter(new MockHttpServletRequest(), strict, (request, currentResponse) -> {
            var httpResponse = (jakarta.servlet.http.HttpServletResponse) currentResponse;
            httpResponse.setHeader("X-Frame-Options", "DENY");
            httpResponse.setHeader("Set-Cookie", "JSESSIONID=not-saved");
            currentResponse.setContentLength(7);
            currentResponse.getOutputStream().write("success".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw new RedisConnectionFailureException("test commit failure");
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getContentAsString()).contains("AUTH_SERVICE_UNAVAILABLE").doesNotContain("success");
    }

    @Test
    void translatesRedisTimeoutWrappedInGenericDataAccessException() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (request, currentResponse) -> {
            throw new QueryTimeoutException("test timeout", new RedisCommandTimeoutException("test timeout"));
        });
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void doesNotMislabelDatabaseErrorsAsRedisUnavailability() {
        var exception = new DataIntegrityViolationException("test database error");
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(),
                new MockHttpServletResponse(), (request, response) -> { throw exception; }))
                .isSameAs(exception);
    }

    @Test
    void propagatesFailureWhenResponseHasAlreadyBeenCommitted() throws Exception {
        var response = new MockHttpServletResponse();
        response.flushBuffer();
        var exception = new RedisConnectionFailureException("test unavailable");
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response,
                (request, currentResponse) -> { throw exception; })).isSameAs(exception);
    }

    /** Mock 响应默认不强制 Servlet 的 writer/stream 互斥，在此执行真实容器约束。 */
    private static class StrictResponse extends HttpServletResponseWrapper {
        private boolean writerSelected;
        private boolean streamSelected;

        StrictResponse(MockHttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writerSelected) {
                throw new IllegalStateException("writer already selected");
            }
            streamSelected = true;
            return super.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (streamSelected) {
                throw new IllegalStateException("output stream already selected");
            }
            writerSelected = true;
            return super.getWriter();
        }

        @Override
        public void reset() {
            super.reset();
            writerSelected = false;
            streamSelected = false;
        }
    }

}
