package com.bifos.assistant.crypto.infra;

import com.bifos.assistant.crypto.domain.entity.ServerSecret;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServerSecretRepository extends JpaRepository<ServerSecret, String> {

    List<ServerSecret> findByNameStartingWithOrderByNameAsc(String namePrefix);


    /** 값이 있고 활성 KEK 가 아닌 KEK 로 감싼 줄이다. KEK 를 바꾼 뒤 기동할 때 다시 감쌀 대상이다 */
    List<ServerSecret> findByKekIdNotAndKekIdIsNotNull(String kekId);


    /**
     * 다른 KEK 로 다시 감싼 값만 바꾼다. 엔티티 저장을 거치지 않아 {@code updated_at} 과 바꾼 사람이 그대로다. 화면의 「마지막 변경」 이
     * 기동 시각으로 바뀌지 않게 하려는 것이다. 부르는 쪽 트랜잭션 안에서 부른다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ServerSecret s set s.kekId = :kekId, s.sealedValue = :sealedValue where s.name = :name")
    int rewrap(@Param("name") String name, @Param("kekId") String kekId, @Param("sealedValue") String sealedValue);
}
