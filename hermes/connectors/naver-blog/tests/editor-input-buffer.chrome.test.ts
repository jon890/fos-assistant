import { afterAll, afterEach, beforeAll, expect, test } from "bun:test";
import { parseBody } from "../src/draft.ts";
import { open } from "../src/editor/open.ts";
import {
  closeTab,
  TITLE_SELECTOR,
  focusField,
  type EditorPage,
} from "../src/editor/page.ts";
import { runDraft } from "../src/editor/run.ts";
import { fill } from "../src/editor/text.ts";
import { localChrome } from "./local-chrome.ts";
import { installInputBuffer } from "./input-buffer-fixture.ts";

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
const draft = { title: "프레임 입력 제목", body: "프레임 입력 본문\n둘째 줄" };
async function tab() {
  const { page, targetId } = await browser.tab({ openSeconds: 0.2 });
  cleanups.push(async () => {
    page.close();
    await closeTab(browser.url, targetId);
  });
  await installInputBuffer(page);
  return page;
}

async function activateEditableAncestor(page: EditorPage) {
  await page.js(`(() => {
    document.body.contentEditable='true';
    document.addEventListener('mouseup',()=>{
      document.body.focus();getSelection().collapse(document.getElementById('title'),0);
    });
  })()`);
}

test("buffer가 준비돼도 비편집 제목의 editable 조상 초점은 직접 편집으로 받지 않는다", async () => {
  const page = await tab();
  await activateEditableAncestor(page);
  expect(await open(page)).toBe("ready");
  const result = await focusField(page, TITLE_SELECTOR, ".se-documentTitle");
  expect(
    await page.js<boolean>("document.activeElement === document.body"),
  ).toBe(true);
  expect(
    await page.js<boolean>(
      "document.body.isContentEditable && !title.isContentEditable",
    ),
  ).toBe(true);
  expect(result.diagnostics.samples.at(-1)).toMatchObject({
    editable: true,
    anchor: "title",
    end: "title",
    collapsed: true,
    input_frame: false,
  });
  expect(result.focused).toBe(false);
});

test("비편집 제목의 editable 조상 초점은 실행에서 글 입력·키 입력·저장을 하지 않는다", async () => {
  const stages: string[] = [],
    start = browser.calls.length;
  const error = await runDraft(
    { NAVER_BLOG_BROWSER_URL: browser.url, NAVER_BLOG_ID: "synthetic-blog" },
    parseBody(draft.body),
    { ...draft, category: "", tags: [] },
    async (stage) => {
      stages.push(stage);
      if (stage === "fill") {
        const page = await browser.latest();
        await installInputBuffer(page);
        await activateEditableAncestor(page);
      }
    },
    new AbortController().signal,
    { stepSeconds: 0.6, openSeconds: 1 },
  ).catch((error) => error);
  expect(error.message).toBe("제목 자리에 커서를 두지 못했다");
  expect(stages).toEqual(["open", "fill"]);
  expect(browser.calls.slice(start)).not.toContain("Input.insertText");
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchKeyEvent");
});

test("비편집 모델 문단과 별도 입력 프레임은 실제 pointer·selection·CDP 입력으로 제목과 본문을 대조한다", async () => {
  const page = await tab();
  expect(await page.js<boolean>("title.isContentEditable")).toBe(false);
  expect(await open(page)).toBe("ready");
  const focused = await focusField(page, TITLE_SELECTOR, ".se-documentTitle");
  expect(focused.focused).toBe(true);
  expect(focused.diagnostics.samples.at(-1)?.input_frame).toBe(true);
  page.stage = "fill";
  await fill(page, draft.title, parseBody(draft.body), "hash");
  expect(
    await page.js<string>("document.getElementById('title').textContent"),
  ).toBe(draft.title);
  expect(
    await page.js<string>(
      "[...document.querySelectorAll('.se-component.se-text .se-text-paragraph')].map(e=>e.textContent).join('\\n')",
    ),
  ).toBe(draft.body);
  expect(await page.js<string>("document.activeElement.tagName")).toBe(
    "IFRAME",
  );
});

const openFailures = [
  ["다른 iframe", "document.querySelector('iframe').id='unrelated_frame'"],
  [
    "잘못된 buffer 이름",
    "document.querySelector('iframe').id='input_buffer_unrelated'",
  ],
  [
    "복수 입력 frame",
    "document.querySelector('iframe').after(document.querySelector('iframe').cloneNode(true))",
  ],
  ["editor 없음", "window.SmartEditor._editors={}"],
  [
    "문서 계약 없음",
    "window.SmartEditor._editors.synthetic.getDocumentData=null",
  ],
  [
    "복수 editor",
    "window.SmartEditor._editors.second=window.SmartEditor._editors.synthetic",
  ],
  [
    "편집 불가 frame body",
    "document.querySelector('iframe').contentDocument.body.contentEditable='false'",
  ],
  [
    "cross origin frame",
    "document.querySelector('iframe').src='data:text/html,<body contenteditable=true>'",
  ],
] as const;
for (const [name, script] of openFailures) {
  test(`별도 buffer의 ${name}는 준비 단계에서 거절한다`, async () => {
    const page = await tab();
    await page.js(script);
    if (name === "cross origin frame")
      await page.waitUntil(() =>
        page.js<boolean>(
          "document.querySelector('iframe').contentDocument === null",
        ),
      );
    const start = browser.calls.length;
    const error = await open(page).catch((error) => error);
    expect(error.code).toBe("editor_failed");
    expect(error.extra.open_diagnostics.last.reason).toBe("title_unready");
    expect(browser.calls.slice(start)).not.toContain(
      "Input.dispatchMouseEvent",
    );
    expect(browser.calls.slice(start)).not.toContain("Input.insertText");
  });
}

const focusFailures = [
  [
    "본문 selection 경합",
    "getSelection().collapse(document.getElementById('body'),0)",
  ],
  [
    "확장된 selection",
    "getSelection().setBaseAndExtent(title,0,document.getElementById('body'),0)",
  ],
  ["selection 없음", "getSelection().removeAllRanges()"],
  [
    "다른 active frame",
    "document.querySelector('iframe:not([id])').contentDocument.body.focus()",
  ],
  [
    "내부 다른 active 요소",
    `const doc=document.querySelector('iframe').contentDocument;
    const el=doc.createElement('div');el.contentEditable='true';el.tabIndex=0;doc.body.append(el);el.focus()`,
  ],
  [
    "내부 확장 selection",
    `const doc=document.querySelector('iframe').contentDocument;
    doc.body.textContent='private-input-marker';const s=doc.getSelection();s.selectAllChildren(doc.body)`,
  ],
] as const;
for (const [name, script] of focusFailures) {
  test(`별도 buffer의 ${name}는 제목 입력을 거절한다`, async () => {
    const page = await tab();
    expect(await open(page)).toBe("ready");
    await page.js(`(() => {
      const other=document.createElement('iframe');document.body.append(other);other.contentDocument.body.contentEditable='true';
      document.addEventListener('mouseup',()=>{${script}});
    })()`);
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
    expect(JSON.stringify(error.extra)).not.toContain("private-input");
    if (name === "내부 다른 active 요소")
      expect(
        await page.js<string>(
          "document.querySelector('iframe').contentDocument.activeElement.tagName",
        ),
      ).toBe("DIV");
  });
}

test("model 제목의 숨은 복제와 ready 뒤 바뀐 input buffer를 각각 판정한다", async () => {
  const page = await tab();
  await page.js(`(() => {const el=document.getElementById('title'), copy=el.cloneNode(true);
    copy.id='hidden-model-copy';copy.style.display='none';el.before(copy);})()`);
  expect(await open(page)).toBe("ready");
  await page.js(
    "document.querySelector('iframe').replaceWith(document.querySelector('iframe').cloneNode(true))",
  );
  const start = browser.calls.length;
  page.stage = "fill";
  const error = await fill(
    page,
    draft.title,
    parseBody(draft.body),
    "hash",
  ).catch((error) => error);
  expect(error.message).toBe("제목 자리에 커서를 두지 못했다");
  expect(browser.calls.slice(start)).not.toContain("Input.dispatchMouseEvent");
  expect(browser.calls.slice(start)).not.toContain("Input.insertText");
});
