package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.infra.UserBrowserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 그 사용자의 브라우저를 끄고 프로필 디렉터리와 줄을 지운다(ADR-20261101 / user-purge).
 *
 * <p>꺼 두는 설정과 상관없이 지운다. 기능을 꺼도 남은 줄과 프로필은 주인이 없어지기 전에 치워야 한다. 줄이 없으면 아무것도 하지
 * 않으므로 다시 불러도 같다. 끄고 지우는 순서는 {@link UserBrowserService} 가 갖는다. 켜거나 끄는 중({@code STARTING},
 * {@code STOPPING})이면 끄기를 거절해 정리 단계가 실패한다. 그 상태는 {@link UserBrowserSweeper} 의 상태 맞추기가 풀고, 정리는
 * 다음 차례에 다시 한다. 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class UserBrowserPurge {

    private final UserBrowserRepository browsers;
    private final UserBrowserService service;


    public void purgeUser(Long userId) {
        browsers.findByUserId(userId).ifPresent(service::remove);
    }
}
