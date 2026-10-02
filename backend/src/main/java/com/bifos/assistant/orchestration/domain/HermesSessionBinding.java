package com.bifos.assistant.orchestration.domain;

import com.bifos.assistant.usage.domain.AgentExecution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Hermes 하위 에이전트 session 하나가 어느 FOS 실행(origin 실행)에서 시작됐는지 적은 등록 한 줄이다.
 *
 * <p>한 번 적은 줄은 바꾸지 않는다. 같은 대화의 다음 turn 이 시작돼도 그 하위 에이전트는 처음 origin 실행에 속한다.
 * 최상위 session 은 여기 적지 않는다. 대화 session 은 여러 turn 이 이어 쓰므로 실행 줄의
 * {@code hermes_session_id} 로 찾는다. 근거는 ADR-037 이다.
 *
 * <p>{@code userId} 와 {@code originExecutionId} 는 서버가 방금 읽은 origin 실행에서 옮겨 적는다. 요청에서 받지
 * 않는다.
 */
@Entity
@Table(
        name = "hermes_session_binding",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_hermes_session_binding_session",
                        columnNames = {"profile_name", "session_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HermesSessionBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_name", nullable = false, length = 64)
    private String profileName;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "origin_execution_id", nullable = false)
    private Long originExecutionId;

    @Column(name = "root_session_id", nullable = false, length = 128)
    private String rootSessionId;

    @Column(name = "parent_session_id", nullable = false, length = 128)
    private String parentSessionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private HermesSessionBinding(
            String profileName, String sessionId, AgentExecution origin, String rootSessionId, String parentSessionId) {
        this.profileName = profileName;
        this.sessionId = sessionId;
        this.userId = origin.userId();
        this.originExecutionId = origin.id();
        this.rootSessionId = rootSessionId;
        this.parentSessionId = parentSessionId;
        this.createdAt = Instant.now();
    }

    public static HermesSessionBinding of(
            String profileName, String sessionId, AgentExecution origin, String rootSessionId, String parentSessionId) {
        return new HermesSessionBinding(profileName, sessionId, origin, rootSessionId, parentSessionId);
    }

    public Long id() {
        return id;
    }

    public String profileName() {
        return profileName;
    }

    public String sessionId() {
        return sessionId;
    }

    public Long userId() {
        return userId;
    }

    public Long originExecutionId() {
        return originExecutionId;
    }

    public String rootSessionId() {
        return rootSessionId;
    }

    public String parentSessionId() {
        return parentSessionId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
