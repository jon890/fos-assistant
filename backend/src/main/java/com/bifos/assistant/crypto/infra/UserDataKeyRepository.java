package com.bifos.assistant.crypto.infra;

import com.bifos.assistant.crypto.domain.UserDataKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDataKeyRepository extends JpaRepository<UserDataKey, Long> {

    Optional<UserDataKey> findByUserId(Long userId);

    /** 활성 KEK 가 아닌 KEK 로 감싼 줄의 번호다. KEK 를 바꾼 뒤 기동할 때 다시 감쌀 대상이다 */
    List<UserDataKey> findByKekIdNot(String kekId);
}
