package com.bifos.assistant.browser.application.dto;

import com.bifos.assistant.browser.domain.CdpTarget;
import java.util.List;

/**
 * 에이전트에게 보일 탭 목록이다. 에이전트가 쥔 탭과 넘기기 카드가 지키는 탭은 빠져 있다.
 *
 * @param running 브라우저가 켜져 있는가. 꺼져 있으면 {@code tabs} 가 비어 있다
 */
public record BrowserTabs(boolean running, List<CdpTarget> tabs) {

    public BrowserTabs {
        tabs = List.copyOf(tabs);
    }
}
