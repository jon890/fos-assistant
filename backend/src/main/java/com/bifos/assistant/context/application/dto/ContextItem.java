package com.bifos.assistant.context.application.dto;

import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import java.time.Instant;
import java.util.List;

/**
 * 문맥 묶음의 항목 하나다. 원래 기록 하나를 가리키고 합치거나 요약하지 않는다(ADR-071).
 *
 * <p>값의 뜻은 각 enum 이 갖고, {@code ref} 의 형식과 {@code scope}, {@code sensitivity} 를 채우는 규칙은
 * {@code docs/features/memory.md} 의 「항목의 칸」 이 갖는다. {@code title} 과 {@code body} 는 글로 옮길
 * 때만 쓰고 저장하거나 로그에 내지 않는다. 그래서 {@link #toString()} 은 {@code source} 와 {@code ref} 만 낸다.
 *
 * @param ref 원래 기록의 참조. Memory 는 {@code memory:<번호>} 다
 * @param ownerUserId {@code USER} 항목의 주인. {@code GROUP} 이면 비어 있다
 * @param asOf 그 내용이 참이던 시각
 * @param conflictsWith Control Plane 이 구조로 알 수 있는 충돌 상대의 {@code ref}
 * @param selection 그 항목을 고른 규칙과 순위. Memory 조립이 채우고 그 밖은 null 이다
 */
public record ContextItem(
    ContextSource source,
    String ref,
    MemoryScope scope,
    Long ownerUserId,
    MemorySensitivity sensitivity,
    ContextTrust trust,
    Instant asOf,
    ContextFreshness freshness,
    ContextBodyMode bodyMode,
    List<String> conflictsWith,
    String title,
    String body,
    ContextSelection selection) {

    public ContextItem {
        conflictsWith = conflictsWith == null ? List.of() : List.copyOf(conflictsWith);
    }


    /** 선택 근거가 없는 항목이다. 결과 항목과 조립 도중의 Memory 항목이 쓴다. */
    public ContextItem(
        ContextSource source,
        String ref,
        MemoryScope scope,
        Long ownerUserId,
        MemorySensitivity sensitivity,
        ContextTrust trust,
        Instant asOf,
        ContextFreshness freshness,
        ContextBodyMode bodyMode,
        List<String> conflictsWith,
        String title,
        String body) {
        this(
            source, ref, scope, ownerUserId, sensitivity, trust, asOf, freshness, bodyMode, conflictsWith, title, body,
            null);
    }


    /** Memory 한 줄의 참조다. */
    public static String memoryRef(Long id) {
        return "memory:" + id;
    }


    /** 본문을 싣는 방식만 바꾼 사본이다. */
    public ContextItem withBodyMode(ContextBodyMode mode) {
        return new ContextItem(
            source, ref, scope, ownerUserId, sensitivity, trust, asOf, freshness, mode, conflictsWith, title, body,
            selection);
    }


    /** 선택 근거만 바꾼 사본이다. */
    public ContextItem withSelection(ContextSelection value) {
        return new ContextItem(
            source, ref, scope, ownerUserId, sensitivity, trust, asOf, freshness, bodyMode, conflictsWith, title, body,
            value);
    }


    /** 제목과 본문이 로그에 남지 않게 출처와 참조만 낸다. */
    @Override
    public String toString() {
        return "ContextItem[source=" + source + ", ref=" + ref + "]";
    }
}
