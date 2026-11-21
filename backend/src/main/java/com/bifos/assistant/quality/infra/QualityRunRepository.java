package com.bifos.assistant.quality.infra;

import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 확인·평가 기록의 저장소다. */
public interface QualityRunRepository extends JpaRepository<QualityRun, Long> {

    Optional<QualityRun> findByPublicId(UUID publicId);


    /** 그룹에서 지금 도는 그 종류의 확인이다. 시작 잠금 안에서 읽는다. */
    Optional<QualityRun> findFirstByGroupIdAndKindAndStatusOrderByIdDesc(
        Long groupId, QualityRunKind kind, QualityRunStatus status);


    /** 서버가 다시 떴을 때 끝내지 못한 줄이다. */
    List<QualityRun> findByStatus(QualityRunStatus status);


    /** 그룹의 그 종류 확인을 최근 순으로 읽는다. 첫 쪽이다. */
    List<QualityRun> findByGroupIdAndKindOrderByIdDesc(Long groupId, QualityRunKind kind, Pageable page);


    /** {@code id} 보다 앞선 확인을 최근 순으로 읽는다. 다음 쪽이다. */
    List<QualityRun> findByGroupIdAndKindAndIdLessThanOrderByIdDesc(Long groupId, QualityRunKind kind, Long id, Pageable page);


    /** 그 시각 전에 시작해 그 상태로 끝난 확인이다. 보관 기간이 지난 실패 대화를 찾는다. */
    List<QualityRun> findByStatusAndStartedAtBefore(QualityRunStatus status, Instant before);
}
