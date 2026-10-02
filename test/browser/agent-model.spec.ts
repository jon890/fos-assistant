import {
  expect,
  PERSONA_EMPTY_AGENT_CODE,
  setSession,
  test,
} from "./fixtures.ts";
import type {
  Locator,
  Page,
} from "../../web/node_modules/@playwright/test/index.js";

/** 다른 검사가 대화를 보내지 않는 에이전트다. 기본 모델을 바꿔도 다른 검사의 실행에 닿지 않는다. */
const AGENT_CODE = PERSONA_EMPTY_AGENT_CODE;

/** 대역 catalog 에 있는 모델이다. 다른 검사가 실제 실행에 쓰지 않는 것을 숨긴다. */
const DEFAULT_MODEL = "example-model-mini";
const HIDDEN_MODEL = "example-deep";

/** 응답을 바꿔 끼우는 검사가 profile 의 기본 모델로 쓰는 이름이다. */
const PROFILE_DEFAULT_MODEL = "example-model";

function section(page: Page): Locator {
  return page.getByTestId("agent-model-section");
}

async function reset(page: Page): Promise<void> {
  const hidden = await page.request.put("/api/admin/model-hidden", {
    data: { entries: [] },
  });
  expect(hidden.ok(), `숨김을 비우지 못했다: ${hidden.status()}`).toBeTruthy();
  const cleared = await page.request.put(
    `/api/admin/agents/${AGENT_CODE}/model-default`,
    { data: { provider: null, model: null, reasoningEffort: null } },
  );
  expect(
    cleared.ok(),
    `기본 모델을 비우지 못했다: ${cleared.status()}`,
  ).toBeTruthy();
}

type Options = {
  defaultModel: string | null;
  defaultFromAgent: boolean;
  providers: { models: string[] }[];
};

async function modelOptions(page: Page): Promise<Options> {
  const response = await page.request.get(
    `/api/chat/model-options?agentCode=${AGENT_CODE}`,
  );
  expect(response.ok()).toBeTruthy();
  return (await response.json()) as Options;
}

test("관리자가 에이전트 기본 모델을 저장하면 다시 열어도 남고 대화의 기본 모델이 그 값이 된다", async ({
  page,
}) => {
  await reset(page);
  try {
    await page.goto(`/agents/${AGENT_CODE}`);
    const model = section(page).getByRole("combobox", { name: "모델" });
    await expect(model).toHaveValue("");
    await model.selectOption({ label: DEFAULT_MODEL });
    await section(page)
      .getByRole("combobox", { name: "강도" })
      .selectOption("high");
    await section(page).getByRole("button", { name: "기본 모델 저장" }).click();
    await expect(section(page).getByRole("status")).toHaveText(
      "기본 모델을 저장했어요.",
    );

    await page.reload();
    await expect(
      section(page).getByRole("combobox", { name: "모델" }),
    ).toHaveValue(JSON.stringify(["openai-codex", DEFAULT_MODEL]));
    await expect(
      section(page).getByRole("combobox", { name: "강도" }),
    ).toHaveValue("high");
    const options = await modelOptions(page);
    expect(options.defaultModel).toBe(DEFAULT_MODEL);
    expect(options.defaultFromAgent).toBe(true);
  } finally {
    await reset(page);
  }
});

test("관리자가 모델을 숨기면 고를 수 있는 목록에서 빠지고 숨긴 모델은 기본 모델로 저장하지 못한다", async ({
  page,
}) => {
  await reset(page);
  try {
    await page.goto(`/agents/${AGENT_CODE}`);
    const save = section(page).getByRole("button", { name: "숨김 저장" });
    await expect(save).toBeDisabled();
    await section(page)
      .getByRole("checkbox", { name: `${HIDDEN_MODEL} 숨기기` })
      .check();
    await save.click();
    await expect(section(page).getByRole("status")).toHaveText(
      "숨김 설정을 저장했어요.",
    );

    const options = await modelOptions(page);
    expect(options.providers[0]?.models).not.toContain(HIDDEN_MODEL);
    expect(options.providers[0]?.models).toContain(DEFAULT_MODEL);
    await expect(
      section(page)
        .getByRole("combobox", { name: "모델" })
        .getByRole("option", { name: `${HIDDEN_MODEL} (숨김)` }),
    ).toBeDisabled();
    const rejected = await page.request.put(
      `/api/admin/agents/${AGENT_CODE}/model-default`,
      {
        data: {
          provider: "openai-codex",
          model: HIDDEN_MODEL,
          reasoningEffort: null,
        },
      },
    );
    expect(rejected.status()).toBe(409);
    expect(((await rejected.json()) as { code: string }).code).toBe(
      "MODEL_HIDDEN",
    );

    await page.reload();
    await expect(
      section(page).getByRole("checkbox", { name: `${HIDDEN_MODEL} 숨기기` }),
    ).toBeChecked();
  } finally {
    await reset(page);
  }
});

test("관리자가 아닌 사용자는 모델 설정을 읽거나 바꾸지 못한다", async ({
  context,
  page,
}) => {
  await setSession(context, {
    email: "member@example.com",
    name: "가족 사용자",
  });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();

  const read = await page.request.get(
    `/api/admin/agents/${AGENT_CODE}/model-settings`,
  );
  expect(read.status()).toBe(403);
  const changedDefault = await page.request.put(
    `/api/admin/agents/${AGENT_CODE}/model-default`,
    { data: { provider: null, model: null, reasoningEffort: null } },
  );
  expect(changedDefault.status()).toBe(403);
  const hiddenList = await page.request.get("/api/admin/model-hidden");
  expect(hiddenList.status()).toBe(403);
  const changedHidden = await page.request.put("/api/admin/model-hidden", {
    data: { entries: [] },
  });
  expect(changedHidden.status()).toBe(403);
});

test("모델 목록을 읽지 못해도 관리자가 숨김을 풀 수 있다", async ({ page }) => {
  await reset(page);
  const saved = await page.request.put("/api/admin/model-hidden", {
    data: { entries: [{ provider: "openai-codex", model: HIDDEN_MODEL }] },
  });
  expect(saved.ok()).toBeTruthy();
  try {
    await page.route(
      (url) =>
        url.pathname === `/api/admin/agents/${AGENT_CODE}/model-settings`,
      (route) =>
        route.fulfill({
          json: {
            agentDefault: {
              provider: null,
              model: null,
              reasoningEffort: null,
            },
            catalog: null,
            hidden: {
              entries: [{ provider: "openai-codex", model: HIDDEN_MODEL }],
            },
          },
        }),
    );
    await page.goto(`/agents/${AGENT_CODE}`);
    await expect(
      section(page).getByTestId("agent-model-catalog-missing"),
    ).toBeVisible();
    await section(page)
      .getByRole("checkbox", { name: `openai-codex ${HIDDEN_MODEL} 숨기기` })
      // 체크를 풀면 그 줄이 사라진다. uncheck 는 사라진 요소의 상태를 확인하려다 멈추므로 누르기만 한다.
      .click();
    await section(page).getByRole("button", { name: "숨김 저장" }).click();
    await expect(section(page).getByRole("status")).toHaveText(
      "숨김 설정을 저장했어요.",
    );
    const hidden = await page.request.get("/api/admin/model-hidden");
    expect(((await hidden.json()) as { entries: unknown[] }).entries).toEqual(
      [],
    );
  } finally {
    await reset(page);
  }
});

/** 실제로 숨기면 그 그룹의 기본값 실행이 막혀 다른 검사가 깨진다. 그래서 응답만 바꿔 끼운다. */
async function routeSettings(
  page: Page,
  hiddenEntries: { provider: string; model: string | null }[],
): Promise<void> {
  await page.route(
    (url) => url.pathname === `/api/admin/agents/${AGENT_CODE}/model-settings`,
    (route) =>
      route.fulfill({
        json: {
          agentDefault: { provider: null, model: null, reasoningEffort: null },
          catalog: {
            defaultProvider: "openai-codex",
            defaultModel: PROFILE_DEFAULT_MODEL,
            providers: [
              {
                provider: "openai-codex",
                name: "openai-codex",
                models: [PROFILE_DEFAULT_MODEL, DEFAULT_MODEL],
              },
            ],
            reasoningEfforts: ["low", "medium", "high", "xhigh", "max"],
          },
          hidden: { entries: hiddenEntries },
        },
      }),
  );
}

test("기본 모델을 정하지 않은 에이전트의 profile 기본 모델이 숨겨져 있으면 경고를 보인다", async ({
  page,
}) => {
  await routeSettings(page, [
    { provider: "openai-codex", model: PROFILE_DEFAULT_MODEL },
  ]);
  await page.goto(`/agents/${AGENT_CODE}`);
  await expect(
    section(page).getByTestId("agent-model-profile-default-hidden"),
  ).toHaveText(
    "이 에이전트는 기본 모델을 정하지 않았는데 profile 의 기본 모델이 숨겨져 있어요. 기본 모델을 정하거나 숨김을 풀기 전에는 모델을 고르지 않은 대화가 실패해요.",
  );
});

test("숨김이 비어 있으면 profile 기본 모델 경고를 보이지 않는다", async ({
  page,
}) => {
  await routeSettings(page, []);
  await page.goto(`/agents/${AGENT_CODE}`);
  await expect(
    section(page).getByRole("combobox", { name: "모델" }),
  ).toBeVisible();
  await expect(
    section(page).getByTestId("agent-model-profile-default-hidden"),
  ).toHaveCount(0);
});
