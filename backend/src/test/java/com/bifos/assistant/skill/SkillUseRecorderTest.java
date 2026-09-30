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

    @Autowired SkillUseRecorder recorder;

    /** 저장이 실패해도 던지지 않는지 보려면 저장소가 던지게 만들 수 있어야 한다. */
    @MockitoSpyBean ExecutionSkillUseRepository uses;

    @BeforeEach
    void 준비한다() {
        uses.deleteAll();
    }

    private List<ExecutionSkillUse> recorded() {
        return uses.findByExecutionIdInOrderByExecutionIdAscSkillNameAsc(List.of(EXECUTION_ID, OTHER_EXECUTION_ID));
    }

    @Test
    void 이름만_있는_미리보기를_MODEL_로_적는다() {
        recorder.recordModel(EXECUTION_ID, "shopping");

        assertThat(recorded()).singleElement().satisfies(use -> {
            assertThat(use.executionId()).isEqualTo(EXECUTION_ID);
            assertThat(use.skillName()).isEqualTo("shopping");
            assertThat(use.source()).isEqualTo(SkillUseSource.MODEL);
            assertThat(use.occurredAt()).isNotNull();
        });
    }

    @Test
    void 참고_파일_경로가_붙은_미리보기는_화살표_앞의_이름만_적는다() {
        recorder.recordModel(EXECUTION_ID, "shopping → references/a.md");

        assertThat(recorded()).extracting(ExecutionSkillUse::skillName).containsExactly("shopping");
    }

    @Test
    void Hermes_기본_스킬처럼_점과_밑줄이_든_이름도_MODEL_로_적는다() {
        recorder.recordModel(EXECUTION_ID, "note_taking.v2");

        assertThat(recorded()).singleElement().satisfies(use -> {
            assertThat(use.skillName()).isEqualTo("note_taking.v2");
            assertThat(use.source()).isEqualTo(SkillUseSource.MODEL);
        });
    }

    @Test
    void 점과_밑줄이_든_이름에_참고_파일_경로가_붙어도_화살표_앞의_이름만_적는다() {
        recorder.recordModel(EXECUTION_ID, "note_taking.v2 → references/a.md");

        assertThat(recorded()).extracting(ExecutionSkillUse::skillName).containsExactly("note_taking.v2");
    }

    @Test
    void 점이나_밑줄로_시작하거나_대문자가_든_이름은_버린다() {
        recorder.recordModel(EXECUTION_ID, ".hidden");
        recorder.recordModel(EXECUTION_ID, "_x");
        recorder.recordModel(EXECUTION_ID, "Note");

        assertThat(recorded()).isEmpty();
    }

    @Test
    void 규칙에_맞지_않는_미리보기는_버린다() {
        recorder.recordModel(EXECUTION_ID, null);
        recorder.recordModel(EXECUTION_ID, "");
        recorder.recordModel(EXECUTION_ID, "   ");
        recorder.recordModel(EXECUTION_ID, "Shopping");
        recorder.recordModel(EXECUTION_ID, "a".repeat(65));
        recorder.recordModel(EXECUTION_ID, "-starts-with-dash");

        assertThat(recorded()).isEmpty();
    }

    @Test
    void 이름이_64자면_상한_안이라_적는다() {
        String longest = "a".repeat(64);

        recorder.recordModel(EXECUTION_ID, longest);

        assertThat(recorded()).extracting(ExecutionSkillUse::skillName).containsExactly(longest);
    }

    @Test
    void 같은_실행에서_같은_스킬을_두_번_읽어도_한_줄이다() {
        recorder.recordModel(EXECUTION_ID, "shopping");
        recorder.recordModel(EXECUTION_ID, "shopping → references/a.md");

        assertThat(recorded()).hasSize(1);
    }

    @Test
    void 출처가_다르거나_실행이_다르면_따로_적는다() {
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
    void 저장소가_예외를_던져도_recordModel_은_던지지_않는다() {
        doThrow(new RuntimeException("저장 실패")).when(uses).save(any(ExecutionSkillUse.class));

        assertThatCode(() -> recorder.recordModel(EXECUTION_ID, "shopping")).doesNotThrowAnyException();

        assertThat(recorded()).isEmpty();
    }

    @Test
    void 동시에_먼저_적혀_유일_제약에_걸려도_던지지_않는다() {
        doThrow(new DataIntegrityViolationException("uk_execution_skill_use_execution_skill_source"))
                .when(uses).save(any(ExecutionSkillUse.class));

        assertThatCode(() -> recorder.recordCommand(EXECUTION_ID, "shopping")).doesNotThrowAnyException();
    }
}
