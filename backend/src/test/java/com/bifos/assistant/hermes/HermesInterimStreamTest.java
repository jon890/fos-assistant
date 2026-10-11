package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.testsupport.HermesInterimWire;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HermesInterimStreamTest {
    @Test
    @DisplayName("pinned SSE는 종료까지 읽고 완전 delta와 일부 접두사를 중간 안내로 확인한다")
    void readsWireAndClassifiesFullAndPartialPrefixes() throws IOException {
        List<RunEvent> events = new ArrayList<>();
        HermesInterimWire.read(HermesInterimWire.fixture(), ToolDetailScope.NONE, events::add);
        assertThat(events).filteredOn(event -> "message.interim".equals(event.type()))
            .extracting(RunEvent::streamedPrefixLength).containsExactly(0, 0, "두 번째  안내".length(), "일부만 ".length());
        assertThat(events.getLast().type()).isEqualTo("run.completed");
        assertThat(events).filteredOn(event -> "reasoning.available".equals(event.type()))
            .allSatisfy(event -> assertThat(event.text()).isNull());
        assertThat(events.toString()).doesNotContain("SYNTHETIC_HIDDEN_REASONING");
    }


    @Test
    @DisplayName("누락되거나 잘못된 boolean과 맞지 않는 delta는 재분류하지 않는다")
    void preservesUnknownAndMismatchedPrefixes() throws IOException {
        for (String field : List.of("", ",\"already_streamed\":\"true\"", ",\"already_streamed\":1",
            ",\"already_streamed\":null", ",\"already_streamed\":false", ",\"already_streamed\":true")) {
            List<RunEvent> events = new ArrayList<>();
            HermesInterimWire.read("data: {\"event\":\"message.delta\",\"delta\":\"답 조각\"}\n\n"
                + "data: {\"event\":\"message.interim\",\"text\":\"다른 안내\"" + field + "}\n\n",
                ToolDetailScope.NONE, events::add);
            assertThat(events.getLast().streamedPrefixLength()).isZero();
            if (!field.endsWith(":true") && !field.endsWith(":false")) {
                assertThat(events.getLast().alreadyStreamed()).isNull();
            }
        }
    }


    @Test
    @DisplayName("도구 경계를 넘어 앞의 답을 지우거나 같은 안내를 재수신해 두 번 빼지 않는다")
    void respectsToolBoundaryAndRepeatedInterim() throws IOException {
        List<RunEvent> events = new ArrayList<>();
        HermesInterimWire.read("""
                data: {"event":"message.delta","delta":"앞의 답"}

                data: {"event":"tool.started","tool":"web_search"}

                data: {"event":"message.interim","text":"앞의 답도 안내","already_streamed":true}

                data: {"event":"message.delta","delta":"새 안내"}

                data: {"event":"message.interim","text":"새 안내","already_streamed":true}

                data: {"event":"message.interim","text":"새 안내","already_streamed":true}

                """, ToolDetailScope.NONE, events::add);
        assertThat(events).filteredOn(event -> "message.interim".equals(event.type()))
            .extracting(RunEvent::streamedPrefixLength).containsExactly(0, 4, 0);
    }


    @Test
    @DisplayName("안내 뒤 같은 접두사의 최종 delta가 와도 재수신한 안내는 그 delta를 지우지 않는다")
    void keepsFinalPrefixOnReplayedInterim() throws IOException {
        for (String identity : List.of("", ",\"timestamp\":1791630000.6")) {
            List<RunEvent> events = new ArrayList<>();
            String interim = "data: {\"event\":\"message.interim\",\"text\":\"새 안내\",\"already_streamed\":true" + identity + "}\n\n";
            HermesInterimWire.read("data: {\"event\":\"message.delta\",\"delta\":\"새 안내\"}\n\n"
                + interim + "data: {\"event\":\"message.delta\",\"delta\":\"새 안내\"}\n\n" + interim,
                ToolDetailScope.NONE, events::add);
            assertThat(events).filteredOn(event -> "message.interim".equals(event.type()))
                .extracting(RunEvent::streamedPrefixLength).containsExactly(4, 0);
        }
    }


    @Test
    @DisplayName("reset과 실행 시작은 앞 시도의 delta와 안내 중복 상태를 비운다")
    void resetsAttemptStateAtExplicitBoundaries() throws IOException {
        for (String boundary : List.of("reset", "run.started")) {
            List<RunEvent> events = new ArrayList<>();
            String interim = "data: {\"event\":\"message.interim\",\"text\":\"안내\",\"already_streamed\":true}\n\n";
            HermesInterimWire.read("data: {\"event\":\"message.delta\",\"delta\":\"안내\"}\n\n" + interim
                + "data: {\"event\":\"message.delta\",\"delta\":\"앞 시도\"}\n\n"
                + "data: {\"event\":\"" + boundary + "\"}\n\n"
                + "data: {\"event\":\"message.delta\",\"delta\":\"안내\"}\n\n" + interim,
                ToolDetailScope.NONE, events::add);
            assertThat(events).filteredOn(event -> "message.interim".equals(event.type()))
                .extracting(RunEvent::streamedPrefixLength).containsExactly(2, 2);
        }
    }


    @Test
    @DisplayName("NBSP와 EM SPACE와 줄바꿈은 upstream과 같이 정규화하고 emoji의 UTF-16 길이를 전달한다")
    void normalizesUnicodeWhitespaceAndPreservesUtf16Length() throws IOException {
        var mapper = JsonMapper.builder().build();
        String prefix = "\u00a0\u2003확인😀\n중\u00a0";
        var delta = mapper.createObjectNode().put("event", "message.delta").put("delta", prefix);
        var interim = mapper.createObjectNode().put("event", "message.interim")
            .put("text", "확인😀 중이에요.").put("already_streamed", true);
        List<RunEvent> events = new ArrayList<>();
        HermesInterimWire.read("data: " + mapper.writeValueAsString(delta) + "\n\n"
            + "data: " + mapper.writeValueAsString(interim) + "\n\n", ToolDetailScope.NONE, events::add);
        assertThat(events.getLast().streamedPrefixLength()).isEqualTo(prefix.length());
    }

    @Test
    @DisplayName("같은 timestamp의 다른 원문은 가린 글이 같아도 다른 안내이고 정확히 재수신한 사건만 같은 번호다")
    void distinguishesDifferentTextsAtSameTimestampBeforeRedaction() throws IOException {
        List<RunEvent> events = new ArrayList<>();
        HermesInterimWire.read("""
                data: {"event":"message.interim","timestamp":1.1,"text":"token=secret-one"}

                data: {"event":"message.interim","timestamp":1.1,"text":"token=secret-two"}

                data: {"event":"message.interim","timestamp":1.1,"text":"token=secret-one"}

                """, ToolDetailScope.NONE, events::add);
        assertThat(events).extracting(RunEvent::text).containsExactly("token=[가림]", "token=[가림]", "token=[가림]");
        assertThat(events).extracting(RunEvent::interimId).containsExactly("1", "2", "1");
        assertThat(events.toString()).doesNotContain("secret-one", "secret-two", "1.1");
    }


    @Test
    @DisplayName("중간 안내의 think와 비밀값은 숨기고 커넥터 호출 뒤와 트리 이력에도 같은 정책을 적용한다")
    void redactsInterimBeforeCreatingDto() throws IOException {
        var mapper = JsonMapper.builder().build();
        var event = HermesRunEventStream.toRunEvent(mapper.readTree("""
                {"event":"message.interim","text":"<think>HIDDEN_SCRATCH</think>token=small-secret 확인할게요.","already_streamed":false}
                """));
        assertThat(event.text()).isEqualTo("token=[가림] 확인할게요.");
        assertThat(event.toString()).doesNotContain("HIDDEN_SCRATCH", "small-secret");
        for (var scope : List.of(ToolDetailScope.ALL, ToolDetailScope.NONE.withTreeHistory(at -> true))) {
            var hidden = HermesRunEventStream.toRunEvent(mapper.readTree("""
                    {"event":"message.interim","text":"외부 서비스의 민감한 결과","already_streamed":false}
                    """), scope);
            assertThat(hidden.text()).contains("가림").doesNotContain("민감한 결과");
        }
        List<RunEvent> events = new ArrayList<>();
        HermesInterimWire.read("""
                data: {"event":"tool.started","tool":"mcp__external__read","preview":"민감한 인자"}

                data: {"event":"message.interim","text":"민감한 결과"}

                """, ToolDetailScope.prefixes(Set.of("mcp__external__")), events::add);
        assertThat(events.getLast().text()).contains("가림").doesNotContain("민감한 결과");
    }
}
