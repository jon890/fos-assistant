import { afterAll, afterEach, beforeAll, expect, test, spyOn } from "bun:test";
import { CdpSession } from "../src/cdp.ts";
import { parseBody } from "../src/draft.ts";
import { filterFocusDiagnostics, focusField } from "../src/editor/focus.ts";
import { open } from "../src/editor/open.ts";
import { closeTab, TITLE_SELECTOR, type EditorPage } from "../src/editor/page.ts";
import { runDraft } from "../src/editor/run.ts";
import { fill } from "../src/editor/text.ts";
import { localChrome } from "./local-chrome.ts";

let browser: Awaited<ReturnType<typeof localChrome>>;
const cleanups: Array<() => Promise<unknown>> = [];
beforeAll(async () => { browser = await localChrome(); console.info("합성 Chrome:", browser.version); }, 15_000);
afterEach(async () => { while (cleanups.length) await cleanups.pop()!(); });
afterAll(async () => { await browser?.stop(); });

async function ready(options = {}, direct = false) {
  const { page, targetId } = await browser.tab(options, direct);
  cleanups.push(async () => { page.close(); await closeTab(browser.url, targetId); });
  expect(await open(page)).toBe("ready");
  page.stage = "fill";
  return page;
}
const focusTitle = (page: EditorPage) => focusField(page, TITLE_SELECTOR, ".se-documentTitle");
const overlay = `const cover = document.createElement('div');
cover.textContent = 'synthetic-private-overlay-marker';
cover.style.cssText = 'position:fixed;inset:0;background:white;z-index:100';
document.body.append(cover);`;
const draft = { title: "합성 제목", body: "합성 본문", category: "", tags: [] };

test("실제 Chrome 준비 판정은 숨겨진 제목이 다시 보일 때까지 기다린다", async () => {
  const { page, targetId } = await browser.tab();
  cleanups.push(async () => { page.close(); await closeTab(browser.url, targetId); });
  await page.js("title.style.display='none';setTimeout(()=>title.style.display='',120)");
  expect(await open(page)).toBe("ready");
});

test("실제 Chrome에서 끝까지 숨겨진 제목은 open에서 거절한다", async () => {
  const { page, targetId } = await browser.tab({ openSeconds: 0.15 });
  cleanups.push(async () => { page.close(); await closeTab(browser.url, targetId); });
  await page.js("document.getElementById('title').style.display='none'");
  const start = browser.calls.length;
  const error = await open(page).catch((error) => error);
  expect(error.message).toBe("글쓰기 화면이 뜨지 않았다");
  expect(browser.calls.slice(start)).not.toContain("Input.insertText");
});

test("제목이 숨겨져도 읽기 경로는 작성 중인 글 알림을 먼저 보존한다", async () => {
  const { page, targetId } = await browser.tab();
  cleanups.push(async () => { page.close(); await closeTab(browser.url, targetId); });
  await page.js(`(() => {
    document.getElementById('title').style.display='none';
    const dim=document.createElement('div');dim.className='popup-dim';
    dim.style.cssText='position:fixed;inset:0';
    const dialog=document.createElement('div');dialog.setAttribute('role','dialog');
    dialog.textContent='작성 중인 글이 있습니다. 이어서 작성하시겠습니까';
    const button=document.createElement('button');button.textContent='취소';
    button.onclick=()=>{window.discarded=true};dialog.append(button);
    document.body.append(dim,dialog);
  })()`);
  const start = browser.calls.length;
  expect(await open(page, { keepDraftNotice: true })).toBe("draft_notice");
  expect(await page.js<boolean>("window.discarded === true")).toBe(false);
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchMouseEvent");
});

for (const direct of [true, false]) for (const scenario of ["replacement", "hidden"] as const) {
  test(`실제 Chrome ${direct ? "직접" : "중계"} 연결의 readiness 직후 ${scenario}는 회복하고 입력을 대조한다`, async () => {
    const page = await ready({}, direct);
    await page.js(scenario === "replacement"
      ? `(() => {const old=document.getElementById('title'), parent=old.parentElement;
          const next=old.cloneNode(true);old.remove();setTimeout(()=>parent.append(next),120)})()`
      : `(() => {const el=document.getElementById('title');el.style.display='none';
          setTimeout(()=>el.style.display='',120)})()`);
    const result = await focusTitle(page);
    expect(result.focused).toBe(true);
    expect(result.diagnostics.attempts).toBeGreaterThan(1);
    expect(result.diagnostics.samples.some((s) => !s.sized)).toBe(true);
    await fill(page, draft.title, parseBody(draft.body), "synthetic-hash");
    expect(await page.js<string>("document.getElementById('title').textContent")).toBe(draft.title);
    expect(await page.js<string>("document.getElementById('body').textContent")).toBe(draft.body);
  });
}

const failures = [
  { name: "끝까지 숨김", script: "document.getElementById('title').style.display='none'", expected: { sized: false } },
  { name: "가리는 overlay", script: overlay, expected: { hit: "other" } },
  { name: "원문 접근을 금지한 DOM", script: `${overlay}
    for(const id of ['title','body']) {
      const el=document.getElementById(id);el.textContent='synthetic-private-field-marker';
      for(const key of ['textContent','innerText','innerHTML','outerHTML','id','className'])
        Object.defineProperty(el,key,{get(){throw new Error('원문 접근 금지')}});
    }`, expected: { hit: "other" } },
  { name: "제목 없음", script: "document.getElementById('title').remove()", expected: { elements: 0 } },
  { name: "편집 불가", script: "document.getElementById('title').contentEditable='false'", expected: { editable: false } },
  { name: "복수 제목", script: "document.getElementById('title').after(document.getElementById('title').cloneNode(true))", expected: { elements: 2 } },
  { name: "클릭 후 본문 커서", script: `document.addEventListener('mouseup',()=>{
    document.getElementById('body').focus();getSelection().collapse(document.getElementById('body'),0)})`, expected: { anchor: "body", active: "body" } },
  { name: "제목 밖으로 이어지는 선택", script: `document.body.contentEditable='true';
    document.getElementById('title').contentEditable='inherit';
    document.addEventListener('mouseup',()=>setTimeout(()=>{
    getSelection().setBaseAndExtent(document.getElementById('title'),0,document.getElementById('outside').firstChild,2)},0))`, expected: { anchor: "title", end: "other", collapsed: false } },
  { name: "제목 밖의 접힌 선택", script: `document.addEventListener('mouseup',()=>{
    getSelection().collapse(document.getElementById('outside').firstChild,1)})`, expected: { anchor: "other", collapsed: true } },
];
for (const scenario of failures) {
  test(`실제 Chrome의 ${scenario.name}는 제목 입력 전에 실패하고 진단만 남긴다`, async () => {
    const page = await ready();
    await page.js(`(() => {${scenario.script}})()`);
    const start = browser.calls.length;
    const error = await fill(page, draft.title, parseBody(draft.body), "synthetic-hash").catch((error) => error);
    expect(error.code).toBe("editor_failed");
    expect(error.stage).toBe("fill");
    expect(error.message).toBe("제목 자리에 커서를 두지 못했다");
    expect(error.extra.focus_diagnostics.attempts).toBe(10);
    expect(error.extra.focus_diagnostics.samples.at(-1)).toMatchObject(scenario.expected);
    const calls = browser.calls.slice(start);
    expect(calls).not.toContain("Input.insertText");
    expect(calls).not.toContain("Input.dispatchKeyEvent");
    expect(calls).not.toContain("Page.setInterceptFileChooserDialog");
    if (["가리는 overlay", "원문 접근을 금지한 DOM", "끝까지 숨김", "제목 없음", "편집 불가", "복수 제목"].includes(scenario.name))
      expect(calls).not.toContain("Input.dispatchMouseEvent");
    const diagnostic = JSON.stringify(error.extra);
    for (const sensitive of [draft.title, draft.body, "synthetic-private", browser.url, "se-documentTitle", "#title"])
      expect(diagnostic).not.toContain(sensitive);
    expect(diagnostic.length).toBeLessThan(2500);
  });
}

test("실제 실행 트리의 제목 실패는 사진과 저장 단계로 가지 않고 자기 탭을 닫는다", async () => {
  const stages: string[] = [], start = browser.calls.length, closed = browser.closed.length;
  const error = await runDraft({ NAVER_BLOG_BROWSER_URL: browser.url, NAVER_BLOG_ID: "synthetic-blog" },
    [{ type: "image", number: 1, file: "private-photo.jpg" }], draft, async (stage) => {
      stages.push(stage);
      if (stage === "fill") await (await browser.latest()).js(`(() => {${overlay}})()`);
    }, new AbortController().signal, { stepSeconds: 0.6, openSeconds: 1 })
    .catch((error) => error);
  expect(error.message).toBe("제목 자리에 커서를 두지 못했다");
  expect(stages).toEqual(["open", "fill"]);
  expect(browser.calls.slice(start)).not.toContain("Input.insertText");
  expect(browser.calls.slice(start)).not.toContain("Page.setInterceptFileChooserDialog");
  expect(browser.closed.length - closed).toBe(1);
});

test("실제 CDP 연결 실패는 커서 실패와 다른 오류이고 원문을 노출하지 않는다", async () => {
  const page = await ready();
  page.close();
  const error = await focusTitle(page).catch((error) => error);
  expect(error.code).toBe("editor_failed");
  expect(error.message).toBe("브라우저 명령이 실패했다: Runtime.evaluate");
  expect(error.extra).toEqual({});
});

test("실제 Chrome에서 끝나지 않는 CDP 평가의 명령 제한은 메서드만 남긴다", async () => {
  const page = await ready();
  const send = CdpSession.prototype.send;
  // 실제 CDP 응답 대기의 상한만 줄인다. 브라우저 결과를 대역으로 바꾸지 않는다.
  const shorter = spyOn(CdpSession.prototype, "send").mockImplementation(function <T>(
    this: CdpSession, method: string, params?: Record<string, unknown>,
  ): Promise<T> {
    return send.call(this, method, params, 50) as Promise<T>;
  });
  try {
    const error = await page.js("new Promise(() => {})").catch((error) => error);
    expect(error.code).toBe("editor_failed");
    expect(error.message).toBe("브라우저 명령이 실패했다: Runtime.evaluate");
    expect(error.extra).toEqual({});
  } finally { shorter.mockRestore(); }
});

test("다른 합성 탭으로 초점을 옮기면 문서 초점이 없는 제목에는 입력하지 않는다", async () => {
  const page = await ready();
  const other = await ready();
  await other.call("Page.bringToFront");
  const start = browser.calls.length;
  const result = await focusTitle(page);
  expect(result.focused).toBe(false);
  expect(result.diagnostics.samples.at(-1)?.page_focused).toBe(false);
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchMouseEvent");
});

test("실제 Chrome 커서 대기 중 중단은 aborted로 끝나고 글자를 넣지 않는다", async () => {
  const controller = new AbortController(), page = await ready({ signal: controller.signal });
  await page.js(`(() => {${overlay}})()`);
  const start = browser.calls.length;
  const timer = setTimeout(() => controller.abort(), 30);
  try {
    const error = await fill(page, draft.title, parseBody(draft.body), "hash").catch((error) => error);
    expect(error.message).toBe("aborted");
    expect(error.code).toBe("editor_failed");
    expect(browser.calls.slice(start)).not.toContain("Input.insertText");
  } finally { clearTimeout(timer); }
});

test("진단 필터는 알려지지 않은 키와 문자열을 버리고 수와 시도 상한을 지킨다", () => {
  const safe = filterFocusDiagnostics({ attempts: 999, url: "sensitive-url", samples: Array(100).fill({
    elements: 999, sized: true, editable: "sensitive-title", hit: "sensitive-class",
    anchor: "title", end: "body", active: "none", x: 42, html: "sensitive-dom",
  }) })!;
  expect(safe.attempts).toBe(10);
  expect(safe.samples).toHaveLength(10);
  expect(safe.samples[0]).toMatchObject({ elements: 100, editable: false, hit: "other" });
  expect(JSON.stringify(safe)).not.toContain("sensitive");
  expect(filterFocusDiagnostics({ attempts: Infinity, samples: [{ elements: NaN }] })!.attempts).toBe(0);
});
