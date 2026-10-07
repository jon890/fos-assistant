package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProactiveCheckProblemRepository extends JpaRepository<ProactiveCheckProblem, Long> {

    /** 가치 평가는 한 살펴보기의 받아들인 후보 집합만 견준다. */
    List<ProactiveCheckProblem> findByCheckIdAndStatusOrderByIdAsc(Long checkId, ProblemStatus status);

    /** 그 점검 대화에서 그 시각 뒤에 남긴 그 상태의 후보를 최근 것부터 읽는다. 다음 살펴보기의 입력에 싣는다. 개수는 {@code page} 가 정한다. */
    List<ProactiveCheckProblem> findByConversationIdAndStatusAndCreatedAtAfterOrderByIdDesc(
            Long conversationId, ProblemStatus status, Instant after, Pageable page);

    /** 그 점검 대화에서 그 시각 뒤에 남긴 그 상태의 후보를 모두 읽는다. 중복 판정에 쓴다. */
    List<ProactiveCheckProblem> findByConversationIdAndStatusAndCreatedAtAfter(
            Long conversationId, ProblemStatus status, Instant after);
}
