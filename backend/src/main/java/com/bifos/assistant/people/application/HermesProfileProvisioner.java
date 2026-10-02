package com.bifos.assistant.people.application;

import com.bifos.assistant.agent.application.ProfileProvisioning;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesProfileKeyStore;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.IssuedToken;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * profile 하나를 끝까지 만들고, Control Plane 이 만든 profile 을 거둔다.
 *
 * <p>만드는 순서와 실패했을 때 되돌리는 일을 이 클래스가 안다. 반만 만들어진 profile 을 남기지
 * 않는다. 남기면 같은 이름으로 다시 만들 수 없고, 그 사람은 profile 이 있는데도 대화하지 못한다.
 *
 * <p>그 profile 에 묶인 MCP 토큰도 이 클래스가 다룬다. 만들 때 발급해 {@code .env} 에 넣고, 되돌리거나
 * 거둘 때 폐기한다. 토큰은 profile 만 증명하므로(ADR-032) profile 과 함께 생기고 함께 사라져야 한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HermesProfileProvisioner implements ProfileProvisioning {

    /** 접두를 붙여 부를 때 Hermes 가 이 profile 을 가리키는 이름이다. */
    private static final String MODEL_NAME_ENV = "API_SERVER_MODEL_NAME";

    /** 그 profile 의 gateway 가 요청마다 검사하는 값이다. */
    private static final String API_KEY_ENV = "API_SERVER_KEY";

    /** 대시보드 plugin 이 profile 을 만들 때 등록한 MCP 서버가 Control Plane 에 붙을 때 쓰는 토큰이다. */
    private static final String MCP_TOKEN_ENV = "MCP_FOS_ASSISTANT_API_KEY";

    private final HermesDashboardClient dashboard;
    private final HermesProfileKeyStore keyStore;
    private final ProfileKeyFactory keys;
    private final AgentTokenService tokens;

    /**
     * profile 을 만들고 그 profile 의 key 를 {@code .env} 와 key 디렉터리에 함께 쓴다.
     *
     * <p>{@code .env} 에 넣는 것은 {@code MCP_FOS_ASSISTANT_API_KEY}, {@code API_SERVER_MODEL_NAME},
     * {@code API_SERVER_KEY} 셋뿐이다. listener 설정을 함께 적으면 공유 listener 를 쓰는 구성에서
     * gateway 가 뜰 때 그 profile 을 건너뛰고, 그 사람이 처음 대화할 때에야 드러난다. 근거는
     * {@code docs/hermes/profiles.md} 의 「공유 listener 를 쓰는 profile 에는 listener 설정을 넣지
     * 않는다」가 갖는다.
     *
     * <p>MCP 토큰은 profile 을 만든 직후 가장 먼저 넣는다. profile 이 만들어지면 MCP 등록이 이미 붙어
     * 있어, 토큰 없이 연결에 실패하는 일이 쌓이면 Hermes 가 다시 붙는 간격을 늘린다.
     */
    @Override
    public void provision(String profileName) {
        dashboard.createProfile(profileName);
        try {
            IssuedToken token = tokens.issue(profileName, "profile " + profileName);
            dashboard.putEnv(profileName, MCP_TOKEN_ENV, token.rawToken());
            String key = keys.next();
            dashboard.putEnv(profileName, MODEL_NAME_ENV, profileName);
            dashboard.putEnv(profileName, API_KEY_ENV, key);
            keyStore.write(profileName, key);
        } catch (RuntimeException failure) {
            throw rollback(profileName, failure);
        }
    }

    /**
     * Control Plane 이 만든 profile 을 거둔다.
     *
     * <p>토큰을 먼저 폐기해 남은 연결의 호출을 막고, profile 을 지운 뒤 key 파일을 지운다. 하나라도 실패하면
     * 그 오류를 그대로 던진다. 부르는 쪽이 그것을 보고 에이전트를 지우지 않게 하기 위해서다. 없는 profile
     * 은 이미 지운 것으로 보므로, 다시 부르면 끝까지 거둔다.
     */
    @Override
    public void deprovision(String profileName) {
        tokens.revokeAllFor(profileName);
        dashboard.deleteProfile(profileName);
        keyStore.delete(profileName);
    }

    /**
     * 만든 것을 거두고, 부르는 쪽으로 올릴 오류를 고른다.
     *
     * <p>토큰을 먼저 폐기하고, key 파일을 지운 뒤 profile 을 지운다. 토큰을 먼저 폐기해야 거두는 사이에
     * 남은 연결이 Control Plane 을 부르지 못한다. key 파일은 내용을 쓰다 실패해도 빈 채로 남을 수 있어,
     * 지우지 않으면 그 이름으로 다시 만들 수 없다.
     *
     * <p>하나가 실패해도 나머지는 시도한다. 모두 거둬야 같은 이름을 다시 쓸 수 있고, 앞의 실패로 뒤를
     * 건너뛰면 거두지 못한 것이 늘어난다.
     *
     * <p>모두 거뒀으면 거뒀다는 것이 드러나는 오류로 바꾼다. 같은 이름으로 다시 시도할 수 있다는
     * 뜻이기도 하다. 하나라도 거두지 못했으면 원래 오류를 그대로 올리고 거두기 실패는 로그로만
     * 남긴다. 거두기 실패가 원래 원인을 가리면 무엇 때문에 만들지 못했는지 알 수 없게 된다.
     */
    private RuntimeException rollback(String profileName, RuntimeException failure) {
        boolean tokensRevoked = revokeTokens(profileName);
        boolean keyRemoved = removeKey(profileName);
        boolean profileRemoved = removeProfile(profileName);
        if (!tokensRevoked || !keyRemoved || !profileRemoved) {
            return failure;
        }
        return new ApiException(
                ErrorCode.HERMES_PROVISION_FAILED,
                "could not provision the Hermes profile; the half-made profile was removed",
                failure);
    }

    private boolean revokeTokens(String profileName) {
        try {
            tokens.revokeAllFor(profileName);
            return true;
        } catch (RuntimeException rollbackFailure) {
            log.error(
                    "profile 을 만들다 실패해 MCP 토큰을 폐기하려 했으나 그것도 실패했다. 쓰이지 않을 토큰이 남는다 profile={}",
                    profileName,
                    rollbackFailure);
            return false;
        }
    }

    private boolean removeKey(String profileName) {
        try {
            keyStore.delete(profileName);
            return true;
        } catch (RuntimeException rollbackFailure) {
            log.error(
                    "profile 을 만들다 실패해 key 파일을 거두려 했으나 그것도 실패했다. 그 이름으로 다시 만들 수 없다 profile={}",
                    profileName,
                    rollbackFailure);
            return false;
        }
    }

    private boolean removeProfile(String profileName) {
        try {
            dashboard.deleteProfile(profileName);
            return true;
        } catch (RuntimeException rollbackFailure) {
            log.error(
                    "profile 을 만들다 실패해 되돌리려 했으나 그것도 실패했다. 반만 만들어진 profile 이 남는다 profile={}",
                    profileName,
                    rollbackFailure);
            return false;
        }
    }
}
