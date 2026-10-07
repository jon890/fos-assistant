package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.model.BrowserUsageHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 지금 화면이나 중계 연결이 쓰고 있는 브라우저를 센다. 열린 핸들이 있는 브라우저는 자동 중지하지 않는다.
 *
 * <p>JVM 메모리에 둔다. Control Plane 은 한 프로세스이고, 재기동하면 화면과 중계 연결도 함께 끊긴다.
 */
@Component
public class BrowserUsage {

    private final Map<Long, Integer> open = new ConcurrentHashMap<>();

    /** 그 브라우저를 쓰기 시작한다. 쓰기를 마치면 돌려받은 핸들을 닫는다. */
    public BrowserUsageHandle open(Long userBrowserId) {
        open.merge(userBrowserId, 1, Integer::sum);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                open.computeIfPresent(userBrowserId, (id, count) -> count > 1 ? count - 1 : null);
            }
        };
    }

    /** 열린 핸들이 하나라도 있는가. */
    public boolean inUse(Long userBrowserId) {
        return open.containsKey(userBrowserId);
    }
}
