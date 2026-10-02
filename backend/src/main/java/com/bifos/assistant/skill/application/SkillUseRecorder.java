package com.bifos.assistant.skill.application;

import com.bifos.assistant.hermes.HermesSkillName;
import com.bifos.assistant.skill.domain.ExecutionSkillUse;
import com.bifos.assistant.skill.domain.SkillUseSource;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실행 하나에서 스킬이 쓰인 것을 {@code execution_skill_use} 에 적는다.
 *
 * <p>이력은 관측용이라 저장이 실패해도 부르는 쪽으로 던지지 않는다. 그것 때문에 대화 turn 이 실패하면 안
 * 된다. 저장은 늘 새 트랜잭션({@code REQUIRES_NEW})에서 한다. 부르는 쪽의 트랜잭션이 뒤에 되돌려져도
 * 이력은 남고, 여기서 난 저장 오류가 부르는 쪽의 트랜잭션을 롤백 전용으로 만들지 않는다.
 *
 * <p>한 실행에서 같은 스킬을 여러 번 읽어도 한 줄이다. 넣기 전에 있는지 보고, 동시에 넣어 유일 제약에
 * 걸리면 이미 적힌 것이므로 {@code debug} 로만 남긴다.
 */
@Service
@Slf4j
public class SkillUseRecorder {

    private final ExecutionSkillUseRepository uses;
    private final TransactionTemplate newTransaction;

    public SkillUseRecorder(ExecutionSkillUseRepository uses, PlatformTransactionManager transactionManager) {
        this.uses = uses;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 모델이 {@code skill_view} 로 읽은 것을 적는다.
     *
     * @param skillName 사건 스트림이 가리기 전 미리보기에서 꺼내 넘긴 스킬 이름. {@code 이름 → 파일 경로} 모양도
     *     받는다. 넘겨받은 값을 믿지 않고 Hermes 스킬 이름 규칙으로 다시 보아, 맞지 않으면 버린다
     */
    public void recordModel(Long executionId, String skillName) {
        record(executionId, HermesSkillName.fromPreview(skillName), SkillUseSource.MODEL);
    }

    /** 사용자가 {@code /이름} 으로 부른 것을 적는다. */
    public void recordCommand(Long executionId, String skillName) {
        record(executionId, HermesSkillName.fromPreview(skillName), SkillUseSource.COMMAND);
    }

    private void record(Long executionId, String skillName, SkillUseSource source) {
        if (executionId == null || skillName == null) {
            return;
        }
        try {
            newTransaction.executeWithoutResult(status -> {
                if (uses.existsByExecutionIdAndSkillNameAndSource(executionId, skillName, source)) {
                    return;
                }
                uses.save(ExecutionSkillUse.of(executionId, skillName, source, Instant.now()));
            });
        } catch (DataIntegrityViolationException ex) {
            log.debug("같은 스킬 사용이 먼저 적혀 있어 건너뛴다 executionId={} skill={} source={}", executionId, skillName, source);
        } catch (RuntimeException ex) {
            log.warn("스킬 사용을 적지 못했다 executionId={} skill={} source={}", executionId, skillName, source, ex);
        }
    }
}
