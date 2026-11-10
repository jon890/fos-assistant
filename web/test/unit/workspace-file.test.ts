import assert from "node:assert/strict";
import { test } from "node:test";
import {
  addressable,
  crumbs,
  explorerHref,
  fileUrl,
  formatSize,
  joinPath,
  parseDelimited,
  previewKind,
} from "../../web/src/lib/workspace-file.ts";

const MIB = 1024 * 1024;

test("미리보기 종류는 backend 의 확장자 표와 같다", () => {
  const cases: [string, string][] = [
    ["a.csv", "table"],
    ["a.TSV", "table"],
    ["a.html", "html"],
    ["a.htm", "html"],
    ["a.svg", "text"],
    ["a.css", "text"],
    ["a.TXT", "text"],
    ["Makefile", "text"],
    [".env", "text"],
    [".gitignore", "none"],
    ["a.png", "image"],
    ["a.bin", "none"],
    ["a.", "none"],
  ];
  for (const [name, expected] of cases) {
    assert.equal(previewKind(name, 10), expected, `${name} 의 미리보기 종류`);
  }
});

test("상한을 넘거나 크기를 모르면 미리보기가 없다", () => {
  assert.equal(previewKind("a.txt", MIB), "text");
  assert.equal(previewKind("a.txt", MIB + 1), "none");
  assert.equal(previewKind("a.png", 20 * MIB), "image");
  assert.equal(previewKind("a.png", 20 * MIB + 1), "none");
  assert.equal(previewKind("a.html", 5 * MIB + 1), "none");
  assert.equal(previewKind("a.txt", null), "none");
});

test("파일 주소는 조각마다 인코딩하고 내려받기는 download=1 로 끝난다", () => {
  const url = fileUrl("보고서/1월 결과.csv", true);
  assert.equal(
    url,
    `/api/workspace/files/${encodeURIComponent("보고서")}/${encodeURIComponent("1월 결과.csv")}?download=1`,
  );
  assert.ok(url.endsWith("?download=1"), url);
  assert.equal(fileUrl("a.txt"), "/api/workspace/files/a.txt");
});

test("경로 줄은 「파일 공간」 부터 연 디렉터리까지다", () => {
  assert.deepEqual(crumbs("a/b"), [
    { name: "파일 공간", path: "" },
    { name: "a", path: "a" },
    { name: "b", path: "a/b" },
  ]);
  assert.deepEqual(crumbs(""), [{ name: "파일 공간", path: "" }]);
});

test("경로 줄은 빈 조각을 건너뛴다", () => {
  assert.deepEqual(crumbs("a//b/"), [
    { name: "파일 공간", path: "" },
    { name: "a", path: "a" },
    { name: "b", path: "a/b" },
  ]);
});

test("어느 조각에든 %, ;, \\ 가 있으면 주소로 쓸 수 없다", () => {
  assert.equal(addressable("보고서/a.txt"), true);
  assert.equal(addressable(""), true);
  assert.equal(addressable("50%/a.txt"), false);
  assert.equal(addressable("a;b/c.txt"), false);
  assert.equal(addressable("a\\b/c.txt"), false);
});

test("경로를 잇고 화면 주소를 만든다", () => {
  assert.equal(joinPath("", "a.txt"), "a.txt");
  assert.equal(joinPath("a", "b.txt"), "a/b.txt");
  assert.equal(explorerHref(""), "/files");
  assert.equal(explorerHref("a b"), "/files?path=a+b");
  assert.equal(explorerHref("a", "a/c.txt"), "/files?path=a&file=a%2Fc.txt");
});

test("따옴표 안의 쉼표와 줄바꿈을 지킨다", () => {
  const { rows, truncated } = parseDelimited(
    'a,"b,c"\n1,"x\ny"\n',
    ",",
    1000,
  );
  assert.deepEqual(rows, [
    ["a", "b,c"],
    ["1", "x\ny"],
  ]);
  assert.equal(truncated, false);
});

test("따옴표 안의 이중 따옴표는 따옴표 하나다", () => {
  const { rows } = parseDelimited('"a""b",c', ",", 1000);
  assert.deepEqual(rows, [['a"b', "c"]]);
});

test("빈 따옴표 칸도 칸이고 그 뒤의 따옴표는 글자다", () => {
  const { rows } = parseDelimited('"",x\n""\n""a"', ",", 1000);
  assert.deepEqual(rows, [["", "x"], [""], ['a"']]);
});

test("탭으로 나누고 CRLF 줄바꿈을 읽는다", () => {
  const { rows } = parseDelimited("a\tb\r\n1\t2", "\t", 1000);
  assert.deepEqual(rows, [
    ["a", "b"],
    ["1", "2"],
  ]);
});

test("maxRows 를 넘으면 그 줄까지만 주고 truncated 다", () => {
  const exact = parseDelimited("a\nb\n", ",", 2);
  assert.deepEqual(exact, { rows: [["a"], ["b"]], truncated: false });
  const over = parseDelimited("a\nb\nc\n", ",", 2);
  assert.deepEqual(over, { rows: [["a"], ["b"]], truncated: true });
});

test("크기를 읽기 쉬운 단위로 바꾼다", () => {
  assert.equal(formatSize(512), "512 B");
  assert.equal(formatSize(1536), "1.5 KB");
  assert.equal(formatSize(Math.round(3.2 * MIB)), "3.2 MB");
});

test("반올림해 1,024 가 되면 다음 단위로 보인다", () => {
  assert.equal(formatSize(1_048_575), "1.0 MB");
  assert.equal(formatSize(1023 * 1024), "1023.0 KB");
});
