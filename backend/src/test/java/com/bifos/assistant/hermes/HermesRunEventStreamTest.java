package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.RunEvent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("도구 결과 객체와 문자열을 중계 전에 같은 규칙으로 가린다")
    void redactsStructuredAndStringToolDetailsBeforeForwarding() {
        RunEvent object = parse("""
                {"event":"tool.completed","tool":"web_search","result":
                  {"token":"small-secret","id":"12345678-1234-5678-9012-123456789abc","price":12000}}
                """);
        RunEvent text = parse("""
                {"data":{"event":"tool.started","tool":"terminal","preview":"Bearer small-secret"}}
                """);

        assertThat(object.detail()).isEqualTo("{\"token\":\"[가림]\",\"id\":\"[항목 1]\",\"price\":12000}");
        assertThat(text.detail()).isEqualTo("[가림]");
    }

    @Test
    @DisplayName("연결용 도구의 결과는 가리되 이름과 성공 여부와 걸린 시간은 남긴다")
    void hidesConnectorDetailAndPreservesToolMetadata() {
        RunEvent event = HermesRunEventStream.toRunEvent(mapper.readTree("""
                {"event":"tool.completed","tool":"lookup","detail":"tiny secret","duration":0.5,"error":false}
                """), ToolDetailScope.ALL);

        assertThat(event.detail()).isEqualTo("[연결 도구 내용 가림]");
        assertThat(event.toolName()).isEqualTo("lookup");
        assertThat(event.durationMs()).isEqualTo(500L);
        assertThat(event.failed()).isFalse();
    }

    @Test
    @DisplayName("32자를 넘는 스킬 이름은 설명에서 가리고 가리기 전에 꺼낸 이름을 따로 싣는다")
    void hidesLongSkillNameInDetailAndCarriesNameTakenBeforeRedaction() {
        String name = "weekly-grocery-shopping-list-builder-2026-v2";

        RunEvent nameOnly = parse("{\"event\":\"tool.started\",\"tool\":\"skill_view\",\"preview\":\"" + name + "\"}");
        RunEvent withPath = parse("{\"data\":{\"event\":\"tool.started\",\"tool\":\"skill_view\",\"preview\":\"" + name
                + " → references/list.md\"}}");

        assertThat(nameOnly.detail()).isEqualTo("[가림]");
        assertThat(nameOnly.skillName()).isEqualTo(name);
        assertThat(withPath.detail()).contains("[가림]").doesNotContain(name);
        assertThat(withPath.skillName()).isEqualTo(name);
    }

    @Test
    @DisplayName("이름 규칙에 맞지 않는 skill view 미리보기에서는 이름을 싣지 않는다")
    void carriesNoSkillNameWhenSkillViewPreviewBreaksNameRule() {
        for (String preview : new String[] {"Shopping", "weekly shopping", "a".repeat(65), "", "../shopping"}) {
            RunEvent event =
                    parse("{\"event\":\"tool.started\",\"tool\":\"skill_view\",\"preview\":\"" + preview + "\"}");

            assertThat(event.skillName()).as("미리보기 「%s」", preview).isNull();
        }
        assertThat(parse("{\"event\":\"tool.started\",\"tool\":\"skill_view\"}").skillName())
                .isNull();
    }

    @Test
    @DisplayName("skill view 가 아닌 도구와 도구 완료 사건과 연결용 실행에서는 스킬 이름을 싣지 않는다")
    void carriesNoSkillNameForOtherToolCompletedEventAndConnectorRun() {
        RunEvent otherTool = parse("{\"event\":\"tool.started\",\"tool\":\"web_search\",\"preview\":\"shopping\"}");
        RunEvent completed = parse("{\"event\":\"tool.completed\",\"tool\":\"skill_view\",\"detail\":\"shopping\"}");
        RunEvent subagent = parse("{\"event\":\"subagent.start\",\"tool\":\"skill_view\",\"preview\":\"shopping\"}");
        RunEvent connector = HermesRunEventStream.toRunEvent(
                mapper.readTree("{\"event\":\"tool.started\",\"tool\":\"skill_view\",\"preview\":\"shopping\"}"),
                ToolDetailScope.ALL);

        assertThat(otherTool.skillName()).isNull();
        assertThat(completed.skillName()).isNull();
        assertThat(subagent.skillName()).isNull();
        assertThat(connector.skillName()).isNull();
        assertThat(connector.detail()).isEqualTo("[연결 도구 내용 가림]");
    }

    @Test
    @DisplayName("초 단위 실수로 오는 걸린 시간을 밀리초 정수로 옮긴다")
    void convertsFractionalSecondsElapsedToIntegerMillis() {
        RunEvent event = parse("{\"event\": \"tool.completed\", \"tool\": \"web_search\", \"duration\": 1.25}");

        assertThat(event.type()).isEqualTo("tool.completed");
        assertThat(event.toolName()).isEqualTo("web_search");
        assertThat(event.durationMs()).isEqualTo(1250L);
    }

    @Test
    @DisplayName("걸린 시간이 0이면 0밀리초가 된다")
    void zeroElapsedBecomesZeroMillis() {
        assertThat(parse("{\"event\": \"tool.completed\", \"duration\": 0}").durationMs())
                .isZero();
    }

    @Test
    @DisplayName("걸린 시간을 보내지 않으면 비운다")
    void leavesElapsedEmptyWhenNotSent() {
        RunEvent event = parse("{\"event\": \"tool.started\", \"tool\": \"web_search\"}");

        assertThat(event.durationMs()).isNull();
        assertThat(event.failed()).isNull();
    }

    @Test
    @DisplayName("도구가 실패로 끝났는지를 읽는다")
    void readsWhetherToolEndedInFailure() {
        assertThat(parse("{\"event\": \"tool.completed\", \"error\": true}").failed())
                .isTrue();
        assertThat(parse("{\"event\": \"tool.completed\", \"error\": false}").failed())
                .isFalse();
    }

    @Test
    @DisplayName("글자 조각은 delta로 온다")
    void textChunksComeAsDelta() {
        RunEvent event = parse("{\"event\": \"message.delta\", \"delta\": \"안녕하\"}");

        assertThat(event.type()).isEqualTo("message.delta");
        assertThat(event.text()).isEqualTo("안녕하");
    }

    @Test
    @DisplayName("사건이 data 안에 실려 와도 같은 칸을 읽는다")
    void readsSameFieldWhenEventIsNestedInData() {
        RunEvent event = parse("{\"data\": {\"event\": \"tool.completed\", \"tool\": \"grep\", \"duration\": 2.5,"
                + " \"error\": true, \"preview\": \"찾지 못했다\"}}");

        assertThat(event.type()).isEqualTo("tool.completed");
        assertThat(event.toolName()).isEqualTo("grep");
        assertThat(event.durationMs()).isEqualTo(2500L);
        assertThat(event.failed()).isTrue();
        assertThat(event.detail()).isEqualTo("찾지 못했다");
    }

    @Test
    @DisplayName("하위 에이전트의 목표와 모델과 토큰을 읽는다")
    void readsSubagentGoalModelAndTokens() {
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
    @DisplayName("data 안의 상태가 실패면 error 없이도 실패로 읽는다")
    void readsFailureFromStatusInDataEvenWithoutError() {
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

    @Test
    @DisplayName("하위 에이전트의 목표와 preview 는 도구 내용과 같은 규칙으로 비밀값과 UUID 를 가린다")
    void redactsSubagentGoalAndPreviewLikeToolDetail() {
        String id = "12345678-1234-5678-9012-123456789abc";
        RunEvent withGoal = parse("{\"event\":\"subagent.start\",\"subagent_id\":\"sa-1\","
                + "\"goal\":\"메일 정리 token=short-secret 대상 " + id + "\"}");
        RunEvent withPreview = parse(
                "{\"event\":\"subagent.complete\",\"preview\":\"Bearer small-secret 로 조회\",\"status\":\"completed\"}");

        assertThat(withGoal.goal()).isEqualTo("메일 정리 token=[가림] 대상 [항목 1]");
        assertThat(withPreview.goal()).isNull();
        assertThat(withPreview.detail()).isEqualTo("[가림] 로 조회");
    }

    @Test
    @DisplayName("옛 커넥터 에이전트의 하위 에이전트 목표는 통째로 가리고 연결을 붙인 에이전트는 비밀값만 가린다")
    void hidesSubagentGoalOfConnectorAgentAndRedactsForBoundAgent() {
        String json = "{\"event\":\"subagent.start\",\"goal\":\"받은 메일 요약 password=abc\"}";

        assertThat(HermesRunEventStream.toRunEvent(mapper.readTree(json), ToolDetailScope.ALL)
                        .goal())
                .isEqualTo("[연결 도구 내용 가림]");
        assertThat(HermesRunEventStream.toRunEvent(
                                mapper.readTree(json), ToolDetailScope.prefixes(Set.of("mcp__mail__")))
                        .goal())
                .isEqualTo("받은 메일 요약 password=[가림]");
    }

    @Test
    @DisplayName("목표와 preview가 없으면 상태를 목표 대신 쓰지 않는다")
    void doesNotUseStatusInPlaceOfGoalWhenGoalAndPreviewAreMissing() {
        RunEvent event = parse("{\"event\":\"subagent.complete\",\"status\":\"completed\"}");

        assertThat(event.goal()).isNull();
        assertThat(event.detail()).isNull();
        assertThat(event.status()).isEqualTo("completed");
    }
}
