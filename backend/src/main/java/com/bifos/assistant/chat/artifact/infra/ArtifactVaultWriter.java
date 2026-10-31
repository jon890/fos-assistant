package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 보관 사본 행을 넣고 바로 영속성 컨텍스트에서 뗀다.
 *
 * <p>묶음 하나는 최대 수백 MB 의 암호문을 한 트랜잭션에 넣는다. 넣은 행을 컨텍스트에 두면 끝날 때까지 모든 본문이 메모리에
 * 남는다. 그래서 한 행씩 넣고 떼어 메모리에는 파일 하나만 있게 한다. 트랜잭션은 부르는 쪽이 연다.
 */
@Component
@RequiredArgsConstructor
public class ArtifactVaultWriter {

    private final EntityManager entityManager;


    /** 행을 넣고 번호를 돌려준다. 돌려준 뒤에는 그 행을 컨텍스트가 들고 있지 않다. */
    public Long insertDetached(ChatArtifact row) {
        entityManager.persist(row);
        entityManager.flush();
        entityManager.detach(row);
        return row.id();
    }
}
