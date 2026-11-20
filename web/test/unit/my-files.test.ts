import assert from "node:assert/strict";
import { test } from "node:test";
import {
  dateGroup,
  decodeText,
  displayName,
  filePreview,
  groupByDate,
  roundSize,
  TEXT_PREVIEW_MAX_BYTES,
} from "../../src/lib/my-files.ts";

// 기기 시간대로 나누므로 기대값도 같은 시간대의 지역 시각으로 만든다.
const local = (year: number, month: number, day: number, hour = 12) =>
  new Date(year, month - 1, day, hour).toISOString();

test("한 주는 월요일에 시작하고 한 줄은 맞는 구분 가운데 가장 앞의 하나에만 든다", () => {
  // 2026-11-20 은 금요일이다. 그 주의 월요일은 11-16 이다.
  const now = new Date(2026, 10, 20, 15);
  assert.equal(dateGroup(local(2026, 11, 20, 0), now).label, "오늘");
  assert.equal(dateGroup(local(2026, 11, 16, 0), now).label, "이번 주");
  assert.equal(dateGroup(local(2026, 11, 15, 23), now).label, "이번 달");
  assert.equal(dateGroup(local(2026, 11, 1, 0), now).label, "이번 달");
  assert.equal(dateGroup(local(2026, 10, 31), now).label, "2026년 10월");
  assert.equal(dateGroup(local(2025, 12, 3), now).label, "2025년 12월");
});

test("이번 주가 지난달에 걸치면 지난달의 날도 이번 주에 든다", () => {
  // 2026-12-02 는 수요일이다. 그 주의 월요일은 11-30 이다.
  const now = new Date(2026, 11, 2, 9);
  assert.equal(dateGroup(local(2026, 11, 30), now).label, "이번 주");
  assert.equal(dateGroup(local(2026, 11, 29), now).label, "2026년 11월");
});

test("일요일에는 그 주의 월요일부터가 이번 주다", () => {
  const now = new Date(2026, 10, 22, 9);
  assert.equal(dateGroup(local(2026, 11, 16), now).label, "이번 주");
  assert.equal(dateGroup(local(2026, 11, 15), now).label, "이번 달");
});

test("줄을 날짜 구분으로 묶어도 최신순 차례를 지킨다", () => {
  const now = new Date(2026, 10, 20, 15);
  const items = [
    { id: 3, vaultedAt: local(2026, 11, 20) },
    { id: 2, vaultedAt: local(2026, 11, 18) },
    { id: 1, vaultedAt: local(2026, 9, 1) },
  ];
  const groups = groupByDate(items, now);
  assert.deepEqual(
    groups.map((group) => [group.group.label, group.items.map((item) => item.id)]),
    [
      ["오늘", [3]],
      ["이번 주", [2]],
      ["2026년 9월", [1]],
    ],
  );
});

test("미리보기는 그림, HTML, 표, 글만 하고 큰 글과 엑셀·PDF 는 없다", () => {
  assert.equal(filePreview("a/b.PNG", 10), "image");
  assert.equal(filePreview("a/index.html", 10), "html");
  assert.equal(filePreview("a.csv", 10), "table");
  assert.equal(filePreview("a.tsv", 10), "table");
  assert.equal(filePreview("a.md", 10), "text");
  assert.equal(filePreview("a.css", 10), "text");
  assert.equal(filePreview("a.txt", TEXT_PREVIEW_MAX_BYTES + 1), "none");
  for (const name of ["a.xlsx", "a.docx", "a.pptx", "a.pdf"])
    assert.equal(filePreview(name, 10), "none");
});

test("보이는 이름은 제목, 그림 이름, 폴더 이름, 파일 이름 차례다", () => {
  assert.equal(displayName("out/a.xlsx", " 11월 가계부 "), "11월 가계부");
  assert.equal(displayName("generated/고양이.png", null), "고양이");
  assert.equal(displayName("trip/index.html", null), "trip");
  assert.equal(displayName("보고서.xlsx", null), "보고서.xlsx");
});

test("전체 용량은 소수점을 떼고 글은 UTF-8 이 아니면 EUC-KR 로 읽는다", () => {
  assert.equal(roundSize("5.0 GB"), "5 GB");
  assert.equal(roundSize("1.2 GB"), "1.2 GB");
  assert.equal(decodeText(new TextEncoder().encode("이름,값").buffer), "이름,값");
  // 「한」 의 EUC-KR 바이트다.
  assert.equal(decodeText(new Uint8Array([0xc7, 0xd1]).buffer), "한");
});
