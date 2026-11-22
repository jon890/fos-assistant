package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.TurnOutcome;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.dto.TurnMark;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 문항 보내기가 끝남, 시간 초과, 한도 거절, 그 밖의 거절을 나눠 돌려주는지 본다. Hermes 와 DB 없이 대역으로 본다. */
class TurnDriverTest {

    private static final CurrentUser EVALUATION = new CurrentUser(7L, "evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER);
    private static final Long CONVERSATION = 11L;

    private final ChatService chat = mock(ChatService.class);
    private final TurnCancellation turns = mock(TurnCancellation.class);
    private final AgentDelegationService delegations = mock(AgentDelegationService.class);
    private final DeployCheckObserver observer = mock(DeployCheckObserver.class);
    private final Clock clock = Clock.systemUTC();
    private TurnDriver driver;


    @BeforeEach
    void setUp() {
        BackgroundTasks background = new BackgroundTasks() {
            @Override
            public Thread start(String name, Runnable task) {
                return Thread.ofVirtual().name(name).start(task);
            }


            @Override
            public Thread unstarted(String name, Runnable task) {
                return Thread.ofVirtual().name(name).unstarted(task);
            }
        };
        QualityProperties properties = new QualityProperties(Duration.ofMinutes(10), Duration.ofMillis(10), Duration.ofDays(30), "0 15 5 * * *",
            "classpath:quality/bundles/", null);
        driver = new TurnDriver(chat, turns, delegations, observer, background, LiveProperties.fixed(QualityProperties.class, properties), clock);
        when(observer.roots(CONVERSATION)).thenReturn(List.of());
        when(turns.markOf(CONVERSATION)).thenReturn(TurnMark.NONE);
    }


    @Test
    @DisplayName("send 가 돌아오면 끝남이다")
    void returnsDoneWhenSendReturns() {
        assertThat(driver.send(EVALUATION, CONVERSATION, "질문", List.of(), Instant.now().plusSeconds(5))).isEqualTo(TurnOutcome.DONE);
        verify(chat).send(eq(EVALUATION), eq(CONVERSATION), eq("질문"), isNull(), anyList());
    }


    @Test
    @DisplayName("실행 줄이 생기기 전 사용자 한도로 거절되면 한도 거절, 다른 까닭이면 거절이다")
    void mapsRejections() {
        doThrow(new ApiException(ErrorCode.USER_BUSY, "busy")).when(chat).send(any(), anyLong(), any(), any(), anyList());
        assertThat(driver.send(EVALUATION, CONVERSATION, "질문", List.of(), Instant.now().plusSeconds(5))).isEqualTo(TurnOutcome.BUSY);

        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "bad")).when(chat).send(any(), anyLong(), any(), any(), anyList());
        assertThat(driver.send(EVALUATION, CONVERSATION, "질문", List.of(), Instant.now().plusSeconds(5))).isEqualTo(TurnOutcome.REJECTED);
    }


    @Test
    @DisplayName("마감이 지나면 도는 turn 을 멈추고 시간 초과다")
    void timesOutAndStopsTheTurn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(chat.send(any(), anyLong(), any(), any(), anyList())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        });
        when(turns.markOf(CONVERSATION)).thenReturn(new TurnMark(true, 99L));
        // 멈추면 막혀 있던 send 가 돌아온다. 실제로는 Hermes run 이 멈춰 turn 이 닫힌다.
        doAnswer(invocation -> {
            release.countDown();
            return null;
        }).when(chat).stop(EVALUATION, 99L);

        TurnOutcome outcome = driver.send(EVALUATION, CONVERSATION, "질문", List.of(), Instant.now().plusMillis(50));

        assertThat(outcome).isEqualTo(TurnOutcome.TIMED_OUT);
        verify(chat, atLeastOnce()).stop(EVALUATION, 99L);
    }
}
