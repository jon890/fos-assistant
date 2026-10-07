package com.bifos.assistant.connector;

import com.bifos.assistant.connector.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.application.ConnectorPolicyProperties;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.shared.config.LiveProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 도구 호출 판정과 승인을 보는 검사들이 함께 쓰는 대역이다.
 *
 * <p>검사 클래스마다 따로 두면 Spring 컨텍스트가 하나씩 늘어난다. 컨텍스트들은 H2 하나를 함께 쓰고, 보관 수를 넘겨
 * 밀려난 컨텍스트가 닫히며 스키마를 지우므로 수를 늘리지 않는다. 쓰는 검사는 이 클래스를 {@code @Import} 하고
 * {@code HermesConnectorClient} 를 같은 이름의 {@code @MockitoBean} 으로 둔다.
 */
@TestConfiguration
public class ConnectorPolicyTestDoubles {
    static final Duration TTL = Duration.ofSeconds(60);
    static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    static final MovingClock CLOCK = new MovingClock(NOW);

    /** 운영의 캐시 대신 검사가 시각을 옮길 수 있는 캐시를 끼운다. */
    @Bean
    @Primary
    ConnectorCatalogCache movingCatalogCache(HermesConnectorClient connector) {
        return new ConnectorCatalogCache(
                connector,
                LiveProperties.fixed(
                        ConnectorPolicyProperties.class,
                        new ConnectorPolicyProperties(TTL, Duration.ofSeconds(5), Duration.ofHours(24), "-")),
                CLOCK);
    }

    @Bean
    ChangeRecorder changeRecorder() {
        return new ChangeRecorder();
    }

    /** 앞선 검사가 읽은 카탈로그가 남지 않게 보관 시간보다 멀리 옮긴다. */
    static void expireCatalog() {
        CLOCK.advance(TTL.plusSeconds(1));
    }

    /** 승인 줄의 사건을 받은 순간에 트랜잭션이 열려 있었는지를 함께 적는다. */
    public static class ChangeRecorder {
        final List<Seen> seen = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(ConnectorActionChanged event) {
            seen.add(new Seen(event, TransactionSynchronizationManager.isActualTransactionActive()));
        }
    }

    record Seen(ConnectorActionChanged event, boolean insideTransaction) {}

    /** 검사가 시각을 옮기는 시계다. */
    static final class MovingClock extends Clock {
        private Instant now;

        private MovingClock(Instant now) {
            this.now = now;
        }

        synchronized void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }
    }
}
