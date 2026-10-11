package com.bifos.persistencefixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.data.jpa.repository.JpaRepository;

/** 운영 스캔 범위 밖에서 정상 영속 타입 추가를 재현하는 검사 전용 타입이다. */
public final class PersistenceExtension {
    private PersistenceExtension() {}


    @Entity
    public static class ExtraEntity {
        @Id
        private Long id;
    }

    public interface ExtraRepository extends JpaRepository<ExtraEntity, Long> {
    }
}
