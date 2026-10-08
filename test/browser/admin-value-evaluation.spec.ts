import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";

const AGENT_CODE = "browser";
const SKILL_NAME = "proactive-check";
/** Long 범위 안에서 어떤 살펴보기도 갖지 않을 번호다. */
const MISSING_CHECK_ID = 999999999;

function section(page: Page) {
  return page.getByRole("region", { name: "가치 평가", exact: true });
}

function skillMd(): string {
  return `---\nname: ${SKILL_NAME}\ndescription: 살펴볼 것을 정해요\n---\n# 먼저 살펴보기\n\n읽기만 해요.\n`;
}

/** 반복 실행마다 문제 키와 행동 글이 달라야 같은 문제나 같은 할 일로 버려지지 않는다. */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

/** 지금 확인한 발견 하나와 그 주제 키를 근거로 든 문제 후보 하나를 담은 버전 3 결과다. */
function problemOutput(topicKey: string, problem: string, suffix: string) {
  return `<fos-check-result>\n${JSON.stringify({
    version: 3,
    outcome: "FINDINGS",
    summary: "평가할 문제가 있어요",
    findings: [
      {
        area: "study",
        topicKey,
        title: `근거 ${suffix}`,
        sourceUrl: `https://example.com/${suffix}`,
        checkedAt: new Date().toISOString(),
        freshness: "CURRENT",
        whyItMatters: "검사",
        facts: ["사실"],
        next: { type: "QUESTION", text: `다음 ${suffix}` },
      },
    ],
    problemCandidates: [
      {
        problemKey: `study:${suffix}`,
        problem,
        relatedGoal: "다음 분기 면접 준비",
        evidence: [topicKey],
        proposedAction: { type: "ACTION", text: `예제를 돌려 본다 ${suffix}` },
        confidence: "HIGH",
        expectedBenefit: "설계 근거를 말한다",
        sideEffect: "NONE",
      },
    ],
  })}\n</fos-check-result>`;
}

async function putTools(page: Page, enabled: string[]) {
  const response = await page.request.put(
    `/api/admin/agents/${AGENT_CODE}/tools`,
    { data: { enabled } },
  );
  if (!response.ok())
    throw new Error(
      `도구를 바꾸지 못했다: ${response.status()} ${await response.text()}`,
    );
}

/** 이전 검사가 남긴 점검 대화를 지운다. 같은 대화에 알린 발견이 남으면 이번 후보가 중복으로 버려질 수 있다. */
async function deleteCheckConversation(page: Page) {
  const status = await page.request.get(
    `/api/agents/${AGENT_CODE}/proactive-check`,
  );
  if (!status.ok()) return;
  const { conversationId } = (await status.json()) as {
    conversationId: string | null;
  };
  if (conversationId)
    await page.request.delete(`/api/chat/conversations/${conversationId}`);
}

async function prepare(page: Page) {
  await deleteCheckConversation(page);
  const uploaded = await page.request.put(
    `/api/agents/${AGENT_CODE}/skills/${SKILL_NAME}`,
    { data: { skillMd: skillMd(), files: [] } },
  );
  if (!uploaded.ok())
    throw new Error(
      `스킬을 올리지 못했다: ${uploaded.status()} ${await uploaded.text()}`,
    );
  await putTools(page, ["skills"]);
}

/** 검사가 바꾼 도구와 스킬을 되돌리고 점검 대화를 지운다. */
async function restore(page: Page) {
  await deleteCheckConversation(page);
  await page.request.delete(`/api/agents/${AGENT_CODE}/skills/${SKILL_NAME}`);
  await putTools(page, []);
}

type LastCheck = { startedAt: string; status: string; outcome: string | null };

async function lastCheck(page: Page): Promise<LastCheck | null> {
  const response = await page.request.get(
    `/api/agents/${AGENT_CODE}/proactive-check`,
  );
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as { lastCheck: LastCheck | null }).lastCheck;
}

test("받아들인 문제 후보가 있는 살펴보기를 평가하면 축 표와 판정을 읽기 전용으로 보인다", async ({
  page,
  hermes,
}) => {
  const suffix = uniqueSuffix();
  const problem = `재처리 방식을 설명할 근거가 부족하다 ${suffix}`;
  try {
    await prepare(page);
    await hermes.setProactiveOutput(
      problemOutput(`study:${suffix}`, problem, suffix),
    );
    const previous = await lastCheck(page);
    const started = await page.request.post(
      `/api/agents/${AGENT_CODE}/proactive-check/runs`,
    );
    expect(started.status()).toBe(202);
    await expect
      .poll(async () => {
        const current = await lastCheck(page);
        return (
          current !== null &&
          current.startedAt !== previous?.startedAt &&
          current.status === "SUCCEEDED" &&
          current.outcome === "FINDINGS"
        );
      })
      .toBe(true);

    await page.goto(`/admin/agents/${AGENT_CODE}`);
    const evaluation = section(page);
    await expect(evaluation.getByText("받아들인 문제 후보 1개")).toBeVisible();
    // 평가하기 전에는 결과 자리가 없다.
    await expect(evaluation.getByText("이 후보의 판단이 없어요.")).toHaveCount(
      0,
    );

    await clickAndWaitForResponse(
      page,
      evaluation.getByRole("button", { name: "이 살펴보기 평가하기" }),
      "POST",
      /^\/api\/admin\/proactive-checks\/\d+\/value-evaluation-runs$/,
    );
    const expectResult = async () => {
      await expect(
        evaluation.getByText("FALLBACK", { exact: true }),
      ).toBeVisible();
      await expect(
        evaluation.getByText("PROVIDER_UNAVAILABLE", { exact: true }),
      ).toBeVisible();
      await expect(evaluation.getByText(problem)).toBeVisible();
      await expect(
        evaluation.getByText("IGNORE", { exact: true }),
      ).toBeVisible();
      await expect(
        evaluation.getByText("EVALUATION_NOT_USABLE", { exact: true }),
      ).toBeVisible();
      await expect(
        evaluation.getByText("이 후보의 판단이 없어요."),
      ).toBeVisible();
    };
    await expectResult();

    // 화면을 다시 열어도 같은 결과를 서버에서 읽어 온다.
    await page.goto(`/admin/agents/${AGENT_CODE}`);
    await expect(evaluation.getByText("받아들인 문제 후보 1개")).toBeVisible();
    await expectResult();

    // 일반 상세에는 이 절을 그리지 않는다.
    await page.goto(`/agents/${AGENT_CODE}`);
    await expect(
      page.getByRole("region", { name: "먼저 살펴보기", exact: true }),
    ).toBeVisible();
    await expect(section(page)).toHaveCount(0);
  } finally {
    await restore(page);
  }
});

test("살펴보기 번호가 숫자가 아니면 400, 없는 번호면 404 로 거절한다", async ({
  page,
}) => {
  const malformed = await page.request.post(
    "/api/admin/proactive-checks/abc/value-evaluation-runs",
  );
  expect(malformed.status()).toBe(400);
  expect(((await malformed.json()) as { code: string }).code).toBe(
    "VALIDATION_FAILED",
  );

  const missing = await page.request.post(
    `/api/admin/proactive-checks/${MISSING_CHECK_ID}/value-evaluation-runs`,
  );
  expect(missing.status()).toBe(404);
  expect(((await missing.json()) as { code: string }).code).toBe(
    "VALUE_EVALUATION_NOT_FOUND",
  );
});
