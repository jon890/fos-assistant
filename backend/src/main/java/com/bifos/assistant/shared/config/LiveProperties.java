package com.bifos.assistant.shared.config;

import java.util.Objects;

/**
 * 실행 중에 쓰는 설정 묶음을 쓸 때마다 읽는 자리다(ADR-20261007 / live-properties).
 *
 * <p>설정 record 를 필드로 쥐지 않고 이 타입을 주입받아 {@link #current()} 를 읽는다.
 * 한 메서드 안에서 같은 값을 여러 번 쓰면 처음 읽은 record 를 지역 변수로 쥔다.
 * 운영 구현은 값을 바꾸는 메서드를 갖지 않는다.
 *
 * @param <T> 설정 record 의 타입
 */
public interface LiveProperties<T> {

    /** 지금 쓸 설정이다. 운영에서는 기동 때 바인딩한 값이다. */
    T current();

    /** 설정 record 의 타입이다. 검사 쪽 구현이 prefix 를 찾는 데 쓴다. */
    Class<T> type();

    /** 늘 같은 값을 돌려주는 구현이다. 운영 빈과 명시 생성자로 대역을 넣는 검사가 쓴다. */
    static <T> LiveProperties<T> fixed(Class<T> type, T value) {
        return new Fixed<>(type, value);
    }

    /** {@link #fixed} 의 구현이다. */
    record Fixed<T>(Class<T> type, T current) implements LiveProperties<T> {

        public Fixed {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(current, "current");
        }
    }
}
