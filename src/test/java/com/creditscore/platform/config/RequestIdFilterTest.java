package com.creditscore.platform.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesARequestIdWhenNoneIsSuppliedAndReturnsItAsAResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/businesses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        String headerValue = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(headerValue).isNotBlank();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void honorsAnIncomingRequestIdInsteadOfOverwritingIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/businesses");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "caller-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("caller-supplied-id");
    }

    @Test
    void putsTheRequestIdInMdcForTheDurationOfTheChainAndClearsItAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/businesses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);
        String[] mdcValueDuringChain = new String[1];
        doAnswer(invocation -> {
            mdcValueDuringChain[0] = MDC.get("requestId");
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilter(request, response, filterChain);

        assertThat(mdcValueDuringChain[0]).isNotBlank();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void clearsMdcEvenWhenTheDownstreamChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/businesses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);
        try {
            doAnswer(invocation -> {
                throw new IllegalStateException("downstream blew up");
            }).when(filterChain).doFilter(request, response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void skipsTheAccessLogLineForActuatorButStillSetsTheRequestId() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = mock(FilterChain.class);

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isNotBlank();
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void stillLogsTheAccessLogLineForANonActuatorPath() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/businesses");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = mock(FilterChain.class);

            filter.doFilter(request, response, filterChain);

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("/api/v1/businesses");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
