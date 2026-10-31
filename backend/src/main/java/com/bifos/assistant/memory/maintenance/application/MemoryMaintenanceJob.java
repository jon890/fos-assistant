package com.bifos.assistant.memory.maintenance.application;

import com.bifos.assistant.feedback.application.MaintenanceEventLog;
import com.bifos.assistant.feedback.domain.MaintenanceDetail;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.memory.maintenance.application.MemoryMaintenanceRules.Proposal;
import com.bifos.assistant.memory.config.MemoryMaintenanceProperties;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.execution.application.ExecutionMemoryRefs;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하루에 한 번 정해진 규칙으로 기억의 정비 후보를 만든다(ADR-20261015 / memory-maintenance).
 *
 * <p>처음 4주는 제안 모드다. 후보와 근거만 {@code decision_feedback_event} 의 정비 사건으로 남기고 기억과 연결을 바꾸지 않는다. 같은 항목,
 * 같은 동작, 같은 revision 의 후보가 이미 있으면(열려 있든 확인했든) 다시 만들지 않는다. 개인 항목의 후보는 주인에게, 그룹 항목의 후보는 그 그룹의
 * 관리자마다 하나씩 남긴다. 로그에는 동작별 후보 수만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryMaintenanceJob {

    private final MemoryRepository memories;
    private final ExecutionMemoryRefs refs;
    private final MaintenanceEventLog events;
    private final AppUserRepository users;
    private final MemoryMaintenanceProperties properties;
    private final Clock clock;


    /** 하루에 한 번 돈다. 시각은 Control Plane 의 시간대를 따르고, 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.memory.maintenance.cron}")
    public void runScheduled() {
        run(clock.instant());
    }


    /**
     * 규칙을 돌려 새 후보를 남긴다.
     *
     * @return 동작별로 새로 남긴 후보 사건 수
     */
    @Transactional
    public Map<String, Integer> run(Instant now) {
        List<Memory> accepted = memories.findByEntryTypeAndStatusOrderByIdAsc(MemoryEntryType.MEMORY, MemoryStatus.ACCEPTED);
        Instant idleSince = now.minus(Duration.ofDays(properties.archiveIdleDays()));
        List<Proposal> proposals = new ArrayList<>(MemoryMaintenanceRules.archive(
            accepted, refs.loadedSince(idleSince), events.unwantedCounts(), properties.archiveIdleDays(), now));
        proposals.addAll(MemoryMaintenanceRules.dedup(accepted));
        Map<String, Integer> created = new LinkedHashMap<>();
        created.put(MemoryMaintenanceRules.ARCHIVE, 0);
        created.put(MemoryMaintenanceRules.DEDUP, 0);
        Map<Long, List<Long>> adminsByGroup = new LinkedHashMap<>();
        Map<Long, Set<String>> openByUser = new HashMap<>();
        for (Proposal proposal : proposals) {
            List<Long> recipients = recipientsOf(proposal.memory(), adminsByGroup);
            for (Long recipient : recipients) {
                if (events.alreadyProposed(recipient, proposal.memory().id(), proposal.action(), proposal.memory().revision())
                    || isOpen(openByUser, recipient, recipients, proposal)) {
                    continue;
                }
                events.propose(
                    recipient,
                    proposal.memory().id(),
                    proposal.action(),
                    proposal.memory().revision(),
                    MaintenanceDetail.proposed(
                        MemoryMaintenanceRules.RULE_VERSION, MaintenanceDetail.MODE_PROPOSE, proposal.evidence()),
                    now);
                created.merge(proposal.action(), 1, Integer::sum);
            }
        }
        log.info(
            "memory maintenance proposed archive={} dedup={}",
            created.get(MemoryMaintenanceRules.ARCHIVE),
            created.get(MemoryMaintenanceRules.DEDUP));
        return created;
    }


    /**
     * 같은 항목과 동작의 후보가 이미 열려 있는가. 열린 채로 항목을 고쳐 revision 이 올라도 같은 후보를 한 줄 더 만들지 않는다. 사용자마다 한 번만
     * 읽는다.
     */
    private boolean isOpen(Map<Long, Set<String>> openByUser, Long recipient, List<Long> peers, Proposal proposal) {
        Set<String> open = openByUser.computeIfAbsent(
            recipient,
            userId -> events.openCandidates(userId, peers).stream()
                .map(event -> event.subjectKey() + "|" + event.reasonCode())
                .collect(Collectors.toSet()));
        return open.contains(FeedbackSubjectType.MEMORY.key(proposal.memory().id()) + "|" + proposal.action());
    }


    /** 개인 항목은 주인, 그룹 항목은 그 그룹의 관리자 모두다. 관리자가 없는 그룹의 항목은 후보를 받을 사람이 없다. */
    private List<Long> recipientsOf(Memory memory, Map<Long, List<Long>> adminsByGroup) {
        if (memory.scope() == MemoryScope.USER) {
            return List.of(memory.ownerUserId());
        }
        return adminsByGroup.computeIfAbsent(
            memory.groupId(),
            groupId -> users.findByGroupIdAndRole(groupId, UserRole.ADMIN).stream().map(AppUser::id).toList());
    }
}
