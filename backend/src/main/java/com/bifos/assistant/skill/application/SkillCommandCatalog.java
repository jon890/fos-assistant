package com.bifos.assistant.skill.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 스킬 커맨드가 부를 수 있는 이름, 곧 그 에이전트의 켜진 스킬 이름을 안다. 근거는 ADR-035 에 있다.
 *
 * <p>{@code skills} toolset 이 꺼진 에이전트는 모델이 스킬을 읽지 못하므로 켜진 이름이 없는 것으로 본다.
 *
 * <p>목록은 에이전트 번호마다 {@link #TTL} 동안 메모리에 들고 있는다. 스킬 저장, 지우기, 켜고 끄기가 Hermes
 * 에 반영되면 {@link SkillsChanged} 를 받아 그 에이전트의 것을 비운다. 도구(toolset) 변경은 비우지 않아
 * {@link #TTL} 이 지난 뒤 반영된다. 목록을 읽다 Hermes 가 실패하면 그 예외를 그대로 올리고 캐시에 두지
 * 않는다. 이름을 확인하지 못한 커맨드를 보내지 않기 위해서다.
 */
@Service
public class SkillCommandCatalog {

    /** 켜진 스킬 이름을 들고 있는 시간이다. */
    static final Duration TTL = Duration.ofSeconds(30);

    private final SkillService skills;
    private final Clock clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

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
    public Set<String> enabledNames(CurrentUser user, Agent agent) {
        Instant now = clock.instant();
        Cached cached = cache.get(agent.id());
        if (cached != null && now.isBefore(cached.readAt().plus(TTL))) {
            return cached.names();
        }
        SkillList list = skills.list(user, agent.code());
        Set<String> names = list.skillsToolsetEnabled()
                ? list.skills().stream()
                        .filter(SkillListItem::enabled)
                        .map(SkillListItem::name)
                        .collect(Collectors.toUnmodifiableSet())
                : Set.of();
        cache.put(agent.id(), new Cached(now, names));
        return names;
    }

    /** 그 에이전트의 스킬이 바뀌었으니 다음 커맨드가 목록을 다시 읽게 한다. */
    @EventListener
    void on(SkillsChanged event) {
        cache.remove(event.agentId());
    }
}
