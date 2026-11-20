package com.bifos.assistant.quality.infra;

import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 확인·평가 기록의 저장소다. */
public interface QualityRunRepository extends JpaRepository<QualityRun, Long> {

    Optional<QualityRun> findByPublicId(UUID publicId);


    /** 그룹에서 지금 도는 그 종류의 확인이다. 시작 잠금 안에서 읽는다. */
    Optional<QualityRun> findFirstByGroupIdAndKindAndStatusOrderByIdDesc(
        Long groupId, QualityRunKind kind, QualityRunStatus status);


    /** 서버가 다시 떴을 때 끝내지 못한 줄이다. */
    List<QualityRun> findByStatus(QualityRunStatus status);
}
