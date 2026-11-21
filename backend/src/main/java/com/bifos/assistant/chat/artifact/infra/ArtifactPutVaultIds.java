package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 대화 하나에서 에이전트의 넣기 도구로 넣은 보관본을 묻는 저장소다. 배포 뒤 확인이 판정과 정리에 쓴다(docs/features/agent-quality/design.md).
 * {@code ChatArtifactRepository} 가 파일 길이 상한에 닿아 따로 둔다.
 */
public interface ArtifactPutVaultIds extends Repository<ChatArtifact, Long> {

    /** 그 대화에서 넣기 도구로 넣은, 지우지 않은 보관본 뿌리 행의 번호다. */
    @Query("""
            select a.id from ChatArtifact a
             where a.vaultUserId = :userId
               and a.conversationId = :conversationId
               and a.vaultOrigin is not null
               and a.vaultBundleId is null
               and a.deletedAt is null
             order by a.id
            """)
    List<Long> findPutVaultIds(@Param("userId") Long userId, @Param("conversationId") Long conversationId);
}
