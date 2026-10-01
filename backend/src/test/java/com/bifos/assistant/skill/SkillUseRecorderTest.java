package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.skill.application.SkillUseRecorder;
import com.bifos.assistant.skill.domain.ExecutionSkillUse;
import com.bifos.assistant.skill.domain.SkillUseSource;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 스킬 사용을 적는 자리가 미리보기에서 이름을 어떻게 고르고, 중복과 저장 실패를 어떻게 다루는지 본다.
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 저장이 새 트랜잭션에서 실제로 끝나야 다음 호출이 그 줄을 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillUseRecorderTest {

    /** 실행 표와 외래 키로 묶이지 않아 실제 실행 줄 없이 번호만 쓴다. */
    private static final Long EXECUTION_ID = 9_101L;

    private static final Long OTHER_EXECUTION_ID = 9_102L;

    @Autowired
    SkillUseRecorder recorder;

    /** 저장이 실패해도 던지지 않는지 보려면 저장소가 던지게 만들 수 있어야 한다. */
    @MockitoSpyBean
    ExecutionSkillUseRepository uses;

    @BeforeEach
    void setUp() {
        uses.deleteAll();
    }

    private List<ExecutionSkillUse> recorded() {
        return uses.findByExecutionIdInOrderByExecutionIdAscSkillNameAsc(List.of(EXECUTION_ID, OTHER_EXECUTION_ID));
    }

    @Test
    @DisplayName("이름만 있는 미리보기를 MODEL 로 적는다")
    void recordsNameOnlyPreviewAsModel() {
        recorder.recordModel(EXECUTION_ID, "shopping");

        assertThat(recorded()).singleElement().satisfies(use -> {
            assertThat(use.executionId()).isEqualTo(EXECUTION_ID);
            assertThat(use.skillName()).isEqualTo("shopping");
            assertThat(use.source()).isEqualTo(SkillUseSource.MODEL);
            assertThat(use.occurredAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("참고 파일 경로가 붙은 미리보기는 화살표 앞의 이름만 적는다")
    void recordsOnlyNameBeforeArrowForPreviewWithReferenceFilePath() {
        recorder.recordModel(EXECUTION_ID, "shopping → references/a.md");

        assertThat(recorded()).extracting(ExecutionSkillUse::skillName).containsExactly("shopping");
    }

    @Test
    @DisplayName("Hermes 기본 스킬처럼 점과 밑줄이 든 이름도 MODEL 로 적는다")
    void recordsNameWithDotAndUnderscoreLikeHermesDefaultSkillAsModel() {
        recorder.recordModel(EXECUTION_ID, "note_taking.v2");

        assertThat(recorded()).singleElement().satisfies(use -> {
            assertThat(use.skillName()).isEqualTo("note_taking.v2");
            assertThat(use.source()).isEqualTo(SkillUseSource.MODEL);
        });
    }

    @Test
    @DisplayName("점과 밑줄이 든 이름에 참고 파일 경로가 붙어도 화살표 앞의 이름만 적는다")
    void recordsOnlyNameBeforeArrowForDotUnderscoreNameWithReferencePath() {
        recorder.recordModel(EXECUTION_ID, "note_taking.v2 → references/a.md");

        assertThat(recorded()).extracting(ExecutionSkillUse::skillName).containsExactly("note_taking.v2");
    }

    @Test
    @DisplayName("점이나 밑줄로 시작하거나 대문자가 든 이름은 버린다")
    void dropsNameStartingWithDotOrUnderscoreOrHavingUppercase() {
        recorder.recordModel(EXECUTION_ID, ".hidden");
        recorder.recordModel(EXECUTION_ID, "_x");
        recorder.recordModel(EXECUTION_ID, "Note");

        assertThat(recorded()).isEmpty();
    }

    @Test
    @DisplayName("규칙에 맞지 않는 미리보기는 버린다")
    void dropsPreviewBreakingRules() {
        recorder.recordModel(EXECUTION_ID, null);
        recorder.recordModel(EXECUTION_ID, "");
        recorder.recordModel(EXECUTION_ID, "   ");
        recorder.recordModel(EXECUTION_ID, "Shopping");
        recorder.recordModel(EXECUTION_ID, "a".repeat(65));
        recorder.recordModel(EXECUTION_ID, "-starts-with-dash");

        assertThat(recorded()).isEmpty();
    }

    @Test
    @DisplayName("이름이 64자면 상한 안이라 적는다")
    void recordsNameOf64CharsAsWithinLimit() {
        String longest = "a".repeat(64);

        recorder.recordModel(EXECUTION_ID, longest);

        assertThat(recorded()).extracting(ExecutionSkillUse::skillName).containsExactly(longest);
    }

    @Test
    @DisplayName("같은 실행에서 같은 스킬을 두 번 읽어도 한 줄이다")
    void readingSameSkillTwiceInOneRunLeavesOneRow() {
        recorder.recordModel(EXECUTION_ID, "shopping");
        recorder.recordModel(EXECUTION_ID, "shopping → references/a.md");

        assertThat(recorded()).hasSize(1);
    }

    @Test
    @DisplayName("출처가 다르거나 실행이 다르면 따로 적는다")
    void recordsSeparatelyWhenSourceOrRunDiffers() {
        recorder.recordModel(EXECUTION_ID, "shopping");
        recorder.recordCommand(EXECUTION_ID, "shopping");
        recorder.recordModel(OTHER_EXECUTION_ID, "shopping");

        assertThat(recorded())
                .extracting(ExecutionSkillUse::executionId, ExecutionSkillUse::source)
                .containsExactlyInAnyOrder(
                        tuple(EXECUTION_ID, SkillUseSource.MODEL),
                        tuple(EXECUTION_ID, SkillUseSource.COMMAND),
                        tuple(OTHER_EXECUTION_ID, SkillUseSource.MODEL));
    }

    @Test
    @DisplayName("저장소가 예외를 던져도 recordModel 은 던지지 않는다")
    void recordModelDoesNotThrowEvenIfStoreThrows() {
        doThrow(new RuntimeException("저장 실패")).when(uses).save(any(ExecutionSkillUse.class));

        assertThatCode(() -> recorder.recordModel(EXECUTION_ID, "shopping")).doesNotThrowAnyException();

        assertThat(recorded()).isEmpty();
    }

    @Test
    @DisplayName("동시에 먼저 적혀 유일 제약에 걸려도 던지지 않는다")
    void doesNotThrowEvenIfUniqueConstraintHitByConcurrentEarlierWrite() {
        doThrow(new DataIntegrityViolationException("uk_execution_skill_use_execution_skill_source"))
                .when(uses)
                .save(any(ExecutionSkillUse.class));

        assertThatCode(() -> recorder.recordCommand(EXECUTION_ID, "shopping")).doesNotThrowAnyException();
    }
}
