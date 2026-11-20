package com.bifos.assistant.crypto.infra;

import com.bifos.assistant.crypto.domain.entity.ServerSecret;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerSecretRepository extends JpaRepository<ServerSecret, String> {

    List<ServerSecret> findByNameStartingWithOrderByNameAsc(String namePrefix);


    /** 값이 있고 활성 KEK 가 아닌 KEK 로 감싼 줄이다. KEK 를 바꾼 뒤 기동할 때 다시 감쌀 대상이다 */
    List<ServerSecret> findByKekIdNotAndKekIdIsNotNull(String kekId);
}
