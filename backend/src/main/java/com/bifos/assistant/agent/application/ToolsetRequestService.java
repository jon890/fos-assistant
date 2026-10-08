package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.AgentToolsetRequest;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.ToolsetRequestStatus;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.AgentToolsetRequestRepository;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.application.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 에이전트 잠금 아래 요청과 결정을 저장하고 기존 도구 서비스로 승인한 도구를 반영한다. */
@Service
@RequiredArgsConstructor
public class ToolsetRequestService {
    private final AgentRepository agents;
    private final AgentToolsetRequestRepository requests;
    private final AppUserRepository users;
    private final SignInRevocation access;
    private final AgentToolService tools;
    private final ToolsetVisibilityService visibility;
    private final NotificationService notifications;
    private final Clock clock;

    @Transactional
    public ToolsetRequestView request(CurrentUser user, String code, String toolset) {
        Long agentId = agents.findIdByCode(code).orElseThrow(ToolsetRequestService::notFound);
        Agent agent = agents.findByIdForUpdate(agentId).orElseThrow(ToolsetRequestService::notFound);
        requireCurrent(user, false);
        requireOwner(user, agent);
        if (ineligible(agent, user.id(), user.groupId(), toolset) != null) {
            throw new ApiException(ErrorCode.TOOLSET_REQUEST_UNAVAILABLE, "this toolset cannot be requested");
        }
        AgentToolsetRequest pending = requests.findByAgentIdAndGroupIdAndRequesterUserIdAndToolsetAndPendingSlot(
                        agent.id(), user.groupId(), user.id(), toolset, 1)
                .orElse(null);
        if (pending != null) {
            return view(pending, agent);
        }
        AgentToolsetsView current = tools.read(user, agent);
        if (current.toolsets().stream().noneMatch(item -> item.name().equals(toolset) && !item.enabled())) {
            throw new ApiException(
                    ErrorCode.TOOLSET_REQUEST_UNAVAILABLE, "this toolset is unavailable or already enabled");
        }
        AgentToolsetRequest row = requests.saveAndFlush(
                AgentToolsetRequest.of(user.groupId(), agent.id(), user.id(), toolset, clock.instant()));
        for (AppUser admin : users.findByGroupIdAndRole(user.groupId(), UserRole.ADMIN)) {
            if (!access.revoked(admin.email())) {
                notifications.notify(
                        admin.id(),
                        NotificationKind.TOOLSET_REQUESTED,
                        "도구 사용 요청이 있어요",
                        user.displayName() + "님이 「" + agent.name() + "」의 도구 사용을 요청했어요.",
                        new NotificationTarget(NotificationTargetType.ADMIN_TOOL_REQUEST, row.publicId()));
            }
        }
        return view(row, agent);
    }

    @Transactional(readOnly = true)
    public List<ToolsetRequestView> list(CurrentUser user, String code, boolean adminView) {
        requireCurrent(user, adminView);
        Agent agent = agents.findByCode(code).orElseThrow(ToolsetRequestService::notFound);
        if (adminView) {
            requireGroup(user, agent);
        } else {
            requireOwner(user, agent);
        }
        return requests.findByAgentIdAndGroupIdOrderByRequestedAtDescIdDesc(agent.id(), user.groupId()).stream()
                .filter(row -> adminView || Objects.equals(row.requesterUserId(), user.id()))
                .map(row -> view(row, agent))
                .toList();
    }

    @Transactional(readOnly = true)
    public ToolsetRequestView read(CurrentUser user, UUID id, boolean adminView) {
        requireCurrent(user, adminView);
        AgentToolsetRequest row = requireRequest(user, id, adminView);
        return view(row, agents.findById(row.agentId()).orElseThrow(ToolsetRequestService::notFound));
    }

    @Transactional
    public ToolsetRequestView decide(CurrentUser user, UUID id, boolean approve, String reason) {
        Agent agent = lockAgent(id);
        requireCurrent(user, true);
        AgentToolsetRequest row = requireRequest(user, id, true);
        if (row.status() != ToolsetRequestStatus.PENDING) {
            return view(row, agent);
        }
        if (!approve
                && (reason == null
                        || reason.isBlank()
                        || reason.strip().length() > 200
                        || reason.contains("\n")
                        || reason.contains("\r"))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a rejection needs one line of at most 200 characters");
        }
        String invalid = ineligible(agent, row.requesterUserId(), row.groupId(), row.toolset());
        ToolsetRequestStatus result;
        if (invalid != null) {
            result = ToolsetRequestStatus.EXPIRED;
            reason = invalid;
        } else if (approve) {
            // 재승인에서도 전체 현재 목록을 다시 검증하고 Hermes 반영을 확인한다. DB 실패 뒤 이미 켜진 경우도 같다.
            AgentToolsetsView current = tools.readAsAdmin(user, agent.code());
            if (current.toolsets().stream().noneMatch(item -> item.name().equals(row.toolset()))) {
                result = ToolsetRequestStatus.EXPIRED;
                reason = "이 도구를 더 이상 사용할 수 없어요.";
            } else {
                List<String> desired = new ArrayList<>(current.toolsets().stream()
                        .filter(AgentToolView::enabled)
                        .map(AgentToolView::name)
                        .toList());
                if (!desired.contains(row.toolset())) {
                    desired.add(row.toolset());
                }
                tools.writeAsAdmin(user, agent.code(), desired);
                result = ToolsetRequestStatus.APPROVED;
                reason = null;
            }
        } else {
            result = ToolsetRequestStatus.REJECTED;
            reason = reason.strip();
        }
        row.finish(result, user.id(), reason, clock.instant());
        requests.saveAndFlush(row);
        String body =
                result == ToolsetRequestStatus.APPROVED ? "「" + agent.name() + "」에서 요청한 도구를 다음 실행부터 쓸 수 있어요." : reason;
        notifications.notify(
                row.requesterUserId(),
                NotificationKind.TOOLSET_REQUEST_DECIDED,
                result == ToolsetRequestStatus.APPROVED ? "도구 사용 요청이 승인됐어요" : "도구 사용 요청 결과가 있어요",
                body,
                new NotificationTarget(NotificationTargetType.TOOLSET_REQUEST, row.publicId()));
        return view(row, agent);
    }

    @Transactional
    public ToolsetRequestView cancel(CurrentUser user, UUID id) {
        Agent agent = lockAgent(id);
        requireCurrent(user, false);
        AgentToolsetRequest row = requireRequest(user, id, false);
        if (row.status() == ToolsetRequestStatus.PENDING) {
            row.finish(ToolsetRequestStatus.CANCELLED, null, null, clock.instant());
            requests.saveAndFlush(row);
        }
        return view(row, agent);
    }

    private Agent lockAgent(UUID id) {
        Long agentId = requests.findAgentIdByPublicId(id).orElseThrow(ToolsetRequestService::notFound);
        return agents.findByIdForUpdate(agentId).orElseThrow(ToolsetRequestService::notFound);
    }

    private AgentToolsetRequest requireRequest(CurrentUser user, UUID id, boolean adminView) {
        AgentToolsetRequest row = requests.findByPublicId(id).orElseThrow(ToolsetRequestService::notFound);
        if (!Objects.equals(row.groupId(), user.groupId())
                || (!adminView && !Objects.equals(row.requesterUserId(), user.id()))) {
            throw notFound();
        }
        return row;
    }

    private void requireCurrent(CurrentUser user, boolean admin) {
        AppUser actual = users.findById(user.id()).orElseThrow(ToolsetRequestService::notFound);
        if (!Objects.equals(actual.groupId(), user.groupId())
                || access.revoked(actual.email())
                || (admin && (!user.isAdmin() || !actual.isAdmin()))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "current permissions do not allow this action");
        }
    }

    private String ineligible(Agent agent, Long requester, Long groupId, String toolset) {
        AppUser owner = users.findById(requester).orElse(null);
        if (agent.isDeleted()
                || agent.connectorManaged()
                || !Objects.equals(agent.ownerUserId(), requester)
                || owner == null
                || !Objects.equals(owner.groupId(), groupId)
                || access.revoked(owner.email())) {
            return "에이전트의 주인이나 사용 권한이 바뀌어 요청이 만료됐어요.";
        }
        if (!AgentToolPolicy.isKnown(toolset)
                || AgentToolPolicy.tierOf(toolset) != AgentToolPolicy.Tier.ADMIN
                || visibility.hiddenFor(groupId).contains(toolset)) {
            return "지금은 이 도구를 요청할 수 없어요.";
        }
        if (agent.visibility() == AgentVisibility.GROUP && AgentToolPolicy.requiresPrivate(toolset)) {
            return "이 도구는 비공개 에이전트에서만 쓸 수 있어요.";
        }
        return null;
    }

    private void requireGroup(CurrentUser user, Agent agent) {
        if (agent.ownerUserId() == null) {
            return;
        }
        AppUser owner = users.findById(agent.ownerUserId()).orElseThrow(ToolsetRequestService::notFound);
        if (!Objects.equals(owner.groupId(), user.groupId())) {
            throw notFound();
        }
    }

    private static void requireOwner(CurrentUser user, Agent agent) {
        if (agent.isDeleted() || agent.connectorManaged() || !Objects.equals(user.id(), agent.ownerUserId())) {
            throw notFound();
        }
    }

    private ToolsetRequestView view(AgentToolsetRequest row, Agent agent) {
        String name =
                users.findById(row.requesterUserId()).map(AppUser::displayName).orElse("사용자");
        return new ToolsetRequestView(
                row.publicId(),
                agent.code(),
                agent.name(),
                agent.isDeleted(),
                name,
                row.toolset(),
                row.status(),
                row.reason(),
                row.requestedAt(),
                row.decidedAt());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.TOOLSET_REQUEST_NOT_FOUND, "no such toolset request");
    }
}
