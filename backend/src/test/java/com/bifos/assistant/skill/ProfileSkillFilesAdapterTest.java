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

/** agent 가 묻는 두 동작이 SkillStore 로 넘어가는지 본다. */
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
                        new SkillBundle(
                                "weekly-plan", SKILL_MD, List.of(new SkillFile("references/guide.md", "안내")))));

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
}
