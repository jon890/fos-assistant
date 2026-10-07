package com.bifos.assistant.testsupport;

import com.bifos.assistant.shared.auth.UserAccessRevoked;
import org.springframework.context.event.EventListener;

/**
 * 접근 폐기 사건을 받는 쪽이 실패하는 상황을 만드는 대역이다.
 *
 * <p>기본은 꺼져 있어 사건을 받아도 아무것도 하지 않는다. 쓰는 검사가 {@link #fail()} 로 켜고, {@link IntegrationTestIsolation} 이
 * 검사 뒤에 {@link #reset()} 으로 끈다.
 */
public class FailingAccessRevoker {
    private volatile boolean failing;

    /** 이 뒤의 폐기 사건에서 예외를 던진다. */
    public void fail() {
        failing = true;
    }

    public void reset() {
        failing = false;
    }

    @EventListener
    public void on(UserAccessRevoked event) {
        if (failing) {
            throw new IllegalStateException("revoke failed");
        }
    }
}
