package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProactiveCheckRepository extends JpaRepository<ProactiveCheck, Long> {

    /** 그 사용자가 그 에이전트로 연 마지막 살펴보기. */
    Optional<ProactiveCheck> findFirstByUserIdAndAgentIdOrderByIdDesc(Long userId, Long agentId);

    /** 그 실행 줄이 살펴보기 turn 인지. 살펴보기 트리를 가리는 데 쓴다. */
    boolean existsByRootExecutionId(Long rootExecutionId);

    Optional<ProactiveCheck> findByRootExecutionId(Long rootExecutionId);

    /** 그 점검 대화의 그 루트 session 으로 보낸 살펴보기 수. session 을 바꿀지 셀 때 쓴다. */
    long countByConversationIdAndHermesRootSessionId(Long conversationId, String hermesRootSessionId);

    /** 그 상태의 살펴보기. 기동할 때 {@code RUNNING} 으로 남은 줄을 찾는다. */
    List<ProactiveCheck> findByStatus(CheckStatus status);

    /** 그 점검 대화에서 그 상태가 아닌 마지막 살펴보기. 변화 신호의 「지난 살펴보기」 를 읽는다. */
    Optional<ProactiveCheck> findFirstByConversationIdAndStatusNotOrderByIdDesc(
            Long conversationId, CheckStatus status);
}
