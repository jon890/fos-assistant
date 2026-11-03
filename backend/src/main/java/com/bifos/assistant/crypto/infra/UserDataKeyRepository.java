package com.bifos.assistant.crypto.infra;

import com.bifos.assistant.crypto.domain.entity.UserDataKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDataKeyRepository extends JpaRepository<UserDataKey, Long> {

    Optional<UserDataKey> findByUserId(Long userId);


    Optional<UserDataKey> findByGroupId(Long groupId);


    /** 활성 KEK 가 아닌 KEK 로 감싼 줄의 번호다. KEK 를 바꾼 뒤 기동할 때 다시 감쌀 대상이다 */
    List<UserDataKey> findByKekIdNot(String kekId);


    /** 사용자 정리의 마지막에 그 사용자의 데이터 key 를 지운다(ADR-20261101 / user-purge). 지운 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserDataKey k where k.userId = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);
}
