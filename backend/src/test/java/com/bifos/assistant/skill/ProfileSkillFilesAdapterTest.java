package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.bifos.assistant.skill.application.ProfileSkillFilesAdapter;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import com.bifos.assistant.skill.infra.SkillProperties;
import com.bifos.assistant.skill.infra.SkillStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** agent 가 묻는 동작이 SkillStore 로 넘어가는지 본다. */
class ProfileSkillFilesAdapterTest {

    private static final String PROFILE = "ua-adaptertest";

    private static final String SKILL_MD = """
            ---
            name: weekly-plan
            description: 이번 주 계획을 세운다
            ---
            # 주간 계획
            """;

    private static SkillStore storeAt(Path root) {
        return new SkillStore(new SkillProperties(root.toString(), "/agent-side/skills/", 3, null), Clock.systemUTC());
    }

    @Test
    @DisplayName("스킬을 올린 profile 은 올렸다고 답하고 deleteAll 뒤에는 아니라고 답한다")
    void reportsUploadedThenNotAfterDeleteAll(@TempDir Path root) {
        SkillStore store = storeAt(root);
        ProfileSkillFilesAdapter adapter = new ProfileSkillFilesAdapter(store);
        store.writeVersion(
                PROFILE,
                Map.of(
                        "weekly-plan",
                        new SkillBundle("weekly-plan", SKILL_MD, List.of(new SkillFile("references/guide.md", "안내")))));

        assertThat(adapter.hasUploaded(PROFILE)).isTrue();

        adapter.deleteAll(PROFILE);

        assertThat(adapter.hasUploaded(PROFILE)).isFalse();
    }

    @Test
    @DisplayName("아무것도 올리지 않은 profile 은 올리지 않았다고 답하고 deleteAll 은 예외 없이 끝난다")
    void emptyProfileHasNothingAndDeleteAllPasses(@TempDir Path root) {
        ProfileSkillFilesAdapter adapter = new ProfileSkillFilesAdapter(storeAt(root));

        assertThat(adapter.hasUploaded(PROFILE)).isFalse();
        assertThatCode(() -> adapter.deleteAll(PROFILE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비밀 요청 칸이 있는 올린 스킬의 이름만 돌린다. 표식 없는 더 새 버전의 것도 센다")
    void listsUploadedSkillsRequestingSecrets(@TempDir Path root) {
        SkillStore store = storeAt(root);
        ProfileSkillFilesAdapter adapter = new ProfileSkillFilesAdapter(store);
        String requestsEnv = "---\nname: legacy-env\ndescription: 옛 스킬\n"
                + "required_environment_variables:\n  - name: API_KEY\n---\n# legacy-env\n";

        assertThat(adapter.uploadedRequestingSecrets(PROFILE)).isEmpty();

        store.writeVersion(
                PROFILE,
                Map.of(
                        "weekly-plan", new SkillBundle("weekly-plan", SKILL_MD, List.of()),
                        "legacy-env", new SkillBundle("legacy-env", requestsEnv, List.of())));

        assertThat(adapter.uploadedRequestingSecrets(PROFILE)).containsExactly("legacy-env");
    }

    @ParameterizedTest
    @ValueSource(strings = {"required_environment_variables", "required_credential_files"})
    @DisplayName("같은 이름의 안전한 게시 완료 버전이 비밀 요청 pending 버전을 가리지 않는다")
    void detectsSecretPendingAfterSafeCurrent(String field, @TempDir Path root) {
        SkillStore store = storeAt(root);
        writeWeeklyPlan(store, SKILL_MD, true);
        writeWeeklyPlan(store, requestingSecret(field), false);

        assertThat(new ProfileSkillFilesAdapter(store).uploadedRequestingSecrets(PROFILE))
                .containsExactly("weekly-plan");
    }

    @ParameterizedTest
    @ValueSource(strings = {"required_environment_variables", "required_credential_files"})
    @DisplayName("같은 이름의 안전한 pending 버전이 비밀 요청 게시 완료 버전을 가리지 않는다")
    void detectsSecretCurrentBeforeSafePending(String field, @TempDir Path root) {
        SkillStore store = storeAt(root);
        writeWeeklyPlan(store, requestingSecret(field), true);
        writeWeeklyPlan(store, SKILL_MD, false);

        assertThat(new ProfileSkillFilesAdapter(store).uploadedRequestingSecrets(PROFILE))
                .containsExactly("weekly-plan");
    }

    @ParameterizedTest
    @ValueSource(strings = {"required_environment_variables", "required_credential_files"})
    @DisplayName("최신 pending 버전이 안전해도 같은 이름의 이전 pending 비밀 요청을 검사한다")
    void detectsSecretInOlderPendingVersion(String field, @TempDir Path root) {
        SkillStore store = storeAt(root);
        writeWeeklyPlan(store, SKILL_MD, true);
        writeWeeklyPlan(store, requestingSecret(field), false);
        writeWeeklyPlan(store, SKILL_MD, false);

        assertThat(store.readPending(PROFILE).get("weekly-plan").skillMd()).isEqualTo(SKILL_MD);
        assertThat(new ProfileSkillFilesAdapter(store).uploadedRequestingSecrets(PROFILE))
                .containsExactly("weekly-plan");
    }

    @Test
    @DisplayName("여러 버전의 비밀 요청 이름은 한 번만 돌리고 새 게시가 성공하면 이전 요청을 제외한다")
    void deduplicatesSecretsAndExcludesSupersededVersions(@TempDir Path root) {
        SkillStore store = storeAt(root);
        writeWeeklyPlan(store, requestingSecret("required_environment_variables"), true);
        writeWeeklyPlan(store, requestingSecret("required_credential_files"), false);
        writeWeeklyPlan(store, requestingSecret("required_environment_variables"), false);
        ProfileSkillFilesAdapter adapter = new ProfileSkillFilesAdapter(store);

        assertThat(adapter.uploadedRequestingSecrets(PROFILE)).containsExactly("weekly-plan");

        writeWeeklyPlan(store, SKILL_MD, true);

        assertThat(adapter.uploadedRequestingSecrets(PROFILE)).isEmpty();
    }

    private static void writeWeeklyPlan(SkillStore store, String skillMd, boolean published) {
        String version =
                store.writeVersion(PROFILE, Map.of("weekly-plan", new SkillBundle("weekly-plan", skillMd, List.of())));
        if (published) {
            store.markPublished(PROFILE, version);
        }
    }

    private static String requestingSecret(String field) {
        return "---\nname: weekly-plan\ndescription: 이번 주 계획\n" + field + ": []\n---\n# 주간 계획\n";
    }
}
