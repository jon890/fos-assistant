package com.bifos.assistant.people.application;

import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesProfileKeyStore;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * profile 하나를 끝까지 만든다.
 *
 * <p>만드는 순서와 실패했을 때 되돌리는 일을 이 클래스가 안다. 반만 만들어진 profile 을 남기지
 * 않는다. 남기면 같은 이름으로 다시 만들 수 없고, 그 사람은 profile 이 있는데도 대화하지 못한다.
 */
@Service
@RequiredArgsConstructor
public class HermesProfileProvisioner {

    private static final Logger log = LoggerFactory.getLogger(HermesProfileProvisioner.class);

    /** 접두를 붙여 부를 때 Hermes 가 이 profile 을 가리키는 이름이다. */
    private static final String MODEL_NAME_ENV = "API_SERVER_MODEL_NAME";

    /** 그 profile 의 gateway 가 요청마다 검사하는 값이다. */
    private static final String API_KEY_ENV = "API_SERVER_KEY";

    private final HermesDashboardClient dashboard;
    private final HermesProfileKeyStore keyStore;
    private final ProfileKeyFactory keys;

    /**
     * profile 을 만들고 그 profile 의 key 를 {@code .env} 와 key 디렉터리에 함께 쓴다.
     *
     * <p>{@code .env} 에 넣는 것은 {@code API_SERVER_MODEL_NAME} 과 {@code API_SERVER_KEY} 둘뿐이다.
     * listener 설정을 함께 적으면 공유 listener 를 쓰는 구성에서 gateway 가 뜰 때 그 profile 을
     * 건너뛰고, 그 사람이 처음 대화할 때에야 드러난다. 근거는 {@code docs/hermes-integration.md} 의
     * 「공유 listener 를 쓰는 profile 에는 listener 설정을 넣지 않는다」가 갖는다.
     */
    public void provision(String profileName) {
        dashboard.createProfile(profileName);
        try {
            String key = keys.next();
            dashboard.putEnv(profileName, MODEL_NAME_ENV, profileName);
            dashboard.putEnv(profileName, API_KEY_ENV, key);
            keyStore.write(profileName, key);
        } catch (RuntimeException failure) {
            throw rollback(profileName, failure);
        }
    }

    /**
     * 만든 것을 만든 순서의 역순으로 거두고, 부르는 쪽으로 올릴 오류를 고른다.
     *
     * <p>key 파일을 먼저 지우고 profile 을 지운다. key 파일은 내용을 쓰다 실패해도 빈 채로 남을 수
     * 있어, 지우지 않으면 그 이름으로 다시 만들 수 없다.
     *
     * <p>한쪽이 실패해도 다른 쪽은 시도한다. 둘 다 거둬야 같은 이름을 다시 쓸 수 있고, 앞의 실패로
     * 뒤를 건너뛰면 거두지 못한 것이 늘어난다.
     *
     * <p>모두 거뒀으면 거뒀다는 것이 드러나는 오류로 바꾼다. 같은 이름으로 다시 시도할 수 있다는
     * 뜻이기도 하다. 하나라도 거두지 못했으면 원래 오류를 그대로 올리고 거두기 실패는 로그로만
     * 남긴다. 거두기 실패가 원래 원인을 가리면 무엇 때문에 만들지 못했는지 알 수 없게 된다.
     */
    private RuntimeException rollback(String profileName, RuntimeException failure) {
        boolean keyRemoved = removeKey(profileName);
        boolean profileRemoved = removeProfile(profileName);
        if (!keyRemoved || !profileRemoved) {
            return failure;
        }
        return new ApiException(
                ErrorCode.HERMES_PROVISION_FAILED,
                "could not provision the Hermes profile; the half-made profile was removed",
                failure);
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
