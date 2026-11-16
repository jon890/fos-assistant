package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.domain.CdpTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 화면의 탭 목록을 다루는 값 함수다. 따라갈 탭과 {@code tabs} 사건의 본문을 정한다. {@link BrowserScreenSession} 이 쓴다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BrowserScreenTabs {

    /**
     * 탭 확인 뒤 옮길 탭이다. 옮기지 않으면 {@code null} 이다.
     *
     * <p>새 탭이 생기면 그리로 옮긴다. 다만 지금 탭이 사람 탭이면 에이전트가 쥔 새 탭으로는 옮기지 않는다(spec DECISION25). 붙은 탭이
     * 사라졌으면 첫 탭으로 옮긴다.
     */
    static String next(List<CdpTarget> before, List<CdpTarget> pages, String current, Set<String> agent) {
        Set<String> known = before.stream().map(CdpTarget::id).collect(Collectors.toSet());
        boolean watchingAgent = agent.contains(current);
        String fresh = pages.stream()
            .map(CdpTarget::id)
            .filter(id -> !known.contains(id) && (watchingAgent || !agent.contains(id)))
            .findFirst()
            .orElse(null);
        if (fresh == null && pages.stream().noneMatch(page -> page.id().equals(current))) {
            return pages.get(0).id();
        }
        return fresh;
    }


    /** {@code tabs} 사건의 본문이다. 탭마다 지금 붙은 탭인지({@code active})와 에이전트가 쥐었는지({@code agent})를 싣는다. */
    static List<Map<String, Object>> views(List<CdpTarget> tabs, String current, Set<String> agent) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (CdpTarget tab : tabs) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", tab.id());
            view.put("title", tab.title());
            view.put("url", tab.url());
            view.put("active", tab.id().equals(current));
            view.put("agent", agent.contains(tab.id()));
            views.add(view);
        }
        return views;
    }
}
