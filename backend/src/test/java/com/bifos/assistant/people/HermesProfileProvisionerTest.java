package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.HermesProfileKeyStore;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.StubHermesDashboardClient;
import com.bifos.assistant.people.application.HermesProfileProvisioner;
import com.bifos.assistant.people.application.ProfileKeyFactory;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** profile 을 만드는 순서와 실패했을 때 되돌리는 것을 본다. */
class HermesProfileProvisionerTest {

    private static final String PROFILE = "kid";

    private final StubHermesDashboardClient dashboard = new StubHermesDashboardClient();

    private HermesProfileKeyStore keyStoreAt(Path dir) {
        return new HermesProfileKeyStore(
                new HermesProperties(
                        dir.toString(),
                        "https://hermes-dashboard.example.com",
                        "test-dashboard-token",
                        "https://hermes-listener.example.com",
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)));
    }

    private HermesProfileProvisioner provisionerAt(Path dir) {
        return new HermesProfileProvisioner(dashboard, keyStoreAt(dir), new ProfileKeyFactory());
    }

    @Test
    void profile_과_key_파일을_만들고_같은_key_를_env_에_넣는다(@TempDir Path dir) {
        provisionerAt(dir).provision(PROFILE);

        assertThat(dashboard.createdProfiles()).containsExactly(PROFILE);
        assertThat(dir.resolve(PROFILE)).exists();
        assertThat(keyStoreAt(dir).resolve(PROFILE))
                .isEqualTo(dashboard.env(PROFILE).get("API_SERVER_KEY"));
    }

    @Test
    void env_에_넣는_것은_모델_이름과_key_둘뿐이다(@TempDir Path dir) {
        provisionerAt(dir).provision(PROFILE);

        Map<String, String> env = dashboard.env(PROFILE);
        assertThat(env).hasSize(2);
        assertThat(env).containsKeys("API_SERVER_MODEL_NAME", "API_SERVER_KEY");
        assertThat(env.get("API_SERVER_MODEL_NAME")).isEqualTo(PROFILE);
    }

    /**
     * 공유 listener 를 쓰는 profile 이 listener 설정을 가지면 gateway 가 뜰 때 그 profile 을 건너뛴다.
     *
     * <p>건너뛴 것은 기동 로그에만 남고, 그 사람이 처음 대화할 때에야 드러난다. 그래서 여기서 고정한다.
     */
    @Test
    void listener_설정을_env_에_넣지_않는다(@TempDir Path dir) {
        provisionerAt(dir).provision(PROFILE);

        assertThat(dashboard.env(PROFILE))
                .doesNotContainKeys("API_SERVER_ENABLED", "API_SERVER_HOST", "API_SERVER_PORT");
    }

    @Test
    void 이미_있는_이름이면_만들지_않고_그대로_알린다(@TempDir Path dir) {
        dashboard.failOnCreate(
                () -> new ApiException(ErrorCode.HERMES_PROFILE_EXISTS, "already taken"));

        assertThatThrownBy(() -> provisionerAt(dir).provision(PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.HERMES_PROFILE_EXISTS);
        assertThat(dashboard.deletedProfiles()).isEmpty();
        assertThat(dir.resolve(PROFILE)).doesNotExist();
    }

    @Test
    void env_를_쓰다_실패하면_profile_을_거두고_key_파일을_남기지_않는다(@TempDir Path dir) {
        dashboard.failOnPutEnv(
                () -> new ApiException(ErrorCode.HERMES_UNAVAILABLE, "dashboard is down"));

        assertThatThrownBy(() -> provisionerAt(dir).provision(PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.HERMES_PROVISION_FAILED);
        assertThat(dashboard.deletedProfiles()).containsExactly(PROFILE);
        assertThat(dir.resolve(PROFILE)).doesNotExist();
    }

    /**
     * key 파일을 쓰다 실패하면 그 파일까지 거둔다.
     *
     * <p>{@code write} 는 파일을 만든 뒤 내용을 쓰므로 내용 쓰기에서 실패하면 빈 파일이 남는다.
     * 남겨 두면 다음에 같은 이름으로 만들 때 그 파일에 걸려 그 이름을 영영 쓸 수 없다.
     */
    @Test
    void key_파일을_쓰다_실패하면_profile_과_그_파일을_함께_거둔다(@TempDir Path dir) throws IOException {
        // 같은 이름의 key 파일이 이미 있으면 덮지 않고 실패한다. 남아 있던 파일을 흉내 낸 것이다.
        Files.writeString(dir.resolve(PROFILE), "앞서 실패해 남아 있던 key");

        assertThatThrownBy(() -> provisionerAt(dir).provision(PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.HERMES_PROVISION_FAILED);
        assertThat(dashboard.deletedProfiles()).containsExactly(PROFILE);
        assertThat(dir.resolve(PROFILE)).doesNotExist();
    }

    /** 되돌리기가 하는 일이 이것이다. 막힌 이름을 풀어 같은 이름으로 다시 만들 수 있게 한다. */
    @Test
    void 되돌린_뒤_같은_이름으로_다시_만들_수_있다(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(PROFILE), "앞서 실패해 남아 있던 key");
        HermesProfileProvisioner provisioner = provisionerAt(dir);
        assertThatThrownBy(() -> provisioner.provision(PROFILE)).isInstanceOf(ApiException.class);

        provisioner.provision(PROFILE);

        assertThat(dir.resolve(PROFILE)).exists();
        assertThat(keyStoreAt(dir).resolve(PROFILE))
                .isEqualTo(dashboard.env(PROFILE).get("API_SERVER_KEY"))
                .isNotEqualTo("앞서 실패해 남아 있던 key");
    }

    /** 대시보드를 부르다 실패한 경우에도 같은 이름을 다시 쓸 수 있어야 한다. */
    @Test
    void 대시보드가_실패해_되돌린_뒤에도_같은_이름으로_다시_만들_수_있다(@TempDir Path dir) {
        dashboard.failOnPutEnv(
                () -> new ApiException(ErrorCode.HERMES_UNAVAILABLE, "dashboard is down"));
        HermesProfileProvisioner provisioner = provisionerAt(dir);
        assertThatThrownBy(() -> provisioner.provision(PROFILE)).isInstanceOf(ApiException.class);

        dashboard.stopFailing();
        provisioner.provision(PROFILE);

        assertThat(dir.resolve(PROFILE)).exists();
        assertThat(dashboard.env(PROFILE)).containsKey("API_SERVER_KEY");
    }

    /** 되돌리기 실패가 원래 원인을 가리면 무엇 때문에 만들지 못했는지 알 수 없게 된다. */
    @Test
    void 되돌리기도_실패하면_원래_오류가_올라온다(@TempDir Path dir) {
        ApiException original = new ApiException(ErrorCode.HERMES_UNAVAILABLE, "dashboard is down");
        dashboard.failOnPutEnv(() -> original);
        dashboard.failOnDelete(
                () -> new ApiException(ErrorCode.HERMES_BUSY, "could not remove the profile"));

        assertThatThrownBy(() -> provisionerAt(dir).provision(PROFILE)).isSameAs(original);
    }

    /**
     * 규칙에 맞지 않는 이름은 key 파일을 쓸 수도 지울 수도 없다.
     *
     * <p>key 파일을 거두는 데 실패해도 profile 은 거둔다. 앞의 실패로 뒤를 건너뛰면 거두지 못한 것이
     * 늘어난다. 하나라도 거두지 못했으므로 원래 오류가 그대로 올라온다.
     */
    @Test
    void key_파일을_거두지_못해도_profile_은_거둔다(@TempDir Path dir) {
        assertThatThrownBy(() -> provisionerAt(dir).provision("위로 올라가는 이름"))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(dashboard.deletedProfiles()).containsExactly("위로 올라가는 이름");
    }
}
