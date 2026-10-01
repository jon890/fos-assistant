package com.bifos.assistant.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class AsyncRequestNotUsableTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    private ListAppender<ILoggingEvent> logs;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        originalLevel = handlerLogger().getLevel();
        handlerLogger().setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        handlerLogger().detachAppender(logs);
        handlerLogger().setLevel(originalLevel);
        logs.stop();
    }

    @Test
    @DisplayName("쓸 수 없는 비동기 응답에는 본문 없이 DEBUG 한 줄만 남긴다")
    void handlesUnusableResponseWithoutBodyOrErrorLog() throws Exception {
        mvc.perform(get("/unusable"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("async response is no longer usable: disconnected client");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("일반 예외는 여전히 ERROR 로그와 500 JSON 응답을 남긴다")
    void keepsUnexpectedExceptionsAsErrorWithJsonBody() throws Exception {
        mvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("internal error"));

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("unexpected error");
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    private static Logger handlerLogger() {
        return (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    }

    @RestController
    private static class FailingController {

        @GetMapping("/unusable")
        String unusable() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("disconnected client", new IOException("Broken pipe"));
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("unexpected failure");
        }
    }
}
