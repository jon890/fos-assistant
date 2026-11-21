import { expect, setSession, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";

/** 단추 연결(OAuth)의 연결 화면과 돌아오는 화면(단추 연결 스펙 REQ3-REQ8). Control Plane 응답은 고정한다. */

const ID = "oauth-demo";
const AUTHORIZE = "https://github.com/apps/oauth-demo/installations/new?state=s";
const SETTINGS = "https://github.com/settings/applications";
const INSTALL = "https://github.com/settings/installations";

function connector(oauth: Record<string, unknown> | null) {
  return {
    id: ID,
    title: "검사용 커리어",
    description: "단추 연결 검사용 플러그인입니다.",
    myStatus: "DISCONNECTED",
    available: true,
    bindings: [],
    tools: [],
    fields: [
      {
        key: "repo",
        label: "저장소",
        description: null,
        secret: false,
        required: true,
        pattern: null,
        hasOptions: false,
        autoSelectSingle: false,
      },
      {
        key: "github_token",
        label: "GitHub token",
        description: null,
        secret: true,
        required: true,
        pattern: null,
        hasOptions: false,
        autoSelectSingle: false,
      },
    ],
    oauth,
  };
}

const OAUTH = {
  provider: "github",
  ready: true,
  fields: ["github_token"],
  settingsUrl: SETTINGS,
  installSettingsUrl: INSTALL,
};

const disconnected = {
  connectorId: ID,
  status: "DISCONNECTED",
  secretPrefixes: {},
  values: {},
  checkedAt: null,
  bindings: [],
  undeclaredTools: 0,
  auth: { method: "manual", provider: null, reconnectRequired: false },
  oauthRevoke: null,
};
const connected = {
  ...disconnected,
  status: "READY",
  values: { repo: "me/site" },
  checkedAt: "2026-11-21T01:00:00Z",
  auth: { method: "oauth", provider: "github", reconnectRequired: false },
};

async function serve(
  page: import("@playwright/test").Page,
  oauth: Record<string, unknown> | null,
  connection: Record<string, unknown>,
) {
  await page.route("**/api/connectors", (route) => route.fulfill({ json: [connector(oauth)] }));
  await page.route(`**/api/connections/${ID}`, (route) => route.fulfill({ json: connection }));
}

test("앱이 설정되면 단추가 보이고 단추가 대신하는 칸은 직접 입력 아래 접혀 있으며, 단추는 단추 밖 칸만 보낸다", async ({
  page,
}) => {
  await serve(page, OAUTH, disconnected);
  let started: unknown;
  await page.route(`**/api/connections/${ID}/oauth/start`, (route) => {
    started = route.request().postDataJSON();
    return route.fulfill({ json: { authorizeUrl: AUTHORIZE } });
  });
  await page.route("https://github.com/**", (route) =>
    route.fulfill({ contentType: "text/html", body: "<p>GitHub 대역</p>" }),
  );
  await page.goto(`/connections/${ID}`);

  const button = page.getByRole("button", { name: "GitHub 으로 연결" });
  await expect(button).toBeDisabled();
  await expect(page.getByLabel("GitHub token", { exact: true })).toBeHidden();
  await page.getByLabel("저장소", { exact: true }).fill(" me/site ");
  await button.click();
  await page.waitForURL(AUTHORIZE);
  expect(started).toEqual({ values: { repo: "me/site" } });
});

test("직접 입력을 펼치면 지금처럼 모든 칸을 넣어 연결한다", async ({ page }) => {
  await serve(page, OAUTH, disconnected);
  await page.goto(`/connections/${ID}`);
  await page.getByText("직접 입력", { exact: true }).click();
  await expect(page.getByLabel("GitHub token", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "연결하기" })).toBeDisabled();
  await page.getByLabel("저장소", { exact: true }).fill("me/site");
  await page.getByLabel("GitHub token", { exact: true }).fill("ghp_value");
  await expect(page.getByRole("button", { name: "연결하기" })).toBeEnabled();
});

test("앱이 설정되지 않으면 입력 칸만 보이고 관리자에게만 서버 비밀값 안내를 보인다", async ({
  page,
  context,
  isolatedMember,
}) => {
  await serve(page, { ...OAUTH, ready: false }, disconnected);
  await page.goto(`/connections/${ID}`);
  await expect(page.getByLabel("GitHub token", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "GitHub 으로 연결" })).toHaveCount(0);
  await expect(page.getByText("서버 비밀값에서 GitHub 앱을 설정하면 단추로 연결할 수 있어요.")).toBeVisible();
  await expect(page.getByRole("link", { name: "서버 비밀값 열기" })).toHaveAttribute("href", "/admin/secrets");

  await setSession(context, isolatedMember);
  await page.goto(`/connections/${ID}`);
  await expect(page.getByLabel("GitHub token", { exact: true })).toBeVisible();
  await expect(page.getByText(/서버 비밀값에서 GitHub 앱을 설정하면/)).toHaveCount(0);
});

test("거절하고 돌아오면 연결 화면에 까닭과 다시 시도를 보이고 주소의 결과를 지운다", async ({ page }) => {
  await serve(page, OAUTH, disconnected);
  let completed: unknown;
  await page.route("**/api/connections/oauth/complete", (route) => {
    completed = route.request().postDataJSON();
    return route.fulfill({
      json: { outcome: "DENIED", connectorId: ID, authorizeUrl: null, reason: null },
    });
  });
  await page.goto("/connections/oauth/callback?error=access_denied&state=s");

  await expect(page.getByTestId("oauth-result")).toContainText("연결하지 않았어요");
  await expect(page.getByTestId("oauth-result").getByRole("button", { name: "다시 시도" })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/connections/${ID}$`));
  expect(completed).toEqual({ state: "s", code: null, error: "access_denied", setupAction: null });
});

test("연결하고 돌아오면 연결됨과 단추로 연결함, 저장소 바꾸기 링크를 보인다", async ({ page }) => {
  await serve(page, OAUTH, connected);
  await page.route("**/api/connections/oauth/complete", (route) =>
    route.fulfill({ json: { outcome: "CONNECTED", connectorId: ID, authorizeUrl: null, reason: null } }),
  );
  await page.goto("/connections/oauth/callback?code=c&state=s&setup_action=install");

  await expect(page.getByTestId("oauth-result")).toHaveText("GitHub 으로 연결했어요.");
  await expect(page.getByTestId("connection-status")).toHaveText("연결됨");
  await expect(page.getByTestId("connection-method")).toHaveText("단추로 연결함");
  await expect(page.getByText(/GitHub token:/)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "GitHub 으로 다시 연결" })).toBeVisible();
  await expect(page.getByRole("link", { name: "GitHub 에서 저장소 바꾸기" })).toHaveAttribute("href", INSTALL);
});

test("어느 플러그인인지 알 수 없는 결과는 돌아오는 화면에 까닭을 보인다", async ({ page }) => {
  await page.route("**/api/connections/oauth/complete", (route) =>
    route.fulfill({ json: { outcome: "INVALID_STATE", connectorId: null, authorizeUrl: null, reason: null } }),
  );
  await page.goto("/connections/oauth/callback?code=c&state=forged");
  await expect(page.getByTestId("oauth-callback")).toContainText("연결 요청이 만료됐거나");
  await expect(page.getByRole("link", { name: "외부 서비스 연결로 돌아가기" })).toBeVisible();
});

test("다시 연결이 필요한 연결은 안내와 다시 연결 단추를 보인다", async ({ page }) => {
  await serve(page, OAUTH, {
    ...connected,
    status: "PENDING",
    auth: { method: "oauth", provider: "github", reconnectRequired: true },
  });
  await page.goto(`/connections/${ID}`);
  await expect(page.getByText("GitHub 연결이 끊겼어요. 다시 연결해 주세요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "GitHub 으로 다시 연결" })).toBeVisible();
});

test("단추 연결을 해제하고 허락 취소가 실패하면 직접 거두는 안내와 설정 링크를 보인다", async ({ page }) => {
  await page.route("**/api/connectors", (route) => route.fulfill({ json: [connector(OAUTH)] }));
  await page.route(`**/api/connections/${ID}`, (route) =>
    route.request().method() === "DELETE"
      ? route.fulfill({
          json: {
            ...disconnected,
            oauthRevoke: { status: "failed", settingsUrl: SETTINGS, installSettingsUrl: INSTALL },
          },
        })
      : route.fulfill({ json: connected }),
  );
  await page.goto(`/connections/${ID}`);
  await clickAndWaitForResponse(
    page,
    page.getByRole("button", { name: "연결 해제" }),
    "DELETE",
    new RegExp(`^/api/connections/${ID}$`),
  );
  const notice = page.getByTestId("oauth-revoke");
  await expect(notice).toContainText("GitHub 설정에서 앱 허락을 직접 거둬 주세요.");
  await expect(notice.getByRole("link", { name: "GitHub 설정 열기" })).toHaveAttribute("href", SETTINGS);
  await expect(notice.getByRole("link", { name: "앱 설치 화면 열기" })).toHaveAttribute("href", INSTALL);
  await expect(page.getByTestId("connection-status")).toHaveText("연결 안 됨");
});
