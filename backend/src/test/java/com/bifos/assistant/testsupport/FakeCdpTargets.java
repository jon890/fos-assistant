package com.bifos.assistant.testsupport;

import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.CdpTargets;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 메모리에서 Chrome 의 탭 HTTP 창구를 흉내 내는 대역이다. {@link IntegrationTestDoubles} 가 운영 {@link CdpTargets} 자리에 넣고
 * {@link IntegrationTestIsolation} 이 검사 뒤에 {@link #reset()} 으로 비운다. 주소는 보지 않는다.
 */
public final class FakeCdpTargets implements CdpTargets {

    /** 열린 탭이다. 검사가 직접 넣고 뺀다. */
    public final List<CdpTarget> pages = new CopyOnWriteArrayList<>();
    /** 닫은 탭 번호다. */
    public final List<String> closed = new CopyOnWriteArrayList<>();
    /** 연 주소다. */
    public final List<String> created = new CopyOnWriteArrayList<>();


    @Override
    public List<CdpTarget> list(URI cdp) {
        return new ArrayList<>(pages);
    }


    @Override
    public CdpTarget create(URI cdp, String url) {
        CdpTarget tab = new CdpTarget("NEW" + (created.size() + 1), "", url);
        created.add(url);
        pages.add(tab);
        return tab;
    }


    @Override
    public void activate(URI cdp, String id) {
        // 앞으로 가져오기는 탭 목록을 바꾸지 않는다
    }


    @Override
    public void close(URI cdp, String id) {
        if (!pages.removeIf(page -> page.id().equals(id))) {
            throw new IllegalStateException("cdp close returned status 404");
        }
        closed.add(id);
    }


    public void reset() {
        pages.clear();
        closed.clear();
        created.clear();
    }
}
