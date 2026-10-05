import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { CONVERSATION_URL, expect, test } from "./fixtures.ts";

const AGENT_CODE = "browser";
const SKILL_NAME = "proactive-check";
const START_NOTICE = "먼저 살펴보기를 시작했어요";

function section(page: Page) {
  return page.getByRole("region", { name: "먼저 살펴보기", exact: true });
}

function skillMd(): string {
  return `---\nname: ${SKILL_NAME}\ndescription: 살펴볼 것을 정해요\n---\n# 먼저 살펴보기\n\n읽기만 해요.\n`;
}

function reportOutput(): string {
  return `<fos-check-result>\n${JSON.stringify({
    version: 2,
    outcome: "FINDINGS",
    summary: "새 보고",
    findings: [
      {
        area: "study",
        topicKey: "report-test",
        title: "보고 근거",
        sourceUrl: "https://example.com/report",
        checkedAt: new Date().toISOString(),
        freshness: "CURRENT",
        whyItMatters: "검사",
        facts: ["사실"],
        next: { type: "QUESTION", text: "다음" },
      },
    ],
    report: {
      changed: ["바뀐 내용"],
      done: ["한 일"],
      next: ["다음에 볼 내용"],
    },
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

/** 이전 검사가 남긴 점검 대화를 지운다. 같은 대화에 알린 발견이 남으면 첫 결과가 「이미 알린 것」 으로 내려간다. */
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

/** 살펴보기 스킬을 올리고 허용된 도구만 켠다. `extraTools` 는 허용 목록 밖의 도구다. */
async function prepare(page: Page, extraTools: string[] = []) {
  await deleteCheckConversation(page);
  const uploaded = await page.request.put(
    `/api/agents/${AGENT_CODE}/skills/${SKILL_NAME}`,
    {
      data: { skillMd: skillMd(), files: [] },
    },
  );
  if (!uploaded.ok())
    throw new Error(
      `스킬을 올리지 못했다: ${uploaded.status()} ${await uploaded.text()}`,
    );
  await putTools(page, ["skills", ...extraTools]);
}

/** 검사가 바꾼 도구와 스킬을 되돌리고 점검 대화를 지운다. */
async function restore(page: Page) {
  await deleteCheckConversation(page);
  await page.request.delete(`/api/agents/${AGENT_CODE}/skills/${SKILL_NAME}`);
  await putTools(page, []);
  await page.request.put(`/api/agents/${AGENT_CODE}/proactive-check/schedule`, {
    data: { enabled: false, time: "09:00", timezone: "Asia/Seoul" },
  });
}

test("허용되지 않은 도구가 켜져 있으면 까닭을 알리고 단추를 끈다", async ({
  page,
}) => {
  try {
    await prepare(page, ["terminal"]);
    await page.goto(`/agents/${AGENT_CODE}`);

    const check = section(page);
    await expect(
      check.getByRole("button", { name: "지금 살펴보기" }),
    ).toBeDisabled();
    await expect(
      check.getByText("명령 실행 도구가 켜져 있어서 살펴볼 수 없어요.").first(),
    ).toBeVisible();
    await expect(
      check.getByText("위 도구 절에서 꺼 주세요.", { exact: false }).first(),
    ).toBeVisible();
    await expect(check.getByText("점검 대화 열기")).toHaveCount(0);
  } finally {
    await restore(page);
  }
});

test("매일 깨우기는 처음에는 꺼져 있고 시각과 시간대를 저장한다", async ({
  page,
}) => {
  try {
    await prepare(page);
    await page.goto(`/agents/${AGENT_CODE}`);

    const check = section(page);
    const enabled = check.getByRole("switch", { name: "매일 깨우기 사용" });
    await expect(enabled).toHaveAttribute("aria-checked", "false");
    await expect(check.getByLabel("시각", { exact: true })).toHaveValue(
      "09:00",
    );
    await expect(check.getByLabel("시간대", { exact: true })).toHaveValue(
      "Asia/Seoul",
    );

    await enabled.click();
    await check.getByLabel("시각", { exact: true }).fill("08:30");
    await check.getByLabel("시간대", { exact: true }).fill("Asia/Seoul");
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === "PUT" &&
        new URL(response.url()).pathname ===
          `/api/agents/${AGENT_CODE}/proactive-check/schedule`,
    );
    await check.getByRole("button", { name: "저장" }).click();
    expect(
      (await saved).ok(),
      "매일 깨우기 설정을 저장하지 못했다",
    ).toBeTruthy();

    const schedule = await page.request.get(
      `/api/agents/${AGENT_CODE}/proactive-check/schedule`,
    );
    expect(schedule.ok(), "저장한 매일 깨우기 설정을 읽지 못했다").toBeTruthy();
    await expect(enabled).toHaveAttribute("aria-checked", "true");
    expect(await schedule.json()).toMatchObject({
      enabled: true,
      time: "08:30",
      timezone: "Asia/Seoul",
    });
  } finally {
    await restore(page);
  }
});

test("격리 실행 공간이 필요한 도구를 켜면 매일 깨우기 저장을 막는 까닭을 보인다", async ({
  page,
}) => {
  try {
    await prepare(page, ["terminal"]);
    await page.goto(`/agents/${AGENT_CODE}`);

    const check = section(page);
    await expect(
      check.getByText(
        "격리된 실행 공간이 준비되기 전에는 매일 깨우기를 켤 수 없어요.",
      ),
    ).toBeVisible();
    await expect(
      check.getByRole("switch", { name: "매일 깨우기 사용" }),
    ).toBeDisabled();
  } finally {
    await restore(page);
  }
});

test("켜진 깨우기가 도구 변경으로 막혀도 사용자가 끌 수 있다", async ({ page }) => {
  try {
    await prepare(page);
    const enabled = await page.request.put(
      `/api/agents/${AGENT_CODE}/proactive-check/schedule`,
      { data: { enabled: true, time: "08:30", timezone: "Asia/Seoul" } },
    );
    expect(enabled.ok()).toBeTruthy();
    await putTools(page, ["skills", "terminal"]);
    await page.goto(`/agents/${AGENT_CODE}`);

    const check = section(page);
    const toggle = check.getByRole("switch", { name: "매일 깨우기 사용" });
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await expect(toggle).toBeEnabled();
    await toggle.click();
    const saved = page.waitForResponse(
      (response) => response.request().method() === "PUT" &&
        new URL(response.url()).pathname === `/api/agents/${AGENT_CODE}/proactive-check/schedule`,
    );
    await check.getByRole("button", { name: "저장" }).click();
    expect((await saved).ok()).toBeTruthy();
    await expect(toggle).toHaveAttribute("aria-checked", "false");
  } finally {
    await restore(page);
  }
});

test("새 보고는 다섯 칸 카드로 보이고 열면 점검 대화로 간다", async ({
  page,
  hermes,
}) => {
  try {
    await prepare(page);
    await hermes.setProactiveOutput(reportOutput());
    await page.goto(`/agents/${AGENT_CODE}`);
    await section(page).getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page).toHaveURL(CONVERSATION_URL);
    // 시작 응답은 202 다. 보고를 저장하기 전에 지금 화면을 열면 빈 응답을 한 번 읽고 검사가 끝난다.
    await expect
      .poll(async () => {
        const response = await page.request.get(
          `/api/agents/${AGENT_CODE}/proactive-check`,
        );
        expect(response.ok()).toBeTruthy();
        const { lastCheck } = await response.json();
        return `${lastCheck?.status}:${lastCheck?.outcome}`;
      })
      .toBe("SUCCEEDED:FINDINGS");
    await page.goto("/now");

    const card = page.getByTestId("now-card-reports");
    await expect(card.getByTestId("card-now-count")).toHaveCount(0);
    for (const label of [
      "무엇이 바뀌었나",
      "무엇을 했나",
      "근거",
      "남은 승인",
      "다음에 볼 것",
    ]) {
      await expect(card.getByText(label, { exact: true })).toBeVisible();
    }
    const opened = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/proactive-checks\/\d+\/report\/open$/.test(
          new URL(response.url()).pathname,
        ),
    );
    await card.getByRole("button", { name: "보고 열기" }).click();
    expect((await opened).ok()).toBeTruthy();
    await expect(page).toHaveURL(CONVERSATION_URL);
  } finally {
    await restore(page);
  }
});

test("단추 하나로 살펴보기를 시작하고 결과와 배지를 보며 점검 대화에서 다시 시작한다", async ({
  page,
}, testInfo) => {
  try {
    await prepare(page);
    await page.goto(`/agents/${AGENT_CODE}`);

    const check = section(page);
    await expect(check.getByText("도구가 켜져 있어서")).toHaveCount(0);
    await check.getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page).toHaveURL(CONVERSATION_URL);

    // 시작 알림 줄과 결과 답이 이력에 들어온다. 보고와 발견은 같은 검사한 원문을 가리킨다.
    await expect(page.getByText(START_NOTICE)).toHaveCount(1);
    await expect(page.getByText("새로 알릴 것", { exact: true })).toBeVisible();
    const source = page.getByRole("link", { name: "example.com" });
    await expect(source).toHaveCount(2);
    await expect(source.nth(0)).toHaveAttribute(
      "href",
      "https://example.com/e2e/study",
    );
    await expect(source.nth(1)).toHaveAttribute("href", "https://example.com/e2e/study");

    // 대화 목록의 점검 대화 줄에 「살펴보기」 배지가 붙는다.
    const mobile = testInfo.project.name === "mobile";
    if (mobile)
      await page.getByRole("button", { name: "사이드바 열기" }).click();
    const row = page
      .getByRole("navigation", { name: "대화 목록" })
      .getByRole("link")
      .filter({ hasText: "먼저 살펴보기 ·" });
    await expect(row.getByText("살펴보기", { exact: true })).toBeVisible();
    if (mobile)
      await page.getByRole("button", { name: "사이드바 닫기" }).click();

    // 점검 대화의 머리 줄에서 다시 시작한다. 같은 발견은 새로 알릴 것이 아니라 참고로 내려간다.
    await page.getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page.getByText(START_NOTICE)).toHaveCount(2);
    await expect(
      page.getByText("이미 알린 것이에요", { exact: false }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "지금 살펴보기" }),
    ).toBeEnabled();
  } finally {
    await restore(page);
  }
});

test("결과를 읽지 못한 살펴보기는 점검 대화와 마지막 살펴보기 줄에 같은 까닭을 보인다", async ({
  page,
  hermes,
}) => {
  try {
    await prepare(page);
    await page.goto(`/agents/${AGENT_CODE}`);

    // 답이 비었으면 형식 탓으로 말하지 않는다.
    await hermes.setProactiveOutput("");
    await section(page).getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page).toHaveURL(CONVERSATION_URL);
    await expect(
      page.getByText("살펴봤지만 답을 받지 못했어요. 다시 눌러 주세요"),
    ).toBeVisible();
    await page.goto(`/agents/${AGENT_CODE}`);
    await expect(
      section(page).getByText(/^마지막 살펴보기.*답을 받지 못했어요/),
    ).toBeVisible();

    // 블록이 없으면 형식 문제로 알린다.
    await hermes.setProactiveOutput("블록 없이 끝난 답");
    await section(page).getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page).toHaveURL(CONVERSATION_URL);
    await expect(
      page.getByText(
        "살펴봤지만 결과 형식이 맞지 않아 정리하지 못했어요. 다시 눌러 주세요",
      ),
    ).toBeVisible();
    await page.goto(`/agents/${AGENT_CODE}`);
    await expect(
      section(page).getByText(/^마지막 살펴보기.*결과 형식이 맞지 않아 정리하지 못했어요/),
    ).toBeVisible();
  } finally {
    await restore(page);
  }
});
