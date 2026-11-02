package com.bifos.assistant.people.application;

import com.bifos.assistant.agent.application.AgentUserPurge;
import com.bifos.assistant.browser.application.UserBrowserPurge;
import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.chat.purge.application.UserConversationPurge;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.followup.application.FollowUpUserPurge;
import com.bifos.assistant.memory.maintenance.application.MemoryUserPurge;
import com.bifos.assistant.notification.application.NotificationUserPurge;
import com.bifos.assistant.people.application.dto.UserPurgeOutcome;
import com.bifos.assistant.people.application.dto.UserPurgeStep;
import com.bifos.assistant.people.application.port.UserPurgeParticipant;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.domain.NoAdminToTakeOverException;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.proactive.check.application.ProactiveUserPurge;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리의 단계 하나를 한 트랜잭션으로 한다(ADR-20261101 / user-purge).
 *
 * <p>단계마다 {@code PersonAccessService} 와 같은 순서로 그 사람의 사용자 행들과 허용 목록 행을 잠근 뒤, 아직 꺼져 있고 예약한 지 30일이
 * 지났는지 다시 본다. 그 사이 다시 켜졌거나 예약이 취소됐으면 아무것도 하지 않는다. 위 패키지의 표는 {@link UserPurgeParticipant} 가
 * 맡고, 아래 패키지의 표는 그 패키지의 정리 빈을 부른다.
 */
@Component
@RequiredArgsConstructor
public class UserPurgeWriter {

    private final AllowedPersonRepository people;
    private final AppUserRepository users;
    private final AgentUserPurge agents;
    private final ArtifactVaultService vault;
    private final MemoryUserPurge memories;
    private final FollowUpUserPurge followUps;
    private final NotificationUserPurge notifications;
    private final ProactiveUserPurge proactive;
    private final UserBrowserPurge browsers;
    private final List<UserPurgeParticipant> participants;
    private final UserConversationPurge conversations;
    private final DataKeyService keys;


    /**
     * 그 사람의 사용자 한 명에게 단계 하나를 한다. 사용자 줄이 이미 없으면 마친 것으로 본다.
     *
     * @throws NoAdminToTakeOverException 그룹 공개 에이전트를 넘겨받을 켜진 관리자가 없으면. 아무것도 바꾸지 않는다
     */
    @Transactional
    public UserPurgeOutcome run(Long personId, Long userId, UserPurgeStep step, Instant cutoff, Instant now) {
        if (lockDue(personId, cutoff).isEmpty()) {
            return UserPurgeOutcome.CANCELLED;
        }
        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return UserPurgeOutcome.DONE;
        }
        switch (step) {
            case TRANSFER_AGENTS -> transferGroupAgents(user);
            case DELETE_VAULT -> vault.purgeUser(userId);
            case DELETE_PERSONAL_DATA -> deletePersonalData(userId);
            case MARK_DELETED -> {
                conversations.deleteAll(userId, now);
                agents.deletePrivateAgents(userId, now);
            }
            case DELETE_USER -> {
                return deleteUser(userId);
            }
        }
        return UserPurgeOutcome.DONE;
    }


    /**
     * 그 사람의 사용자가 모두 지워졌으면 예약을 비운다. 허용 목록 줄은 꺼진 채 남는다.
     *
     * @return 예약을 비웠으면 참. 다시 켜졌거나 사용자가 남아 있으면 거짓
     */
    @Transactional
    public boolean finish(Long personId, Instant cutoff) {
        Optional<AllowedPerson> person = lockDue(personId, cutoff);
        if (person.isEmpty() || !users.findAllByNormalizedEmail(person.get().email()).isEmpty()) {
            return false;
        }
        person.get().cancelPurge();
        people.save(person.get());
        return true;
    }


    private void transferGroupAgents(AppUser user) {
        if (!agents.ownsGroupAgents(user.id())) {
            return;
        }
        Long newOwner = people.findAdminToTakeOver(user.groupId(), UserRole.ADMIN, user.id())
            .orElseThrow(NoAdminToTakeOverException::new);
        agents.transferGroupAgents(user.id(), newOwner);
    }


    private void deletePersonalData(Long userId) {
        memories.purgeUser(userId);
        followUps.purgeUser(userId);
        notifications.purgeUser(userId);
        proactive.purgeUser(userId);
        agents.releaseToolsetRequests(userId);
        participants.forEach(participant -> participant.purgeUser(userId));
        browsers.purgeUser(userId);
    }


    /**
     * 대화 정리가 모두 끝났으면 데이터 key 와 사용자 줄을 지운다.
     *
     * <p>보관본과 개인 데이터를 같은 트랜잭션에서 한 번 더 지운다. 앞 단계 뒤에 대화 결과물이 자동 보관으로 다시 들어왔거나 사용자를
     * 가리키는 줄(알림 같은 것)이 새로 생겼을 수 있다. 남아 있으면 사용자 줄 삭제를 외래 키가 막는다. 대화 정리가 끝나 결과물 행이
     * 없으므로 이 뒤로는 새 보관본이 들어오지 않는다.
     */
    private UserPurgeOutcome deleteUser(Long userId) {
        if (!conversations.allPurged(userId)) {
            return UserPurgeOutcome.WAITING;
        }
        vault.purgeUser(userId);
        deletePersonalData(userId);
        keys.deleteUserKey(userId);
        users.deletePurged(userId);
        return UserPurgeOutcome.DONE;
    }


    /**
     * {@code PersonAccessService} 와 같은 순서로 사용자 행들, 허용 목록 행을 잠근다. 꺼져 있고 cutoff 이전에 예약했을 때만 준다.
     */
    private Optional<AllowedPerson> lockDue(Long personId, Instant cutoff) {
        Optional<String> email = people.findEmailById(personId);
        if (email.isEmpty()) {
            return Optional.empty();
        }
        users.findAllByNormalizedEmail(AllowedPerson.normalizeEmail(email.get())).stream()
            .map(AppUser::id)
            .sorted()
            .forEach(users::findByIdForUpdate);
        return people.findByIdForUpdate(personId)
            .filter(person -> !person.enabled())
            .filter(person -> person.purgeScheduledAt() != null && !person.purgeScheduledAt().isAfter(cutoff));
    }
}
