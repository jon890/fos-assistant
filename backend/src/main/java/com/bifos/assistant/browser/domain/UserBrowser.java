package com.bifos.assistant.browser.domain;

import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 사용자 한 사람의 브라우저 한 줄이다.
 *
 * <p>칸의 뜻은 {@code docs/backend/schema/browser.md} 가 갖고, 상태 전이는 {@code docs/backend/user-browser.md} 의
 * 「상태 전이」 가 갖는다. 쿠키와 저장소, 열린 주소는 이 줄에 없다. 로그인 세션은 프로필 디렉터리에만 남는다.
 *
 * <p>전이는 {@link Version} 의 낙관적 잠금으로 하나씩만 일어난다. 허용하지 않는 전이는 {@link ErrorCode#BROWSER_BUSY} 다.
 */
@Entity
@Table(
        name = "user_browser",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_browser_user", columnNames = "user_id"))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBrowser {

    /** {@code last_active_at} 을 다시 쓰기까지의 최소 간격이다. 화면 입력마다 줄을 고치지 않게 한다. */
    public static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserBrowserStatus status;

    /** 프로필 디렉터리 이름이다. {@code SHA-256("u" + userId)} 의 소문자 16진수 64자리다. */
    @Column(name = "profile_key", nullable = false, updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String profileKey;

    /** 켜져 있을 때만 있다. */
    @Column(name = "container_id", length = 80)
    private String containerId;

    /** {@code FAILED} 의 까닭이다. Control Plane 이 정한 코드만 넣는다. */
    @Column(name = "last_error", length = 40)
    private String lastError;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 새 브라우저다. 컨테이너 없이 {@code STOPPED} 로 생긴다. */
    public static UserBrowser create(Long userId, String profileKey, Instant now) {
        UserBrowser browser = new UserBrowser();
        browser.userId = userId;
        browser.profileKey = profileKey;
        browser.status = UserBrowserStatus.STOPPED;
        browser.createdAt = now;
        browser.updatedAt = now;
        return browser;
    }

    /** 켜기를 시작한다. 멈춰 있거나 실패한 줄만 켠다. */
    public void beginStart(Instant now) {
        require(UserBrowserStatus.STOPPED, UserBrowserStatus.FAILED);
        this.status = UserBrowserStatus.STARTING;
        this.containerId = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    /** CDP 가 답했다. 켠 시각과 마지막 활동 시각을 지금으로 둔다. */
    public void markRunning(String containerId, Instant now) {
        require(UserBrowserStatus.STARTING);
        this.status = UserBrowserStatus.RUNNING;
        this.containerId = containerId;
        this.startedAt = now;
        this.lastActiveAt = now;
        this.lastError = null;
        this.updatedAt = now;
    }

    /** 켜거나 멈추다 실패했다. 컨테이너 번호는 그대로 두어 다음 끄기가 지울 수 있게 한다. */
    public void markFailed(String code, Instant now) {
        require(UserBrowserStatus.STARTING, UserBrowserStatus.STOPPING);
        this.status = UserBrowserStatus.FAILED;
        this.lastError = code;
        this.updatedAt = now;
    }

    /** 끄기를 시작한다. 켜져 있거나 실패한 줄만 끈다. */
    public void beginStop(Instant now) {
        require(UserBrowserStatus.RUNNING, UserBrowserStatus.FAILED);
        this.status = UserBrowserStatus.STOPPING;
        this.updatedAt = now;
    }

    /** 컨테이너를 지웠다. */
    public void markStopped(Instant now) {
        require(UserBrowserStatus.STOPPING);
        this.status = UserBrowserStatus.STOPPED;
        this.containerId = null;
        this.startedAt = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * 실제 컨테이너가 없거나 꺼져 있어 {@code STOPPED} 로 맞춘다. 상태 맞추기만 부른다.
     *
     * <p>{@code RUNNING} 인데 컨테이너가 사라졌거나, {@code STARTING} 과 {@code STOPPING} 이 오래 그대로인 줄이다. 남은 컨테이너는 부르는
     * 쪽이 먼저 지운다.
     */
    public void resetStopped(Instant now) {
        require(UserBrowserStatus.RUNNING, UserBrowserStatus.STARTING, UserBrowserStatus.STOPPING);
        this.status = UserBrowserStatus.STOPPED;
        this.containerId = null;
        this.startedAt = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * 화면 입력이나 중계 통신이 있었다. 마지막 기록에서 {@link #TOUCH_INTERVAL} 이 지나지 않았으면 쓰지 않는다.
     *
     * @return 마지막 활동 시각을 바꿨으면 참
     */
    public boolean touch(Instant now) {
        if (lastActiveAt != null && now.isBefore(lastActiveAt.plus(TOUCH_INTERVAL))) {
            return false;
        }
        this.lastActiveAt = now;
        this.updatedAt = now;
        return true;
    }

    private void require(UserBrowserStatus... expected) {
        if (!Arrays.asList(expected).contains(status)) {
            throw new ApiException(
                    ErrorCode.BROWSER_BUSY,
                    "user browser must be one of " + Arrays.toString(expected) + " but is " + status);
        }
    }
}
