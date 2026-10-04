package com.bifos.assistant.context;

import com.bifos.assistant.shared.util.Sha256;
import java.util.List;

/**
 * 조립한 문자열과 그 길이, 자리가 없어 싣지 못한 항목의 번호, 그리고 글로 옮긴 문맥 묶음이다.
 *
 * <p>길이와 지문을 실행 기록에 남긴다. 빠진 항목은 번호로 들고 있어 화면이 그 항목에 표시를 달 수
 * 있다.
 *
 * <p>{@code instructions} 에 Memory 본문이 들어 있어 {@link #toString()} 은 길이와 개수만 낸다(ADR-071).
 */
public record AssembledContext(String instructions, long chars, List<Long> omittedMemoryIds, ContextBundle bundle) {

    public AssembledContext {
        omittedMemoryIds = omittedMemoryIds == null ? List.of() : List.copyOf(omittedMemoryIds);
        bundle = bundle == null ? ContextBundle.empty() : bundle;
    }

    /** 빠진 항목이 하나도 없고 묶음이 빈 문맥이다. */
    public AssembledContext(String instructions, long chars) {
        this(instructions, chars, List.of());
    }

    /** 묶음이 빈 문맥이다. */
    public AssembledContext(String instructions, long chars, List<Long> omittedMemoryIds) {
        this(instructions, chars, omittedMemoryIds, ContextBundle.empty());
    }

    /** 넣을 항목이 없으면 빈 문자열 대신 null 을 보낸다. */
    public static AssembledContext empty() {
        return new AssembledContext(null, 0, List.of(), ContextBundle.empty());
    }

    /**
     * 자리가 없어 싣지 못한 항목 수다.
     *
     * <p>실행 기록의 {@code context_omitted_items} 에 이 값을 적는다. 0 보다 크면 그 실행은 볼 수
     * 있는 Memory 를 전부 받지 못했다는 뜻이다.
     */
    public int omittedItems() {
        return omittedMemoryIds.size();
    }

    /**
     * 실행 기록에 남길 지문이다. SHA-256 의 앞 16바이트를 16진수 32글자로 적는다.
     *
     * <p>본문에 개인 Memory 가 들어 있어 본문 대신 이 값만 남긴다. 같은 문맥은 같은 값을, 다른 문맥은
     * 다른 값을 낸다.
     *
     * <p>문자열이 없으면 null 이다. 실행 기록에는 공통 답변 지침을 추가한 뒤의 지문을 남긴다.
     * Memory가 없어도 공통 지침이 같으면 같은 지문을 기록하므로, 지문만으로 Memory 주입 여부를 판단하지 않는다.
     */
    public String instructionsHash() {
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        return Sha256.hex16(instructions);
    }

    /** 본문이 로그에 남지 않게 글자 수와 빠진 수와 항목 수만 낸다. */
    @Override
    public String toString() {
        return "AssembledContext[chars=" + chars + ", omittedItems=" + omittedItems() + ", items="
                + bundle.items().size() + "]";
    }
}
