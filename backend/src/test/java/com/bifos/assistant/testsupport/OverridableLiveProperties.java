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

    /**
     * 검사 도중 값을 바꾼다. {@link IntegrationTestIsolation} 이 검사 앞뒤에 기동 값으로 되돌린다.
     * 한 검사 안에서 설정이 바뀌는 순간을 확인할 때만 쓰고, 검사 전체에 거는 값은 {@link OverrideProperties} 로 단다.
     */
    public static <T> void during(LiveProperties<T> live, T value) {
        if (!(live instanceof OverridableLiveProperties<T> overridable)) {
            throw new IllegalStateException("검사용 LiveProperties 가 아니다: " + live.type());
        }
        overridable.override(value);
    }


    /** 기동 값으로 되돌린다. */
    void reset() {
        current = startup;
    }
}
