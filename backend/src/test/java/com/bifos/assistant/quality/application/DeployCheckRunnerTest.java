package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.quality.application.dto.CheckObservation;
import com.bifos.assistant.quality.application.dto.TurnObservation;
import com.bifos.assistant.quality.application.dto.TurnOutcome;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.quality.domain.FeatureResult;
import com.bifos.assistant.quality.domain.type.FeatureStatus;
import com.bifos.assistant.quality.domain.type.QualityFeature;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 배포 뒤 확인 실행기가 평가 비서의 Control Plane MCP 연결이 끊긴 채 실패한 기능을 환경 오류로 바꾸는지 본다. Hermes 와 DB 없이 대역으로 본다. 대화를 실제로
 * 거치는 흐름은 가짜 Hermes e2e 가 본다.
 */
class DeployCheckRunnerTest {

    private static final Long RUN = 1L;
    private static final Long CONVERSATION = 41L;
    private static final String PROFILE = "evaluation-profile";

    private final DeployCheckRuns runs = mock(DeployCheckRuns.class);
    private final DeployCheckObserver observer = mock(DeployCheckObserver.class);
    private final EvaluationUserService evaluationUsers = mock(EvaluationUserService.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ChatService chat = mock(ChatService.class);
    private final TurnDriver driver = mock(TurnDriver.class);
    private final ControlPlaneConnection connection = mock(ControlPlaneConnection.class);
    private DeployCheckRunner runner;


    @BeforeEach
    void setUp() {
        QualityProperties properties = new QualityProperties(Duration.ofMinutes(10), Duration.ofMillis(10), Duration.ofDays(30), "0 15 5 * * *",
            "classpath:quality/bundles/", null);
        runner = new DeployCheckRunner(runs, new DeployCheckJudge(), observer, mock(DeployCheckCleaner.class), evaluationUsers, users, chat,
            mock(AttachmentService.class), mock(TaskService.class), mock(TurnCancellation.class), driver, connection, mock(BackgroundTasks.class),
            LiveProperties.fixed(QualityProperties.class, properties), Clock.systemUTC());
        when(users.findById(7L)).thenReturn(Optional.of(AppUser.of("evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER)));
        Agent primary = mock(Agent.class);
        when(primary.code()).thenReturn(PROFILE);
        when(primary.hermesProfile()).thenReturn(PROFILE);
        when(evaluationUsers.primaryAgent(any())).thenReturn(Optional.of(primary));
        Conversation conversation = mock(Conversation.class);
        when(conversation.id()).thenReturn(CONVERSATION);
        when(conversation.publicId()).thenReturn(UUID.randomUUID());
        when(chat.startEmpty(any(), eq(PROFILE))).thenReturn(conversation);
        when(driver.send(any(), eq(CONVERSATION), any(), anyList(), any())).thenReturn(TurnOutcome.DONE);
        when(observer.roots(anyLong())).thenReturn(List.of());
        // 기억 읽기만 통과하는 답이다. 도구 사건이 없어 검색과 도구 찾기는 실패한다.
        TurnObservation turn = new TurnObservation("좋아하는 색은 청록이에요", "SUCCEEDED", null, List.of(), List.of(), List.of(), false);
        when(observer.observe(any(), eq(CONVERSATION), eq(false))).thenReturn(new CheckObservation(List.of(turn), 0, false, null, null, false));
        when(connection.atStart(PROFILE)).thenReturn(true);
    }


    @Test
    @DisplayName("연결이 끊긴 채 끝나면 실패한 기능은 모두 ENVIRONMENT 이고 통과한 기억 읽기는 그대로다")
    void disconnectedFailuresBecomeEnvironmentErrors() {
        when(connection.atEnd(PROFILE)).thenReturn(false);

        Map<QualityFeature, FeatureResult> results = run();

        assertThat(results.get(QualityFeature.MEMORY_READ).status()).isEqualTo(FeatureStatus.PASSED);
        assertThat(results.values().stream().filter(result -> result.status() != FeatureStatus.PASSED))
            .isNotEmpty()
            .allSatisfy(result -> assertThat(result.reasonCode()).isEqualTo("ENVIRONMENT"));
    }


    @Test
    @DisplayName("연결이 살아 있으면 기능 규칙의 까닭을 그대로 남기고 도구 찾기는 NO_TOOL 이다")
    void connectedFailuresKeepTheirReasons() {
        when(connection.atEnd(PROFILE)).thenReturn(true);

        Map<QualityFeature, FeatureResult> results = run();

        assertThat(results.get(QualityFeature.SEARCH).reasonCode()).isEqualTo("NO_SEARCH");
        assertThat(results.get(QualityFeature.TOOL_DISCOVERY).reasonCode()).isEqualTo("NO_TOOL");
        assertThat(results.values()).noneMatch(result -> "ENVIRONMENT".equals(result.reasonCode()));
    }


    private Map<QualityFeature, FeatureResult> run() {
        runner.run(RUN, 7L);
        ArgumentCaptor<FeatureResult> recorded = ArgumentCaptor.forClass(FeatureResult.class);
        verify(runs, atLeastOnce()).record(eq(RUN), recorded.capture(), anyInt());
        verify(runs).finish(RUN);
        return recorded.getAllValues().stream().collect(Collectors.toMap(FeatureResult::feature, Function.identity()));
    }
}
