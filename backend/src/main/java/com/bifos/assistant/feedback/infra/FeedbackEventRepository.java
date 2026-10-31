package com.bifos.assistant.feedback.infra;

import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
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
    List<FeedbackEvent> findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(
        Long userId, Collection<String> subjectKeys);


    /**
     * 그 대화에 묶인 사건의 제안 열쇠다. 대화를 지울 때 그 제안의 사건을 함께 지우려고 읽는다.
     *
     * <p>「참고한 기억」 단추 사건은 그 대화의 답에 대한 반응이지 기억을 제안한 흐름이 아니다. 그 사건은 대화와 함께 지우되, 그 기억의 다른
     * 대화 사건까지 지우지 않게 열쇠를 넓히는 데서 뺀다.
     */
    @Query("select distinct e.subjectKey from FeedbackEvent e where e.userId = :userId and e.conversationId = :conversationId"
        + " and e.eventType not in (com.bifos.assistant.feedback.domain.type.FeedbackEventType.MARKED_IRRELEVANT,"
        + " com.bifos.assistant.feedback.domain.type.FeedbackEventType.MARKED_WRONG,"
        + " com.bifos.assistant.feedback.domain.type.FeedbackEventType.MAINTENANCE_PROPOSED,"
        + " com.bifos.assistant.feedback.domain.type.FeedbackEventType.MAINTENANCE_CONFIRMED,"
        + " com.bifos.assistant.feedback.domain.type.FeedbackEventType.MAINTENANCE_DENIED)")
    List<String> findSubjectKeysOfConversation(
        @Param("userId") Long userId, @Param("conversationId") Long conversationId);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.userId = :userId and e.conversationId = :conversationId")
    int deleteOfConversation(@Param("userId") Long userId, @Param("conversationId") Long conversationId);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.userId = :userId and e.subjectKey in :subjectKeys")
    int deleteOfSubjects(@Param("userId") Long userId, @Param("subjectKeys") Collection<String> subjectKeys);


    /** 그 사용자의 그 종류 사건을 일어난 순서로 낸다. */
    List<FeedbackEvent> findByUserIdAndEventTypeOrderByOccurredAtAscIdAsc(Long userId, FeedbackEventType eventType);


    /** 그 사용자들의 그 종류 사건이다. 그룹 항목의 후보를 관리자 누구든 확인한 것으로 보려고 읽는다. */
    List<FeedbackEvent> findByUserIdInAndEventTypeIn(Collection<Long> userIds, Collection<FeedbackEventType> eventTypes);


    /** 그 사용자가 그 항목에 그 동작의 후보를 그 판으로 이미 남겼는가. 열려 있든 확인했든 다시 만들지 않으려고 본다. */
    boolean existsByUserIdAndSubjectKeyAndEventTypeAndReasonCodeAndSubjectVersion(
        Long userId, String subjectKey, FeedbackEventType eventType, String reasonCode, String subjectVersion);


    /** 그 종류 사건이 있는 열쇠와 사건 수다. 정비 규칙이 부정 신호가 쌓인 항목을 찾으려고 읽는다. */
    @Query("select e.subjectKey, count(e) from FeedbackEvent e where e.eventType in :eventTypes group by e.subjectKey")
    List<Object[]> countSubjectKeysOfTypes(@Param("eventTypes") Collection<FeedbackEventType> eventTypes);


    /** 마지막 사건이 그때보다 앞선 제안의 사용자와 열쇠다. 보관 기간 정리가 제안 단위로 지우려고 읽는다. */
    @Query("select e.userId, e.subjectKey from FeedbackEvent e group by e.userId, e.subjectKey"
        + " having max(e.occurredAt) < :before")
    List<Object[]> findSubjectsLastOccurredBefore(@Param("before") Instant before);
}
