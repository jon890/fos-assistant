package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.dto.SoulDocument;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 에이전트의 성격을 읽고 쓰는 순서를 안다.
 *
 * <p>본문은 그 profile 의 {@code SOUL.md} 가 갖고 이 저장소는 화면만 준다. 데이터베이스에 본문을 두지
 * 않으므로 정본이 하나이고 반영 상태가 갈리지 않는다. 근거는 ADR-019 에 있다.
 *
 * <p>{@code hermes} 는 대시보드를 부르는 방법만 알고 부르는 순서와 권한 판정은 여기가 안다.
 */
@Service
@RequiredArgsConstructor
public class PersonaService {

    private final AgentService agents;
    private final HermesDashboardClient dashboard;

    /** 그 에이전트의 지금 본문과 요청자가 고칠 수 있는지를 돌려준다. */
    public PersonaSnapshot read(CurrentUser user, String code) {
        Agent agent = agents.requireReadable(user, code);
        SoulDocument soul = dashboard.readSoul(agent.hermesProfile());
        return new PersonaSnapshot(soul.content(), Sha256.hex16(soul.content()), editable(user, agent));
    }

    /**
     * 본문을 쓰고 쓴 결과를 돌려준다.
     *
     * <p>쓰기 직전에 한 번 더 읽어 화면이 받아 간 본문과 같을 때만 쓴다. 다르면 그 사이 다른 사람이
     * 고친 것이고 {@link ErrorCode#PERSONA_STALE} 로 거절한다.
     *
     * <p>다시 읽은 것과 쓰는 것 사이에 트랜잭션이 없다. 데이터베이스를 쓰지 않으므로 걸 것이 없고, 그
     * 사이에 다른 사람이 쓰면 그 글이 덮어쓰인다. 가족 몇 사람이 쓰는 서비스에서 그 창이 실제로
     * 문제가 될 만큼 넓지 않다고 보고 그대로 둔다.
     */
    public PersonaSnapshot write(CurrentUser user, String code, String body, String baseHash) {
        Agent agent = agents.requireReadable(user, code);
        if (!editable(user, agent)) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "only the owner of this agent or the family admin can edit it");
        }
        String trimmed = body == null ? "" : body.strip();
        if (trimmed.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a persona cannot be empty");
        }
        requireUnchanged(dashboard.readSoul(agent.hermesProfile()), baseHash);
        dashboard.putSoul(agent.hermesProfile(), trimmed);
        return new PersonaSnapshot(trimmed, Sha256.hex16(trimmed), true);
    }

    /**
     * 읽어 둔 본문이 지금 본문과 같은지 본다.
     *
     * <p>지문은 언제나 대시보드가 돌려준 원문 그대로에 건다. 공백을 떼고 걸면 줄바꿈으로 끝나는
     * {@code SOUL.md} 를 화면이 받아 간 지문과 여기서 만든 지문이 갈려 첫 저장이 언제나 거절된다.
     *
     * <p>{@code baseHash} 가 비어 있는 것은 아직 아무것도 읽지 않았다는 뜻이다. 지금 본문이 있으면
     * 보지 않고 덮어쓰는 것이라 거절하고, 없으면 처음 쓰는 것이라 통과시킨다. 화면은 언제나 읽기를
     * 먼저 하므로 이 경로로 오지 않는다.
     */
    private static void requireUnchanged(SoulDocument current, String baseHash) {
        if (baseHash == null || baseHash.isBlank()) {
            if (current.exists() && !current.content().isBlank()) {
                throw stale();
            }
            return;
        }
        if (!Objects.equals(Sha256.hex16(current.content()), baseHash)) {
            throw stale();
        }
    }

    private static ApiException stale() {
        return new ApiException(
                ErrorCode.PERSONA_STALE, "this persona changed since it was read; read it again");
    }

    /** 주인과 {@code ADMIN} 만 고친다. 가족이 함께 쓰는 에이전트는 여럿이 함께 읽는 글이다. */
    private static boolean editable(CurrentUser user, Agent agent) {
        return user.isAdmin() || Objects.equals(agent.ownerUserId(), user.id());
    }
}
