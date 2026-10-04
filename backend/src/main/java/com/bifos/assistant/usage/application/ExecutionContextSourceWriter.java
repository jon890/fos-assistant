package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실행에 실은 문맥 항목의 참조를 실은 순서대로 {@code execution_context_source} 에 남긴다(ADR-071).
 *
 * <p>사건 저장처럼 관측용이라 저장이 실패해도 부르는 쪽으로 던지지 않는다. 저장은 늘 새 트랜잭션({@code REQUIRES_NEW})에서
 * 한다. 부르는 쪽에 트랜잭션이 있어도 여기서 난 저장 오류가 그 트랜잭션을 롤백 전용으로 만들지 않아, 실행 줄은 그대로 남는다.
 * 로그에는 제목과 본문 없이 실행 번호와 개수만 낸다.
 *
 * <p>{@link ExecutionRecorder} 가 사용자 잠금 밖에서 실행 줄을 만든 직후에 부른다. 잠금 안의 일이 늘면 같은 사용자의 다른
 * turn 이 기다린다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ExecutionContextSourceWriter {

    private static final TransactionDefinition NEW_TRANSACTION =
            new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    private final ExecutionContextSourceRepository contextSources;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    /** 참조가 없으면 아무것도 하지 않는다. 있으면 position 0 부터 실은 순서대로 적는다. */
    public void write(Long executionId, List<ContextSourceRef> sources) {
        if (sources.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<ExecutionContextSource> rows = new ArrayList<>(sources.size());
        for (int position = 0; position < sources.size(); position++) {
            ContextSourceRef ref = sources.get(position);
            rows.add(ExecutionContextSource.of(
                    executionId, position, ref.source(), ref.ref(), ref.bodyMode(), ref.freshness(), now));
        }
        try {
            new TransactionTemplate(transactionManager, NEW_TRANSACTION)
                    .executeWithoutResult(status -> contextSources.saveAll(rows));
        } catch (RuntimeException ex) {
            log.warn("execution context sources not recorded executionId={} count={}", executionId, sources.size(), ex);
        }
    }
}
