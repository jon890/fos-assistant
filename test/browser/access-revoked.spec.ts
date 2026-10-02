import { expect, setSession, test } from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";
import type {
  BrowserContext,
  Page,
} from "../../web/node_modules/@playwright/test/index.js";

const ADMIN = { email: TEST_EMAIL, name: "브라우저 테스트" };

type Person = { email: string; name: string };

/**
 * 한 번 들어온 적 있는 사람을 만들고 끈다. 끝나면 그 사람의 세션으로 돌아와 있다.
 *
 * <p>더한 사람은 지워지지 않고 폭마다 같은 검사가 한 번씩 돈다. 그래서 검사마다 다른 `slot` 을 쓰고 폭
 * 이름을 주소와 profile 이름에 섞는다. 같은 값을 쓰면 뒤에 도는 쪽이 이미 있는 이메일로 거절된다.
 */
async function revokedPerson(
  context: BrowserContext,
  page: Page,
  slot: string,
  project: string,
): Promise<Person> {
  const person = {
    email: `revoked-${slot}-${project}@example.com`,
    name: `꺼질 사람 ${slot} ${project}`,
  };
  const added = await page.request.post("/api/admin/people", {
    data: {
      email: person.email,
      displayName: person.name,
      hermesProfile: `revoked${slot}${project}`,
    },
  });
  expect(
    added.ok(),
    `사람을 미리 더하지 못했다: ${added.status()} ${await added.text()}`,
  ).toBeTruthy();
  const { id } = (await added.json()) as { id: number };

  // 켜져 있는 동안 한 번 들어와 그 사람의 사용자가 생기게 한다.
  await setSession(context, person);
  await page.goto("/");
  await expect(page).not.toHaveURL(/\/signin$/);

  await setSession(context, ADMIN);
  const disabled = await page.request.patch(`/api/admin/people/${id}`, {
    data: { enabled: false },
  });
  expect(
    disabled.ok(),
    `사람을 끄지 못했다: ${disabled.status()} ${await disabled.text()}`,
  ).toBeTruthy();

  await setSession(context, person);
  return person;
}

/** 세션 쿠키의 이름들이다. 쿠키가 나뉘어 저장돼도 이름에 `session-token` 이 든다. */
async function sessionCookieNames(context: BrowserContext): Promise<string[]> {
  const cookies = await context.cookies();
  return cookies
    .filter((cookie) => cookie.name.includes("session-token"))
    .map((cookie) => cookie.name);
}

test("꺼진 사용자가 화면을 열면 로그인 화면으로 간다", async ({
  context,
  page,
}, testInfo) => {
  await revokedPerson(context, page, "page", testInfo.project.name);

  await page.goto("/agents");

  await expect(page).toHaveURL(/\/signin$/);
  expect(await sessionCookieNames(context)).toEqual([]);
});

test("꺼진 사용자의 API 요청은 401 이고 세션이 지워진다", async ({
  context,
  page,
}, testInfo) => {
  await revokedPerson(context, page, "api", testInfo.project.name);

  const response = await page.request.get("/api/me");

  // 화면을 열기 전에 먼저 본다. 뒤에 보면 API 라우트가 쿠키를 지우지 못해도 화면 쪽 경로가 대신 지워 통과한다.
  expect(await sessionCookieNames(context)).toEqual([]);
  expect(response.status()).toBe(401);
  expect(await response.json()).toMatchObject({ code: "ACCESS_REVOKED" });

  await page.goto("/");
  await expect(page).toHaveURL(/\/signin$/);
});

test("켜져 있는 사용자는 /signout/revoked 를 열어도 로그아웃되지 않는다", async ({
  context,
  page,
}) => {
  await page.goto("/signout/revoked");

  await expect(page).toHaveURL(/\/$/);
  await expect(page).not.toHaveURL(/\/signin$/);
  expect(await sessionCookieNames(context)).not.toEqual([]);
});
