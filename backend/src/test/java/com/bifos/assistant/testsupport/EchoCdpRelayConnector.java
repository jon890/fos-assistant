package com.bifos.assistant.testsupport;

import com.bifos.assistant.browser.domain.CdpRelay;
import com.bifos.assistant.browser.domain.CdpRelayConnector;
import com.bifos.assistant.browser.domain.CdpRelayListener;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 받은 조각을 그대로 되돌려 주는 Chrome 쪽 중계 대역이다. 연 횟수와 받은 조각을 기록한다.
 *
 * <p>{@link IntegrationTestDoubles} 가 운영 {@link CdpRelayConnector} 자리에 넣는다. 공유 시험 컨텍스트의 CDP 주소는 닿지 않으므로
 * 중계 WebSocket 의 통합 시험이 이 대역을 쓴다. {@link IntegrationTestIsolation} 이 검사마다 {@link #reset()} 으로 비운다.
 */
public final class EchoCdpRelayConnector implements CdpRelayConnector {

    private final List<String> fragments = new ArrayList<>();
    private int opened;

    @Override
    public synchronized CdpRelay open(URI cdp, String kind, String id, CdpRelayListener listener) {
        opened++;
        AtomicBoolean closed = new AtomicBoolean();
        return new CdpRelay() {
            @Override
            public void send(String fragment, boolean last) {
                if (closed.get()) {
                    throw new IllegalStateException("relay is closed");
                }
                record(fragment);
                listener.onFragment(fragment, last);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    /** 연 횟수다. */
    public synchronized int opened() {
        return opened;
    }

    /** 받은 조각이다. 받은 차례대로다. */
    public synchronized List<String> fragments() {
        return List.copyOf(fragments);
    }

    public synchronized void reset() {
        fragments.clear();
        opened = 0;
    }

    private synchronized void record(String fragment) {
        fragments.add(fragment);
    }
}
