import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:http";
import { join } from "node:path";
import { expect, test } from "./fixtures.ts";
import { AUTH_SECRET, JWT_SECRET } from "./settings.ts";

test.beforeEach(async ({ page }) => {
  // page fixture가 넣는 로그인 쿠키를 페이지 준비가 끝난 뒤 지운다.
  await page.context().clearCookies();
});

test("로그인 없이 앱 이름과 다섯 안내 절을 읽고 가로로 넘치지 않는다", async ({
  page,
}) => {
  const response = await page.goto("/privacy");
  expect(response?.status()).toBe(200);
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "데이터 보관 안내",
  );
  await expect(page.getByText("검사용 비서", { exact: true })).toBeVisible();
  await expect(page.getByRole("navigation")).toHaveCount(0);
  await expect(page.getByRole("button")).toHaveCount(0);
  await expect(page.getByRole("heading", { level: 2 })).toHaveText([
    "서버 관리자의 접근",
    "암호화의 보호 범위",
    "AI에 전달되는 내용",
    "대화 삭제와 남는 기록",
    "외부 서비스 연결 해제",
  ]);
  await expect(
    page.getByText(/암호화하기 전에 저장한 메시지도 평문/),
  ).toBeVisible();
  await expect(
    page.getByText(/서버 관리자가 암호화 키를 설정한 설치/),
  ).toBeVisible();
  await expect(
    page.getByText(/AI 모델 제공사에 요청의 일부로 평문/),
  ).toBeVisible();
  await expect(
    page.getByText(/백업을 언제 버릴지는 서버 관리자/),
  ).toBeVisible();
  const overflow = await page.locator("article").evaluate((article) => {
    const viewport = document.documentElement.clientWidth;
    return [article, ...article.querySelectorAll("*")].some((element) => {
      const rect = element.getBoundingClientRect();
      return (
        rect.left < 0 ||
        rect.right > viewport ||
        element.scrollWidth > element.clientWidth
      );
    });
  });
  expect(overflow).toBe(false);
});

test("로그인 안내 링크와 돌아가기 링크를 키보드로 연다", async ({ page }) => {
  await page.goto("/signin");
  const login = page.getByRole("button", { name: "Google 계정으로 로그인" });
  const privacy = page.getByRole("link", { name: "데이터 보관 안내" });
  await expect(login).toBeVisible();
  await expect(privacy).toHaveAttribute("href", "/privacy");
  await page.keyboard.press("Tab");
  await expect(login).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(privacy).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page.locator("main")).toHaveAttribute("aria-busy", "false");
  // 새 문서에서 시작해 브라우저마다 다른 링크 이동 뒤의 초점 복원을 피한다.
  await page.reload();
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "로그인 화면으로" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/signin$/);
  await expect(login).toBeVisible();
});

test("Control Plane이 응답하지 않아도 안내를 열고 서버와 브라우저에서 API를 부르지 않는다", async ({
  page,
}) => {
  const controlPlaneRequests: string[] = [];
  const browserApiRequests: string[] = [];
  // 실패하는 Control Plane 대역으로 웹 서버의 실제 서버 요청도 센다.
  const controlPlane = createServer((request, response) => {
    controlPlaneRequests.push(request.url ?? "");
    response.writeHead(503).end();
  });
  controlPlane.listen(0, "127.0.0.1");
  await once(controlPlane, "listening");
  const controlAddress = controlPlane.address();
  if (!controlAddress || typeof controlAddress === "string")
    throw new Error("대역 주소가 없어요");
  const portReservation = createServer();
  portReservation.listen(0, "127.0.0.1");
  await once(portReservation, "listening");
  const webAddress = portReservation.address();
  if (!webAddress || typeof webAddress === "string")
    throw new Error("웹 주소가 없어요");
  await new Promise<void>((resolve) => portReservation.close(() => resolve()));
  const webUrl = `http://127.0.0.1:${webAddress.port}`;
  const server = spawn(
    process.execPath,
    [join(import.meta.dirname, "../../web/.next/standalone/server.js")],
    {
      env: {
        ...process.env,
        HOSTNAME: "127.0.0.1",
        PORT: String(webAddress.port),
        AUTH_URL: webUrl,
        AUTH_SECRET,
        AUTH_TRUST_HOST: "true",
        AUTH_GOOGLE_ID: "browser-google-id",
        AUTH_GOOGLE_SECRET: "browser-google-secret",
        ASSISTANT_JWT_SECRET: JWT_SECRET,
        CONTROL_PLANE_BASE_URL: `http://127.0.0.1:${controlAddress.port}`,
        APP_NAME: "독립 검사용 비서",
      },
      stdio: "pipe",
    },
  );
  let output = "";
  server.stdout.on("data", (chunk) => {
    output += String(chunk);
  });
  server.stderr.on("data", (chunk) => {
    output += String(chunk);
  });
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.startsWith("/api/"))
      browserApiRequests.push(request.url());
  });
  try {
    await expect.poll(() => output, { timeout: 15_000 }).toContain("Ready");
    const response = await page.goto(`${webUrl}/privacy`);
    expect(response?.status()).toBe(200);
    await expect(
      page.getByText("독립 검사용 비서", { exact: true }),
    ).toBeVisible();
    await expect(page.getByRole("heading", { level: 2 })).toHaveCount(5);
    await expect(page.locator("main")).toHaveAttribute("aria-busy", "false");
    await page.getByRole("link", { name: "로그인 화면으로" }).click();
    await expect(
      page.getByRole("button", { name: "Google 계정으로 로그인" }),
    ).toBeVisible();
    expect(controlPlaneRequests).toEqual([]);
    expect(browserApiRequests).toEqual([]);
  } finally {
    if (server.exitCode === null && server.signalCode === null) {
      const exited = once(server, "exit");
      server.kill("SIGTERM");
      await exited;
    }
    await new Promise<void>((resolve) => controlPlane.close(() => resolve()));
  }
});
