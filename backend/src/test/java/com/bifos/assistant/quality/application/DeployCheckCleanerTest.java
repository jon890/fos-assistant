package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.quality.domain.EvalResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import com.bifos.assistant.quality.domain.type.QualityRunTrigger;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 배포 뒤 확인의 정리가 대화 단위로 기억을 되돌리고, 하루 정리가 평가 줄을 건드리지 않는지 본다. */
@BackendIntegrationTest
class DeployCheckCleanerTest {

    private static final CurrentUser EVALUATION = new CurrentUser(71L, "evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER);
    private static final Long CHECK_CONVERSATION = 9001L;
    private static final Long OTHER_CONVERSATION = 9002L;

    @Autowired
    DeployCheckCleaner cleaner;

    @Autowired
    MemoryService memoryService;

    @Autowired
    MemoryRepository memories;

    @Autowired
    MemoryRevisionRepository revisions;

    @Autowired
    MemoryCaptureRepository captures;

    @Autowired
    QualityRunRepository runs;


    @BeforeEach
    void setUp() {
        captures.deleteAll();
        revisions.deleteAll();
        memories.deleteAll();
        runs.deleteAll();
    }


    @Test
    @DisplayName("그 대화가 만든 기억과 제안은 지우고 고친 기억은 되돌리며, 다른 대화의 기억은 남긴다")
    void forgetsOnlyCapturesOfTheConversation() {
        Memory created = memoryService.create(EVALUATION, MemoryScope.USER, "새 기억", "확인이 남긴 것", false);
        capture(created, CHECK_CONVERSATION, MemoryCaptureKind.CREATED, created.revision());
        Memory proposed = memoryService.proposeUser(EVALUATION, "제안", "확인이 제안한 것", null);
        capture(proposed, CHECK_CONVERSATION, MemoryCaptureKind.PROPOSED, null);
        Memory prepared = memoryService.create(EVALUATION, MemoryScope.USER, "평가 사용자가 좋아하는 색", "평가 사용자가 좋아하는 색은 청록이다.", false);
        int before = memories.findById(prepared.id()).orElseThrow().revision();
        memoryService.update(EVALUATION, prepared.id(), "평가 사용자가 좋아하는 색은 빨강이다.", false, null);
        capture(prepared, CHECK_CONVERSATION, MemoryCaptureKind.UPDATED, before);
        Memory other = memoryService.create(EVALUATION, MemoryScope.USER, "다른 대화", "평가 문항이 남긴 것", false);
        capture(other, OTHER_CONVERSATION, MemoryCaptureKind.CREATED, other.revision());

        boolean all = cleaner.forgetCaptured(EVALUATION, CHECK_CONVERSATION);

        assertThat(all).isTrue();
        assertThat(memories.findById(created.id())).isEmpty();
        assertThat(memories.findById(proposed.id())).isEmpty();
        assertThat(memories.findById(prepared.id()).orElseThrow().content()).isEqualTo("평가 사용자가 좋아하는 색은 청록이다.");
        assertThat(memories.findById(other.id())).isPresent();
    }


    @Test
    @DisplayName("하루 정리는 보관 기간이 지난 실패한 평가 줄을 배포 뒤 확인으로 읽지 않는다")
    void sweepIgnoresEvalRows() {
        QualityRun eval = QualityRun.startEval(1L, QualityRunTrigger.ADMIN, null, 1, EvalResult.started("MEDIUM", List.of("service-concepts")),
            Instant.parse("2026-01-01T00:00:00Z"));
        eval.finish(QualityRunStatus.FAILED, Instant.parse("2026-01-01T00:10:00Z"));
        Long id = runs.save(eval).id();

        cleaner.sweepExpired();

        assertThat(runs.findById(id).orElseThrow().evalResult().bundles()).hasSize(1);
    }


    private void capture(Memory memory, Long conversationId, MemoryCaptureKind kind, Integer baseRevision) {
        captures.save(MemoryCapture.of(memory.id(), EVALUATION.id(), conversationId, 501L, kind, baseRevision, null, false));
    }
}
