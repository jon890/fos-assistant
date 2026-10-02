package com.bifos.assistant.people.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 들어와도 된다고 정한 사람이다.
 *
 * <p>실제로 들어온 적이 있는 사람은 {@code app_user} 가 갖는다. 허용한 시점에는 그 행이 아직 없으므로
 * 둘을 외래 키로 잇지 않고 메일 주소로만 잇는다.
 *
 * <p>목록에서 뺄 때 행을 지우지 않고 {@code enabled} 를 내린다. 이 저장소는 실행 기록이 가리키는 것을
 * 지우지 않는다.
 */
@Entity
@Table(name = "allowed_person")
@Accessors(fluent = true)
public class AllowedPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    @Getter
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    @Getter
    private String displayName;

    @Column(name = "hermes_profile", nullable = false, unique = true, length = 64)
    @Getter
    private String hermesProfile;

    @Column(name = "enabled", nullable = false)
    @Getter
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    @Getter
    private Instant createdAt;

    protected AllowedPerson() {}

    private AllowedPerson(String email, String displayName, String hermesProfile, Instant now) {
        this.email = normalizeEmail(email);
        this.displayName = displayName;
        this.hermesProfile = hermesProfile;
        this.enabled = true;
        this.createdAt = now;
    }

    public static AllowedPerson of(String email, String displayName, String hermesProfile, Instant now) {
        return new AllowedPerson(email, displayName, hermesProfile, now);
    }

    /**
     * 메일 주소를 표에 넣고 표에서 찾는 하나의 모양으로 맞춘다.
     *
     * <p>넣을 때와 찾을 때가 모두 이 함수를 지난다. 읽는 쪽에서만 맞추면 대문자가 섞인 주소가 중복 검사를
     * 빠져나가 같은 사람이 두 행으로 들어온다.
     */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** 들어오지 못하게 막는다. 행은 남는다. */
    public void disable() {
        this.enabled = false;
    }

    /**
     * 다시 들어올 수 있게 한다.
     *
     * <p>이미 만들어진 profile 과 에이전트는 그대로 두고 판정만 되돌린다. 막을 때 그 둘을 거두지 않기
     * 때문에 다시 켜는 데 되살릴 것이 없다.
     */
    public void enable() {
        this.enabled = true;
    }
}
