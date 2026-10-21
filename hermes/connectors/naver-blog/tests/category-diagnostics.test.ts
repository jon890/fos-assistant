import { afterEach, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { listCategories } from "../src/categories.ts";
import { guard, logToolDiagnostic, ToolError } from "../src/errors.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";

const TIMES = {
  stepSeconds: 0.1,
  photoSeconds: 0.3,
  saveSeconds: 0.3,
  openSeconds: 0.2,
};
const BLOG_ID = "example-blog";
const ALLOWED_KEYS = ["tool", "editor_code", "stage", "message", "diagnosis"];
const ALLOWED_DIAGNOSIS_KEYS = [
  "publish_button",
  "covered_by",
  "category_button",
  "category_covered_by",
  "layers",
];
const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function setup() {
  const cdp = new FakeCdp({
    allowed: [...EDITOR_METHODS, "Storage.getCookies"],
  });
  cleanups.push(() => cdp.stop());
  // 로그인 쿠키가 있어야 편집기 단계 실패가 로그인 만료로 읽히지 않는다.
  cdp.on("Storage.getCookies", () => ({
    cookies: [
      { name: "NID_AUT", domain: ".naver.com" },
      { name: "NID_SES", domain: ".naver.com" },
    ],
  }));
  const editor = new FakeEditor();
  editor.install(cdp);
  const base = await mkdtemp(join(tmpdir(), "naver-blog-diagnostics-"));
  cleanups.push(() => rm(base, { recursive: true, force: true }));
  const env = {
    NAVER_BLOG_BROWSER_URL: cdp.url,
    NAVER_BLOG_ID: BLOG_ID,
    NAVER_BLOG_JOB_DIR: join(base, "jobs"),
  };
  return { cdp, editor, env };
}

/** 일을 돌리는 동안 표준 오류에 쓴 줄을 모은다. */
async function captureStderr<T>(work: () => Promise<T>) {
  const lines: string[] = [];
  const original = process.stderr.write;
  process.stderr.write = ((chunk: string | Uint8Array) => {
    lines.push(String(chunk));
    return true;
  }) as typeof process.stderr.write;
  try {
    return { result: await work(), lines };
  } finally {
    process.stderr.write = original;
  }
}

function diagnosticOf(lines: string[]) {
  const found = lines.filter((line) => line.includes("naver_blog_diagnostic"));
  expect(found).toHaveLength(1);
  expect(found[0]!.endsWith("\n")).toBe(true);
  expect(found[0]!.trimEnd().includes("\n")).toBe(false);
  return JSON.parse(found[0]!).naver_blog_diagnostic as Record<string, any>;
}

function expectNoLeak(lines: string[], cdp: FakeCdp) {
  const text = lines.join("");
  expect(text).not.toContain(BLOG_ID);
  expect(text).not.toContain(cdp.url);
  expect(text).not.toMatch(/https?:|localhost|127\.0\.0\.1/);
}

const COVERED = {
  publish_button: "shown",
  covered_by: "div.layer_popup__x1.is_show__y2",
  category_button: "missing",
  category_covered_by: null,
  layers: ["div.layer_popup__x1.is_show__y2"],
};

const STEPS = [
  {
    name: "발행 설정을 열지 못함",
    arrange: (editor: FakeEditor) => {
      editor.publishIgnored = 99;
      editor.publishDiagnosis = COVERED;
    },
    message: "카테고리 목록을 읽을 수 없다",
  },
  {
    name: "카테고리 선택기를 열지 못함",
    arrange: (editor: FakeEditor) => {
      editor.categoryButtonMissing = true;
      editor.publishDiagnosis = { ...COVERED, category_button: "missing" };
    },
    message: "카테고리 선택기를 열지 못했다",
  },
  {
    name: "카테고리 목록이 보이지 않음",
    arrange: (editor: FakeEditor) => {
      editor.categoryListStaysClosed = true;
      editor.publishDiagnosis = { ...COVERED, category_button: "shown" };
    },
    message: "카테고리 목록이 보이지 않는다",
  },
];

for (const step of STEPS) {
  test(`list_categories: ${step.name} 단계를 표준 오류 한 줄로 남기고 모델에게는 EDITOR_CHANGED 를 준다`, async () => {
    const { cdp, editor, env } = await setup();
    step.arrange(editor);

    const { result, lines } = await captureStderr(() =>
      listCategories(env, TIMES).catch((caught) => caught),
    );

    expect(result).toBeInstanceOf(ToolError);
    expect((result as ToolError).code).toBe("NAVER_BLOG_EDITOR_CHANGED");
    const entry = diagnosticOf(lines);
    expect(Object.keys(entry).sort()).toEqual([...ALLOWED_KEYS].sort());
    expect(entry.tool).toBe("list_categories");
    expect(entry.editor_code).toBe("editor_failed");
    expect(entry.stage).toBe("open");
    expect(entry.message).toBe(step.message);
    expect(Object.keys(entry.diagnosis).sort()).toEqual(
      [...ALLOWED_DIAGNOSIS_KEYS].sort(),
    );
    expect(entry.diagnosis.layers).toEqual(COVERED.layers);
    expectNoLeak(lines, cdp);
  });
}

test("모델에게 가는 결과에는 코드만 있고 진단 칸이 없다", async () => {
  const { editor, env } = await setup();
  editor.categoryButtonMissing = true;

  const { result } = await captureStderr(() =>
    guard(() => listCategories(env, TIMES)),
  );

  expect((result as { isError?: boolean }).isError).toBe(true);
  expect(JSON.parse(result.content[0]!.text)).toEqual({
    error: { code: "NAVER_BLOG_EDITOR_CHANGED" },
  });
});

test("진단 줄은 클래스 이름 모양이 아닌 값을 버린다", async () => {
  const { cdp, editor, env } = await setup();
  editor.categoryButtonMissing = true;
  editor.publishDiagnosis = {
    publish_button: `https://blog.naver.com/${BLOG_ID}`,
    covered_by: `${cdp.url}/json`,
    category_button: "shown",
    category_covered_by: "div.layer_x",
    layers: [`div.layer_x`, `https://blog.naver.com/${BLOG_ID}`, "제목 글자"],
    extra_field: BLOG_ID,
  };

  const { lines } = await captureStderr(() =>
    listCategories(env, TIMES).catch(() => null),
  );

  const entry = diagnosticOf(lines);
  expect(entry.diagnosis).toEqual({
    publish_button: null,
    covered_by: null,
    category_button: "shown",
    category_covered_by: "div.layer_x",
    layers: ["div.layer_x"],
  });
  expectNoLeak(lines, cdp);
});

test("주소나 경로가 든 문구는 진단 줄에서 버린다", async () => {
  const { lines } = await captureStderr(async () =>
    logToolDiagnostic(
      "list_categories",
      {
        code: "editor_failed",
        stage: "open",
        message: `열지 못했다 https://blog.naver.com/${BLOG_ID}`,
      },
      true,
    ),
  );

  expect(diagnosticOf(lines).message).toBeNull();
  expect(lines.join("")).not.toContain(BLOG_ID);
});

test("편집기 오류가 아닌 예외는 클래스 이름만 남긴다", async () => {
  const { lines } = await captureStderr(async () =>
    logToolDiagnostic("list_drafts", { name: "RangeError" }, false),
  );

  expect(diagnosticOf(lines)).toEqual({
    tool: "list_drafts",
    error_class: "RangeError",
  });
});

test("진단 스크립트가 빈 값을 주면 EDITOR_CHANGED 가 아니라 UNAVAILABLE 이다", async () => {
  const { editor, env } = await setup();
  editor.categoryButtonMissing = true;
  editor.publishDiagnosis = {};

  const { result } = await captureStderr(() =>
    listCategories(env, TIMES).catch((caught) => caught),
  );

  expect((result as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
});
