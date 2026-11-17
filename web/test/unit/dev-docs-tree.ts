import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

/** 단위 테스트가 쓰는 가짜 문서 트리다. 실제 저장소 문서는 자주 바뀌어 쓰지 않는다. */
export const KOREAN_ADR = "backend/docs/adr/ADR-003-기억은-주입으로-강제한다.md";

const FILES: Record<string, string> = {
  "docs/prd.md": [
    "# 무엇을 만드는가",
    "",
    "## 범위",
    "",
    "본문",
    "",
    "## 로드맵",
    "",
    "| 단계 | 무엇 | 문서 |",
    "| --- | --- | --- |",
    "| 지금 | 개발 문서 | [개발 현황](features/sample.md) |",
    "",
    "```text",
    "## 펜스 안 제목",
    "```",
    "",
    "## 범위 밖",
    "",
    "끝",
  ].join("\n"),
  "docs/code-architecture.md": "# 코드 구조\n",
  "hermes/docs/hermes-contract.md": "# Hermes 계약\n",
  "docs/features/sample.md": "# 견본 기능\n",
  "docs/features/sample/spec.md": "# 견본 스펙 제목\n",
  "docs/features/sample/design.md": "# 견본 설계\n",
  "docs/features/sample/tasks/plan-a/phase-01.md": "# 지울 계획\n",
  "docs/features/other.md": "```md\n# 펜스 안\n```\n\n# 다른 기능\n",
  "docs/adr/INDEX.md": [
    "# ADR 목록",
    "",
    "## 결정 목록",
    "",
    "| 식별자 | 제목 | 층 | 상태 |",
    "| --- | --- | --- | --- |",
    "| [ADR-001](ADR-001-runtime.md) | 런타임을 둔다 | 공통 | Accepted. 일부는 [ADR-002](ADR-002-x.md) 가 대체한다 |",
    "| [ADR-009](ADR-009-missing.md) | 없는 파일 | 공통 | Accepted |",
    "| [ADR-010](ADR-010-%E0%A4%A.md) | 풀지 못하는 링크 | 공통 | Accepted |",
    "| [ADR-011](ADR-011-code.md) | `hermes/connectors/` 에 [둔다](x.md) | 공통 | Accepted |",
    "",
    "## 보관",
    "",
    "| 식별자 | 제목 | 층 | 상태 |",
    "| --- | --- | --- | --- |",
    "| [ADR-005](archive/ADR-005-old.md) | 옛 결정 | 공통 | `Superseded` ([ADR-001](../ADR-001-runtime.md)) |",
  ].join("\n"),
  "docs/adr/ADR-001-runtime.md": "## ADR-001: 런타임을 둔다\n",
  "docs/adr/archive/ADR-005-old.md": "## ADR-005: 옛 결정\n",
  "docs/adr/ADR-011-code.md": "## ADR-011: 코드\n",
  "backend/docs/adr/INDEX.md": [
    "# backend ADR",
    "",
    "| 식별자 | 제목 | 상태 |",
    "| --- | --- | --- |",
    `| [ADR-003](${encodeURIComponent("ADR-003-기억은-주입으로-강제한다.md")}) | 기억은 주입으로 강제한다 | Accepted |`,
  ].join("\n"),
  [KOREAN_ADR]: "## ADR-003: 기억은 주입으로 강제한다\n",
  "docs/code-rules/INDEX.md": "# 코드 규칙\n",
  "docs/code-rules/rule-10.md": "# rule-10\n",
  "docs/code-rules/rule-2.md": "# rule-2\n",
};

export async function makeDocsTree(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "dev-docs-"));
  for (const [file, content] of Object.entries(FILES)) {
    await mkdir(dirname(join(root, file)), { recursive: true });
    await writeFile(join(root, file), content);
  }
  return root;
}
