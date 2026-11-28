import { expect, test } from "bun:test";
import {
  editUrl,
  fieldValueScript,
  listResumesScript,
  pageStateScript,
  readPeriodsScript,
  readResumeScript,
  setPeriodSteps,
  setTextScript,
} from "./page.ts";

const tricky = `따옴표 " ' 백슬래시 \\ 줄바꿈\n</script> \${x} \`백틱\``;

const expressions = () => [
  pageStateScript(),
  listResumesScript(),
  readResumeScript(tricky),
  readPeriodsScript([tricky, ""]),
  fieldValueScript({ kind: "about" }),
  fieldValueScript({ kind: "description", projectTitle: tricky }),
  setTextScript({ kind: "title", projectTitle: tricky }, tricky),
  ...setPeriodSteps(tricky, "end", 2026, 11).flatMap((step) => [step.run, step.waitFor ?? "true"]),
];

test("모든 탭 안 식이 JavaScript 로 컴파일된다", () => {
  for (const expression of expressions()) expect(() => new Function(`return ${expression};`)).not.toThrow();
});

test("끼워 넣은 값이 식 안에서 그대로 보존된다", () => {
  // insertText 에 닿은 값을 기록하는 화면 대역으로 값 넣기 표현식을 실행한다.
  let inserted: unknown;
  const element = {
    value: tricky,
    maxLength: 0,
    scrollIntoView() {},
    focus() {},
    setSelectionRange() {},
    __reactPropsX: { onBlur() {} },
  };
  const document = {
    querySelectorAll: (selector: string) => {
      if (selector === "[data-agent-field-key=title]") return [element];
      if (selector === "button") return [];
      return [];
    },
    execCommand: (_: string, __: boolean, value: string) => {
      inserted = value;
      return true;
    },
  };
  // 제목 하나와 기간 단추 두 개를 가진 칸 묶음이라 findBox 가 성공한다.
  const box = {
    querySelectorAll: (selector: string) =>
      selector === "[data-agent-field-key=title]"
        ? [element]
        : selector === "[data-agent-field-key=description]"
          ? [{ value: "" }]
          : [{ textContent: "2025.01" }, { textContent: "YYYY.MM" }],
    parentElement: null,
  };
  (element as unknown as { parentElement: unknown }).parentElement = box;
  const run = new Function("document", `return ${setTextScript({ kind: "title", projectTitle: tricky }, tricky)};`);
  expect(run(document)).toBe("OK");
  expect(inserted).toBe(tricky);
});

test("기간 단계는 넷이고 정한 표지를 기대한다", () => {
  const steps = setPeriodSteps("주문 API", "start", 2025, 1);
  expect(steps.map((step) => step.expect)).toEqual(["opened", "year", "month", "confirmed"]);
  expect(steps[2]!.run).toContain('"1월"');
  expect(steps[1]!.run).toContain('"2025"');
});

test("편집 화면 주소는 키를 감싼다", () => {
  expect(editUrl("ab=c/d")).toBe("https://www.wanted.co.kr/cv/ab%3Dc%2Fd");
});
