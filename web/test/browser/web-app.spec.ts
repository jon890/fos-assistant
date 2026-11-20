import { expect, test } from "./fixtures.ts";

// 홈 화면에 추가할 때 읽는 웹 앱 설정과 아이콘을 본다(docs/features/attention/spec.md 의 REQ4).
// 화면을 그리지 않는 검사라 넓은 화면에서만 돈다.

test("manifest 는 실행할 때의 앱 이름과 주소창 없는 표시를 담는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "화면 폭과 상관없는 사례다");
  const response = await page.request.get("/manifest.webmanifest");
  expect(response.status()).toBe(200);
  const manifest = (await response.json()) as {
    name: string;
    display: string;
    start_url: string;
    icons: { src: string; sizes: string }[];
  };
  expect(manifest.name).toBe("검사용 비서");
  expect(manifest.display).toBe("standalone");
  expect(manifest.start_url).toBe("/");
  expect(manifest.icons.map((icon) => icon.sizes)).toEqual(["192x192", "512x512"]);
  for (const icon of manifest.icons) {
    const image = await page.request.get(icon.src);
    expect(image.status(), icon.src).toBe(200);
    expect(image.headers()["content-type"]).toBe("image/png");
  }
  expect((await page.request.get("/app-icon/64")).status()).toBe(404);
});

test("화면은 manifest 와 iOS 홈 화면 아이콘을 가리킨다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "화면 폭과 상관없는 사례다");
  await page.goto("/signin");
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute("href", /manifest\.webmanifest/);
  const appleIcon = await page.locator('link[rel="apple-touch-icon"]').getAttribute("href");
  expect(appleIcon).toBeTruthy();
  const image = await page.request.get(appleIcon!);
  expect(image.status()).toBe(200);
  expect(image.headers()["content-type"]).toBe("image/png");
  await expect(page.locator('meta[name="mobile-web-app-capable"]')).toHaveAttribute("content", "yes");
});
