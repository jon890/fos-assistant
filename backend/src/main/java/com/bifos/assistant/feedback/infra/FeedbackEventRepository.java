package com.bifos.assistant.feedback.infra;

import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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


    /**
     * 대화를 지울 때 그 대화의 제안과 같은 열쇠의 다른 사건을 지우되 정비 사건은 남긴다. 정비 사건은 대화에 묶이지 않은 규칙의 기록이라,
     * 지우면 확인한 후보가 같은 revision 으로 다시 열리고 정밀도 표본이 사라진다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.userId = :userId and e.subjectKey in :subjectKeys"
        + " and e.eventType not in (com.bifos.assistant.feedback.domain.type.FeedbackEventType.MAINTENANCE_PROPOSED,"
        + " com.bifos.assistant.feedback.domain.type.FeedbackEventType.MAINTENANCE_CONFIRMED,"
        + " com.bifos.assistant.feedback.domain.type.FeedbackEventType.MAINTENANCE_DENIED)")
    int deleteOfSubjectsKeepingMaintenance(
        @Param("userId") Long userId, @Param("subjectKeys") Collection<String> subjectKeys);


    /**
     * 그 사용자의 한 제안 열쇠에 남은 사건을 다른 제안 열쇠로 옮긴다. 옛 제안의 줄을 새 제안 줄로 옮긴 뒤 반응이 새 줄을 가리키게 하려고
     * 한 번 쓴다. 사건의 나머지 칸은 그대로 둔다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update FeedbackEvent e set e.subjectType = :toType, e.subjectKey = :toKey"
        + " where e.userId = :userId and e.subjectKey = :fromKey")
    int repointSubject(
        @Param("userId") Long userId,
        @Param("fromKey") String fromKey,
        @Param("toType") FeedbackSubjectType toType,
        @Param("toKey") String toKey);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.userId = :userId and e.subjectKey in :subjectKeys")
    int deleteOfSubjects(@Param("userId") Long userId, @Param("subjectKeys") Collection<String> subjectKeys);


    /** {@link #deleteOfSubjects} 와 같되 {@code keepIds} 의 줄은 남긴다. 보관 기간이 지나도 지우지 않을 제어 사건을 가리는 데 쓴다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.userId = :userId and e.subjectKey in :subjectKeys and e.id not in :keepIds")
    int deleteOfSubjectsExcept(
        @Param("userId") Long userId,
        @Param("subjectKeys") Collection<String> subjectKeys,
        @Param("keepIds") Collection<Long> keepIds);


    /**
     * 그 사용자의 카드마다 항목마다 마지막 제어 사건이다(숨김, 미루기, 복원). {@code (user_id, card_key, subject_key, id)} 색인을 따라
     * 카드와 항목별 가장 큰 번호를 고른다. 번호가 가장 큰 사건이 가장 나중에 넣은 사건이다.
     */
    @Query("select e from FeedbackEvent e where e.id in (select max(x.id) from FeedbackEvent x"
        + " where x.userId = :userId and x.cardKey is not null and x.eventType in :types"
        + " group by x.cardKey, x.subjectKey)")
    List<FeedbackEvent> findCurrentControls(
        @Param("userId") Long userId, @Param("types") Collection<FeedbackEventType> types);


    /** 그 사용자의 그 제안 열쇠들 가운데 카드마다 항목마다 마지막 제어 사건의 번호다. 보관 기간 정리가 남길 줄을 가리려고 읽는다. */
    @Query("select max(e.id) from FeedbackEvent e where e.userId = :userId and e.subjectKey in :subjectKeys"
        + " and e.cardKey is not null and e.eventType in :types group by e.cardKey, e.subjectKey")
    List<Long> findCurrentControlIds(
        @Param("userId") Long userId,
        @Param("subjectKeys") Collection<String> subjectKeys,
        @Param("types") Collection<FeedbackEventType> types);


    /** 그 카드의 그 항목의 마지막 제어 사건이다. */
    Optional<FeedbackEvent> findFirstByUserIdAndCardKeyAndSubjectKeyAndEventTypeInOrderByIdDesc(
        Long userId, String cardKey, String subjectKey, Collection<FeedbackEventType> types);


    /** 그 사용자의 그 항목의 그 상태에서 가장 나중에 남긴 노출 사건이다. 열기와 동작이 그때의 계기와 판정을 이어 받는다. */
    Optional<FeedbackEvent> findFirstByUserIdAndSubjectKeyAndSubjectVersionAndEventTypeOrderByIdDesc(
        Long userId, String subjectKey, String subjectVersion, FeedbackEventType eventType);


    boolean existsByUserIdAndEventKey(Long userId, String eventKey);


    /** 그 사용자의 줄 가운데 {@code eventKeys} 에 이미 있는 열쇠다. 이미 남긴 노출을 거르는 데 쓴다. */
    @Query("select e.eventKey from FeedbackEvent e where e.userId = :userId and e.eventKey in :eventKeys")
    List<String> findEventKeys(@Param("userId") Long userId, @Param("eventKeys") Collection<String> eventKeys);


    /**
     * {@code since} 뒤의 지금 화면 지표 사건이다. 노출, 열기, 동작과, 카드가 있는 제어 사건 가운데 숨김과 미루기를 읽는다. 모든
     * 사용자의 것이다. 지표가 센다.
     */
    @Query("select e from FeedbackEvent e where e.occurredAt >= :since and ("
        + "(e.subjectType = com.bifos.assistant.feedback.domain.type.FeedbackSubjectType.ATTENTION_ITEM"
        + " and e.eventType in :views)"
        + " or (e.cardKey is not null and e.eventType in :controls and e.reasonCode in :reasons))")
    List<FeedbackEvent> findAttentionMetricEvents(
        @Param("since") Instant since,
        @Param("views") Collection<FeedbackEventType> views,
        @Param("controls") Collection<FeedbackEventType> controls,
        @Param("reasons") Collection<String> reasons);


    /** {@code before} 보다 먼저 남긴 지금 화면의 노출, 열기, 동작 사건을 지운다. 제어 사건은 지우지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeedbackEvent e where e.subjectType = com.bifos.assistant.feedback.domain.type.FeedbackSubjectType.ATTENTION_ITEM"
        + " and e.eventType in :views and e.occurredAt < :before")
    int deleteAttentionViewsBefore(
        @Param("views") Collection<FeedbackEventType> views, @Param("before") Instant before);


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
