package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.connector.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.application.ConnectorPolicyProperties;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorCatalogCacheTest {
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

    private final HermesConnectorClient connector = mock(HermesConnectorClient.class);
    private final MovingClock clock = new MovingClock(START);
    private final ConnectorCatalogCache cache =
            new ConnectorCatalogCache(connector, new ConnectorPolicyProperties(TTL), clock);

    @Test
    @DisplayName("보관 시간 안에서는 카탈로그를 한 번만 읽고 처음 읽은 값으로 답한다")
    void readsCatalogOnceWithinTtl() {
        when(connector.readCatalog()).thenReturn(List.of(manifest("demo-notes", "처음")));
        cache.find("demo-notes");
        when(connector.readCatalog()).thenReturn(List.of(manifest("demo-notes", "바뀜")));
        clock.set(START.plus(TTL).minusMillis(1));

        assertThat(cache.find("demo-notes")).map(ConnectorManifest::title).contains("처음");
        verify(connector, times(1)).readCatalog();
    }

    @Test
    @DisplayName("보관 시간이 지나면 카탈로그를 다시 읽어 바뀐 값으로 답한다")
    void rereadsCatalogWhenTtlHasPassed() {
        when(connector.readCatalog()).thenReturn(List.of(manifest("demo-notes", "처음")));
        cache.find("demo-notes");
        when(connector.readCatalog()).thenReturn(List.of(manifest("demo-notes", "바뀜")));
        clock.set(START.plus(TTL));

        assertThat(cache.find("demo-notes")).map(ConnectorManifest::title).contains("바뀜");
        verify(connector, times(2)).readCatalog();
    }

    @Test
    @DisplayName("카탈로그에 없는 번호는 빈 값이다")
    void unknownConnectorIsEmpty() {
        when(connector.readCatalog()).thenReturn(List.of(manifest("demo-notes", "처음")));

        assertThat(cache.find("other")).isEmpty();
    }

    @Test
    @DisplayName("도구 정책을 판정할 수 없는 manifest 는 화면 경로와 같이 없는 것으로 본다")
    void manifestWithUnjudgeablePolicyIsEmpty() {
        ConnectorManifest loose = new ConnectorManifest(
                "demo-notes",
                "느슨함",
                "",
                List.of(),
                "list_scopes",
                "demo",
                List.of(),
                false,
                2,
                List.of(
                        new ConnectorTool("list_scopes", "READ", "none", null),
                        new ConnectorTool("write_note", "WRITE", "none", null)));
        when(connector.readCatalog()).thenReturn(List.of(loose));

        assertThat(cache.find("demo-notes")).isEmpty();
    }

    @Test
    @DisplayName("다시 읽다가 난 예외는 그대로 나오고 옛 값으로 답하지 않는다")
    void readFailureIsThrownInsteadOfServingOldValue() {
        when(connector.readCatalog()).thenReturn(List.of(manifest("demo-notes", "처음")));
        cache.find("demo-notes");
        IllegalStateException failure = new IllegalStateException("catalog is unreachable");
        when(connector.readCatalog()).thenThrow(failure);
        clock.set(START.plus(TTL));

        assertThatThrownBy(() -> cache.find("demo-notes")).isSameAs(failure);
    }

    private static ConnectorManifest manifest(String id, String title) {
        return new ConnectorManifest(
                id,
                title,
                "",
                List.of(),
                "list_scopes",
                "demo",
                List.of(),
                false,
                2,
                List.of(new ConnectorTool("list_scopes", "READ", "none", null)));
    }

    /** 검사가 시각을 옮기는 시계다. */
    private static final class MovingClock extends Clock {
        private Instant now;

        private MovingClock(Instant now) {
            this.now = now;
        }

        private void set(Instant now) {
            this.now = now;
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
        public Instant instant() {
            return now;
        }
    }
}
