package com.bifos.assistant.proactive.check.infra;

import com.bifos.assistant.proactive.check.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.check.domain.type.FindingKind;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProactiveCheckFindingRepository extends JpaRepository<ProactiveCheckFinding, Long> {

    /** 그 번호 뒤의 그 종류 발견을 번호 순서로 읽는다. 옛 줄을 제안 줄로 옮기는 작업이 번호를 따라 나누어 읽는다. */
    List<ProactiveCheckFinding> findByKindAndIdGreaterThanOrderByIdAsc(FindingKind kind, Long afterId, Pageable page);
}
