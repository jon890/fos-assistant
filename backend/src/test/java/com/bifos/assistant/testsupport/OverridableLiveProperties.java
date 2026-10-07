package com.bifos.assistant.testsupport;

import com.bifos.assistant.shared.config.LiveProperties;
import java.util.Objects;

/**
 * 검사가 값을 바꾸고 되돌릴 수 있는 {@link LiveProperties} 다(ADR-20261007 / live-properties).
 *
 * <p>{@link IntegrationTestDoubles} 의 빈 후처리기가 운영 빈을 이것으로 감싼다. {@link IntegrationTestIsolation} 이 검사 전에
 * {@link OverrideProperties} 값을 {@link #override} 로 넣고, 검사 뒤에 {@link #reset} 으로 기동 값을 돌려놓는다.
 *
 * @param <T> 설정 record 의 타입
 */
public final class OverridableLiveProperties<T> implements LiveProperties<T> {

    private final Class<T> type;
    private final T startup;
    private volatile T current;

    OverridableLiveProperties(LiveProperties<T> startup) {
        this.type = Objects.requireNonNull(startup.type(), "type");
        this.startup = Objects.requireNonNull(startup.current(), "current");
        this.current = this.startup;
    }

    @Override
    public T current() {
        return current;
    }

    @Override
    public Class<T> type() {
        return type;
    }

    /** 기동 때 바인딩한 값이다. */
    T startup() {
        return startup;
    }

    /** 다음 {@link #reset} 까지 {@link #current} 가 이 값을 돌려준다. */
    void override(Object value) {
        current = type.cast(Objects.requireNonNull(value, "value"));
    }

    /** 기동 값으로 되돌린다. */
    void reset() {
        current = startup;
    }
}
