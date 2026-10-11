import { afterAll, afterEach, beforeAll, expect, test } from "bun:test";
import { parseBody } from "../src/draft.ts";
import { open } from "../src/editor/open.ts";
import { filterOpenDiagnostics } from "../src/editor/open-diagnostics.ts";
import { closeTab, type EditorPage } from "../src/editor/page.ts";
import { runDraft } from "../src/editor/run.ts";
import { fill } from "../src/editor/text.ts";
import { localChrome } from "./local-chrome.ts";

let browser: Awaited<ReturnType<typeof localChrome>>;
const cleanups: Array<() => Promise<unknown>> = [];
beforeAll(async () => {
  browser = await localChrome();
}, 15_000);
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});
afterAll(async () => {
  await browser?.stop();
});
const draft = {
  title: "준비 검증 제목",
  body: "준비 검증 본문",
  category: "",
  tags: [],
};

async function tab(openSeconds = 0.3) {
  const { page, targetId } = await browser.tab({ openSeconds });
  cleanups.push(async () => {
    page.close();
    await closeTab(browser.url, targetId);
  });
  return page;
}

for (const before of [true, false])
  for (const style of ["display:none", "opacity:0"]) {
    test(`숨은 제목 clone ${before ? "앞" : "뒤"} ${style}는 실제 제목만 준비하고 입력·판독한다`, async () => {
      const page = await tab();
      await page.js(`(() => { const el=document.getElementById('title'), clone=el.cloneNode(true);
      clone.id='hidden-clone';clone.textContent='private-hidden-clone-marker';
      clone.style.cssText=${JSON.stringify(style)};el.${before ? "before" : "after"}(clone); })()`);
      expect(await open(page)).toBe("ready");
      page.stage = "fill";
      await fill(page, draft.title, parseBody(draft.body), "hash");
      expect(
        await page.js<string>("document.getElementById('title').textContent"),
      ).toBe(draft.title);
      expect(
        await page.js<string>("document.getElementById('body').textContent"),
      ).toBe(draft.body);
      expect(
        await page.js<string>(
          "document.getElementById('hidden-clone').textContent",
        ),
      ).toBe("private-hidden-clone-marker");
    });
  }

for (const scenario of ["inherit", "delayed", "replace"]) {
  test(`실제 Chrome 제목 ${scenario}는 현재 준비 필드를 기다리고 입력한다`, async () => {
    const page = await tab(0.6);
    await page.js(
      scenario === "inherit"
        ? "title.contentEditable='inherit';title.parentElement.contentEditable='true'"
        : scenario === "delayed"
          ? "title.contentEditable='false';setTimeout(()=>title.contentEditable='true',120)"
          : `(() => {const el=document.getElementById('title'), parent=el.parentElement, next=el.cloneNode(true);
        el.remove();setTimeout(()=>parent.append(next),120)})()`,
    );
    expect(await open(page)).toBe("ready");
    page.stage = "fill";
    await fill(page, draft.title, parseBody(draft.body), "hash");
    expect(
      await page.js<string>("document.getElementById('title').textContent"),
    ).toBe(draft.title);
  });
}

const failures = [
  {
    name: "복수 준비",
    script: "title.after(title.cloneNode(true))",
    reason: "title_ambiguous",
    candidates: 2,
    prepared: 2,
  },
  {
    name: "숨은 제목",
    script: "title.style.display='none'",
    reason: "title_unready",
    candidates: 1,
    prepared: 0,
  },
  {
    name: "편집 불가",
    script: "title.contentEditable='false'",
    reason: "title_unready",
    candidates: 1,
    prepared: 0,
  },
  {
    name: "제목 없음",
    script: "title.remove()",
    reason: "title_missing",
    candidates: 0,
    prepared: 0,
  },
  {
    name: "다른 페이지",
    script: "document.title='private-account-page-title'",
    reason: "page_loading",
    candidates: 1,
    prepared: 1,
  },
  {
    name: "실제 iframe 안 제목",
    script: `const frame=document.createElement('iframe');document.body.append(frame);
      const field=document.querySelector('.se-documentTitle');frame.contentDocument.body.append(field);`,
    reason: "frame_only",
    candidates: 0,
    prepared: 0,
  },
  {
    name: "후보 상한 초과",
    script:
      "const el=document.getElementById('title');for(let i=0;i<100;i++)el.after(el.cloneNode(true))",
    reason: "title_ambiguous",
    candidates: 100,
    prepared: 0,
  },
];

for (const scenario of failures) {
  test(`실제 Chrome open의 ${scenario.name}는 입력·저장 전에 구분하여 실패한다`, async () => {
    const page = await tab(0.15);
    await page.js(`(() => {${scenario.script}})()`);
    const start = browser.calls.length;
    const error = await open(page).catch((error) => error);
    expect(error.code).toBe("editor_failed");
    expect(error.stage).toBe("open");
    expect(error.message).toBe("글쓰기 화면이 뜨지 않았다");
    expect(error.extra.open_diagnostics).toMatchObject({
      attempts: 40,
      last: {
        reason: scenario.reason,
        candidates: scenario.candidates,
        prepared: scenario.prepared,
      },
    });
    if (scenario.reason === "frame_only")
      expect(error.extra.open_diagnostics.last).toMatchObject({
        frames: 1,
        frame_candidates: 1,
      });
    const calls = browser.calls.slice(start);
    for (const method of [
      "Input.insertText",
      "Input.dispatchKeyEvent",
      "Input.dispatchMouseEvent",
      "Page.setInterceptFileChooserDialog",
    ])
      expect(calls).not.toContain(method);
    const safe = JSON.stringify(error.extra);
    for (const sensitive of [
      draft.title,
      draft.body,
      "private-",
      browser.url,
      "se-documentTitle",
    ])
      expect(safe).not.toContain(sensitive);
    expect(safe.length).toBeLessThan(1000);
  });
}

test("제목 복제는 ready 뒤에도 다시 판정하고 준비 후보가 늘면 입력을 거절한다", async () => {
  const page = await tab();
  expect(await open(page)).toBe("ready");
  page.stage = "fill";
  await page.js("title.after(title.cloneNode(true))");
  const start = browser.calls.length;
  const error = await fill(
    page,
    draft.title,
    parseBody(draft.body),
    "hash",
  ).catch((error) => error);
  expect(error.message).toBe("제목 자리에 커서를 두지 못했다");
  expect(browser.calls.slice(start)).not.toContain("Input.insertText");
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchMouseEvent");
});

test("준비 진단 필터는 알 수 없는 문자열과 키를 버리고 개수만 제한한다", () => {
  const safe = filterOpenDiagnostics({
    attempts: 999,
    url: "private-url",
    last: {
      reason: "private-reason",
      page: "private-title",
      host: "private-host",
      document_state: "complete",
      candidates: 10000,
      prepared: Infinity,
      frames: -4,
      editable: NaN,
      page_focused: "private-focus",
      title: "private-title",
      html: "private-dom",
    },
  })!;
  expect(safe).toMatchObject({
    attempts: 40,
    last: {
      reason: "page_loading",
      page: "other",
      host: "other",
      candidates: 100,
      prepared: 0,
      frames: 0,
      editable: 0,
      page_focused: false,
    },
  });
  expect(JSON.stringify(safe)).not.toContain("private");
  for (const value of [null, [], {}, { last: [] }, { last: "private-dom" }])
    expect(filterOpenDiagnostics(value)).toBeUndefined();
});

test("제목의 초기 문서 이름과 화면 아래 배치는 기존 대기와 실제 스크롤로 회복한다", async () => {
  const page = await tab(0.6);
  await page.js(`document.title='loading';title.parentElement.style.marginTop='2000px';
    setTimeout(()=>document.title='네이버 블로그',120)`);
  expect(await open(page)).toBe("ready");
  page.stage = "fill";
  await fill(page, draft.title, parseBody(draft.body), "hash");
  expect(
    await page.js<string>("document.getElementById('title').textContent"),
  ).toBe(draft.title);
});

test("로그인 분류는 제목 준비 실패와 구분하고 입력하지 않는다", async () => {
  const page = await tab();
  await page.js("document.title='NAVER 로그인 · private-account-marker'");
  const start = browser.calls.length;
  const error = await open(page).catch((error) => error);
  expect(error.code).toBe("login_required");
  expect(error.extra.open_diagnostics).toMatchObject({
    attempts: 1,
    last: { reason: "login_required", page: "login" },
  });
  expect(JSON.stringify(error.extra)).not.toContain("private");
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchMouseEvent");
});

test("제목이 없어도 읽기 경로는 복구 알림과 자동저장본을 건드리지 않는다", async () => {
  const page = await tab();
  await page.js(`(() => {title.remove();
    const dim=document.createElement('div');dim.className='popup-dim';dim.style.cssText='position:fixed;inset:0';
    const dialog=document.createElement('div');dialog.setAttribute('role','dialog');
    dialog.textContent='작성 중인 글이 있습니다. 이어서 작성하시겠습니까';
    const button=document.createElement('button');button.textContent='취소';button.onclick=()=>window.discarded=true;
    dialog.append(button);document.body.append(dim,dialog);})()`);
  const start = browser.calls.length;
  expect(await open(page, { keepDraftNotice: true })).toBe("draft_notice");
  expect(await page.js<boolean>("window.discarded === true")).toBe(false);
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchMouseEvent");
});

test("입력마다 제목 문단을 교체하는 제어 경합은 과거 필드에 입력하지 않는다", async () => {
  const page = await tab();
  expect(await open(page)).toBe("ready");
  await page.js(`document.addEventListener('mouseup',()=>{
    const el=document.getElementById('title');el.replaceWith(el.cloneNode(true));})`);
  const start = browser.calls.length;
  page.stage = "fill";
  const error = await fill(
    page,
    draft.title,
    parseBody(draft.body),
    "hash",
  ).catch((error) => error);
  expect(error.message).toBe("제목 자리에 커서를 두지 못했다");
  expect(browser.calls.slice(start)).not.toContain("Input.insertText");
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchKeyEvent");
});

test("준비 실패의 구조 관측은 제목과 DOM 원문 속성을 읽지 않는다", async () => {
  const page = await tab(0.15);
  await page.js(`(() => {const el=document.getElementById('title');el.contentEditable='false';
    for(const key of ['textContent','innerText','innerHTML','outerHTML','id','className'])
      Object.defineProperty(el,key,{get(){throw new Error('private-dom-marker')}});})()`);
  const error = await open(page).catch((error) => error);
  expect(error.extra.open_diagnostics.last.reason).toBe("title_unready");
  expect(JSON.stringify(error.extra)).not.toContain("private");
});

test("실제 실행의 open 실패는 fill·사진·저장 전에 끝나고 자기 탭만 닫는다", async () => {
  const stages: string[] = [],
    start = browser.calls.length,
    closed = browser.closed.length;
  const error = await runDraft(
    { NAVER_BLOG_BROWSER_URL: browser.url, NAVER_BLOG_ID: "synthetic-blog" },
    [{ type: "image", number: 1, file: "private-photo.jpg" }],
    draft,
    async (stage) => {
      stages.push(stage);
      if (stage === "open")
        await (
          await browser.latest()
        ).js("document.querySelector('.se-documentTitle').remove()");
    },
    new AbortController().signal,
    { stepSeconds: 0.6, openSeconds: 0.2 },
  ).catch((error) => error);
  expect(error.extra.open_diagnostics.last.reason).toBe("title_missing");
  expect(stages).toEqual(["open"]);
  expect(browser.calls.slice(start)).not.toContain("Input.insertText");
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchMouseEvent");
  expect(browser.closed.length - closed).toBe(1);
});
