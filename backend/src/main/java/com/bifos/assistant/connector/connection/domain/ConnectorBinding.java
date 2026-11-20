package com.bifos.assistant.connector.connection.domain;

import com.bifos.assistant.shared.domain.CreatedAtEntity;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.CyclePhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.ColumnDefault;

/**
 * 에이전트 하나에 연결 하나를 붙인 것이다(ADR-083). 에이전트와 연결은 다대다다.
 *
 * <p>연결 상태는 「값이 확인됐는가」 이고 이 상태는 「그 에이전트의 profile 에 설치되고 반영됐는가」 다. 재시작 대기는
 * profile 마다라 여기에 둔다. 떼면 행을 지운다.
 *
 * <p>상태를 바꾸는 메서드는 시각을 인자로 받는다. 비밀 칸의 값을 받는 메서드는 두지 않는다.
 *
 * <p>바인딩은 에이전트의 사용 여부나 사진 수신 정책을 바꾸지 않는다. 바인딩 도구의 접근은 커넥터 판정이 정한다.
 */
@Entity
@Table(
    name = "agent_connector_binding",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_connector_binding",
        columnNames = {"agent_id", "connection_id"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorBinding extends CreatedAtEntity {
    /** 설치 시도 하나가 결과를 적기까지 걸릴 수 있는 시간의 넉넉한 상한이다. 설치와 상태 조회, probe 의 HTTP 를 덮는다. */
    private static final Duration INSTALL_IN_FLIGHT = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    private ConnectorConnection connection;

    /**
     * 붙일 때 manifest 가 선언한 MCP 서버 이름이다. 비밀이 아니다.
     *
     * <p>turn 마다 카탈로그를 읽지 않고, 카탈로그에서 커넥터가 빠져도 그 profile 에 설치된 서버 이름을 잃지 않게 둔다.
     * 마이그레이션이 만든 옛 바인딩은 비어 있고 연결 확인과 관리자 반영 완료가 채운다.
     */
    @Column(name = "mcp_server", length = 64)
    private String mcpServer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BindingStatus status;

    @Column(name = "restart_required", nullable = false)
    private boolean restartRequired;

    /**
     * 재시작이 필요해진 가장 늦은 설치의 결과 트랜잭션 시각이다.
     *
     * <p>재시작 감지가 공유 gateway 기동 시각과 비교하고(ADR-20261120 / connector-auto-apply), 관리자가 목록을 본 뒤에 다시 설치가
     * 있었으면 반영 완료가 대기를 풀지 않게 하는 데도 쓴다.
     */
    @Column(name = "restart_required_since")
    private Instant restartRequiredSince;

    /**
     * 반영 예정 시각이다(ADR-20261007 / connector-live-reload).
     *
     * <p>재시작 없이 공유 gateway 의 MCP 설정 맞추기 주기가 반영할 설치를 보냈을 때 적는다. 이 시각이 지나면 Control Plane 이
     * 반영 맞추기를 스스로 한 번 돌린다. 예정이 없으면 비어 있다.
     */
    @Column(name = "apply_due_at")
    private Instant applyDueAt;

    /** 껐다 켜기 단계다(ADR-20261120 / connector-auto-apply). 껐다 켜기 중이 아니면 비어 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_phase", length = 16)
    private CyclePhase cyclePhase;

    /** {@code IDLE_WAIT} 면 단계를 시작한 시각, {@code OFF} 면 꺼졌다는 답을 처음 받은 설치 시도의 시작 시각이다. */
    @Column(name = "cycle_at")
    private Instant cycleAt;

    /** 자동 반영이 실패한 시각이다. 자동 경로가 다시 집지 않고, {@code READY} 나 사람이 시작한 설치가 비운다. */
    @Column(name = "apply_failed_at")
    private Instant applyFailedAt;

    /**
     * 자동 대기가 시작된 시각이다(ADR-20261120 / connector-auto-apply). 비어 있을 때만 적고 단계가 바뀌어도 그대로 둔다. 관리자 알림의
     * 24시간을 여기서 센다. {@code READY} 와 사람이 시작한 설치가 비운다.
     */
    @Column(name = "waiting_since")
    private Instant waitingSince;

    /** 지금 대기에 관리자 알림을 보낸 시각이다. 한 대기에 한 번만 보낸다. {@link #waitingSince} 가 비워질 때 함께 비운다. */
    @Column(name = "admin_notified_at")
    private Instant adminNotifiedAt;

    /** 커밋한 설치 시도다. 결과를 한 번 적용한 뒤 비우며, 시각이 같아도 새 시도와 옛 응답을 구분한다. */
    @Column(name = "install_attempt_id", columnDefinition = "BINARY(16)")
    private UUID installAttemptId;

    @Column(name = "desired_enabled", nullable = false)
    private boolean desiredEnabled;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 사건 감지를 켰는가(ADR-20261105 / event-watch).
     *
     * <p>두 사건 감지 컬럼은 넣기와 고치기에서 뺀다. 다른 흐름이 이 엔티티를 저장할 때 상태를 옛 값으로 덮지 않게 하려는 것이다. 쓰기는
     * {@code ConnectorEventWatches} 의 조건부 update 만 하고 {@code updated_at} 을 바꾸지 않는다.
     */
    @ColumnDefault("false")
    @Column(name = "event_watch_enabled", nullable = false, insertable = false, updatable = false)
    private boolean eventWatchEnabled;

    /** 사건 감지 상태 JSON 글이다. 뜻은 {@link EventWatchState} 가 갖는다. */
    @Column(name = "event_watch_state", insertable = false, updatable = false, columnDefinition = "TEXT")
    private String eventWatchState;

    /**
     * 마지막으로 성공한 설치가 답한 빠진 에이전트용 스킬의 JSON 글이다(ADR-20261120 / plugin-agent-skills). 없으면 null 이다.
     * 뜻은 {@link SkippedSkill} 가 갖고, 목록은 {@link #skippedSkills()} 로 읽는다.
     */
    @Column(name = "skipped_skills", columnDefinition = "TEXT")
    private String skippedSkillsJson;


    private ConnectorBinding(Agent agent, ConnectorConnection connection, String mcpServer, Instant now) {
        this.agent = agent;
        this.connection = connection;
        this.mcpServer = mcpServer;
        this.status = BindingStatus.PENDING;
        this.updatedAt = now;
    }


    public static ConnectorBinding pending(Agent agent, ConnectorConnection connection, String mcpServer, Instant now) {
        return new ConnectorBinding(agent, connection, mcpServer, now);
    }


    /** 설치에 쓴 서버 이름을 적는다. 옛 바인딩은 확인할 때 여기로 채운다. */
    public void recordServer(String mcpServer) {
        this.mcpServer = mcpServer;
    }


    /** 시작 트랜잭션이 새 시도를 적고 설치 중인 바인딩을 실행 대상에서 내린다. */
    public void beginInstall(UUID attemptId, Instant now) {
        this.desiredEnabled = false;
        recordInstallAttempt(attemptId, now);
    }


    /** 설치를 보내지 않는 대기 확인은 켜려는 의도를 보존하고 새 결과 대조 시도만 적는다. */
    public void recordInstallAttempt(UUID attemptId, Instant now) {
        this.installAttemptId = Objects.requireNonNull(attemptId);
        // 실행 승인의 바인딩 revision도 같은 시계 값에서 새 설치를 구분해야 한다.
        Instant revision = now.truncatedTo(ChronoUnit.MICROS);
        pending(revision.isAfter(updatedAt) ? revision : updatedAt.plus(1, ChronoUnit.MICROS));
    }


    /** 현재 시도만 한 번 소비한다. 옛 응답과 중복 응답은 상태를 바꾸지 못한다. */
    public boolean consumeInstall(UUID attemptId) {
        if (attemptId == null || !attemptId.equals(installAttemptId)) {
            return false;
        }
        this.installAttemptId = null;
        return true;
    }


    /**
     * 설치가 성공했다. 켜려는 의도가 돌아오지만 반영 확인 전이라 상태는 {@code PENDING} 이다.
     *
     * <p>재시작 대기는 앞선 대기를 지우지 않고 논리 OR 로 누적한다. 재시작이 필요한 설치였으면 그 시각을 대기의 시작으로 적는다.
     */
    public void installed(boolean restartRequired, Instant now) {
        installed(restartRequired, now, now);
    }


    /** 재시작 대기 시각을 상태 시각과 따로 적는다. 결과 트랜잭션이 plugin 의 답을 받은 시각을 쓴다(ADR-20261120 / connector-auto-apply). */
    public void installed(boolean restartRequired, Instant now, Instant restartSince) {
        this.desiredEnabled = true;
        this.restartRequired = this.restartRequired || restartRequired;
        if (restartRequired) {
            this.restartRequiredSince = restartSince;
        }
        this.checkedAt = now;
        pending(now);
    }


    /** 반영을 확인했다. 재시작 대기, 반영 예정, 껐다 켜기 단계와 실패 표시를 함께 푼다. */
    public void ready(Instant now) {
        this.status = BindingStatus.READY;
        this.restartRequired = false;
        this.applyDueAt = null;
        clearCycle();
        this.applyFailedAt = null;
        clearWaiting();
        this.checkedAt = now;
        this.updatedAt = now;
    }


    public void pending(Instant now) {
        this.status = BindingStatus.PENDING;
        this.updatedAt = now;
    }


    /** 반영 예정 시각을 적는다. 이미 더 늦은 예정이 있으면 그 값을 둔다. 앞선 설치가 아직 반영되지 않았을 수 있기 때문이다. */
    public void scheduleApply(Instant dueAt) {
        if (this.applyDueAt == null || this.applyDueAt.isBefore(dueAt)) {
            this.applyDueAt = dueAt;
        }
    }


    /** 예정한 확인을 시작하기 전에 비운다. 확인은 한 번만 시도한다. */
    public void clearApplyDue() {
        this.applyDueAt = null;
    }


    /** 성공한 설치가 답한 빠진 스킬로 덮어쓴다. 실패한 설치는 부르지 않아 옛 기록이 남는다. */
    public void recordSkippedSkills(List<SkippedSkill> skipped) {
        this.skippedSkillsJson = SkippedSkill.toJson(skipped);
    }


    /** 빠진 에이전트용 스킬이다. 기록이 없으면 빈 목록이다. */
    public List<SkippedSkill> skippedSkills() {
        return SkippedSkill.fromJson(skippedSkillsJson);
    }


    /** 껐다 켜기가 필요하다. 단계가 없을 때만 {@code IDLE_WAIT} 로 두고 시각을 적는다. 기다림 상한은 처음 시각에서 센다. */
    public void needCycle(Instant now) {
        if (this.cyclePhase == null) {
            this.cyclePhase = CyclePhase.IDLE_WAIT;
            this.cycleAt = now;
        }
    }


    /** 그 서버가 꺼졌다는 답을 받았다. {@code OFF} 로 처음 넘어갈 때만 시각을 적는다. 다시 켜는 때는 이 시각에서 센다. */
    public void cycledOff(Instant now) {
        if (this.cyclePhase != CyclePhase.OFF) {
            this.cyclePhase = CyclePhase.OFF;
            this.cycleAt = now;
        }
    }


    public void clearCycle() {
        this.cyclePhase = null;
        this.cycleAt = null;
    }


    /** 자동 반영이 실패했다. 실패도 관리자를 기다리는 대기라 대기 시각이 비었으면 함께 적는다. */
    public void markApplyFailed(Instant now) {
        this.applyFailedAt = now;
        if (this.waitingSince == null) {
            this.waitingSince = now;
        }
    }


    /**
     * 결과를 적은 뒤 자동 대기가 남았으면 대기 시각을 적는다. 재시작 대기, 껐다 켜기 단계, 반영 예정이 자동 대기다. 이미 적혀 있으면
     * 그대로 둔다. 단계가 바뀌어도 24시간은 처음 대기에서 센다(스펙 DECISION17).
     */
    public void trackWaiting(Instant now) {
        boolean waiting = this.restartRequired || this.cyclePhase != null || this.applyDueAt != null;
        if (this.status == BindingStatus.PENDING && waiting && this.waitingSince == null) {
            this.waitingSince = now;
        }
    }


    /** 이 대기에 관리자 알림을 보냈다. */
    public void adminNotified(Instant now) {
        this.adminNotifiedAt = now;
    }


    private void clearWaiting() {
        this.waitingSince = null;
        this.adminNotifiedAt = null;
    }


    public void clearApplyFailed() {
        this.applyFailedAt = null;
    }


    /**
     * 사람이 시작한 설치가 실패한 바인딩을 다시 잇는다. 실패 표시를 비우고, 실패한 {@code IDLE_WAIT} 면 기다림 상한을 지금부터 다시 센다.
     * 옛 시각이 남으면 다음 주기가 상한이 지났다고 보고 주인이 대화 중이어도 바로 끈다.
     *
     * <p>대기 시각과 알림 기록도 비운다. 새 반영이 시작됐으므로 그 결과가 다시 대기면 24시간을 처음부터 센다(스펙 DECISION17).
     */
    public void resumeAfterFailure(Instant now) {
        if (this.applyFailedAt != null && this.cyclePhase == CyclePhase.IDLE_WAIT) {
            this.cycleAt = now;
        }
        this.applyFailedAt = null;
        clearWaiting();
    }


    /**
     * 시작 트랜잭션이 커밋한 설치 시도의 결과를 아직 적지 않았는가. 그 사이 관리자 다시 확인이 새 시도를 시작하면 진행 중인 설치의 결과가
     * 버려진다. 결과 커밋이 실패해 시도가 남은 줄을 영영 막지 않도록 {@code INSTALL_IN_FLIGHT} 가 지나면 끝난 것으로 본다.
     */
    public boolean installInFlight(Instant now) {
        return this.installAttemptId != null && this.updatedAt.isAfter(now.minus(INSTALL_IN_FLIGHT));
    }


    /** 앞선 대기 값을 지우지 않고 논리 OR 로 누적한다. */
    public void markRestartRequired(boolean restartRequired) {
        this.restartRequired = this.restartRequired || restartRequired;
    }

}
