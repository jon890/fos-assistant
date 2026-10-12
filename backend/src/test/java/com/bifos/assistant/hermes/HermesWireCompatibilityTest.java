package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.testsupport.HermesInterimWire;
import com.bifos.assistant.testsupport.HermesWire;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HermesWireCompatibilityTest {
    @Test
    @DisplayName("HTTP 파서는 미지 사건과 칸 및 SSE 메타데이터를 무시하고 뒤의 답을 읽는다")
    void ignoresUnknownEventsFieldsAndTransportMetadata() throws Exception {
        var events = new ArrayList<RunEvent>();
        HermesInterimWire.read(HermesWire.fixture("unknown"), ToolDetailScope.NONE, events::add);

        assertThat(events).extracting(RunEvent::type).containsExactly("message.delta", "message.delta", "run.completed");
        assertThat(events).extracting(RunEvent::text).containsExactly("앞", "뒤", "앞뒤");
    }


    @Test
    @DisplayName("현재 판의 공유 사건 표본을 실제 HTTP 파서로 읽는다")
    void readsPinnedEventFixturesThroughHttpParser() throws Exception {
        var events = new ArrayList<RunEvent>();
        HermesInterimWire.read(HermesWire.fixture("events"), ToolDetailScope.NONE, events::add);

        assertThat(events).hasSize(13);
        assertThat(events.get(3).toolName()).isEqualTo("web_search");
        assertThat(events.get(3).durationMs()).isZero();
        assertThat(events.get(3).failed()).isFalse();
        assertThat(events.get(5).inputTokens()).isZero();
        assertThat(events.get(6).text()).isNull();
        assertThat(events.get(9).text()).isEqualTo("합성 답");
        assertThat(events.get(12).type()).isEqualTo("run.interrupted");
    }
}
