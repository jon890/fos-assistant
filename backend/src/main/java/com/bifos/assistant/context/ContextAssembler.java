package com.bifos.assistant.context;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.OmittedMemories;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 실행 하나에 넣을 instructions 를 조립한다.
 *
 * <p>Memory 를 먼저 문맥 묶음의 항목으로 만들고, 그 항목을 지금과 같은 글로 옮긴다(ADR-071). 같은 이름의 개인 문서와 그룹
 * 문서에 붙는 충돌 표시를 빼면 글이 바뀌지 않아 {@code instructions_hash} 도 그대로다.
 *
 * <p>층은 그룹 항상 층, 개인 항상 층, 개인 사실 구역, 질문과 관련된 기억 구역, 색인 층 순서다. 개인 사실 구역은 색인에 오를 짧은 개인 항목을
 * 본문까지 싣는다(ADR-20261008 / memory-facts). 질문과 관련된 기억 구역은 질문이 있을 때만 관련 있는 항목을 본문까지 싣는다
 * (ADR-20261015 / memory-hybrid-selection). 두 구역에 실은 항목은 색인에서 뺀다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContextAssembler implements OmittedMemories {
    private static final String GROUP_HEADER = "# 우리 그룹이 함께 아는 것";
    private static final String USER_HEADER = "# 지금 묻는 사람에 대해 아는 것";
    private static final String RESPONSE_INSTRUCTIONS = """
            # 답변 형식

            표는 GFM 마크다운 형식으로 작성한다. 머리 줄 바로 다음에 각 열의 구분 줄을 반드시 둔다.
            예:
            | 항목 | 값 |
            | --- | --- |
            | 예시 | 내용 |
            """.stripTrailing();
    private static final String TOOL_CALL_INSTRUCTIONS = """
            # 도구 호출

            로컬 MCP 등록 이름이 mcp__... 인 도구를 tool_call 로 호출할 때 calls 배열에는 name 과 arguments 를 담은 정확히 한 항목만 넣는다.
            같은 서버의 읽기 도구도 여러 개를 묶지 말고 도구마다 별도 호출한다.
            MCP 서버 연결이 HTTP 여도 로컬 등록 이름이면 이 단건 규칙을 따른다. connectors__... HTTP 원격 도구 서버는 해당 서버의 계약을 따른다.
            """.stripTrailing();
    /**
     * {@code memory_remember} 을 받는 에이전트에 싣는 기억 지침이다(ADR-20261007 / memory-remember). 기준과 예시는 도구 설명과 같은 뜻이다.
     */
    static final String MEMORY_INSTRUCTIONS = """
            # 기억

            사용자가 이번 메시지에서 자기에 관한 오래 쓰일 사실을 말하면 memory_remember 로 남긴다. 기억해 달라고 하지 않아도 남긴다.
            남길 것은 가족 구성, 이름과 관계, 선호, 상황, 결정이다. 작업 기록, 한 번만 쓰일 요청, 대화 요약은 남기지 않는다.
            사용자의 말에 있는 부정(못, 안, 않, 없, 아니)은 content 에 그대로 살린다. 이미 기억한 사실이 바뀌었으면 그 번호를 memory_id 로 주어 고친다.
            도구 결과가 제안으로 남았다고 하면 사용자가 받아들여야 기억한다는 것을 답에서 알린다. 사용자가 기억해 달라고 했으면 기억했는지 답에서 알린다.
            """.stripTrailing();

    /** 개인 사실 구역의 머리 줄과 안내 글이다. 구역을 고르는 규칙은 {@code docs/features/memory.md} 의 「개인 사실 구역」 이 갖는다. */
    private static final String FACTS_HEADER = """
            # 지금 묻는 사람에 대해 기억한 것

            아래는 이 사람에 대해 기억한 짧은 사실이다. 필요하면 번호로 memory_read 를 불러 다시 읽는다.
            """.stripTrailing();

    private static final String INDEX_HEADER = """
            # 더 물어볼 수 있는 것

            아래는 제목만 적은 것이다. 필요하면 memory_read 도구로 본문을 읽는다.
            """.stripTrailing();

    /** 지금 조립의 층마다 고르는 규칙이다. 값의 뜻은 {@link ContextSelection} 이 갖는다. */
    private static final Map<ContextSource, String> SELECTORS = Map.of(
        ContextSource.MEMORY_ALWAYS, ContextSelection.ALWAYS_BY_ID,
        ContextSource.MEMORY_FACTS, ContextSelection.FACTS_RECENT,
        ContextSource.MEMORY_INDEX, ContextSelection.INDEX_BY_ID);

    private final MemoryService memories;
    private final ContextProperties properties;
    private final Clock clock;
    private final MemorySearchSection searchSection;


    /** Memory 예산과 관계없이 모든 에이전트 실행에 공통 답변과 도구 호출 지침을 넣는다. */
    public AssembledContext withResponseInstructions(AssembledContext context) {
        return withResponseInstructions(context, false);
    }


    /**
     * 공통 답변과 도구 호출 지침을 넣는다. {@code memory_remember} 를 받는 실행이면 기억 지침을 그 뒤에 더한다(ADR-20261007 / memory-remember).
     *
     * @param remembers Control Plane MCP 도구를 받고 먼저 살펴보기가 아닌 실행이다. 옛 커넥터 에이전트는 거짓이다
     */
    public AssembledContext withResponseInstructions(AssembledContext context, boolean remembers) {
        String instructions = RESPONSE_INSTRUCTIONS + ContextBudgetWriter.SEPARATOR + TOOL_CALL_INSTRUCTIONS;
        if (remembers) {
            instructions += ContextBudgetWriter.SEPARATOR + MEMORY_INSTRUCTIONS;
        }
        if (context.instructions() != null && !context.instructions().isBlank()) {
            instructions += ContextBudgetWriter.SEPARATOR + context.instructions();
        }
        return new AssembledContext(instructions, instructions.length(), context.omittedMemoryIds(), context.bundle());
    }


    /**
     * 고르는 자리를 여기 하나로 모은다. 항목이 많아지면 이 클래스에서 검색으로 바꾼다.
     *
     * <p>색인 층에 쓸 자리를 먼저 떼어 두고 항상 층과 개인 사실 구역을 담은 뒤 색인 층을 담는다. 그러지 않으면 본문이 긴
     * 항목 하나가 상한을 거의 채워 색인이 통째로 빠지고, 색인이 없으면 {@code memory_read} 로 읽을 번호도 사라져 에이전트가
     * 나머지 Memory 에 닿을 길이 없어진다. 개인 사실 구역은 떼어 둔 자리를 쓰지 않으므로, 색인 전체가 몫(상한의 1/4) 안에 들 때는
     * 색인을 밀어내지 못한다. 몫보다 긴 색인은 몫까지만 떼어 두므로 남는 줄이 빠질 수 있다.
     *
     * <p>싣는 것은 요청자가 볼 수 있고 그 에이전트가 받는 collection 의 항목뿐이다(ADR-053). 커넥터 에이전트와
     * 찾지 못한 에이전트는 아무것도 받지 않는다.
     *
     * @param agentId 이 실행을 도는 에이전트의 번호
     */
    public AssembledContext assemble(CurrentUser user, Long agentId) {
        return assemble(user, agentId, null);
    }


    /**
     * 질문과 관련된 기억 구역까지 싣는 조립이다. 질문이 null 이거나 공백뿐이면 {@link #assemble(CurrentUser, Long)} 와 같은 글을 낸다.
     *
     * @param question 사용자가 보낸 turn 의 글이나 위임받은 실행의 작업 글
     */
    public AssembledContext assemble(CurrentUser user, Long agentId, String question) {
        return assemble(user, memories.accessOf(agentId), question);
    }


    /**
     * 사용자가 자기 Memory 목록을 볼 때의 조립이다. collection 과 민감도를 거르지 않는다.
     *
     * <p>실행에 보내지 않는다. 목록에서 자리가 없어 빠질 항목에 표시를 다는 데만 쓴다.
     */
    public AssembledContext assembleForOwner(CurrentUser user) {
        return assemble(user, MemoryAccess.owner(), null);
    }


    @Override
    public Set<Long> omittedFor(CurrentUser user) {
        return Set.copyOf(assembleForOwner(user).omittedMemoryIds());
    }


    private AssembledContext assemble(CurrentUser user, MemoryAccess access, String question) {
        Instant now = clock.instant();
        List<Memory> always = memories.alwaysInjectedFor(user, access);
        List<Memory> indexed = memories.indexedFor(user, access);
        long maxChars = properties.maxChars();
        // 개인 사실 후보를 빼기 전의 색인 전체로 몫을 정한다. 색인 전체가 몫(상한의 1/4) 안에 들 때는 구역에 실린 항목이
        // 색인에서 빠지므로 색인은 떼어 둔 자리보다 길어지지 않는다
        long indexBudget = Math.min(indexLength(indexed, sameNameDocuments(indexed)), maxChars / properties.indexBudgetRatio());

        ContextBudgetWriter builder = new ContextBudgetWriter(maxChars);
        builder.limit(maxChars - indexBudget);
        appendAlways(builder, GROUP_HEADER, always, MemoryScope.GROUP, now);
        appendAlways(builder, USER_HEADER, always, MemoryScope.USER, now);
        Set<Long> facts = appendFacts(builder, indexed, now);
        List<Memory> afterFacts = indexed.stream().filter(memory -> !facts.contains(memory.id())).toList();
        // 다른 범위에 같은 이름의 짝이 있는 문서는 구역에 싣지 않는다. 한 줄 형식에는 충돌 표시를 붙일 자리가 없어 색인에 둔다
        MemorySearchPlacement search = searchSection.place(
            question, afterFacts, sameNameDocuments(afterFacts).keySet(), builder, memory -> memoryFreshness(memory, now));
        builder.limit(maxChars);
        appendIndex(builder, search, sameNameDocuments(search.indexOrder()), now);
        return withSelections(builder.build());
    }


    /**
     * Memory 항목마다 고른 규칙과 {@code source} 안의 순위를 붙인다(ADR-20261015 / memory-eval-gate). 빠진 항목도 순위를 받는다.
     *
     * <p>묶음 항목에만 붙고 글로 옮기지 않으므로 {@code instructions} 와 그 지문은 바뀌지 않는다. 순위는 그 {@code source} 안의 차례이고,
     * 담을 때 이미 근거를 붙인 항목도 차례를 센다.
     */
    private static AssembledContext withSelections(AssembledContext context) {
        Map<ContextSource, Integer> ranks = new EnumMap<>(ContextSource.class);
        List<ContextItem> items = context.bundle().items().stream().map(item -> {
            String selector = SELECTORS.get(item.source());
            int rank = ranks.merge(item.source(), 1, Integer::sum);
            // 개인 사실 구역과 질문으로 고른 항목은 고른 규칙과 순위가 항목마다 달라 담을 때 이미 근거를 붙였다
            if (selector == null || item.selection() != null) {
                return item;
            }
            return item.withSelection(ContextSelection.ranked(selector, rank));
        }).toList();
        return new AssembledContext(
            context.instructions(), context.chars(), context.omittedMemoryIds(), new ContextBundle(items));
    }


    /**
     * 개인 사실 구역을 담고 실은 항목의 번호를 돌려준다.
     *
     * <p>예산은 항상 층 뒤에 남은 자리와 {@code factsMaxChars} 가운데 작은 쪽이다. 고르기는 한 번만 하고, 고른 항목을 번호 순으로
     * 담는다. 고르기와 담기가 같은 길이 계산을 쓰므로 고른 항목은 모두 들어간다. 그래도 들어가지 않는 항목은 빠진 항목으로 세지 않고
     * 색인으로 돌린다.
     */
    private Set<Long> appendFacts(ContextBudgetWriter builder, List<Memory> indexed, Instant now) {
        long budget = Math.min(builder.room(), properties.factsMaxChars());
        if (budget <= 0) {
            return Set.of();
        }
        List<Memory> chosen = chooseFacts(indexed.stream().filter(this::factCandidate).toList(), budget, builder.isEmpty());
        Set<Long> placed = new HashSet<>();
        Map<Long, Integer> chosenRanks = new HashMap<>();
        chosen.forEach(memory -> chosenRanks.put(memory.id(), chosenRanks.size() + 1));
        chosen.stream().sorted(Comparator.comparing(Memory::id)).forEach(memory -> {
            // 순위는 고른 차례(최근에 고친 순)다. 싣는 차례(번호 순)가 아니다
            ContextItem item = MemoryItems.item(
                memory,
                ContextSource.MEMORY_FACTS,
                ContextBodyMode.INLINE,
                List.of(),
                memory.title(),
                memory.content(),
                memoryFreshness(memory, now))
                .withSelection(ContextSelection.ranked(ContextSelection.FACTS_RECENT, chosenRanks.get(memory.id())));
            if (builder.appendIfFits(FACTS_HEADER, MemoryItems.inlineLine(memory), item)) {
                placed.add(memory.id());
            } else {
                log.warn("memory facts returned an item to the index memoryId={}", memory.id());
            }
        });
        return placed;
    }


    /** 색인에 오를 항목 가운데 본문이 짧은 개인 평문 항목만 개인 사실 구역의 후보다. */
    private boolean factCandidate(Memory memory) {
        return memory.scope() == MemoryScope.USER
            && memory.entryType() == MemoryEntryType.MEMORY
            && memory.sensitivity() == MemorySensitivity.NORMAL
            && !memory.sealed()
            && memory.content().length() <= properties.factsItemMaxChars();
    }


    /**
     * 최근에 고친 것부터, 같으면 번호가 큰 것부터 보며 머리와 구분 줄을 포함한 구역 글이 예산 안이면 담고 아니면 건너뛴다.
     *
     * @param startsEmpty 구역 앞에 담은 글이 없다. 글이 있으면 구역 앞의 구분 줄도 센다
     */
    private static List<Memory> chooseFacts(List<Memory> candidates, long budget, boolean startsEmpty) {
        Comparator<Memory> recentFirst = Comparator.comparing(
            Memory::updatedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
            .thenComparing(Memory::id)
            .reversed();
        List<Memory> chosen = new ArrayList<>();
        long used = 0;
        for (Memory memory : candidates.stream().sorted(recentFirst).toList()) {
            boolean first = chosen.isEmpty();
            long cost = ContextBudgetWriter.next(startsEmpty && first, !first, FACTS_HEADER, MemoryItems.inlineLine(memory))
                .length();
            if (used + cost <= budget) {
                chosen.add(memory);
                used += cost;
            }
        }
        return chosen;
    }


    private void appendAlways(
        ContextBudgetWriter builder, String header, List<Memory> memories, MemoryScope scope, Instant now) {
        memories.stream()
            .filter(memory -> memory.scope() == scope)
            .filter(ContextAssembler::plainOrSkipped)
            .sorted(Comparator.comparing(Memory::id))
            .forEach(memory -> {
                ContextItem item = alwaysItem(memory, now);
                builder.append(memory.id(), header, "- " + item.body(), item);
            });
    }


    /** 항상 층의 줄은 본문까지 싣는다. */
    private ContextItem alwaysItem(Memory memory, Instant now) {
        return MemoryItems.item(
            memory,
            ContextSource.MEMORY_ALWAYS,
            ContextBodyMode.INLINE,
            List.of(),
            null,
            memory.content(),
            memoryFreshness(memory, now));
    }


    /** 색인 층의 줄은 제목만 싣는다. 본문은 {@code memory_read} 로만 읽는다. */
    private ContextItem indexItem(Memory memory, List<Memory> sameNames, Instant now) {
        List<String> conflictsWith = sameNames.stream()
            .map(other -> ContextItem.memoryRef(other.id()))
            .toList();
        return MemoryItems.item(
            memory,
            ContextSource.MEMORY_INDEX,
            ContextBodyMode.TITLE_ONLY,
            conflictsWith,
            memory.title(),
            null,
            memoryFreshness(memory, now));
    }


    /** collection 별 기준을 먼저 보고, 0 이하 기준은 신선도 판정을 끈다. */
    private ContextFreshness memoryFreshness(Memory memory, Instant now) {
        Duration staleAfter = properties
            .memoryCollectionStaleAfter()
            .getOrDefault(memory.collection(), properties.memoryStaleAfter());
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            return ContextFreshness.UNKNOWN;
        }
        return ResultHeader.freshnessOf(memory.updatedAt(), now, staleAfter);
    }


    /**
     * 본문을 풀지 않는다. 암호문인 줄은 싣지 않는다(ADR-055).
     *
     * <p>민감 항목은 항상 층에 오지 못하므로 여기 암호문이 있으면 데이터가 어긋난 것이다. 번호만 경고로 남긴다.
     */
    private static boolean plainOrSkipped(Memory memory) {
        if (memory.sealed()) {
            log.warn("memory context skipped a sealed always item memoryId={}", memory.id());
            return false;
        }
        return true;
    }


    /** 색인 층을 {@code search} 가 정한 차례로 담는다. 질문으로 점수를 받은 항목에는 그 근거를 붙인다. */
    private void appendIndex(
        ContextBudgetWriter builder, MemorySearchPlacement search, Map<Long, List<Memory>> sameNames, Instant now) {
        search.indexOrder().forEach(memory -> {
            List<Memory> others = sameNames.getOrDefault(memory.id(), List.of());
            ContextItem item = indexItem(memory, others, now);
            ContextSelection scored = search.indexSelections().get(memory.id());
            if (scored != null) {
                item = item.withSelection(scored);
            }
            builder.append(memory.id(), INDEX_HEADER, indexLine(memory.id(), item.title(), others), item);
        });
    }


    /** 색인 한 줄이다. 제목의 줄바꿈은 공백 하나로 바꾼다. 같은 이름의 문서가 다른 범위에 있으면 그 번호를 줄 끝에 적는다. */
    private static String indexLine(Long memoryId, String title, List<Memory> sameNames) {
        StringBuilder line = new StringBuilder("- [" + memoryId + "] " + MemoryItems.oneLine(title));
        for (Memory other : sameNames) {
            String kind = other.scope() == MemoryScope.GROUP ? "그룹" : "개인";
            line.append(" (같은 이름의 ")
                .append(kind)
                .append(" 문서 [")
                .append(other.id())
                .append("] 가 있다)");
        }
        return line.toString();
    }


    /**
     * 색인에 함께 오른 개인 문서와 그룹 문서 가운데 collection 과 document_key 가 같은 것을 서로 짝짓는다(ADR-071).
     *
     * <p>Control Plane 이 구조로 알 수 있는 충돌만 찾는다. 두 글의 뜻이 어긋나는지는 보지 않는다. 짝이 없는 항목은 맵에 없다.
     *
     * @return 항목 번호마다 범위가 다른 같은 이름 문서들. 번호 오름차순이다
     */
    private static Map<Long, List<Memory>> sameNameDocuments(List<Memory> indexed) {
        Map<List<String>, List<Memory>> byName = indexed.stream()
            .filter(memory -> memory.entryType() == MemoryEntryType.DOCUMENT && memory.documentKey() != null)
            .collect(Collectors.groupingBy(memory -> List.of(memory.collection(), memory.documentKey())));
        Map<Long, List<Memory>> sameNames = new HashMap<>();
        byName.values()
            .forEach(documents -> documents.forEach(document -> {
                List<Memory> others = documents.stream()
                    .filter(other -> other.scope() != document.scope())
                    .sorted(Comparator.comparing(Memory::id))
                    .toList();
                if (!others.isEmpty()) {
                    sameNames.put(document.id(), others);
                }
            }));
        return sameNames;
    }


    /**
     * 색인 층을 통째로 실을 때 늘어나는 글자 수다.
     *
     * <p>항상 층 뒤에 붙으므로 그 사이의 구분 줄까지 센다. 색인할 항목이 없으면 0 이고, 그때는 떼어
     * 두는 자리도 없다.
     */
    private static long indexLength(List<Memory> memories, Map<Long, List<Memory>> sameNames) {
        if (memories.isEmpty()) {
            return 0;
        }
        StringBuilder rendered = new StringBuilder();
        Set<String> headers = new HashSet<>();
        memories.stream().sorted(Comparator.comparing(Memory::id)).forEach(memory -> {
            List<Memory> others = sameNames.getOrDefault(memory.id(), List.of());
            String line = indexLine(memory.id(), memory.title(), others);
            ContextBudgetWriter.appendTo(rendered, headers, INDEX_HEADER, line);
        });
        return rendered.length() + ContextBudgetWriter.SEPARATOR.length();
    }

}
