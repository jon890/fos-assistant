package com.bifos.assistant.usage.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 일반 경로의 응답에 내부 값을 실어도 되는 요청자인지 정한다.
 *
 * <p>내부 값은 금액, 모델, 토큰, 시각 구간, 설정 구분값이다. 화면이 {@code MEMBER} 역할에게 그리지 않는
 * 값이고, 화면에서만 가리면 브라우저의 개발자 도구로 보인다. 그래서 응답을 만드는 쪽이 이 판정을 불러
 * {@code ADMIN} 역할이 아니면 그 값을 비운다. 근거는 ADR-063 에 있다.
 *
 * <p>어느 응답의 어느 값을 빼는지는 {@code docs/backend/conversation.md} 의 「역할에 따라 응답에서 빼는
 * 값」 표가 갖는다. 저장은 바꾸지 않고 응답을 만들 때만 뺀다. 도구 {@code detail} 의 판정은
 * {@link ToolDetailPolicy} 가 따로 갖는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InternalValuePolicy {

    /** 이 사람에게 내부 값을 실어도 되는가. */
    public static boolean visibleTo(CurrentUser viewer) {
        return viewer.isAdmin();
    }
}
