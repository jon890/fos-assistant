package com.bifos.assistant.context.eval;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Memory 회수 합성 측정의 사례 묶음이다. 칸의 뜻은 {@code docs/features/memory.md} 의 「사례 묶음」 이 갖는다. 모든 값은 합성이다.
 *
 * @param version 시험 세트 형식의 판
 * @param note 시험 세트의 안내 글
 * @param users 사례에서 부르는 사용자. 그룹 이름이 같으면 같은 그룹이다
 * @param agents 사례에서 부르는 에이전트와 그 에이전트가 받는 collection
 * @param cases 측정 사례
 */
record MemoryEvalDataset(int version, String note, List<User> users, List<Agent> agents, List<Case> cases) {


    static final String RESOURCE = "/memory-eval/family-cases.json";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    /** 클래스패스의 시험 세트를 읽는다. */
    static MemoryEvalDataset load() {
        try (InputStream in = MemoryEvalDataset.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("시험 세트가 없다: " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    /**
     * JSON 글을 읽고 참조가 맞는지 검사한다.
     *
     * @throws IllegalStateException JSON 이 깨졌거나 키가 겹치거나 없는 이름을 가리킬 때
     */
    static MemoryEvalDataset parse(String json) {
        MemoryEvalDataset dataset;
        try {
            dataset = JSON.readValue(json, MemoryEvalDataset.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("시험 세트를 읽지 못했다: " + ex.getOriginalMessage(), ex);
        }
        dataset.validate();
        return dataset;
    }


    /** 사용자 key 로 찾는다. */
    User user(String key) {
        return users.stream().filter(each -> each.key().equals(key)).findFirst().orElseThrow();
    }


    private void validate() {
        Set<String> userKeys = requireUnique("사용자 key", users.stream().map(User::key).toList());
        Set<String> groups = new HashSet<>(users.stream().map(User::group).toList());
        Set<String> agentKeys = requireUnique("에이전트 key", agents.stream().map(Agent::key).toList());
        requireUnique("사례 id", cases.stream().map(Case::id).toList());
        for (Case each : cases) {
            validateCase(each, userKeys, groups, agentKeys);
        }
    }


    private static void validateCase(Case each, Set<String> userKeys, Set<String> groups, Set<String> agentKeys) {
        String where = "사례 " + each.id() + ": ";
        require(each.category() != null, where + "category 가 없다");
        require(userKeys.contains(each.asker()), where + "asker 가 users 에 없다: " + each.asker());
        require(agentKeys.contains(each.agent()), where + "agent 가 agents 에 없다: " + each.agent());
        Set<String> memoryKeys = requireUnique(
            where + "Memory key",
            each.memories().stream().map(MemorySeed::key).toList());
        for (MemorySeed memory : each.memories()) {
            validateMemory(where + "Memory " + memory.key() + ": ", memory, userKeys, groups);
        }
        if (each.filler() != null) {
            require(userKeys.contains(each.filler().owner()), where + "filler owner 가 users 에 없다");
            require(each.filler().count() > 0, where + "filler count 는 1 이상이다");
            require(each.filler().contentChars() > 0, where + "filler contentChars 는 1 이상이다");
        }
        for (String expected : each.expect()) {
            require(memoryKeys.contains(expected), where + "expect 가 없는 Memory 를 가리킨다: " + expected);
        }
        Set<String> seen = new HashSet<>(each.expect());
        for (Forbidden forbidden : each.forbidden()) {
            require(forbidden.kind() != null, where + "forbidden.kind 가 없다");
            require(
                memoryKeys.contains(forbidden.memory()),
                where + "forbidden 이 없는 Memory 를 가리킨다: " + forbidden.memory());
            require(
                seen.add(forbidden.memory()),
                where + "한 Memory 가 expect 와 forbidden 에 겹쳐 있거나 두 번 적혔다: " + forbidden.memory());
        }
        validateSupersedes(where, each);
        validateRelated(where, each);
        Set<String> checkIds = new HashSet<>();
        for (JudgeCheck check : each.judge()) {
            require(
                check.id() != null && !check.id().isBlank() && checkIds.add(check.id()),
                where + "judge 의 id 가 비었거나 겹친다: " + check.id());
            require(check.question() != null && !check.question().isBlank(), where + "judge 의 question 이 비었다");
        }
    }


    /**
     * {@code supersedes} 는 같은 사례의 같은 주인 항목을 가리키고, 가리키는 항목과 {@code SUPERSEDED} 오답지가 서로 맞아야 한다. 단계 2 가
     * 이 값으로 「대체함」 링크를 만들기 때문이다.
     */
    private static void validateSupersedes(String where, Case each) {
        Map<String, MemorySeed> byKey = new HashMap<>();
        each.memories().forEach(memory -> byKey.put(memory.key(), memory));
        Set<String> superseded = new HashSet<>();
        for (MemorySeed memory : each.memories()) {
            if (memory.supersedes() == null) {
                continue;
            }
            MemorySeed old = byKey.get(memory.supersedes());
            require(old != null, where + "supersedes 가 없는 Memory 를 가리킨다: " + memory.supersedes());
            require(!old.key().equals(memory.key()), where + "supersedes 가 자기 자신을 가리킨다: " + memory.key());
            require(
                old.scope() == memory.scope()
                    && Objects.equals(old.owner(), memory.owner())
                    && Objects.equals(old.group(), memory.group()),
                where + "supersedes 가 범위나 주인이 다른 Memory 를 가리킨다: " + memory.supersedes());
            superseded.add(old.key());
        }
        Set<String> forbiddenSuperseded = new HashSet<>();
        each.forbidden().stream()
            .filter(forbidden -> forbidden.kind() == ForbiddenKind.SUPERSEDED)
            .forEach(forbidden -> forbiddenSuperseded.add(forbidden.memory()));
        require(
            superseded.equals(forbiddenSuperseded),
            where + "supersedes 가 가리키는 항목 " + superseded + " 와 SUPERSEDED 오답지 " + forbiddenSuperseded + " 가 다르다");
    }


    /** {@code related} 는 같은 사례의 다른 항목 가운데 범위와 주인이 같은 것을 가리켜야 한다. 연결의 규칙과 같다. */
    private static void validateRelated(String where, Case each) {
        Map<String, MemorySeed> byKey = new HashMap<>();
        each.memories().forEach(memory -> byKey.put(memory.key(), memory));
        for (MemorySeed memory : each.memories()) {
            for (String key : memory.related()) {
                MemorySeed other = byKey.get(key);
                require(other != null, where + "related 가 없는 Memory 를 가리킨다: " + key);
                require(!other.key().equals(memory.key()), where + "related 가 자기 자신을 가리킨다: " + key);
                require(
                    other.scope() == memory.scope()
                        && Objects.equals(other.owner(), memory.owner())
                        && Objects.equals(other.group(), memory.group()),
                    where + "related 가 범위나 주인이 다른 Memory 를 가리킨다: " + key);
            }
        }
    }


    private static void validateMemory(String where, MemorySeed memory, Set<String> userKeys, Set<String> groups) {
        if (memory.scope() == MemoryScope.USER) {
            require(userKeys.contains(memory.owner()), where + "USER 항목의 owner 가 users 에 없다: " + memory.owner());
        } else {
            require(groups.contains(memory.group()), where + "GROUP 항목의 group 이 users 에 없다: " + memory.group());
        }
        require(memory.entryType() != MemoryEntryType.SOURCE, where + "entryType 은 MEMORY 나 DOCUMENT 만 쓴다");
        if (memory.entryType() == MemoryEntryType.DOCUMENT) {
            require(
                memory.documentKey() != null && !memory.documentKey().isBlank(),
                where + "DOCUMENT 에 documentKey 가 없다");
            require(memory.scope() == MemoryScope.USER, where + "DOCUMENT 는 USER 항목이다");
            require(
                memory.status() == MemoryStatus.ACCEPTED && memory.retrieval() == MemoryRetrieval.SEARCH,
                where + "DOCUMENT 는 ACCEPTED 이고 SEARCH 로 고정이다");
        }
        if (memory.status() != MemoryStatus.ACCEPTED) {
            require(memory.scope() == MemoryScope.USER, where + "PROPOSED 와 REJECTED 는 USER 항목이다");
        }
    }


    private static Set<String> requireUnique(String what, List<String> values) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            require(value != null && seen.add(value), what + " 가 비었거나 겹친다: " + value);
        }
        return seen;
    }


    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }


    /**
     * @param key 사례에서 부르는 이름
     * @param role 역할
     * @param group 그룹 이름
     */
    record User(String key, UserRole role, String group) {
    }

    /**
     * @param key 사례에서 부르는 이름
     * @param collections 받는 collection 과 민감 허용
     */
    record Agent(String key, List<Grant> collections) {

        Agent {
            collections = collections == null ? List.of() : List.copyOf(collections);
        }
    }

    record Grant(String collection, boolean allowSensitive) {
    }

    /**
     * @param id 사례 번호
     * @param question 사례의 질문. 조립에 넘기고 판정에는 쓰지 않는다
     * @param filler 판정 대상이 아닌 보조 항목
     * @param expect 답에 필요한 Memory 의 key
     * @param forbidden 실리면 안 되는 Memory
     * @param judge 답 판정이 묻는 「예, 아니오」 질문. 조립 측정은 읽지 않는다
     */
    record Case(
        String id,
        Category category,
        String asker,
        String agent,
        String question,
        List<MemorySeed> memories,
        Filler filler,
        List<String> expect,
        List<Forbidden> forbidden,
        List<JudgeCheck> judge) {

        Case {
            memories = memories == null ? List.of() : List.copyOf(memories);
            expect = expect == null ? List.of() : List.copyOf(expect);
            forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
            judge = judge == null ? List.of() : List.copyOf(judge);
        }
    }

    /**
     * 답 판정이 판정 모델에 묻는 질문 하나다. 답 글만 보고 판정할 수 있게 쓴다.
     *
     * @param id 사례 안에서 겹치지 않는 이름
     * @param question 「예, 아니오」 로 답할 판정 질문
     * @param expect 기억이 제대로 쓰인 답에서 기대하는 판정
     */
    record JudgeCheck(String id, String question, boolean expect) {
    }

    /**
     * 사례에 넣을 Memory 한 줄이다. 선택 칸의 기본값은 {@code core}, {@code SEARCH}, {@code ACCEPTED}, {@code NORMAL},
     * {@code MEMORY}, 0일 전이다.
     *
     * @param owner {@code USER} 항목의 주인 사용자 key
     * @param group {@code GROUP} 항목의 그룹 이름
     * @param updatedDaysAgo 시험 시계 기준 며칠 전에 고친 항목인가
     * @param supersedes 이 항목이 대체하는 같은 사례의 옛 항목 key. 없으면 null
     * @param related 이 항목과 {@code RELATED} 로 이을 같은 사례의 항목 key. 없으면 빈 목록이다
     */
    record MemorySeed(
        String key,
        String owner,
        MemoryScope scope,
        String group,
        String title,
        String content,
        String collection,
        MemoryRetrieval retrieval,
        MemoryStatus status,
        MemorySensitivity sensitivity,
        MemoryEntryType entryType,
        String documentKey,
        int updatedDaysAgo,
        String supersedes,
        List<String> related) {

        MemorySeed {
            scope = scope == null ? MemoryScope.USER : scope;
            collection = collection == null ? Memory.DEFAULT_COLLECTION : collection;
            retrieval = retrieval == null ? MemoryRetrieval.SEARCH : retrieval;
            status = status == null ? MemoryStatus.ACCEPTED : status;
            sensitivity = sensitivity == null ? MemorySensitivity.NORMAL : sensitivity;
            entryType = entryType == null ? MemoryEntryType.MEMORY : entryType;
            related = related == null ? List.of() : List.copyOf(related);
        }
    }

    /**
     * 제목이 「보조 사실 {n}」(n 은 1부터)이고 본문이 {@code contentChars} 글자인 {@code USER} 의 {@code ACCEPTED},
     * {@code SEARCH} 항목을 {@code count} 개 지어 넣는다.
     *
     * @param owner 주인 사용자 key
     * @param updatedDaysAgo 시험 시계 기준 며칠 전에 고친 항목인가
     */
    record Filler(String owner, int count, int contentChars, int updatedDaysAgo) {
    }

    /**
     * @param memory 실리면 안 되는 Memory 의 key
     * @param kind 실렸을 때의 뜻
     */
    record Forbidden(String memory, ForbiddenKind kind) {
    }

    /** 실리면 안 되는 항목의 종류다. */
    enum ForbiddenKind {
        /** 남의 항목, 다른 그룹의 항목, 권한 밖 collection, 민감 허용 없는 민감 항목, 제안, 거절, 보관. 묶음에 있으면 실패다. */
        BOUNDARY,
        /** 새 값이 따로 있는 옛 값이다. 본문으로 실리면 오기억 노출이다. */
        SUPERSEDED,
        /** 질문과 비슷하지만 다른 사람이나 다른 것의 사실이다. 본문으로 실리면 오기억 노출이다. */
        DISTRACTOR
    }

    /** 사례의 범주다. */
    enum Category {
        INFORMATION_EXTRACTION,
        MULTI_SESSION,
        KNOWLEDGE_UPDATE,
        TEMPORAL,
        ABSTENTION,
        BOUNDARY,
        LOAD,
        /** 선호를 무시하면 답이 어긋나는 질문에 그 선호가 실린다(PrefEval). */
        PREFERENCE,
        /** 지금 구조가 놓치는 사실이다. 본문에만 있고 제목에는 없는 사실, 예산에서 빠지는 항목. */
        RECALL_GAP
    }
}
