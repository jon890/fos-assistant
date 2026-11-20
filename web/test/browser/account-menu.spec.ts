import { expect, setSession, test } from "./fixtures.ts";
import { openAccountMenu } from "./helpers.ts";

// 이름 메뉴의 줄과 차례, 밝기 고르기, 키보드 동작을 본다(docs/features/app-shell/spec.md 의 REQ3).
// 역할 읽기 실패는 레이아웃이 서버에서 읽는 값이라 이 테스트에서 만들 수 없다. 그 배치는 verify 가 코드로 본다.

test("ADMIN 의 이름 메뉴는 이름 아래 설정, 관리자 화면, 화면 밝기, 로그아웃 차례다", async ({ page }, testInfo) => {
  await page.goto("/new");
  await openAccountMenu(page, testInfo);
  const menu = page.getByRole("menu");
  const order = [
    menu.getByText("브라우저 테스트", { exact: true }),
    menu.getByRole("menuitem", { name: "설정" }),
    menu.getByRole("menuitem", { name: "관리자 화면" }),
    menu.getByText("화면 밝기", { exact: true }),
    menu.getByRole("menuitemradio", { name: "밝게" }),
    menu.getByRole("menuitem", { name: "로그아웃" }),
  ];
  const tops: number[] = [];
  for (const item of order) {
    await expect(item).toBeVisible();
    tops.push((await item.boundingBox())!.y);
  }
  expect(tops).toEqual([...tops].sort((a, b) => a - b));

  await menu.getByRole("menuitem", { name: "관리자 화면" }).click();
  await expect(page).toHaveURL(/\/admin$/);
});

test("MEMBER 역할의 이름 메뉴에는 관리자 화면이 없다", async ({ context, page }, testInfo) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  await page.goto("/new");
  await openAccountMenu(page, testInfo);
  const menu = page.getByRole("menu");
  await expect(menu.getByText("가족 사용자", { exact: true })).toBeVisible();
  await expect(menu.getByRole("menuitem")).toHaveText(["설정", "로그아웃"]);
  await expect(menu.getByRole("menuitemradio")).toHaveText(["밝게", "어둡게", "기기 설정"]);
});

test("이름 메뉴는 키보드로 열고 고르고 Esc 로 닫는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "키보드 사례는 넓은 화면에서 본다");
  await page.addInitScript(() => localStorage.setItem("theme", "light"));
  await page.goto("/new");
  const trigger = page.getByRole("button", { name: "계정 메뉴" });
  await trigger.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("menu")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu")).toHaveCount(0);
  await expect(trigger).toBeFocused();

  await page.keyboard.press("Enter");
  // 화살표마다 다음 줄에 초점이 간 것을 기다린다. 이어 빠르게 누르면 초점 이동이 따라오지 못해 입력이 빠진다.
  // 이름표와 구분선은 초점을 받지 않는다.
  const steps = [
    page.getByRole("menuitem", { name: "설정" }),
    page.getByRole("menuitem", { name: "관리자 화면" }),
    page.getByRole("menuitemradio", { name: "밝게" }),
  ];
  const dark = page.getByRole("menuitemradio", { name: "어둡게" });
  for (const item of steps) {
    await expect(async () => {
      if (!(await item.evaluate((element) => element === document.activeElement)))
        await page.keyboard.press("ArrowDown");
      await expect(item).toBeFocused({ timeout: 500 });
    }).toPass();
  }
  await page.keyboard.press("ArrowDown");
  await expect(dark).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);
});

test.describe("360px 폭", () => {
  test.use({ hasTouch: true });

  test("서랍 안의 이름 줄에서 메뉴를 열고 손가락으로 밝기를 고르면 새로 고쳐도 남는다", async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 780 });
    await page.addInitScript(() => {
      if (!sessionStorage.getItem("theme-seeded")) {
        localStorage.setItem("theme", "light");
        sessionStorage.setItem("theme-seeded", "1");
      }
    });
    await page.goto("/new");
    await page.getByRole("button", { name: "사이드바 열기" }).tap();
    await page.getByRole("button", { name: "계정 메뉴" }).tap();
    const menu = page.getByRole("menu");
    await expect(menu).toBeVisible();
    // 메뉴가 화면 안에 다 들어온다.
    await expect(menu).toBeInViewport({ ratio: 1 });
    await menu.getByRole("menuitemradio", { name: "어둡게" }).tap();
    await expect(page.locator("html")).toHaveClass(/\bdark\b/);
    await page.reload();
    await expect(page.locator("html")).toHaveClass(/\bdark\b/);
  });
});
