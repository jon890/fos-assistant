package com.bifos.assistant.user.application;

import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 결과물 자동 보관을 켰는지 읽고 바꾼다(#530 PR 3).
 *
 * <p>고른 적이 없는 사용자는 {@link #DEFAULT_ENABLED} 를 따른다. 기본값은 이 상수 한 곳만 바꾸면 된다. 칸은 비어 있는
 * 채로 두므로 이미 있는 사용자도 함께 바뀐다.
 */
@Service
@RequiredArgsConstructor
public class ArtifactAutoVaultSetting {

    /** 고른 적이 없는 사용자의 값이다. 꺼 두면 사용자가 켜기 전까지 보관 공간을 쓰지 않는다. */
    public static final boolean DEFAULT_ENABLED = false;

    private final AppUserRepository users;
    private final Clock clock;


    @Transactional(readOnly = true)
    public boolean isEnabled(Long userId) {
        Boolean chosen = users.findArtifactAutoVault(userId);
        return chosen == null ? DEFAULT_ENABLED : chosen;
    }


    @Transactional
    public void change(Long userId, boolean enabled) {
        users.updateArtifactAutoVault(userId, enabled, clock.instant());
    }
}
