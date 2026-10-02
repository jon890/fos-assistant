package com.bifos.assistant.skill.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.skill.domain.ExecutionSkillUse;
import com.bifos.assistant.skill.domain.SkillUseCount;
import com.bifos.assistant.skill.domain.SkillUseOccurrence;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 스킬 호출 이력을 읽는다. 누가 어디까지 보는지는 ADR-034 가 정한다.
 *
 * <p>관리하는 사람은 에이전트별 합계만 보고, 각 사용자는 자기 실행에서 부른 것과 그 대화를 본다. 가족용
 * 에이전트에서 다른 가족의 대화가 어디 있는지 드러나지 않게 하려는 것이다.
 */
@Service
@RequiredArgsConstructor
public class SkillUsageQuery {

    private final ExecutionSkillUseRepository uses;
    private final AgentRepository agents;
    private final ActiveConversationPublicIds conversations;

    /** 그 에이전트의 실행 전체에서 센 스킬 이름별 합계다. 횟수는 실행 수다. 누가 불렀는지는 담지 않는다. */
    public Map<String, SkillUsageSummary> byAgent(Long agentId) {
        Map<String, SkillUsageSummary> summaries = new HashMap<>();
        for (SkillUseCount count : uses.countByAgent(agentId)) {
            summaries.put(count.skillName(), new SkillUsageSummary(count.count(), count.lastInvokedAt()));
        }
        return summaries;
    }

    /**
     * 그 사용자의 실행에서 부른 것을 에이전트와 스킬 이름으로 묶는다. 횟수는 실행 수다. 마지막 호출이 최근인
     * 것부터다.
     *
     * <p>마지막 호출이 속한 대화의 공개 식별자를 함께 준다. 그 대화를 지웠으면 비운다. 지운 대화는 화면에서
     * 열 수 없어 가리켜도 갈 곳이 없다.
     */
    public List<UserSkillUsage> byUser(Long userId) {
        Map<GroupKey, Group> groups = new LinkedHashMap<>();
        for (SkillUseOccurrence occurrence : uses.findOccurrencesByUser(userId)) {
            groups.computeIfAbsent(new GroupKey(occurrence.agentId(), occurrence.skillName()), key -> new Group())
                    .add(occurrence);
        }
        Map<Long, Agent> agentsById =
                agents
                        .findAllById(groups.keySet().stream()
                                .map(GroupKey::agentId)
                                .filter(Objects::nonNull)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(Agent::id, Function.identity()));
        Map<Long, UUID> activeConversations = conversations.activePublicIdsOf(groups.values().stream()
                .map(group -> group.lastConversationId)
                .filter(Objects::nonNull)
                .toList());
        List<UserSkillUsage> usages = new ArrayList<>();
        for (Map.Entry<GroupKey, Group> entry : groups.entrySet()) {
            Agent agent = agentsById.get(entry.getKey().agentId());
            if (agent == null) {
                continue;
            }
            Group group = entry.getValue();
            usages.add(new UserSkillUsage(
                    agent.code(),
                    agent.name(),
                    entry.getKey().skillName(),
                    group.count,
                    group.lastInvokedAt,
                    group.lastConversationId == null ? null : activeConversations.get(group.lastConversationId)));
        }
        usages.sort(Comparator.comparing(UserSkillUsage::lastInvokedAt)
                .reversed()
                .thenComparing(UserSkillUsage::agentCode)
                .thenComparing(UserSkillUsage::skillName));
        return usages;
    }

    /**
     * 여러 실행에서 쓴 스킬 이름을 실행 번호별로 낸다. 목록 한 페이지의 실행을 한 번에 읽는다.
     *
     * <p>사용이 없는 실행은 결과에 없다. 부르는 쪽이 빈 목록으로 채운다. 같은 스킬을 커맨드로 부르고 모델도
     * 읽었으면 이름은 한 번만 넣는다.
     */
    public Map<Long, List<String>> skillNamesByExecution(Collection<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<String>> names = new LinkedHashMap<>();
        for (ExecutionSkillUse use : uses.findByExecutionIdInOrderByExecutionIdAscSkillNameAsc(executionIds)) {
            names.computeIfAbsent(use.executionId(), id -> new LinkedHashSet<>())
                    .add(use.skillName());
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        names.forEach((executionId, skillNames) -> result.put(executionId, List.copyOf(skillNames)));
        return result;
    }

    private record GroupKey(Long agentId, String skillName) {}

    /**
     * 에이전트와 스킬 이름이 같은 사용을 모은다. 마지막 호출의 대화만 기억한다.
     *
     * <p>호출 횟수는 실행 수다. 같은 실행의 {@code COMMAND} 와 {@code MODEL} 줄은 한 번만 센다.
     */
    private static final class Group {
        private final Set<Long> countedExecutions = new HashSet<>();
        private long count;
        private Instant lastInvokedAt;
        private Long lastConversationId;

        void add(SkillUseOccurrence occurrence) {
            if (countedExecutions.add(occurrence.executionId())) {
                count++;
            }
            if (lastInvokedAt == null || occurrence.occurredAt().isAfter(lastInvokedAt)) {
                lastInvokedAt = occurrence.occurredAt();
                lastConversationId = occurrence.conversationId();
            }
        }
    }
}
