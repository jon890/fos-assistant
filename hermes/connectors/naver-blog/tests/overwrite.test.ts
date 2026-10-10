import { afterEach, expect, test } from "bun:test";
import { draftChanges, type DraftContent, draftRevision } from "../src/changes.ts";
import { EditorError } from "../src/editor/editor-error.ts";
import { type OverwriteInput, type OverwriteStage, runOverwrite } from "../src/editor/overwrite.ts";
import { EDITOR_METHODS, FakeCdp, type FakeDraft, FakeEditor } from "./fake-cdp.ts";

const TIMES = { stepSeconds: 0.1, photoSeconds: 0.3, saveSeconds: 0.3, openSeconds: 0.2 };
const WRITE_URL = "https://blog.naver.com/PostWriteForm.naver?blogId=example-blog";
const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

const paragraph = (value: string) => ({
  id: "SE-p",
  nodes: [{ id: "SE-n", value, "@ctype": "textNode" }],
  "@ctype": "paragraph",
});

const ORIGINAL_ID = "224000000001";
const DRAFTS: FakeDraft[] = [
  {
    logNo: 224000000001,
    title: "가상국수 다녀온 날",
    modiDate: Date.UTC(2026, 9, 1, 3, 0, 0),
    components: [
      { id: "SE-title", "@ctype": "documentTitle", title: [paragraph("가상국수 다녀온 날")] },
      { id: "SE-text-1", "@ctype": "text", value: [paragraph("안녕하세요")] },
      { id: "SE-image-a", "@ctype": "image", src: "https://example.com/a.jpg" },
      { id: "SE-text-2", "@ctype": "text", value: [paragraph("간판부터 정겨운 느낌")] },
      { id: "SE-image-b", "@ctype": "image", src: "https://example.com/b.jpg" },
      { id: "SE-text-3", "@ctype": "text", value: [paragraph("또 올게요")] },
    ],
    category: "가상국수로그",
    tags: ["가상국수", "점심"],
  },
  {
    logNo: 224000000002,
    title: "두 번째 글",
    modiDate: Date.UTC(2026, 8, 1),
    components: [{ "@ctype": "documentTitle", title: [paragraph("두 번째 글")] }],
    category: "일상",
    tags: [],
  },
];

/** `read_draft` 가 원래 글에서 읽는 네 칸. */
const BASE: DraftContent = {
  title: "가상국수 다녀온 날",
  category: "가상국수로그",
  tags: ["가상국수", "점심"],
  body: ["안녕하세요", "[기존 사진 1]", "간판부터 정겨운 느낌", "[기존 사진 2]", "또 올게요"].join("\n"),
};

/** 승인 카드에 보인 지문과 바뀌는 내용을 실은 덮어쓰기 인자. */
const inputFor = (edited: DraftContent): OverwriteInput => ({
  draft_id: ORIGINAL_ID,
  revision: draftRevision(BASE),
  changes: draftChanges(BASE, edited),
  ...edited,
});

test("큰 변경 구간은 verify 에서 거절하고 사본이나 원본을 저장하지 않는다", async () => {
  const { editor, stages, run } = await setup();
  const body = Array(2000).fill("가").join("\n");
  editor.drafts[0]!.components = [
    DRAFTS[0]!.components[0]!,
    { "@ctype": "text", value: Array.from({ length: 2000 }, () => paragraph("가")) },
  ];
  const original = structuredClone(editor.drafts);
  const current = { ...BASE, body };
  const error = await failure(run({
    ...current, draft_id: ORIGINAL_ID, revision: draftRevision(current),
    changes: "큰 변경", body: Array(2000).fill("나").join("\n"),
  }));
  expect(error.code).toBe("editor_failed");
  expect(error.stage).toBe("verify");
  expect(error.message).toContain("나눠 고쳐 주세요");
  expect(stages).toEqual(["open", "load", "verify"]);
  expect(editor.drafts).toEqual(original);
});

async function setup() {
  const cdp = new FakeCdp({ allowed: EDITOR_METHODS });
  cleanups.push(() => cdp.stop());
  const otherTab = cdp.addTarget("https://example.com/other");
  const editor = new FakeEditor();
  // 대역이 저장으로 글 목록을 고치므로 시험마다 복제해 넘긴다.
  editor.drafts = structuredClone(DRAFTS);
  editor.install(cdp);
  const env = { NAVER_BLOG_BROWSER_URL: cdp.url, NAVER_BLOG_ID: "example-blog" };
  const stages: OverwriteStage[] = [];
  const run = (input: OverwriteInput, onStage: (stage: OverwriteStage) => unknown = () => {}) =>
    runOverwrite(
      env,
      input,
      (stage) => {
        stages.push(stage);
        return onStage(stage);
      },
      new AbortController().signal,
      TIMES,
    );
  return { cdp, editor, otherTab, stages, run };
}

/** 연 글쓰기 탭이 모두 닫혔고 미리 열어 둔 탭만 남았는지 본다. */
function expectTabsClosed(cdp: FakeCdp, otherTab: string, opened: number) {
  expect(cdp.opened).toEqual(Array(opened).fill(WRITE_URL));
  expect(cdp.targetIds()).toEqual([otherTab]);
  expect(cdp.violations).toEqual([]);
}

const findDraft = (editor: FakeEditor, id: string) =>
  editor.drafts.find((draft) => String(draft.logNo) === id);
const withoutTitle = (components: unknown[]) =>
  components.filter((component) => (component as Record<string, unknown>)["@ctype"] !== "documentTitle");
const imageIds = (draft: FakeDraft | undefined) =>
  (draft?.components ?? [])
    .map((component) => component as Record<string, unknown>)
    .filter((component) => component["@ctype"] === "image")
    .map((component) => component.id);
const textOf = (draft: FakeDraft | undefined) => JSON.stringify(draft?.components ?? []);

async function failure(promise: Promise<unknown>) {
  const error = await promise.catch((caught) => caught);
  expect(error).toBeInstanceOf(EditorError);
  return error as EditorError;
}

const EDITED: DraftContent = {
  title: "가상국수 다녀온 날",
  category: "일상",
  tags: ["가상국수", "저녁"],
  body: ["안녕하세요", "[기존 사진 2]", "간판부터 정겨운 느낌 그대로", "[기존 사진 1]", "또 올게요"].join("\n"),
};

test("원본 사본을 새 글로 남긴 뒤 같은 글의 본문, 사진 차례, 카테고리, 태그를 고친다", async () => {
  const { cdp, editor, otherTab, stages, run } = await setup();
  let originalAtClick: FakeDraft | undefined;

  const result = await run(inputFor(EDITED), (stage) => {
    if (stage === "save_clicking") originalAtClick = structuredClone(findDraft(editor, ORIGINAL_ID));
  });

  const backup = editor.drafts.find((draft) => !DRAFTS.some((known) => known.logNo === draft.logNo));
  expect(editor.drafts).toHaveLength(3);
  expect(result.backup_draft_id).toBe(String(backup?.logNo));
  expect(result.backup_title).toBe("[덮어쓰기 전 원본] 가상국수 다녀온 날");
  expect(backup?.title).toBe("[덮어쓰기 전 원본] 가상국수 다녀온 날");
  expect(withoutTitle(backup!.components)).toEqual(withoutTitle(DRAFTS[0]!.components));
  expect(backup?.category).toBe("가상국수로그");
  expect(new Set(backup?.tags)).toEqual(new Set(["가상국수", "점심"]));

  // 저장 단추를 누르기 직전까지 원래 글은 그대로다.
  expect(originalAtClick).toEqual(DRAFTS[0]);
  const original = findDraft(editor, ORIGINAL_ID);
  expect(textOf(original)).toContain("간판부터 정겨운 느낌 그대로");
  expect(imageIds(original)).toEqual(["SE-image-b", "SE-image-a"]);
  expect(original?.category).toBe("일상");
  expect(new Set(original?.tags)).toEqual(new Set(["가상국수", "저녁"]));
  expect(original?.modiDate).toBe(DRAFTS[0]!.modiDate + 1000);

  expect(result.draft_id).toBe(ORIGINAL_ID);
  expect(result.saved_before).toBe(3);
  expect(result.saved_after).toBe(result.saved_before);
  // 다시 읽은 본문의 기존 구성요소 번호는 문서 차례로 다시 매겨진다.
  expect(result.state).toEqual({
    revision: draftRevision({
      ...EDITED,
      body: ["안녕하세요", "[기존 사진 1]", "간판부터 정겨운 느낌 그대로", "[기존 사진 2]", "또 올게요"].join("\n"),
    }),
  });
  expect(stages).toEqual(["open", "load", "verify", "backup", "apply", "settings", "save", "save_clicking", "state"]);
  expectTabsClosed(cdp, otherTab, 2);
});

test("목록에 없는 글이면 draft_not_found 로 멈춘다", async () => {
  const { cdp, editor, otherTab, run } = await setup();

  const error = await failure(run({ ...inputFor(EDITED), draft_id: "224000000009" }));

  expect(error.code).toBe("draft_not_found");
  expect(error.stage).toBe("load");
  expect(editor.drafts).toEqual(DRAFTS);
  expectTabsClosed(cdp, otherTab, 1);
});

test("지문이 다르면 draft_changed 로 멈추고 사본을 만들지 않으며 원래 글도 그대로다", async () => {
  const { cdp, editor, otherTab, run } = await setup();

  const error = await failure(
    run({ ...inputFor(EDITED), revision: draftRevision({ ...BASE, title: "읽은 뒤 바뀐 제목" }) }),
  );

  expect(error.code).toBe("draft_changed");
  expect(error.stage).toBe("verify");
  expect(error.extra).toEqual({});
  expect(editor.drafts).toEqual(DRAFTS);
  expectTabsClosed(cdp, otherTab, 1);
});

test("승인한 바뀌는 내용이 다시 만든 것과 다르면 changes_mismatch 로 멈춘다", async () => {
  const { cdp, editor, otherTab, run } = await setup();

  const error = await failure(run({ ...inputFor(EDITED), changes: "본문:\n+ 승인 카드에 없던 줄" }));

  expect(error.code).toBe("changes_mismatch");
  expect(editor.drafts).toEqual(DRAFTS);
  expectTabsClosed(cdp, otherTab, 1);
});

test("원래 글에 없는 기존 구성요소 줄이면 component_not_found 로 멈춘다", async () => {
  const { cdp, editor, otherTab, run } = await setup();

  const error = await failure(run(inputFor({ ...BASE, body: `${BASE.body}\n[기존 지도 1]` })));

  expect(error.code).toBe("component_not_found");
  expect(editor.drafts).toEqual(DRAFTS);
  expectTabsClosed(cdp, otherTab, 1);
});

test("앞 사진의 줄을 빼면 원래 글에 뒤 사진 하나만 남는다", async () => {
  const { cdp, editor, otherTab, run } = await setup();
  const body = ["안녕하세요", "간판부터 정겨운 느낌", "[기존 사진 2]", "또 올게요"].join("\n");

  const result = await run(inputFor({ ...BASE, body }));

  expect(imageIds(findDraft(editor, ORIGINAL_ID))).toEqual(["SE-image-b"]);
  expect(new Set(findDraft(editor, ORIGINAL_ID)?.tags)).toEqual(new Set(BASE.tags));
  expect(result.saved_after).toBe(result.saved_before);
  expectTabsClosed(cdp, otherTab, 2);
});

test("사본이 목록에 생기지 않으면 backup_failed 이고 원래 글은 그대로다", async () => {
  const { cdp, editor, otherTab, run } = await setup();
  editor.saveIncrements = false;

  const error = await failure(run(inputFor(EDITED)));

  expect(error.code).toBe("backup_failed");
  expect(error.stage).toBe("backup");
  expect(error.extra).toEqual({});
  expect(editor.drafts).toEqual(DRAFTS);
  expectTabsClosed(cdp, otherTab, 2);
});

test("사본 저장이 원래 글을 고치면 backup_failed 와 original_changed 로 멈춘다", async () => {
  const { cdp, editor, otherTab, run } = await setup();
  editor.backupUpdatesLoaded = true;

  const error = await failure(run(inputFor(EDITED)));

  expect(error.code).toBe("backup_failed");
  expect(error.extra).toEqual({ original_changed: true });
  expect(editor.drafts).toHaveLength(2);
  // 내용은 원래 그대로다. 제목만 사본 제목으로 바뀌었다.
  expect(withoutTitle(findDraft(editor, ORIGINAL_ID)!.components)).toEqual(withoutTitle(DRAFTS[0]!.components));
  expectTabsClosed(cdp, otherTab, 2);
});

test("저장 단추를 누른 뒤 그 글이 고쳐지지 않으면 save_unconfirmed 이고 사본 번호를 싣는다", async () => {
  const { cdp, editor, otherTab, run } = await setup();
  editor.ignoreUpdate = true;

  const error = await failure(run(inputFor(EDITED)));

  const backup = editor.drafts.find((draft) => !DRAFTS.some((known) => known.logNo === draft.logNo));
  expect(error.code).toBe("save_unconfirmed");
  expect(error.stage).toBe("save");
  expect(error.extra).toEqual({ backup_draft_id: String(backup?.logNo) });
  expect(findDraft(editor, ORIGINAL_ID)).toEqual(DRAFTS[0]);
  expectTabsClosed(cdp, otherTab, 2);
});

test("저장 뒤 목록이 그 글의 고친 시각을 주지 않으면 성공으로 보지 않고 save_unconfirmed 다", async () => {
  const { cdp, editor, otherTab, run } = await setup();
  editor.loseModiDateOnUpdate = true;

  const error = await failure(run(inputFor(EDITED)));

  const backup = editor.drafts.find((draft) => !DRAFTS.some((known) => known.logNo === draft.logNo));
  expect(error.code).toBe("save_unconfirmed");
  expect(error.extra).toEqual({ backup_draft_id: String(backup?.logNo) });
  expectTabsClosed(cdp, otherTab, 2);
});

test("저장 단추 직전 알림이 실패하면 단추를 누르지 않고 원래 글은 그대로다", async () => {
  const { cdp, editor, otherTab, run } = await setup();

  const error = await failure(
    run(inputFor(EDITED), (stage) => {
      if (stage === "save_clicking") throw new Error("작업이 이미 끝났다");
    }),
  );

  expect(error.code).toBe("editor_failed");
  expect(error.stage).toBe("save");
  expect(findDraft(editor, ORIGINAL_ID)).toEqual(DRAFTS[0]);
  expectTabsClosed(cdp, otherTab, 2);
});

test("사본을 만든 뒤 중단되면 같은 코드와 문장에 사본 번호를 더해 멈추고 원래 글은 그대로다", async () => {
  const { cdp, editor, otherTab } = await setup();
  const controller = new AbortController();

  const error = await failure(
    runOverwrite(
      { NAVER_BLOG_BROWSER_URL: cdp.url, NAVER_BLOG_ID: "example-blog" },
      inputFor(EDITED),
      (stage) => {
        if (stage === "apply") controller.abort();
      },
      controller.signal,
      TIMES,
    ),
  );

  const backup = editor.drafts.find((draft) => !DRAFTS.some((known) => known.logNo === draft.logNo));
  expect(error.code).toBe("editor_failed");
  expect(error.message).toBe("aborted");
  expect(error.stage).toBe("apply");
  expect(error.extra).toEqual({ backup_draft_id: String(backup?.logNo) });
  expect(findDraft(editor, ORIGINAL_ID)).toEqual(DRAFTS[0]);
  expectTabsClosed(cdp, otherTab, 2);
});

test("사본을 만든 뒤 편집기 밖의 예외는 editor_failed 와 사본 번호로 바꾼다", async () => {
  const { cdp, editor, otherTab, run } = await setup();

  const error = await failure(
    run(inputFor(EDITED), (stage) => {
      if (stage === "settings") throw new Error("작업 상태를 쓰지 못했다");
    }),
  );

  const backup = editor.drafts.find((draft) => !DRAFTS.some((known) => known.logNo === draft.logNo));
  expect(error.code).toBe("editor_failed");
  expect(error.stage).toBe("settings");
  expect(error.extra).toEqual({ backup_draft_id: String(backup?.logNo) });
  expectTabsClosed(cdp, otherTab, 2);
});
