package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.RunEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hermes v0.21.0 이 실행 스트림으로 보내는 사건 하나를 우리 형태로 옮기는 것을 검사한다.
 *
 * <p>실행 상태 응답의 형태는 {@link RealHermesResponseShapeTest} 가 갖는다. 여기는 스트림 사건만 본다.
 */
class HermesRunEventStreamTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private RunEvent parse(String raw) {
        return HermesRunEventStream.toRunEvent(mapper.readTree(raw));
    }

    @Test
    void 초_단위_실수로_오는_걸린_시간을_밀리초_정수로_옮긴다() {
        RunEvent event =
                parse("{\"event\": \"tool.completed\", \"tool\": \"web_search\", \"duration\": 1.25}");

        assertThat(event.type()).isEqualTo("tool.completed");
        assertThat(event.toolName()).isEqualTo("web_search");
        assertThat(event.durationMs()).isEqualTo(1250L);
    }

    @Test
    void 걸린_시간이_0이면_0밀리초가_된다() {
        assertThat(parse("{\"event\": \"tool.completed\", \"duration\": 0}").durationMs()).isZero();
    }

    @Test
    void 걸린_시간을_보내지_않으면_비운다() {
        RunEvent event = parse("{\"event\": \"tool.started\", \"tool\": \"web_search\"}");

        assertThat(event.durationMs()).isNull();
        assertThat(event.failed()).isNull();
    }

    @Test
    void 도구가_실패로_끝났는지를_읽는다() {
        assertThat(parse("{\"event\": \"tool.completed\", \"error\": true}").failed()).isTrue();
        assertThat(parse("{\"event\": \"tool.completed\", \"error\": false}").failed()).isFalse();
    }

    @Test
    void 글자_조각은_delta로_온다() {
        RunEvent event = parse("{\"event\": \"message.delta\", \"delta\": \"안녕하\"}");

        assertThat(event.type()).isEqualTo("message.delta");
        assertThat(event.text()).isEqualTo("안녕하");
    }

    @Test
    void 사건이_data_안에_실려_와도_같은_칸을_읽는다() {
        RunEvent event =
                parse("{\"data\": {\"event\": \"tool.completed\", \"tool\": \"grep\", \"duration\": 2.5,"
                        + " \"error\": true, \"preview\": \"찾지 못했다\"}}");

        assertThat(event.type()).isEqualTo("tool.completed");
        assertThat(event.toolName()).isEqualTo("grep");
        assertThat(event.durationMs()).isEqualTo(2500L);
        assertThat(event.failed()).isTrue();
        assertThat(event.detail()).isEqualTo("찾지 못했다");
    }

    @Test
    void 하위_에이전트의_목표와_모델과_토큰을_읽는다() {
        RunEvent event = parse("{\"event\":\"subagent.complete\",\"subagent_id\":\"sa-1\","
                + "\"goal\":\"숙소 조사\",\"model\":\"model-a\",\"child_session_id\":\"child-1\","
                + "\"input_tokens\":12300,\"output_tokens\":410,\"status\":\"completed\",\"duration_seconds\":1.5}");

        assertThat(event.subagentId()).isEqualTo("sa-1");
        assertThat(event.goal()).isEqualTo("숙소 조사");
        assertThat(event.model()).isEqualTo("model-a");
        assertThat(event.childSessionId()).isEqualTo("child-1");
        assertThat(event.inputTokens()).isEqualTo(12300L);
        assertThat(event.outputTokens()).isEqualTo(410L);
        assertThat(event.status()).isEqualTo("completed");
        assertThat(event.durationMs()).isEqualTo(1500L);
        assertThat(event.failed()).isFalse();
    }

    @Test
    void data_안의_상태가_실패면_error_없이도_실패로_읽는다() {
        RunEvent event = parse("{\"data\":{\"event\":\"subagent.complete\",\"subagent_id\":\"sa-2\","
                + "\"goal\":\"조사\",\"model\":\"model-b\",\"child_session_id\":\"child-2\","
                + "\"input_tokens\":5,\"output_tokens\":2,\"status\":\"failed\",\"duration_seconds\":0.25}}");

        assertThat(event.subagentId()).isEqualTo("sa-2");
        assertThat(event.goal()).isEqualTo("조사");
        assertThat(event.model()).isEqualTo("model-b");
        assertThat(event.childSessionId()).isEqualTo("child-2");
        assertThat(event.inputTokens()).isEqualTo(5L);
        assertThat(event.outputTokens()).isEqualTo(2L);
        assertThat(event.status()).isEqualTo("failed");
        assertThat(event.durationMs()).isEqualTo(250L);
        assertThat(event.failed()).isTrue();
    }
}
