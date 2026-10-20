package com.bifos.assistant.chat.turn.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ChatEventStreamsTest {

    private final Logger logger = (Logger)LoggerFactory.getLogger(ChatEventStreams.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final ChatEventStreams streams = new ChatEventStreams(Duration.ofSeconds(20));


    @BeforeEach
    void setUp() {
        logs.list = new CopyOnWriteArrayList<>();
        logs.start();
        logger.addAppender(logs);
    }


    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }


    @Test
    @DisplayName("스트림 안의 API 거절은 코드와 문구를 스택 트레이스 없이 warn 로그로 남긴다")
    void logsApiExceptionAsWarn() throws InterruptedException {
        streams.open(events -> {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "the selected model is unavailable for this agent");
        });

        awaitLog(Level.WARN, "chat stream api error code=VALIDATION_FAILED "
            + "message=the selected model is unavailable for this agent");

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN)
            .singleElement().satisfies(event -> assertThat(event.getThrowableProxy()).isNull());
    }


    @Test
    @DisplayName("스트림 안의 일반 예외는 기존 error 로그만 남기고 API 거절 warn 로그는 남기지 않는다")
    void logsUnexpectedExceptionAsError() throws InterruptedException {
        streams.open(events -> {
            throw new IllegalStateException("unexpected failure");
        });

        awaitLog(Level.ERROR, "chat stream failed");

        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().startsWith("chat stream api error"));
    }


    private void awaitLog(Level level, String message) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (logs.list.stream().anyMatch(event -> event.getLevel() == level
                && event.getFormattedMessage().equals(message))) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).isEqualTo(message);
        });
    }
}
