package com.bifos.assistant.memory.maintenance.application;

import com.bifos.assistant.feedback.application.MaintenanceEventLog;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.MaintenanceDetail;
import com.bifos.assistant.memory.maintenance.application.dto.MaintenanceCandidate;
import com.bifos.assistant.memory.maintenance.application.dto.MaintenanceReview;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정비 후보를 사람에게 보이고 「맞음」, 「아님」 으로 확인한 결과를 남긴다(ADR-20261015 / memory-maintenance).
 *
 * <p>항목을 고칠 수 있는 사람(개인 항목은 주인, 그룹 항목은 그 그룹의 {@code ADMIN})만 그 항목의 후보를 보고 확인한다. 남의 후보, 없는 후보,
 * 지운 항목의 후보는 모두 없는 것과 같은 응답이다. 이 단계에서는 확인해도 기억을 바꾸지 않고 결과만 남긴다.
 */
@Service
@RequiredArgsConstructor
public class MemoryMaintenanceService {

    private final MaintenanceEventLog events;
    private final MemoryRepository memories;
    private final AppUserRepository users;
    private final Clock clock;


    /** 요청자가 고칠 수 있는 항목의 확인 전 후보를 오래된 것부터 낸다. 제목은 지금 항목에서 읽는다. */
    @Transactional(readOnly = true)
    public List<MaintenanceCandidate> candidatesFor(CurrentUser user) {
        List<FeedbackEvent> open = events.openCandidates(user.id(), peersOf(user));
        Map<Long, Memory> byId = memories.findAllById(open.stream().map(MaintenanceEventLog::memoryIdOf).distinct().toList())
            .stream()
            .collect(Collectors.toMap(Memory::id, Function.identity()));
        List<MaintenanceCandidate> candidates = new ArrayList<>();
        for (FeedbackEvent event : open) {
            Memory memory = byId.get(MaintenanceEventLog.memoryIdOf(event));
            if (editable(user, memory)) {
                candidates.add(new MaintenanceCandidate(
                    event.id(),
                    memory.id(),
                    memory.scope(),
                    memory.title(),
                    event.reasonCode(),
                    summary(event, memory),
                    memory.archivable(),
                    event.occurredAt()));
            }
        }
        return candidates;
    }


    /**
     * 후보 하나를 확인한다. 기억은 바꾸지 않고 확인 사건만 남긴다.
     *
     * @throws ApiException 후보가 없거나 요청자의 것이 아니거나 고칠 수 없는 항목일 때. 모두 MEMORY_NOT_FOUND
     */
    @Transactional
    public void review(CurrentUser user, Long candidateId, MaintenanceReview review) {
        FeedbackEvent candidate = events.openCandidates(user.id(), peersOf(user)).stream()
            .filter(event -> event.id().equals(candidateId))
            .findFirst()
            .orElseThrow(MemoryMaintenanceService::notFound);
        Memory memory = memories.findById(MaintenanceEventLog.memoryIdOf(candidate)).orElse(null);
        if (!editable(user, memory)) {
            throw notFound();
        }
        events.review(user.id(), candidate, review == MaintenanceReview.CORRECT, clock.instant());
    }


    /** 그룹 관리자는 같은 그룹의 다른 관리자와 후보를 나눈다. 그 밖의 사용자는 자기 자신뿐이다. */
    private Collection<Long> peersOf(CurrentUser user) {
        if (!user.isAdmin() || user.groupId() == null) {
            return List.of(user.id());
        }
        return users.findByGroupIdAndRole(user.groupId(), UserRole.ADMIN).stream().map(AppUser::id).toList();
    }


    private static boolean editable(CurrentUser user, Memory memory) {
        if (memory == null || memory.status() != MemoryStatus.ACCEPTED) {
            return false;
        }
        return memory.scope() == MemoryScope.USER
            ? Objects.equals(memory.ownerUserId(), user.id())
            : user.isAdmin() && Objects.equals(memory.groupId(), user.groupId());
    }


    /** 근거의 한국어 요약이다. 본문은 담지 않는다. */
    private static String summary(FeedbackEvent event, Memory memory) {
        MaintenanceDetail detail = event.detail();
        Map<String, Object> evidence = detail == null || detail.evidence() == null ? Map.of() : detail.evidence();
        if (MemoryMaintenanceRules.DEDUP.equals(event.reasonCode())) {
            return "같은 제목과 본문의 항목 #" + evidence.get("keepMemoryId") + " 이 더 먼저 만들어졌습니다. 이 항목을 정리할 후보입니다.";
        }
        String text = evidence.get("idleDays") + "일 넘게 실리거나 읽히지 않았고, 「관련 없음」 이나 「틀림」 이 "
            + evidence.get("unwantedMarks") + "번 눌렸습니다.";
        return memory.archivable() ? text : text + " 자동 보관을 허락하지 않은 항목이라 후보로만 보입니다.";
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such maintenance candidate");
    }
}
