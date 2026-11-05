package com.bifos.assistant.proactive.check.infra;

import com.bifos.assistant.proactive.check.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.check.domain.type.ProblemStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProactiveCheckProblemRepository extends JpaRepository<ProactiveCheckProblem, Long> {

    /** 그 번호 뒤의 그 상태 후보를 번호 순서로 읽는다. 옛 줄을 제안 줄로 옮기는 작업이 번호를 따라 나누어 읽는다. */
    List<ProactiveCheckProblem> findByStatusAndIdGreaterThanOrderByIdAsc(ProblemStatus status, Long afterId, Pageable page);
}
