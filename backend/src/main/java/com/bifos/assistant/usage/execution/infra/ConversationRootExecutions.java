package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * 대화 하나의 루트 실행을 묻는 저장소다. 배포 뒤 확인이 기능 하나가 돈 turn 들을 모은다(docs/features/agent-quality/design.md).
 * {@code AgentExecutionRepository} 가 파일 길이 상한에 닿아 따로 둔다.
 */
public interface ConversationRootExecutions extends Repository<AgentExecution, Long> {

    /** 그 대화의 루트 실행을 만든 순서로 읽는다. 사진 조회의 두 turn, 위임의 자동 turn 이 모두 든다. */
    List<AgentExecution> findByConversationIdAndParentExecutionIdIsNullOrderByIdAsc(Long conversationId);
}
