package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 도구를 부르지 않는 연결도 만료 전에 미리 갱신한다(스펙 DECISION17, 설계 「갱신 주기」).
 *
 * <p>끊김을 쓰려는 순간이 아니라 이 주기에 알아채고, 오래 쓰지 않아 refresh token 이 만료되는 일을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorOAuthRefresher {
    /** 이 시간 안에 만료되는 토큰을 갱신한다. 주기(10분)보다 길어 한 주기를 놓쳐도 만료 전에 다시 본다. */
    static final Duration AHEAD = Duration.ofMinutes(15);

    private final ConnectorConnectionRepository connections;
    private final ConnectorOAuthTokens tokens;


    /** 10분마다 돈다. 검사에서는 {@code -} 로 끄고 본체를 직접 부른다. */
    @Scheduled(cron = "${assistant.connector.oauth.refresh-cron}")
    public void runScheduled() {
        refreshDue();
    }


    /**
     * {@code READY} 인 단추 연결 가운데 현재 칸이 없거나 곧 만료되는 것을 갱신한다. 재기동 뒤 첫 주기는 메모리가 비어 모두 갱신한다.
     * 한 연결의 실패는 경고로 남기고 다음 연결로 간다.
     */
    public void refreshDue() {
        for (ConnectorConnection connection : connections.findByStatus(ConnectionStatus.READY)) {
            if (!connection.fields().auth().usesOAuth()) {
                continue;
            }
            try {
                tokens.refresh(connection.id(), AHEAD);
            } catch (RuntimeException ex) {
                log.warn("connector OAuth scheduled refresh failed connection={} exception={}",
                    connection.id(), ex.getClass().getSimpleName());
            }
        }
    }
}
