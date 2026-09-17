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
}
