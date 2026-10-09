package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.RecoveredRunRecorder;
import com.bifos.assistant.chat.application.ResultDeliveryRecorder;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryAttemptRepository;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.feedback.application.FeedbackConversations;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.memory.application.MemoryContentSealer;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.people.application.HermesProfileProvisioner;
import com.bifos.assistant.proactive.application.CheckNotificationPolicy;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import com.bifos.assistant.skill.infra.SkillStore;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.PriceCatalog;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * backend 통합 검사가 함께 쓰는 Spring 검사 컨텍스트다.
 *
 * <p>검사 클래스마다 대역과 웹 환경이 달라지면 Spring 이 컨텍스트를 따로 띄우고 보관한다. 기동이 전체 검사 시간의 절반 가까이를
 * 차지하고, 보관한 컨텍스트가 검사 JVM 힙을 채운다. 이 주석을 단 검사는 아래를 함께 쓴다.
 *
 * <ul>
 *   <li>웹 환경은 {@code RANDOM_PORT} 다. 실제 HTTP 로 부르는 검사와 아닌 검사가 같은 컨텍스트를 쓴다
 *   <li>Hermes 실행은 {@link IntegrationTestDoubles} 의 {@code StubHermesRunsClient} 가 받는다
 *   <li>시계는 {@link TestClock} 이다. 검사가 정하지 않으면 실제 시각을 준다
 *   <li>Hermes 의 사건 스트림, toolset, 커넥터, 스킬, 모델, 대시보드 클라이언트는 Mockito mock 이다. 검사는 {@code @Autowired}
 *       로 받아 동작을 정한다. mock 은 검사마다 초기화된다
 *   <li>검사가 동작을 바꾸거나 호출을 확인하는 운영 빈 몇 개는 Mockito spy 다. 정하지 않으면 실제 동작 그대로다
 *   <li>요청 밖 작업은 {@link TrackingBackgroundTasks} 가 띄운다. {@link IntegrationTestIsolation} 이 검사가 끝날 때 그 작업이
 *       모두 끝나기를 기다리고 대역과 시계를 되돌린다
 * </ul>
 *
 * <p>설정을 바꾸는 검사는 {@link OverrideProperties} 를 더한다. {@link IntegrationTestIsolation} 이 검사마다 적용하고 되돌리므로
 * 컨텍스트가 늘지 않는다. 쓰는 법은 {@code backend/docs/code-architecture.md} 「설정 바꾸기」 가 갖는다.
 *
 * <p>{@link IntegrationTestIsolation} 은 {@code @SpringBootTest} 보다 뒤에 등록한다. JUnit 은 {@code AfterEachCallback} 을
 * 등록의 역순으로 부르므로, 이 순서여야 Spring 의 mock 초기화와 트랜잭션 되돌리기보다 join 이 먼저 돈다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestDoubles.class)
@MockitoBean(
        types = {
            HermesRunEventStream.class,
            HermesToolsetClient.class,
            HermesConnectorClient.class,
            HermesSkillClient.class,
            HermesModelClient.class,
            HermesDashboardClient.class
        })
@MockitoSpyBean(
        types = {
            AgentConnectorBindings.class,
            AgentDelegationService.class,
            AgentEndpointProbe.class,
            AgentRepository.class,
            AgentService.class,
            AppUserRepository.class,
            AttachmentService.class,
            AutonomyDecisionRepository.class,
            ChatMessageRepository.class,
            ChatPendingMessageRepository.class,
            ChatService.class,
            CheckNotificationPolicy.class,
            ContextAssembler.class,
            ConversationEventHub.class,
            ExecutionEventRepository.class,
            ExecutionSkillUseRepository.class,
            FeedbackConversations.class,
            HermesProfileProvisioner.class,
            MemoryContentSealer.class,
            PriceCatalog.class,
            ProactiveCheckGuard.class,
            ProactiveCheckService.class,
            RecoveredRunRecorder.class,
            ResultDeliveryAttemptRepository.class,
            ResultDeliveryRecorder.class,
            SkillCommandCatalog.class,
            SkillService.class,
            SkillStore.class,
            TurnCancellation.class,
            UserExecutionLimiter.class
        })
@ExtendWith(IntegrationTestIsolation.class)
public @interface BackendIntegrationTest {}
