package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위임 결과를 전했다는 표시의 조건부 update 에 트랜잭션 경계를 준다.
 *
 * <p>저장소의 조건부 update 를 부르는 쪽의 트랜잭션에 참여시키고, 없으면 그 문장만의 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class ExecutionDeliveryWriter {

    private final AgentExecutionRepository repository;

    /** 이 실행의 결과를 부모에게 전했다고 적는다. 뜻은 {@link AgentExecutionRepository#markResultDelivered} 가 적는다. */
    @Transactional
    public int markResultDelivered(Long id, Instant at) {
        return repository.markResultDelivered(id, at);
    }

    /** 한 루트 아래의 위임 결과를 모두 전했다고 적는다. 뜻은 {@link AgentExecutionRepository#markTreeDelivered} 가 적는다. */
    @Transactional
    public int markTreeDelivered(Long rootExecutionId, Instant at) {
        return repository.markTreeDelivered(rootExecutionId, at);
    }
}
