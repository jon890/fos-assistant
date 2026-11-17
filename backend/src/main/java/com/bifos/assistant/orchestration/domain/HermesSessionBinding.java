package com.bifos.assistant.orchestration.domain;

import com.bifos.assistant.orchestration.domain.type.NativeResultStatus;
import com.bifos.assistant.shared.domain.CreatedAtEntity;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Hermes 하위 에이전트 session 하나가 어느 FOS 실행(origin 실행)에서 시작됐는지 적은 등록 한 줄이다.
 *
 * <p>origin 과 사용자 칸은 바꾸지 않는다. 같은 대화의 다음 turn 이 시작돼도 그 하위 에이전트는 처음 origin 실행에 속한다.
 * 끝 소식과 결과 칸({@code stopped_at} 부터 {@code notice_message_id} 까지, 맡긴 일 앞부분)은 엔티티 저장이 쓰지 않고
 * {@code HermesSessionBindingRepository} 의 조건부 update 로만 바꾼다(ADR-20261117 / native-delegation-wake).
 * 최상위 session 은 여기 적지 않는다. 대화 session 은 여러 turn 이 이어 쓰므로 실행 줄의
 * {@code hermes_session_id} 로 찾는다. 근거는 ADR-037 이다.
 *
 * <p>{@code userId} 와 {@code originExecutionId} 는 서버가 방금 읽은 origin 실행에서 옮겨 적는다. 요청에서 받지
 * 않는다.
 */
@Entity
@Table(
    name = "hermes_session_binding",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_hermes_session_binding_session",
        columnNames = {"profile_name", "session_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class HermesSessionBinding extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "profile_name", nullable = false, length = 64)
    @Getter
    private String profileName;

    @Column(name = "session_id", nullable = false, length = 128)
    @Getter
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    @Getter
    private Long userId;

    @Column(name = "origin_execution_id", nullable = false)
    @Getter
    private Long originExecutionId;

    @Column(name = "root_session_id", nullable = false, length = 128)
    @Getter
    private String rootSessionId;

    @Column(name = "parent_session_id", nullable = false, length = 128)
    @Getter
    private String parentSessionId;

    /** 끝 소식으로 부모 대화를 깨울 최상위 자식인가. 등록할 때 한 번 정한다. */
    @Column(name = "wake_eligible", nullable = false, updatable = false)
    @Getter
    private boolean wakeEligible;

    @Column(name = "goal_preview", length = 512, insertable = false, updatable = false)
    @Getter
    private String goalPreview;

    @Column(name = "stopped_at", insertable = false, updatable = false)
    @Getter
    private Instant stoppedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", length = 16, insertable = false, updatable = false)
    @Getter
    private NativeResultStatus resultStatus;

    @Column(name = "result_text", columnDefinition = "LONGTEXT", insertable = false, updatable = false)
    @Getter
    private String resultText;

    @Column(name = "result_key_id", insertable = false, updatable = false)
    @Getter
    private Long resultKeyId;

    @Column(name = "result_delivered_at", insertable = false, updatable = false)
    @Getter
    private Instant resultDeliveredAt;

    @Column(name = "timeout_noticed_at", insertable = false, updatable = false)
    @Getter
    private Instant timeoutNoticedAt;

    @Column(name = "notice_message_id", insertable = false, updatable = false)
    @Getter
    private Long noticeMessageId;


    private HermesSessionBinding(
        String profileName,
        String sessionId,
        AgentExecution origin,
        String rootSessionId,
        String parentSessionId,
        boolean wakeEligible) {
        this.profileName = profileName;
        this.sessionId = sessionId;
        this.userId = origin.userId();
        this.originExecutionId = origin.id();
        this.rootSessionId = rootSessionId;
        this.parentSessionId = parentSessionId;
        this.wakeEligible = wakeEligible;
    }


    public static HermesSessionBinding of(
        String profileName,
        String sessionId,
        AgentExecution origin,
        String rootSessionId,
        String parentSessionId) {
        return new HermesSessionBinding(profileName, sessionId, origin, rootSessionId, parentSessionId, false);
    }


    /** 끝 소식으로 깨울지를 정해 적는 등록 줄이다. 판정은 {@code SubagentSessionRegistrar} 가 한다. */
    public static HermesSessionBinding of(
        String profileName,
        String sessionId,
        AgentExecution origin,
        String rootSessionId,
        String parentSessionId,
        boolean wakeEligible) {
        return new HermesSessionBinding(profileName, sessionId, origin, rootSessionId, parentSessionId, wakeEligible);
    }
}
