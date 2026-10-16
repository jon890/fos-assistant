package com.bifos.assistant.context.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.context.eval.MemoryEvalDataset.JudgeCheck;
import com.bifos.assistant.context.eval.MemoryJudgeHarness.JudgeInput;
import com.bifos.assistant.context.eval.MemoryJudgeReport.Verdict;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 답 판정 하네스를 대역 모델로 확인한다. 실제 모델과 provider 는 부르지 않는다. */
class MemoryJudgeHarnessTest {

    private static final String KEY = "test-judge-key";
    private static final MemoryJudgeSettings SETTINGS = new MemoryJudgeSettings("http://127.0.0.1:1", KEY, "answer-model", "judge-model", 100, 64);

    private HttpServer server;


    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }


    @Test
    @DisplayName("판정 질문 둘인 사례는 답 둘과 판정 넷을 부르고 답의 system 글이 기억 있음과 없음이다")
    void asksTwoAnswersAndJudgesEachCheckTwice() {
        RecordingModel model = new RecordingModel("YES");

        MemoryJudgeReport report = new MemoryJudgeHarness().run(
            List.of(input("C-1", 2)), model, SETTINGS, 2);

        assertThat(model.calls).hasSize(6);
        assertThat(model.calls.subList(0, 2))
            .extracting(Call::model, Call::system, Call::user)
            .containsExactly(
                tuple("answer-model", "기억 있음", "질문 C-1"),
                tuple("answer-model", "기억 없음", "질문 C-1"));
        assertThat(model.calls.subList(2, 6)).allSatisfy(call -> {
            assertThat(call.model()).isEqualTo("judge-model");
            assertThat(call.system()).isEqualTo(MemoryJudgeHarness.JUDGE_INSTRUCTIONS);
            assertThat(call.user()).contains("# 사용자 질문", "# 어시스턴트의 답", "# 판정 질문");
        });
        assertThat(report.calls()).isEqualTo(6);
        assertThat(report.checks()).isEqualTo(2);
        assertThat(report.markdown()).startsWith(MemoryJudgeReport.HEADLINE);
    }


    @Test
    @DisplayName("판정의 첫 줄이 YES 나 NO 면 그 값이고 그 밖은 판정 불가다")
    void parsesFirstLineAsVerdict() {
        assertThat(MemoryJudgeHarness.verdictOf("YES\n까닭")).isEqualTo(Verdict.YES);
        assertThat(MemoryJudgeHarness.verdictOf(" no ")).isEqualTo(Verdict.NO);
        assertThat(MemoryJudgeHarness.verdictOf("모르겠다")).isEqualTo(Verdict.INVALID);
        assertThat(MemoryJudgeHarness.verdictOf(null)).isEqualTo(Verdict.INVALID);

        RecordingModel model = new RecordingModel("답 하나", "답 둘", "YES", "모르겠다");
        MemoryJudgeReport report = new MemoryJudgeHarness().run(List.of(input("C-1", 1)), model, SETTINGS, 2);

        assertThat(report.cases().getFirst().checks().getFirst().withMemory()).isEqualTo(Verdict.YES);
        assertThat(report.cases().getFirst().checks().getFirst().withoutMemory()).isEqualTo(Verdict.INVALID);
        assertThat(report.matchedWithMemory()).isEqualTo(1);
        assertThat(report.invalid()).isEqualTo(1);
    }


    @Test
    @DisplayName("호출 상한이 사례 하나 반이면 두 번째 사례를 재지 않고 예산 소진을 적는다")
    void stopsBeforeCaseThatExceedsBudget() {
        RecordingModel model = new RecordingModel("NO");
        MemoryJudgeSettings small = new MemoryJudgeSettings("http://127.0.0.1:1", KEY, "answer-model", "judge-model", 6, 64);

        MemoryJudgeReport report = new MemoryJudgeHarness().run(List.of(input("C-1", 1), input("C-2", 1)), model, small, 2);

        assertThat(report.cases()).extracting(MemoryJudgeReport.CaseResult::caseId).containsExactly("C-1");
        assertThat(report.budgetExhausted()).isTrue();
        assertThat(report.calls()).isEqualTo(4).isLessThanOrEqualTo(small.maxCalls());
        assertThat(model.calls).hasSize(4);
    }


    @Test
    @DisplayName("판정 질문이 없는 사례는 모델을 부르지 않는다")
    void skipsCasesWithoutChecks() {
        RecordingModel model = new RecordingModel("YES");

        MemoryJudgeReport report = new MemoryJudgeHarness().run(List.of(input("C-0", 0)), model, SETTINGS, 2);

        assertThat(model.calls).isEmpty();
        assertThat(report.cases()).isEmpty();
        assertThat(report.budgetExhausted()).isFalse();
    }


    @Test
    @DisplayName("설정은 주소가 없으면 꺼짐이고 주소만 있으면 빠진 이름을 알리며 toString 에 key 가 없다")
    void readsSettingsFromEnvironment() {
        assertThat(MemoryJudgeSettings.fromEnv(Map.of())).isEmpty();
        assertThatThrownBy(() -> MemoryJudgeSettings.fromEnv(Map.of(MemoryJudgeSettings.BASE_URL, "http://judge.test")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(MemoryJudgeSettings.API_KEY);

        MemoryJudgeSettings settings = MemoryJudgeSettings.fromEnv(Map.of(
            MemoryJudgeSettings.BASE_URL, "http://judge.test",
            MemoryJudgeSettings.API_KEY, KEY,
            MemoryJudgeSettings.ANSWER_MODEL, "answer-model",
            MemoryJudgeSettings.JUDGE_MODEL, "judge-model",
            MemoryJudgeSettings.MAX_CALLS, "40")).orElseThrow();

        assertThat(settings.maxCalls()).isEqualTo(40);
        assertThat(settings.maxOutputTokens()).isEqualTo(512);
        assertThat(settings.toString()).doesNotContain(KEY);
    }


    @Test
    @DisplayName("OpenAI 호환 호출은 /chat/completions 에 key 와 model, messages 를 보내고 첫 선택지의 글을 돌려준다")
    void callsOpenAiCompatibleEndpoint() throws IOException {
        List<String> bodies = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> authorizations = new ArrayList<>();
        String baseUrl = startServer(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"YES\"}}]}",
            bodies, paths, authorizations);

        String answer = new OpenAiCompatibleChatModel(baseUrl, KEY, 64).complete("judge-model", "지침", "질문");

        assertThat(answer).isEqualTo("YES");
        assertThat(paths).containsExactly("/chat/completions");
        assertThat(authorizations).containsExactly("Bearer " + KEY);
        JsonNode body = JsonMapper.builder().build().readTree(bodies.getFirst());
        assertThat(body.path("model").asString()).isEqualTo("judge-model");
        assertThat(body.path("messages").path(0).path("role").asString()).isEqualTo("system");
        assertThat(body.path("messages").path(1).path("content").asString()).isEqualTo("질문");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
    }


    @Test
    @DisplayName("OpenAI 호환 호출이 500 이면 상태 코드만 담은 예외를 던지고 key 를 내지 않는다")
    void failsWithoutLeakingKey() throws IOException {
        String baseUrl = startServer(500, "{\"error\":\"" + KEY + "\"}", new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>());

        assertThatThrownBy(() -> new OpenAiCompatibleChatModel(baseUrl, KEY, 64).complete("judge-model", "지침", "질문"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("500")
            .hasMessageNotContaining(KEY);
    }


    private String startServer(
        int status, String response, List<String> bodies, List<String> paths, List<String> authorizations)
        throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }


    private static JudgeInput input(String id, int checks) {
        List<JudgeCheck> list = new ArrayList<>();
        for (int n = 1; n <= checks; n++) {
            list.add(new JudgeCheck("j" + n, "판정 " + n + "?", true));
        }
        return new JudgeInput(id, "질문 " + id, "기억 있음", "기억 없음", list);
    }


    private record Call(String model, String system, String user) {
    }

    /** 받은 호출을 적고 정해 둔 답을 차례로 낸다. 다 쓰면 마지막 답을 되풀이한다. */
    private static final class RecordingModel implements ChatModel {

        private final List<Call> calls = new ArrayList<>();
        private final Deque<String> answers;
        private String last;


        RecordingModel(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }


        @Override
        public String complete(String model, String system, String user) {
            calls.add(new Call(model, system, user));
            if (!answers.isEmpty()) {
                last = answers.poll();
            }
            return last;
        }
    }
}
