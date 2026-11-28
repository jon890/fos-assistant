import { expect, test } from "bun:test";
import { Window } from "happy-dom";
import {
  fieldValueScript,
  pageStateScript,
  readPeriodsScript,
  setPeriodSteps,
  setTextScript,
} from "./page.ts";

// 실제 화면 표현식을 원티드와 닮은 작은 고정 화면에서 실행한다.
// 마크업은 원티드 구조를 추정한 것이다(옛 프로필 동기화 스킬이 적어 둔 속성만 쓴다).
// 실제 화면은 배포 뒤 사용자 계정으로 확인한다.

const project = (title: string, description: string, start: string, end: string) => `
  <div class="project">
    <input data-agent-field-key="title" value="${title}">
    <textarea data-agent-field-key="description">${description}</textarea>
    <div><button>${start}</button><button>${end}</button></div>
  </div>`;

const resume = (careerBody: string) => `
  <div><textarea data-agent-field-key="about">소개 글</textarea></div>
  <div class="career"><button>2020.01</button><button>YYYY.MM</button>${careerBody}</div>`;

function screen(html: string, href = "https://www.wanted.co.kr/cv/KEY") {
  const window = new Window({ url: href });
  window.document.body.innerHTML = html;
  // happy-dom 은 배치를 계산하지 않는다. 확인 단추가 보이는 것으로 치도록 모든 요소에 보이는 크기를 준다.
  const prototype = window.HTMLElement.prototype as unknown as Record<string, unknown>;
  prototype.getBoundingClientRect = () => ({ width: 10, height: 10 });
  prototype.scrollIntoView = () => {};
  const run = (expression: string): unknown =>
    new Function("document", "location", "URL", `return ${expression};`)(window.document, window.location, URL);
  return { window, document: window.document, run };
}

const twoProjects = resume(project("주문 API", "- 주문", "2025.01", "2026.10") + project("정산 배치", "- 정산", "2024.3", "2024.12"));

test("제목으로 프로젝트 상자를 찾아 기간 단추와 본문을 읽는다", () => {
  const { run } = screen(twoProjects);
  expect(run(readPeriodsScript(["주문 API", "정산 배치", "없는 제목"]))).toEqual([
    { start: "2025-01", end: "2026-10" },
    { start: "2024-03", end: "2024-12" },
    null,
  ]);
  expect(run(fieldValueScript({ kind: "description", projectTitle: "정산 배치" }))).toEqual({ value: "- 정산" });
  expect(run(fieldValueScript({ kind: "end", projectTitle: "주문 API" }))).toBe("2026-10");
  expect(run(fieldValueScript({ kind: "about" }))).toEqual({ value: "소개 글" });
});

test("같은 제목이 둘이면 AMBIGUOUS, 없으면 NO_ELEMENT 다", () => {
  const { run } = screen(resume(project("같은 제목", "a", "2025.01", "2025.02") + project("같은 제목", "b", "2025.03", "2025.04")));
  expect(run(fieldValueScript({ kind: "description", projectTitle: "같은 제목" }))).toBe("AMBIGUOUS");
  expect(run(fieldValueScript({ kind: "title", projectTitle: "다른 제목" }))).toBe("NO_ELEMENT");
});

test("프로젝트 기간 단추가 아직 없으면 경력 상자를 고르지 않는다(프로젝트가 둘이면 BOUNDARY)", () => {
  const unrendered = (title: string) => `
    <div class="project"><input data-agent-field-key="title" value="${title}">
    <textarea data-agent-field-key="description">x</textarea></div>`;
  const { run } = screen(resume(unrendered("주문 API") + unrendered("정산 배치")));
  expect(run(fieldValueScript({ kind: "end", projectTitle: "주문 API" }))).toBe("BOUNDARY");
  expect(run(readPeriodsScript(["주문 API"]))).toEqual([null]);
});

test("글 칸 넣기는 insertText 뒤 React onBlur 를 부른다", () => {
  const { document, run } = screen(twoProjects);
  const textarea = document.querySelectorAll("[data-agent-field-key=description]")[1] as unknown as HTMLTextAreaElement;
  let blurred = 0;
  (textarea as unknown as Record<string, unknown>).__reactProps$x = { onBlur: () => blurred++ };
  (document as unknown as { execCommand: (c: string, u: boolean, v: string) => boolean }).execCommand = (_c, _u, v) => {
    textarea.value = v;
    return true;
  };
  expect(run(setTextScript({ kind: "description", projectTitle: "정산 배치" }, "- 새 본문\n- 둘째"))).toBe("OK");
  expect(textarea.value).toBe("- 새 본문\n- 둘째");
  expect(blurred).toBe(1);
  // React props 가 없으면 값은 들어가도 저장할 것이 없다.
  expect(run(setTextScript({ kind: "description", projectTitle: "주문 API" }, "x"))).toBe("NO_PROPS");
});

test("기간 단계는 그 프로젝트의 단추를 열고 고르기 창 안의 확인만 누른다", () => {
  const { document, run } = screen(
    twoProjects + `<div class="picker"><div class="wds-z4o2j"></div><button id="ok">확인</button></div><button id="other">확인</button>`,
  );
  const clicked: string[] = [];
  for (const button of Array.from(document.querySelectorAll("button")))
    button.addEventListener("click", () => clicked.push(button.id || button.textContent || ""));
  const [open, , , confirm] = setPeriodSteps("정산 배치", "end", 2026, 11);
  expect(run(open!.run)).toBe("opened");
  expect(clicked).toEqual(["2024.12"]);
  expect(run(confirm!.run)).toBe("confirmed");
  expect(clicked).toEqual(["2024.12", "ok"]);
});

test("주소로 화면을 판정하고 http 가 아닌 주소는 로그인으로 보지 않는다", () => {
  expect(screen("", "https://www.wanted.co.kr/cv/list").run(pageStateScript())).toBe("LIST");
  expect(screen("", "https://www.wanted.co.kr/cv/KEY").run(pageStateScript())).toBe("EDIT");
  expect(screen("", "https://id.wanted.jobs/login").run(pageStateScript())).toBe("LOGIN");
  expect(screen("", "https://www.wanted.co.kr/events").run(pageStateScript())).toBe("OTHER");
  expect(screen("", "about:blank").run(pageStateScript())).toBe("OTHER");
});
