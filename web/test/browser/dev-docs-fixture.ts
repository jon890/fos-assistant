import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

/**
 * 브라우저 테스트의 「개발 문서」 가짜 트리다. 실제 저장소 문서는 자주 바뀌어 쓰지 않는다.
 * `docs/` 밖에 Markdown 파일을 둘 수 없어(test/repo/doc-files.test.ts) 문자열로 두고 서버를 띄우기 전에 임시 폴더에 쓴다.
 */
export const DEV_DOCS_COMMIT = "0123456789abcdef0123456789abcdef01234567";
export const ROADMAP_ITEM = "견본 로드맵 항목";
export const SAMPLE_TITLE = "견본 기능";
export const SAMPLE_SPEC_TITLE = "견본 스펙 문서";
export const OTHER_TITLE = "다른 기능";
export const SAMPLE_SECTION = "사람이 도와야 하는 지점";
export const KOREAN_ADR_TITLE = "기억은 주입으로 강제한다";
export const FLOW_FILL = "#fdecea";

const FILES: Record<string, string> = {
  "docs/prd.md": [
    "# 무엇을 만드는가",
    "",
    "## 로드맵",
    "",
    "| 단계 | 무엇 | 문서 |",
    "| --- | --- | --- |",
    `| 지금 | ${ROADMAP_ITEM} | [견본](features/sample.md) |`,
    "",
    "## 범위 밖",
    "",
    "로드맵이 아닌 절",
  ].join("\n"),
  "docs/features/sample.md": [
    `# ${SAMPLE_TITLE}`,
    "",
    "## 개요",
    "",
    ...Array.from({ length: 80 }, (_, i) => `채울 문단 ${i + 1}.\n`),
    `## ${SAMPLE_SECTION}`,
    "",
    "도착한 절이다.",
  ].join("\n"),
  "docs/features/sample/spec.md": `# ${SAMPLE_SPEC_TITLE}\n`,
  "docs/features/other.md": [
    `# ${OTHER_TITLE}`,
    "",
    `[절로 가기](sample.md#${SAMPLE_SECTION.replaceAll(" ", "-")})`,
    "",
    "[코드 파일](../../web/src/app/layout.tsx)",
    "",
    "![구조 그림](../images/x.png)",
    "",
    "<script>window.__devDocsXss = 1</script>",
    "",
    '<img src="x" onerror="window.__devDocsXss = 2">',
    "",
    "[막힌 링크](javascript:alert(1))",
    "",
    "```mermaid",
    "flowchart LR",
    "  A[시작] --> B[끝]",
    `  classDef warm fill:${FLOW_FILL},stroke:#c62828`,
    "  class A warm",
    "```",
    "",
    "```mermaid",
    "sequenceDiagram",
    "  사람->>화면: 연다",
    "```",
    "",
    "```mermaid",
    "flowchart LR",
    "  A[[[ 틀린 문법",
    "```",
  ].join("\n"),
  "docs/adr/INDEX.md": [
    "# ADR 목록",
    "",
    "| 식별자 | 제목 | 층 | 상태 |",
    "| --- | --- | --- | --- |",
    `| [ADR-003](${encodeURIComponent("ADR-003-기억은-주입으로-강제한다.md")}) | ${KOREAN_ADR_TITLE} | 공통 | Accepted |`,
  ].join("\n"),
  "docs/adr/ADR-003-기억은-주입으로-강제한다.md": `## ADR-003: ${KOREAN_ADR_TITLE}\n`,
  "docs/code-rules/INDEX.md": "# 코드 규칙\n",
  "docs/code-rules/rule-1.md": "# rule-1\n",
};

/** 가짜 트리를 새 임시 폴더에 쓰고 그 경로를 돌려준다. */
export async function writeDevDocsFixture(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "browser-dev-docs-"));
  for (const [file, content] of Object.entries(FILES)) {
    await mkdir(dirname(join(root, file)), { recursive: true });
    await writeFile(join(root, file), content);
  }
  return root;
}
