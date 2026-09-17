/**
 * 영역(workspace)의 안내문이 실행에 실리고, 접근 권한과 고정이 지켜지는지 본다.
 *
 * <p>사전에 `home`(가족 공개)과 `kid-private`(kid 개인)이라는 두 영역이 마운트 루트에 등록돼 있다.
 * 두 영역의 `AGENTS.md` 는 각각 서로 다른 마커 문장을 담고 있어, fake Hermes 가 그대로 돌려주는
 * `instructions` 에서 어느 영역이 실렸는지 구분할 수 있다.
 */
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

export const HOME_WORKSPACE = { code: "home", marker: "가족-영역-마커-9f21" } as const;
export const KID_PRIVATE_WORKSPACE = { code: "kid-private", marker: "개인-영역-마커-7c40" } as const;

type WorkspaceView = { code: string; name: string; visibility: string };
type Turn = { conversationId: number; assistantText: string };

/** 안내문 하나를 담은 영역 디렉터리를 마운트 루트 아래에 만든다. */
async function writeGuide(root: string, dir: string, marker: string): Promise<void> {
  const target = join(root, dir);
  await mkdir(target, { recursive: true });
  await writeFile(join(target, "AGENTS.md"), `# 영역 규칙\n\n${marker}\n`);
}

export const workspaceScenario: Scenario = {
  name: "작업 영역",

  async run(context) {
    step("영역 디렉터리를 마운트 루트에 두고 등록한다");
    await writeGuide(context.workspaceRoot, HOME_WORKSPACE.code, HOME_WORKSPACE.marker);
    await writeGuide(context.workspaceRoot, KID_PRIVATE_WORKSPACE.code, KID_PRIVATE_WORKSPACE.marker);

    expectStatus(
      await call(context, "/admin/workspaces", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: HOME_WORKSPACE.code,
          name: "가족 공용",
          sourcePath: HOME_WORKSPACE.code,
          visibility: "FAMILY",
        },
      }),
      200,
      "가족 공개 영역 등록",
    );
    expectStatus(
      await call(context, "/admin/workspaces", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: KID_PRIVATE_WORKSPACE.code,
          name: "kid 개인",
          sourcePath: KID_PRIVATE_WORKSPACE.code,
          visibility: "PRIVATE",
          ownerEmail: "kid@example.com",
        },
      }),
      200,
      "개인 영역 등록",
    );

    step("가족 공개 영역은 다른 구성원도 목록에서 보고, 남의 개인 영역은 보지 못한다");
    const kidList = expectStatus(
      await call(context, "/workspaces", { token: context.tokens.kid }),
      200,
      "kid 의 영역 목록",
    ).json<WorkspaceView[]>();
    expect(
      kidList.some((it) => it.code === HOME_WORKSPACE.code),
      `kid 의 목록에 ${HOME_WORKSPACE.code} 가 없다: ${JSON.stringify(kidList)}`,
    );
    expect(
      kidList.some((it) => it.code === KID_PRIVATE_WORKSPACE.code),
      `kid 의 목록에 자기 개인 영역이 없다: ${JSON.stringify(kidList)}`,
    );

    const dadList = expectStatus(
      await call(context, "/workspaces", { token: context.tokens.dad }),
      200,
      "dad 의 영역 목록",
    ).json<WorkspaceView[]>();
    expect(
      dadList.every((it) => it.code !== KID_PRIVATE_WORKSPACE.code),
      `dad 의 목록에 kid 의 개인 영역이 보인다: ${JSON.stringify(dadList)}`,
    );

    step("영역을 주면 그 영역의 안내문이 실행에 실린다");
    const withWorkspace = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "오늘 뭐 하지?", workspaceCode: HOME_WORKSPACE.code, agentCode: "dad" },
      }),
      200,
      "영역을 준 대화",
    ).json<Turn>();
    expect(
      withWorkspace.assistantText.includes(HOME_WORKSPACE.marker),
      `안내문이 실행에 실리지 않았다: ${withWorkspace.assistantText}`,
    );

    step("이어지는 대화는 요청이 영역을 다시 주지 않아도 처음 영역을 유지한다");
    const continued = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId: withWorkspace.conversationId, text: "계속 이어서" },
      }),
      200,
      "이어지는 대화",
    ).json<Turn>();
    expect(
      continued.assistantText.includes(HOME_WORKSPACE.marker),
      `이어지는 대화가 처음 영역의 안내문을 잃었다: ${continued.assistantText}`,
    );

    step("영역을 주지 않으면 안내문 없이 실행한다");
    const withoutWorkspace = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "그냥 물어볼게", agentCode: "dad" },
      }),
      200,
      "영역 없는 대화",
    ).json<Turn>();
    expect(
      !withoutWorkspace.assistantText.includes(HOME_WORKSPACE.marker) &&
        !withoutWorkspace.assistantText.includes(KID_PRIVATE_WORKSPACE.marker),
      `영역을 주지 않았는데 안내문이 실렸다: ${withoutWorkspace.assistantText}`,
    );

    step("남의 개인 영역은 없는 것과 같은 오류로 응답한다");
    const refused = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "몰래 봐야지", workspaceCode: KID_PRIVATE_WORKSPACE.code, agentCode: "dad" },
      }),
      404,
      "dad 가 kid 의 개인 영역을 쓰려는 대화",
    );
    expect(
      refused.json<{ code: string }>().code === "WORKSPACE_NOT_FOUND",
      `기대한 오류 코드가 아니다: ${refused.body}`,
    );
  },
};
