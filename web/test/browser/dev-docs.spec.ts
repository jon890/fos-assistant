import type { Page, TestInfo } from "../../node_modules/@playwright/test/index.js";
import {
  DEV_DOCS_COMMIT,
  FLOW_FILL,
  KOREAN_ADR_TITLE,
  OTHER_TITLE,
  ROADMAP_ITEM,
  SAMPLE_SECTION,
  SAMPLE_SPEC_TITLE,
  SAMPLE_TITLE,
} from "./dev-docs-fixture.ts";
import { expect, setSession, test } from "./fixtures.ts";
import { openAccountMenu } from "./helpers.ts";

const REPO = "https://github.com/jon890/fos-assistant";

function catalog(page: Page) {
  return page.getByRole("navigation", { name: "문서 목록" });
}

function article(page: Page) {
  return page.locator("article");
}

test("이름 메뉴의 설정에서 「개발 문서」 로 로드맵과 커밋이 보이고 고치는 단추가 없다", async ({ page }, testInfo) => {
  await page.goto("/new");
  await openAccountMenu(page, testInfo);
  await page.getByRole("menuitem", { name: "설정" }).click();
  await expect(page).toHaveURL(/\/settings$/);
  await page
    .getByRole("navigation", { name: "설정 메뉴" })
    .getByRole("link", { name: "개발 문서", exact: true })
    .click();
  await expect(page).toHaveURL(/\/dev-docs$/);

  await expect(article(page).getByRole("heading", { name: "로드맵" })).toBeVisible();
  await expect(article(page).getByRole("cell", { name: ROADMAP_ITEM })).toBeVisible();
  await expect(article(page).getByText("로드맵이 아닌 절")).toHaveCount(0);

  const commit = page.getByRole("link", { name: DEV_DOCS_COMMIT.slice(0, 7), exact: true });
  await expect(commit).toHaveAttribute("href", `${REPO}/commit/${DEV_DOCS_COMMIT}`);
  await expect(page.getByRole("button", { name: /고치기|편집/ })).toHaveCount(0);

  // 로드맵 표 안 링크는 화면 안 문서로 간다.
  await article(page).getByRole("link", { name: "견본", exact: true }).click();
  await expect(page).toHaveURL(/\/dev-docs\/docs\/features\/sample\.md$/);
  await expect(article(page).getByRole("heading", { name: SAMPLE_TITLE, level: 1 })).toBeVisible();
  await expect(page.getByRole("link", { name: "GitHub 에서 보기" })).toHaveAttribute(
    "href",
    `${REPO}/blob/${DEV_DOCS_COMMIT}/docs/features/sample.md`,
  );
});

test("MEMBER 역할도 개발 문서를 열고 로그인하지 않으면 로그인 화면으로 간다", async ({ context, page, isolatedMember }) => {
  await setSession(context, isolatedMember);
  await page.goto("/dev-docs");
  await expect(article(page).getByRole("cell", { name: ROADMAP_ITEM })).toBeVisible();

  await context.clearCookies();
  await page.goto("/dev-docs");
  await expect(page).toHaveURL(/\/signin/);
});

test("찾기는 제목에 맞는 줄만 남기고 스펙 제목만 맞으면 기능 줄을 펼친다", async ({ page }) => {
  await page.goto("/dev-docs");
  const search = catalog(page).getByRole("searchbox", { name: "문서 찾기" });

  await search.fill("다른");
  await expect(catalog(page).getByRole("link", { name: OTHER_TITLE })).toBeVisible();
  await expect(catalog(page).getByRole("link", { name: SAMPLE_TITLE })).toHaveCount(0);

  await search.fill("스펙 문서");
  await expect(catalog(page).getByRole("link", { name: SAMPLE_TITLE })).toBeVisible();
  await expect(catalog(page).getByRole("link", { name: SAMPLE_SPEC_TITLE })).toBeVisible();

  await search.fill("기억은 주입");
  await expect(catalog(page).getByRole("link", { name: new RegExp(KOREAN_ADR_TITLE) })).toBeVisible();

  await search.fill("맞지 않는 글자");
  await expect(catalog(page).getByText("맞는 문서가 없어요")).toBeVisible();
});

test("본문의 HTML 은 돌지 않고 링크는 화면 안, 같은 커밋 GitHub, 한글 절로 간다", async ({ page }) => {
  await page.goto("/dev-docs/docs/features/other.md");
  await expect(article(page).getByRole("heading", { name: OTHER_TITLE, level: 1 })).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { __devDocsXss?: number }).__devDocsXss)).toBeUndefined();
  // 본문의 HTML 은 요소로 만들어지지 않는다. 이벤트 속성 탐침도 돌지 않는다.
  await expect(article(page).locator("script, img")).toHaveCount(0);
  await expect(article(page).getByRole("link", { name: "막힌 링크" })).toHaveCount(0);
  await expect(article(page).getByText("막힌 링크")).toBeVisible();

  const code = article(page).getByRole("link", { name: "코드 파일" });
  await expect(code).toHaveAttribute("href", `${REPO}/blob/${DEV_DOCS_COMMIT}/web/src/app/layout.tsx`);
  await expect(code).toHaveAttribute("target", "_blank");
  const picture = article(page).getByRole("link", { name: "그림: 구조 그림" });
  await expect(picture).toHaveAttribute("href", `${REPO}/blob/${DEV_DOCS_COMMIT}/docs/images/x.png`);
  await expect(article(page).locator("img")).toHaveCount(0);

  await article(page).getByRole("link", { name: "절로 가기" }).click();
  await expect(page).toHaveURL(/\/dev-docs\/docs\/features\/sample\.md#/);
  await expect(article(page).getByRole("heading", { name: SAMPLE_SECTION })).toBeInViewport();

  const missing = await page.request.get("/dev-docs/docs/nope.md");
  expect(missing.status()).toBe(404);
});

test("mermaid 는 flowchart 를 손그림과 classDef 색으로, sequence 를 기본 모양으로 그리고 틀린 블록만 원문을 남긴다", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("theme", "dark"));
  await page.goto("/dev-docs/docs/features/other.md");
  const diagrams = article(page).getByTestId("mermaid-diagram");
  await expect(diagrams).toHaveCount(2);

  const flow = diagrams.nth(0);
  await expect(flow.locator("svg")).toBeVisible();
  // 손그림 노드는 mermaid 가 data-look="handDrawn" 과 rough-node 로 그린다.
  await expect(flow.locator('svg .rough-node[data-look="handDrawn"]')).toHaveCount(2);
  // 손그림은 classDef 의 fill 을 그 색의 빗금 선(stroke)으로 칠한다.
  const strokes = await flow
    .locator("svg .rough-node.warm path")
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute("stroke")?.toLowerCase()));
  expect(strokes).toContain(FLOW_FILL);

  await expect(diagrams.nth(1).locator("svg")).toBeVisible();
  // 어두운 테마여도 흰 바탕이다. 테마 전환 애니메이션이 끝날 때까지 다시 본다.
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);
  await expect(flow).toHaveCSS("background-color", "rgb(255, 255, 255)");

  await expect(article(page).getByText("그림을 그리지 못했어요")).toBeVisible();
  await expect(article(page).getByText("A[[[ 틀린 문법")).toBeVisible();
});
