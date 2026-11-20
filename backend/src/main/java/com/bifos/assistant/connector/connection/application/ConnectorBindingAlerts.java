package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.config.ConnectorBindingProperties;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.DueBinding;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.application.port.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자동 반영이 실패했거나 대기가 생긴 뒤 {@code admin-alert-after}(24시간) 안에 {@code READY} 가 되지 않은 바인딩을 그룹 관리자에게 알린다
 * (ADR-20261120 / connector-auto-apply, 스펙 DECISION8, DECISION17).
 *
 * <p>자동으로 끝날 대기(껐다 켜기, 재시작 대기, 반영 예정)에는 바로 알리지 않는다. 실패 표시를 적는 곳이 여럿이라 결과 트랜잭션마다 알리지 않고 이
 * 단계가 반영 예정 확인과 같은 주기에 실패 표시와 대기 시각을 함께 본다. 한 대기에 한 번만 보내도록 {@code admin_notified_at} 을 적는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBindingAlerts {
    private static final String TITLE = "연결 반영을 확인해 주세요";

    private final ConnectorBindingRepository bindings;
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final SignInRevocation access;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. 바인딩마다 트랜잭션을 따로 연다. */
    private final TransactionTemplate transactions;
    private final ConnectorBindingProperties properties;
    private final Clock clock;


    /** 대기가 이 시각 전에 시작됐으면 관리자 알림과 「다시 확인」 단추의 대상이다(스펙 DECISION16). */
    public Instant staleBefore() {
        return clock.instant().minus(properties.adminAlertAfter());
    }


    /**
     * 알릴 바인딩마다 트랜잭션을 열고 연결 사용자 행, 에이전트 행 차례로 잠근 뒤 다시 읽는다. 조건이 그대로면 알림 시각을 적고 그 사용자 그룹의
     * 차단되지 않은 관리자마다 알림 한 건을 남긴다. 알림과 알림 시각은 한 트랜잭션이라 함께 남거나 함께 빠진다. 한 바인딩의 실패는 경고로 남기고
     * 다음 바인딩으로 간다.
     *
     * @return 알린 바인딩 수
     */
    public int notifyDue() {
        Instant now = clock.instant();
        Instant before = now.minus(properties.adminAlertAfter());
        int notified = 0;
        for (DueBinding due : bindings.findAdminAlertDue(before)) {
            try {
                Boolean sent = transactions.execute(status -> notifyLocked(due, now, before));
                if (Boolean.TRUE.equals(sent)) {
                    notified++;
                }
            } catch (RuntimeException ex) {
                log.warn("connector binding {} failed at admin-alert: {}", due.bindingId(), ex.getClass().getSimpleName());
            }
        }
        return notified;
    }


    private boolean notifyLocked(DueBinding due, Instant now, Instant before) {
        AppUser owner = users.findByIdForUpdate(due.userId()).orElse(null);
        if (owner == null) {
            return false;
        }
        agents.findByIdForUpdate(due.agentId());
        ConnectorBinding binding = bindings.findById(due.bindingId()).orElse(null);
        if (binding == null || !due(binding, before)) {
            return false;
        }
        binding.adminNotified(now);
        bindings.save(binding);
        String body = body(binding);
        NotificationTarget target = new NotificationTarget(NotificationTargetType.ADMIN_CONNECTIONS, null);
        for (AppUser admin : users.findByGroupIdAndRole(owner.groupId(), UserRole.ADMIN)) {
            if (!access.revoked(admin.email())) {
                notifications.notify(admin.id(), NotificationKind.CONNECTOR_REINSTALLED, TITLE, body, target);
            }
        }
        return true;
    }


    /** 저장소 조회와 같은 조건이다. 잠근 뒤 다시 본다. */
    private static boolean due(ConnectorBinding binding, Instant before) {
        if (binding.status() != BindingStatus.PENDING || binding.adminNotifiedAt() != null) {
            return false;
        }
        return binding.applyFailedAt() != null
            || (binding.waitingSince() != null && !binding.waitingSince().isAfter(before));
    }


    /** 에이전트 code 와 플러그인 번호로 줄을 가리킨다. 둘 다 관리자 목록에 보이는 값이다. */
    private static String body(ConnectorBinding binding) {
        String target = "에이전트 " + binding.agent().code() + " 의 " + binding.connection().connectorId() + " 연결";
        String reason = binding.applyFailedAt() != null
            ? "을 자동으로 반영하지 못했어요."
            : "이 하루 넘게 자동 반영을 기다리고 있어요.";
        return target + reason + " 「연결 반영 확인」에서 「다시 확인」을 눌러 주세요.";
    }
}
