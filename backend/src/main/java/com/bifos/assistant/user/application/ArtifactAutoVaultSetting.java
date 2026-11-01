package com.bifos.assistant.user.application;

import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 결과물 자동 보관을 켰는지 읽고 바꾼다(#530 PR 3).
 *
 * <p>칸이 비어 있으면 {@link #DEFAULT_ENABLED} 를 따른다. 칸의 DB 기본값({@code DEFAULT TRUE})이 새 사용자와 이미 있는 사용자를
 * 켜짐으로 채우고, 이 상수는 칸이 비어 있을 때의 값이다. 둘은 같은 값이어야 한다.
 */
@Service
@RequiredArgsConstructor
public class ArtifactAutoVaultSetting {

    /** 고른 적이 없는 사용자의 값이다. 켜 두어 완성 결과물이 대화와 따로 남는다(2026-11-01 사용자 결정). */
    public static final boolean DEFAULT_ENABLED = true;

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
