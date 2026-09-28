import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { join, relative } from "node:path";
import test from "node:test";
import ts from "../../web/node_modules/typescript/lib/typescript.js";

const SRC_ROOT = join(import.meta.dirname, "../../web/src");
const DECLARATIVE_ENDING = /[가-힣][^.!?\n]*다\.?$/u;

async function sourceFiles(dir: string): Promise<string[]> {
  const found: string[] = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) found.push(...await sourceFiles(path));
    else if (/\.tsx?$/.test(entry.name)) found.push(path);
  }
  return found;
}

function declarativeLines(source: string, file: string): string[] {
  const parsed = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true,
    file.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const found: string[] = [];
  function visit(node: ts.Node) {
    // 화면에 표시하지 않는 Provider 사용 오류만 검사에서 제외한다.
    if (file === "components/shell/conversations-provider.tsx"
        && ts.isStringLiteral(node)
        && ts.isNewExpression(node.parent)
        && node.parent.expression.getText(parsed) === "Error"
        && node.text === "ConversationsProvider 가 필요하다.") return;
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)
        || ts.isJsxText(node) || ts.isTemplateHead(node) || ts.isTemplateMiddle(node)
        || ts.isTemplateTail(node)) {
      const start = parsed.getLineAndCharacterOfPosition(node.getStart(parsed)).line + 1;
      node.text.split("\n").forEach((line, index) => {
        for (const sentence of line.split(/(?<=[.!?])\s*/u)) {
          const value = sentence.trim();
          if (DECLARATIVE_ENDING.test(value)) found.push(`${file}:${start + index}: ${value}`);
        }
      });
    }
    ts.forEachChild(node, visit);
  }
  visit(parsed);
  return found;
}

test("화면 문구는 해요체를 쓰고 코드 주석은 검사하지 않는다", () => {
  assert.deepEqual(declarativeLines('const message = "아직 대화가 없어요."; // 아직 대화가 없다.', "sample.ts"), []);
  assert.deepEqual(declarativeLines('const message = "아직 대화가 없다.";', "sample.ts"),
    ["sample.ts:1: 아직 대화가 없다."]);
  assert.deepEqual(declarativeLines('throw new Error("대화가 없다.");', "sample.ts"),
    ["sample.ts:1: 대화가 없다."]);
  assert.deepEqual(declarativeLines('const message = "앞 문장은 평서체다. 뒤 문장은 해요체예요.";', "sample.ts"),
    ["sample.ts:1: 앞 문장은 평서체다."]);
});

test("웹 화면의 한국어 문구에 평서체 문장이 없다", async () => {
  const violations: string[] = [];
  for (const file of await sourceFiles(SRC_ROOT)) {
    const path = relative(SRC_ROOT, file);
    violations.push(...declarativeLines(await readFile(file, "utf-8"), path));
  }
  assert.deepEqual(violations, []);
});
