package com.bifos.assistant.memory.application;

import com.bifos.assistant.agent.application.AgentMemoryCollectionService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentMemoryCollectionChange;
import com.bifos.assistant.memory.application.model.AgentMemoryCountScope;
import com.bifos.assistant.memory.application.model.AgentMemoryGrantInput;
import com.bifos.assistant.memory.application.model.AgentMemorySetting;
import com.bifos.assistant.memory.application.model.AgentMemorySettingChange;
import com.bifos.assistant.memory.application.model.AgentMemorySettingCollection;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.domain.MemoryCollectionCount;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 에이전트의 Memory collection 설정을 읽고 바꾼다(ADR-20261008 / agent-memory-grants-admin).
 *
 * <p>항목 수는 셈 대상의 실릴 수 있는 항목만 센다. 셈 대상은 에이전트 주인이 있으면 주인과 주인의 그룹이고, 없으면 관리자의
 * 그룹이다. 그룹의 collection 목록도 이 그룹으로 읽는다. 항목의 제목과 번호는 내지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AgentMemorySettingService {

    /** 한 번에 줄 수 있는 collection 의 최대 수다. */
    private static final int MAX_COLLECTIONS = 64;

    private final AgentMemoryCollectionService agentCollections;
    private final MemoryCollectionService collections;
    private final MemoryRepository memories;
    private final AppUserRepository users;

    /**
     * 에이전트가 받는 collection 과 민감 허용, 셈 대상의 collection 마다 항목 수, 최근 변경 기록을 낸다.
     *
     * <p>주인 번호가 있어도 그 사용자를 찾지 못하면 주인이 없는 에이전트처럼 관리자 그룹의 항목만 센다. 그룹의 collection
     * 목록이 비어 있으면 기본 목록을 넣으므로 쓰기 트랜잭션으로 돈다.
     *
     * @throws ApiException 없거나 지운 에이전트면 {@code AGENT_NOT_FOUND}, 커넥터 에이전트면 {@code FORBIDDEN}
     */
    @Transactional
    public AgentMemorySetting settingOf(CurrentUser admin, String code) {
        Agent agent = agentCollections.requireEditableAgent(code);
        Optional<AppUser> owner = ownerOf(agent);
        Long groupId = owner.map(AppUser::groupId).orElse(admin.groupId());
        Long userId = owner.map(AppUser::id).orElse(null);

        Map<String, AgentMemoryCollection> rows = agentCollections.rowsOf(agent.id()).stream()
                .collect(Collectors.toMap(AgentMemoryCollection::collection, Function.identity()));
        List<MemoryCollectionCount> counts = memories.countLoadableByCollection(userId, groupId);
        Map<String, Long> entries = counts.stream()
                .collect(Collectors.groupingBy(
                        MemoryCollectionCount::collection, Collectors.summingLong(MemoryCollectionCount::entries)));
        Map<String, Long> sensitiveEntries = counts.stream()
                .filter(count -> count.sensitivity() == MemorySensitivity.SENSITIVE)
                .collect(Collectors.groupingBy(
                        MemoryCollectionCount::collection, Collectors.summingLong(MemoryCollectionCount::entries)));

        List<AgentMemorySettingCollection> view = new ArrayList<>();
        Set<String> unlisted = new TreeSet<>(rows.keySet());
        for (MemoryCollection collection : collections.collectionsOf(groupId)) {
            unlisted.remove(collection.key());
            view.add(collectionView(collection.key(), collection.displayName(), true, rows, entries, sensitiveEntries));
        }
        for (String key : unlisted) {
            view.add(collectionView(key, key, false, rows, entries, sensitiveEntries));
        }

        return new AgentMemorySetting(
                owner.isPresent() ? AgentMemoryCountScope.OWNER : AgentMemoryCountScope.GROUP,
                owner.map(AppUser::displayName).orElse(null),
                view,
                changesOf(agent.id()));
    }

    /**
     * 에이전트가 받는 collection 을 관리자가 고른 목록으로 바꾸고 바뀐 설정을 낸다.
     *
     * <p><b>이 메서드에는 트랜잭션을 걸지 않는다.</b> 걸면 에이전트와 그룹 목록을 읽는 것이 잠금 전에 같은 트랜잭션에
     * 들어가, {@link AgentMemoryCollectionService#replace} 가 잠금을 기다린 뒤에도 다른 저장이 커밋하기 전의 옛 줄을 본다.
     * 그 메서드의 잠금 읽기가 자기 트랜잭션의 첫 읽기여야 한다. 그룹 목록에도 지금 받는 줄에도 없는 key 의 거절도 그 메서드가
     * 잠금 뒤에 한다.
     *
     * @param next 받을 collection 전체다. 비어 있으면 모두 뗀다
     * @throws ApiException 같은 collection 이 둘이거나 64개를 넘거나 목록에 없는 collection 이 있으면
     *     {@code VALIDATION_FAILED}, 없거나 지운 에이전트면 {@code AGENT_NOT_FOUND}, 커넥터 에이전트면 {@code FORBIDDEN}
     */
    public AgentMemorySetting replace(CurrentUser admin, String code, List<AgentMemoryGrantInput> next) {
        if (next.size() > MAX_COLLECTIONS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "too many memory collections");
        }
        Map<String, Boolean> grants = new LinkedHashMap<>();
        for (AgentMemoryGrantInput input : next) {
            if (grants.put(input.collection(), input.allowSensitive()) != null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "duplicate memory collection");
            }
        }

        Agent agent = agentCollections.requireEditableAgent(code);
        Long groupId = ownerOf(agent).map(AppUser::groupId).orElse(admin.groupId());
        Set<String> listed = collections.collectionsOf(groupId).stream()
                .map(MemoryCollection::key)
                .collect(Collectors.toSet());
        agentCollections.replace(agent.id(), grants, listed, admin.id());
        return settingOf(admin, code);
    }

    private Optional<AppUser> ownerOf(Agent agent) {
        return agent.ownerUserId() == null ? Optional.empty() : users.findById(agent.ownerUserId());
    }

    private List<AgentMemorySettingChange> changesOf(Long agentId) {
        List<AgentMemoryCollectionChange> changes = agentCollections.recentChangesOf(agentId);
        Set<Long> userIds = changes.stream()
                .map(AgentMemoryCollectionChange::changedByUserId)
                .collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        users.findAllById(userIds).forEach(user -> names.put(user.id(), user.displayName()));
        return changes.stream()
                .map(change -> new AgentMemorySettingChange(
                        change.collection(),
                        change.changeType().name(),
                        change.allowSensitive(),
                        names.get(change.changedByUserId()),
                        change.changedAt()))
                .toList();
    }

    private static AgentMemorySettingCollection collectionView(
            String key,
            String displayName,
            boolean listed,
            Map<String, AgentMemoryCollection> rows,
            Map<String, Long> entries,
            Map<String, Long> sensitiveEntries) {
        AgentMemoryCollection row = rows.get(key);
        return new AgentMemorySettingCollection(
                key,
                displayName,
                listed,
                row != null,
                row != null && row.allowSensitive(),
                entries.getOrDefault(key, 0L),
                sensitiveEntries.getOrDefault(key, 0L));
    }
}
