package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProactiveCheckFindingRepository extends JpaRepository<ProactiveCheckFinding, Long> {

    /** 그 점검 대화에서 그 시각 뒤에 낸 그 종류의 발견을 최근 것부터 읽는다. 다음 살펴보기의 입력에 싣는다. 개수는 {@code page} 가 정한다. */
    List<ProactiveCheckFinding> findByConversationIdAndKindAndCreatedAtAfterOrderByIdDesc(
            Long conversationId, FindingKind kind, Instant after, Pageable page);

    /** 그 점검 대화에서 그 시각 뒤에 낸 그 종류의 발견을 모두 읽는다. 이미 알린 것인지 판정하는 데 쓴다. */
    List<ProactiveCheckFinding> findByConversationIdAndKindAndCreatedAtAfter(
            Long conversationId, FindingKind kind, Instant after);
}
