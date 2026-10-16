package com.bifos.assistant.context.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Case;
import com.bifos.assistant.context.eval.MemoryJudgeHarness.JudgeInput;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TestContextAssemblers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 답 판정 하네스를 실제 모델로 돌린다. {@code ./gradlew memoryJudgeEval} 만 이 검사를 돌리고 설정이 없으면 건너뛴다. 방법은
 * {@code docs/features/memory.md} 의 「답 판정」 이 갖는다.
 *
 * <p>품질 단언은 두지 않는다. 판정 모델과 예산을 사용자가 정하고 사람 대조로 판정기를 확인하기 전에는 숫자를 게이트로 쓰지 않는다.
 */
@Tag("model-eval")
@BackendIntegrationTest
class MemoryJudgeEvalTest {

    private static final MemoryEvalDataset DATASET = MemoryEvalDataset.load();
    private static final Path REPORT_DIR = Path.of("build", "reports", "memory-judge");
    private static final Instant BASE = Instant.parse("2026-11-02T00:00:00Z");

    @Autowired
    MemoryService memoryService;

    @Autowired
    ContextProperties properties;

    @Autowired
    MemoryRepository memories;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private MemoryEvalFixture fixture;


    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.tearDown();
        }
    }


    @Test
    @DisplayName("판정 질문이 있는 합성 사례를 기억 있음과 없음으로 답하게 하고 판정 결과를 보고서로 남긴다")
    void judgesAnswersWithAndWithoutMemory() {
        Optional<MemoryJudgeSettings> settings = MemoryJudgeSettings.fromEnv(System.getenv());
        Assumptions.assumeTrue(settings.isPresent(), MemoryJudgeSettings.BASE_URL + " 가 없어 답 판정을 건너뛴다");
        clock.set(BASE);
        fixture = new MemoryEvalFixture(DATASET, memories, agents, grants, jdbc, clock);
        Map<String, Long> agentIds = fixture.createAgents();
        ContextAssembler assembler = TestContextAssemblers.of(memoryService, properties, clock);
        String withoutMemory = assembler.withResponseInstructions(AssembledContext.empty()).instructions();
        List<JudgeInput> inputs = new ArrayList<>();
        for (Case each : DATASET.cases()) {
            if (each.judge().isEmpty()) {
                continue;
            }
            fixture.seed(each);
            AssembledContext context = assembler.assemble(fixture.userOf(each.asker()), agentIds.get(each.agent()), each.question());
            inputs.add(new JudgeInput(
                each.id(),
                each.question(),
                assembler.withResponseInstructions(context).instructions(),
                withoutMemory,
                each.judge()));
        }

        MemoryJudgeSettings value = settings.orElseThrow();
        MemoryJudgeReport report = new MemoryJudgeHarness().run(
            inputs,
            new OpenAiCompatibleChatModel(value.baseUrl(), value.apiKey(), value.maxOutputTokens()),
            value,
            DATASET.version());

        writeReport(report);
        assertThat(report.calls()).isLessThanOrEqualTo(value.maxCalls());
    }


    private static void writeReport(MemoryJudgeReport report) {
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve("report.md"), report.markdown());
            Files.writeString(REPORT_DIR.resolve("report.json"), report.json());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        System.out.println(report.markdown());
    }
}
