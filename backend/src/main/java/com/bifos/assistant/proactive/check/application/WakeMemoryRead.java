package com.bifos.assistant.proactive.check.application;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.util.ExternalData;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 살펴보기 입력의 「기억에서 꺼낸 최근 일」 절을 만든다(ADR-20261101 / proactive-suggestion).
 *
 * <p>그 에이전트가 받는 기억 가운데 지난 살펴보기 뒤에 바뀐 항목의 번호와 제목만 싣는다. 항상 싣는 항목은 본문이 이미 문맥에 있어 그렇게
 * 표시한다. 무엇이 목표인지는 모델이 제목을 보고 정한다. 종류를 칸이나 enum 으로 나누지 않는다.
 *
 * <p>후보는 {@link MemoryService#alwaysInjectedFor} 와 {@link MemoryService#indexedFor} 가 낸 항목뿐이다. 그 목록은 이미 요청자의 읽기
 * 권한, 에이전트가 받는 collection 과 민감도, 승인, 대체되지 않음을 지났다. 본문은 싣지 않는다. 모델은 색인 항목을 번호로 {@code memory_read} 를 불러 읽고, 그 도구가 같은 권한을 다시 본다.
 */
@Component
@RequiredArgsConstructor
class WakeMemoryRead {

    /** 앞선 살펴보기가 없을 때 「최근」 으로 보는 기간이다. */
    static final Duration FIRST_WINDOW = Duration.ofDays(7);

    /** 바뀐 항목을 싣는 최대 수다. 늦게 바뀐 것부터 싣는다. */
    static final int MAX_CHANGED = 10;

    static final String HEADER = "기억에서 꺼낸 최근 일";
    static final String GUIDE = "아래는 이 에이전트가 받는 기억 가운데 지난 살펴보기 뒤에 바뀐 항목의 번호와 제목이다. "
        + "무엇이 목표이고 무엇이 최근 일인지는 직접 판단하고, 필요한 본문은 번호로 memory_read 를 불러 읽는다. "
        + "「본문은 문맥에 있음」 이 붙은 항목은 위 기억 문맥에 본문이 있어 읽지 않는다.";
    static final String IN_CONTEXT = " · 본문은 문맥에 있음";
    static final String NONE = "지난 살펴보기 뒤에 바뀐 기억이 없다.";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final MemoryService memories;


    /**
     * 절 하나를 만든다. 머리 줄과 안내 글은 Control Plane 이 정하고, 제목은 사람과 모델이 쓴 글이라 {@code <external-data>} 로
     * 감싼다.
     *
     * @param since 이 시각 뒤에 바뀐 항목을 싣는다. 마지막으로 성공한 살펴보기의 시작 시각이다. null 이면 {@code now} 에서 {@link #FIRST_WINDOW} 앞이다
     */
    String section(CurrentUser owner, Long agentId, Instant since, Instant now) {
        List<Memory> changed = changed(owner, agentId, since == null ? now.minus(FIRST_WINDOW) : since);
        if (changed.isEmpty()) {
            return HEADER + "\n" + NONE;
        }
        String lines = changed.stream()
            .map(memory -> "- [" + memory.id() + "] " + oneLine(memory.title())
                + " · 바뀜 " + DATE.format(changedAt(memory)) + inContext(memory))
            .collect(Collectors.joining("\n"));
        return HEADER + "\n" + GUIDE + "\n" + ExternalData.wrap(lines);
    }


    /** 지난 살펴보기 뒤에 바뀐 항목이다. 그 에이전트가 받는 항목뿐이다. */
    private List<Memory> changed(CurrentUser owner, Long agentId, Instant since) {
        MemoryAccess access = memories.accessOf(agentId);
        Map<Long, Memory> visible = Stream
            .concat(memories.alwaysInjectedFor(owner, access).stream(), memories.indexedFor(owner, access).stream())
            .collect(Collectors.toMap(Memory::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        return visible.values().stream()
            .filter(memory -> changedAt(memory).isAfter(since))
            .sorted(Comparator.comparing(WakeMemoryRead::changedAt).reversed().thenComparing(Memory::id))
            .limit(MAX_CHANGED)
            .toList();
    }


    /** 받아들인 시각과 고친 시각 가운데 늦은 쪽이다. 제안으로 남았다가 나중에 받아들인 항목도 받아들인 때 바뀐 것으로 본다. */
    static Instant changedAt(Memory memory) {
        Instant accepted = memory.acceptedAt();
        Instant updated = memory.updatedAt();
        if (accepted == null) {
            return updated;
        }
        return accepted.isAfter(updated) ? accepted : updated;
    }


    /** 항상 싣는 항목은 본문이 이미 문맥에 있고 {@code memory_read} 로는 읽히지 않는다. 그 사실을 줄 끝에 적는다. */
    private static String inContext(Memory memory) {
        return memory.retrieval() == MemoryRetrieval.ALWAYS ? IN_CONTEXT : "";
    }


    private static String oneLine(String value) {
        return value == null ? "-" : value.replaceAll("\\s+", " ").strip();
    }
}
