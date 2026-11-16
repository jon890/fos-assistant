package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.browser.application.port.BrowserKeptTabs;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 중계의 탭 닫기가 묻는 「사람이 쓰는 탭인가」 를 판정 줄로 답한다. 열린 넘기기 카드의 탭과 살아 있는 이어 가기 표식의 탭이다. 뜻은
 * {@code docs/features/browser-cowork/spec.md} 의 REQ9, REQ11 이 갖는다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorBrowserKeptTabs implements BrowserKeptTabs {

    private final ConnectorActionRepository actions;
    private final Clock clock;


    @Override
    public boolean kept(long userId, String tab, Instant since) {
        return actions.existsKeptTab(userId, tab, since, clock.instant(), ActionStatus.WAITING);
    }
}
