package com.bifos.assistant.feedback.infra;

import com.bifos.assistant.feedback.domain.FeedbackEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedbackEventRepository extends JpaRepository<FeedbackEvent, Long> {

    /** 그 사용자가 그때 뒤로 남긴 사건을 일어난 순서로 낸다. */
    List<FeedbackEvent> findByUserIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAscIdAsc(Long userId, Instant since);

    /** 그 사용자의 그 제안들에 남은 사건이다. */
    List<FeedbackEvent> findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(Long userId, Collection<String> subjectKeys);

    /** 그 대화에 묶인 사건의 제안 열쇠다. 대화를 지울 때 그 제안의 사건을 함께 지우려고 읽는다. */
    @Query("select distinct e.subjectKey from FeedbackEvent e where e.userId = :userId and e.conversationId = :conversationId")
    List<String> findSubjectKeysOfConversation(
            @Param("userId") Long userId, @Param("conversationId") Long conversationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.userId = :userId and e.conversationId = :conversationId")
    int deleteOfConversation(@Param("userId") Long userId, @Param("conversationId") Long conversationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.userId = :userId and e.subjectKey in :subjectKeys")
    int deleteOfSubjects(@Param("userId") Long userId, @Param("subjectKeys") Collection<String> subjectKeys);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.occurredAt < :before")
    int deleteOccurredBefore(@Param("before") Instant before);
}
