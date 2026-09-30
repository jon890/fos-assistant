package com.bifos.assistant.skill.application;

import com.bifos.assistant.agent.domain.Agent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 스킬 커맨드가 부를 수 있는 이름, 곧 그 에이전트의 켜진 스킬 이름을 안다. 근거는 ADR-035 에 있다.
 *
 * <p>{@code skills} toolset 이 꺼진 에이전트는 모델이 스킬을 읽지 못하므로 켜진 이름이 없는 것으로 본다.
 * 누가 그 에이전트를 쓸 수 있는지는 보지 않는다. 대화 turn 이 이미 판정한 뒤에 부른다.
 *
 * <p>목록은 에이전트 번호마다 {@link #TTL} 동안 메모리에 들고 있는다. 스킬 저장, 지우기, 켜고 끄기가 Hermes
 * 에 반영되면 {@link SkillsChanged} 를 받아 그 에이전트의 것을 비운다. 도구(toolset) 변경은 비우지 않아
 * {@link #TTL} 이 지난 뒤 반영된다. 목록을 읽다 Hermes 가 실패하면 그 예외를 그대로 올리고 캐시에 두지
 * 않는다. 이름을 확인하지 못한 커맨드를 보내지 않기 위해서다.
 *
 * <p>목록을 읽는 동안 {@link SkillsChanged} 가 오면 읽은 목록은 이미 옛 것일 수 있다. 에이전트마다 세대 번호를
 * 두어 사건마다 올리고, 읽기 시작할 때의 번호가 그대로일 때만 캐시에 넣는다.
 */
@Service
public class SkillCommandCatalog {

    /** 켜진 스킬 이름을 들고 있는 시간이다. */
    static final Duration TTL = Duration.ofSeconds(30);

    private final SkillService skills;
    private final Clock clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private final Map<Long, Long> generations = new ConcurrentHashMap<>();

    /** 읽은 시각과 그때의 켜진 스킬 이름이다. */
    private record Cached(Instant readAt, Set<String> names) {
    }

    @Autowired
    public SkillCommandCatalog(SkillService skills) {
        this(skills, Clock.systemUTC());
    }

    public SkillCommandCatalog(SkillService skills, Clock clock) {
        this.skills = skills;
        this.clock = clock;
    }

    /** 그 에이전트에서 커맨드로 부를 수 있는 스킬 이름이다. {@code skills} toolset 이 꺼져 있으면 빈 집합이다. */
    public Set<String> enabledNames(Agent agent) {
        Long agentId = agent.id();
        Instant now = clock.instant();
        Cached cached = cache.get(agentId);
        if (cached != null && now.isBefore(cached.readAt().plus(TTL))) {
            return cached.names();
        }
        long generation = generationOf(agentId);
        SkillList list = skills.commandList(agent);
        Set<String> names = list.skillsToolsetEnabled()
                ? list.skills().stream()
                        .filter(SkillListItem::enabled)
                        .map(SkillListItem::name)
                        .collect(Collectors.toUnmodifiableSet())
                : Set.of();
        // 사건은 세대 번호를 먼저 올리고 캐시를 비운다. 같은 칸의 compute 와 remove 는 차례로 돌아서, 번호를
        // 본 뒤에 온 사건이면 넣은 것을 그 사건이 비운다.
        cache.compute(agentId, (id, previous) ->
                generationOf(id) == generation ? new Cached(now, names) : previous);
        return names;
    }

    /**
     * 그 에이전트의 스킬이 바뀌었으니 다음 커맨드가 목록을 다시 읽게 한다.
     *
     * <p>저장과 지우기는 트랜잭션 안에서 사건을 내므로 트랜잭션이 끝난 뒤에 받는다. 끝나기 전에 비우면 그 사이 다른
     * 요청이 끝나기 전의 목록을 다시 캐시에 넣을 수 있다. 커밋뿐 아니라 되돌려도 비운다. 사건을 낸 때에는 Hermes
     * 게시와 버전 디렉터리 쓰기나 지우기가 이미 끝났고, 트랜잭션을 되돌려도 그것은 돌아오지 않는다. 비우지 않으면
     * {@link #TTL} 동안 Hermes 에서 이미 지운 스킬을 커맨드로 받는다. 트랜잭션 없이 내는 켜고 끄기는 바로 받는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    public void on(SkillsChanged event) {
        generations.merge(event.agentId(), 1L, Long::sum);
        cache.remove(event.agentId());
    }

    private long generationOf(Long agentId) {
        return generations.getOrDefault(agentId, 0L);
    }
}
