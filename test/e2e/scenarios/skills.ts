/**
 * 주인이 스킬을 올리면 버전 디렉터리가 게시되어 목록에 `UPLOADED` 로 보이고, 다른 사용자는 쓰지 못하며,
 * 지우면 사라지는 것을 전체 흐름에서 본다.
 *
 * <p>`dad` 에이전트를 쓴다. 그 profile 은 `skills` 도구가 꺼진 채 시작하므로 첫 저장이 도구까지 함께 켜는
 * 길을 지난다. 끝나면 스킬을 모두 지워 뒤의 시나리오에 남기지 않는다.
 */
import { call, expect, expectStatus, step, type Context, type Scenario } from "../harness.ts";
import { DAD_BINDING } from "./binding.ts";

type SkillItem = { name: string; description: string; source: "UPLOADED" | "HERMES"; enabled: boolean; usage?: unknown };
type SkillList = { skills: SkillItem[]; editable: boolean; skillsToolsetEnabled: boolean };
type SkillDetail = { name: string; description: string; body: string; files: { path: string; size: number }[] };
type ErrorBody = { code: string };

const NAME = "weekly-plan";
const DESCRIPTION = "이번 주 계획을 세운다";
const SKILL_MD = `---\nname: ${NAME}\ndescription: ${DESCRIPTION}\n---\n# 주간 계획\n\n요일별로 할 일을 나눈다.\n`;
const GUIDE = "월요일은 장보기, 수요일은 청소";
const VERSION_DIR = /\/dad-profile\/v[0-9]{13}-[a-z0-9]{4}$/;

export const skillsScenario: Scenario = {
  name: "에이전트 스킬",

  async run(context) {
    step("주인이 스킬을 올리면 원문과 파일 크기가 돌아오고 버전 디렉터리가 게시된다");
    const saved = expectStatus(
      await call(context, `/agents/dad/skills/${NAME}`, {
        method: "PUT",
        token: context.tokens.dad,
        body: { skillMd: SKILL_MD, files: [{ path: "references/guide.md", content: GUIDE }] },
      }),
      200,
      "스킬 저장",
    ).json<SkillDetail>();
    expect(saved.name === NAME && saved.description === DESCRIPTION, `저장 응답이 다르다: ${JSON.stringify(saved)}`);
    expect(saved.body === SKILL_MD, "저장 응답의 본문이 보낸 원문과 다르다");
    expect(
      saved.files.length === 1 && saved.files[0]!.path === "references/guide.md"
        && saved.files[0]!.size === Buffer.byteLength(GUIDE, "utf-8"),
      `저장 응답의 파일이 다르다: ${JSON.stringify(saved.files)}`,
    );
    const published = context.hermes.skillDirsOf(DAD_BINDING.profileName);
    expect(published.length === 1 && VERSION_DIR.test(published[0]!), `게시된 경로가 다르다: ${JSON.stringify(published)}`);

    step("목록에 UPLOADED 로 보이고 skills 도구가 함께 켜진다");
    const list = await listOf(context, context.tokens.dad);
    const uploaded = list.skills.find((skill) => skill.name === NAME);
    expect(uploaded?.source === "UPLOADED" && uploaded.enabled, `올린 스킬이 목록에 없거나 출처가 다르다: ${JSON.stringify(list)}`);
    expect(uploaded?.description === DESCRIPTION, `목록의 설명이 다르다: ${uploaded?.description}`);
    expect(list.skills.some((skill) => skill.name === "hermes-help" && skill.source === "HERMES"), "Hermes 기본 스킬이 HERMES 로 보이지 않는다");
    expect(list.editable && list.skillsToolsetEnabled, `편집 여부와 도구 상태가 다르다: ${JSON.stringify(list)}`);

    step("올린 스킬이 있는 동안은 skills 도구를 끄지 못한다");
    const toolsOff = expectStatus(
      await call(context, "/agents/dad/tools", { method: "PUT", token: context.tokens.dad, body: { enabled: [] } }),
      400,
      "skills 끄기",
    );
    expect(toolsOff.json<ErrorBody>().code === "VALIDATION_FAILED", `기대한 오류 코드가 아니다: ${toolsOff.body}`);

    step("Hermes 기본 스킬과 같은 이름은 거절한다");
    const taken = expectStatus(
      await call(context, "/agents/dad/skills/hermes-help", {
        method: "PUT",
        token: context.tokens.dad,
        body: { skillMd: SKILL_MD.replace(NAME, "hermes-help") },
      }),
      409,
      "같은 이름 저장",
    );
    expect(taken.json<ErrorBody>().code === "SKILL_NAME_TAKEN", `기대한 오류 코드가 아니다: ${taken.body}`);

    step("볼 수 없는 사용자에게는 없는 에이전트와 같고, 볼 수만 있는 사용자는 쓰지 못한다");
    const hidden = expectStatus(
      await call(context, `/agents/dad/skills/${NAME}`, { method: "PUT", token: context.tokens.kid, body: { skillMd: SKILL_MD } }),
      404,
      "kid 가 비공개 에이전트에 스킬 저장",
    );
    expect(hidden.json<ErrorBody>().code === "AGENT_NOT_FOUND", `기대한 오류 코드가 아니다: ${hidden.body}`);
    await setVisibility(context, "GROUP", null);
    try {
      const forbidden = expectStatus(
        await call(context, `/agents/dad/skills/${NAME}`, { method: "PUT", token: context.tokens.kid, body: { skillMd: SKILL_MD } }),
        403,
        "kid 가 그룹 에이전트에 스킬 저장",
      );
      expect(forbidden.json<ErrorBody>().code === "FORBIDDEN", `기대한 오류 코드가 아니다: ${forbidden.body}`);
      expectStatus(
        await call(context, `/agents/dad/skills/${NAME}`, { method: "DELETE", token: context.tokens.kid }),
        403,
        "kid 가 그룹 에이전트의 스킬 지우기",
      );
      const readerList = await listOf(context, context.tokens.kid);
      expect(!readerList.editable, "볼 수만 있는 사용자에게 편집 가능으로 보인다");
      expect(readerList.skills.some((skill) => skill.name === NAME), "볼 수만 있는 사용자의 목록에 올린 스킬이 없다");
    } finally {
      await setVisibility(context, "PRIVATE", "dad@example.com");
    }

    step("다시 읽으면 앞머리를 포함한 원문이 오고, 본문을 생략한 파일은 그대로 남는다");
    const detail = expectStatus(
      await call(context, `/agents/dad/skills/${NAME}`, { token: context.tokens.dad }),
      200,
      "스킬 읽기",
    ).json<SkillDetail>();
    expect(detail.body === SKILL_MD, "읽은 본문이 저장한 원문과 다르다");
    const resaved = expectStatus(
      await call(context, `/agents/dad/skills/${NAME}`, {
        method: "PUT",
        token: context.tokens.dad,
        body: { skillMd: `${SKILL_MD}\n고쳤다.\n`, files: [{ path: "references/guide.md" }] },
      }),
      200,
      "본문을 생략한 파일과 함께 다시 저장",
    ).json<SkillDetail>();
    expect(
      resaved.files.length === 1 && resaved.files[0]!.size === Buffer.byteLength(GUIDE, "utf-8"),
      `생략한 파일의 크기가 달라졌다: ${JSON.stringify(resaved.files)}`,
    );
    const republished = context.hermes.skillDirsOf(DAD_BINDING.profileName);
    expect(republished.length === 1 && republished[0] !== published[0], "다시 저장했는데 새 버전이 게시되지 않았다");

    step("끄면 목록에 꺼진 것으로 보인다");
    expectStatus(
      await call(context, `/agents/dad/skills/${NAME}/enabled`, { method: "PUT", token: context.tokens.dad, body: { enabled: false } }),
      204,
      "스킬 끄기",
    );
    const afterToggle = await listOf(context, context.tokens.dad);
    expect(afterToggle.skills.find((skill) => skill.name === NAME)?.enabled === false, "끈 스킬이 켜진 것으로 보인다");

    step("지우면 목록에서 사라지고 빈 목록이 게시된다");
    expectStatus(
      await call(context, `/agents/dad/skills/${NAME}`, { method: "DELETE", token: context.tokens.dad }),
      204,
      "스킬 지우기",
    );
    const afterDelete = await listOf(context, context.tokens.dad);
    expect(!afterDelete.skills.some((skill) => skill.name === NAME), "지운 스킬이 목록에 남았다");
    expect(context.hermes.skillDirsOf(DAD_BINDING.profileName).length === 0, "마지막 스킬을 지웠는데 빈 목록이 게시되지 않았다");
    const missing = expectStatus(
      await call(context, `/agents/dad/skills/${NAME}`, { method: "DELETE", token: context.tokens.dad }),
      404,
      "없는 스킬 지우기",
    );
    expect(missing.json<ErrorBody>().code === "SKILL_NOT_FOUND", `기대한 오류 코드가 아니다: ${missing.body}`);
  },
};

async function listOf(context: Context, token: string): Promise<SkillList> {
  return expectStatus(await call(context, "/agents/dad/skills", { token }), 200, "스킬 목록").json<SkillList>();
}

/** 관리자가 dad 에이전트의 공개 범위를 바꾼다. 다른 사용자가 볼 수 있을 때의 권한을 보려고 잠깐 그룹에 연다. */
async function setVisibility(context: Context, visibility: "PRIVATE" | "GROUP", ownerEmail: string | null): Promise<void> {
  expectStatus(
    await call(context, "/admin/agents/dad", {
      method: "PATCH",
      token: context.tokens.dad,
      body: { enabled: true, visibility, ownerEmail },
    }),
    200,
    `공개 범위를 ${visibility} 로`,
  );
}
