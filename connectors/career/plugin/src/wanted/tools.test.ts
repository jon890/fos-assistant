import { expect, test } from "bun:test";
import { CareerError } from "../errors.ts";
import type { BrowserLink, CdpSession } from "../browser/cdp.ts";
import { CareerTools } from "../tools.ts";
import { fingerprint, labelOf, parseField } from "./fields.ts";
import { WantedTools, type Timing } from "./tools.ts";

// 원티드 탭 대역이다. 표현식은 본문의 표지로 알아본다(page.ts 참고).
// 시나리오가 따로 정하지 않으면 화면은 서버 값을 그대로 보인다.

type Project = { title: string; description: string; start: string; end: string };
type Scenario = {
  state?: (url: string) => string;
  resumes?: { resumeKey: string; title: string; updatedAt: string }[];
  readResume?: (call: number) => "LOGIN" | "NOT_FOUND" | undefined;
  pageValue?: (target: { kind: string; projectTitle?: string }) => unknown;
  setText?: (call: number) => string | undefined;
  saves?: boolean;
  dropAt?: number;
  openFails?: CareerError;
  tick?: number;
  ready?: boolean;
  throwAt?: number;
};

class World {
  about = "백엔드 개발자입니다.";
  projects: Project[] = [
    { title: "주문 API", description: "- 주문을 나눴다", start: "2025-01", end: "2026-10" },
    { title: "정산 배치", description: "- 정산을 옮겼다", start: "2024-03", end: "2024-12" },
  ];
  urls: string[] = [];
  opened = 0;
  closed: string[] = [];
  clock = 0;
  private reads = 0;
  private sets = 0;
  private evaluations = 0;
  private picking: { title: string; which: "start" | "end"; year?: string; month?: string } | undefined;

  constructor(private readonly scenario: Scenario = {}) {}

  link(): BrowserLink {
    return {
      openTab: async () => {
        if (this.scenario.openFails) throw this.scenario.openFails;
        this.opened++;
        return { tabId: `T${this.opened}`, session: this.session(`T${this.opened}`) };
      },
      closeTab: async (tabId) => {
        this.closed.push(tabId);
      },
    };
  }

  api() {
    return {
      resume: { about: this.about, title: "기본 이력서" },
      careers: [{ company_name: "가나다", projects: this.projects.map(({ title, description }) => ({ title, description })) }],
      skills: [{ name: "Java" }],
      links: [],
    };
  }

  private session(tab: string): CdpSession {
    let url = "about:blank";
    const world = this;
    return {
      tab,
      async navigate(next: string) {
        url = next;
        world.urls.push(next);
      },
      async evaluate(expression: string) {
        world.evaluations++;
        if (world.scenario.dropAt && world.evaluations >= world.scenario.dropAt)
          throw new CareerError("CAREER_BROWSER_IN_USE", { tab });
        if (world.scenario.throwAt && world.evaluations === world.scenario.throwAt) return { ok: true, value: { status: "OK", resume: null } };
        return { ok: true, value: world.evaluate(expression, url) };
      },
      close() {},
    } as unknown as CdpSession;
  }

  private evaluate(expression: string, url: string): unknown {
    const json = (pattern: RegExp) => JSON.parse(pattern.exec(expression)![1]!);
    if (expression === '!!document.querySelector("[data-agent-field-key]")') return this.scenario.ready ?? true;
    if (expression.includes('return "LIST"')) {
      if (this.scenario.state) return this.scenario.state(url);
      return url.endsWith("/cv/list") ? "LIST" : "EDIT";
    }
    if (expression.includes("ResumeItem_ResumeItem__title__"))
      return this.scenario.resumes ?? [{ resumeKey: "KEY=1", title: "기본 이력서", updatedAt: "2026.10.01" }];
    if (expression.includes("/api/chaos/resumes/v2/")) {
      this.reads++;
      this.clock += this.scenario.tick ?? 0;
      const status = this.scenario.readResume?.(this.reads);
      return status ? { status } : { status: "OK", resume: this.api(), ai: [] };
    }
    if (expression.includes("periodText(b[0])")) {
      const titles = json(/return (\[.*?\])\.map/s) as string[];
      return titles.map((title) => {
        const project = this.projects.find((p) => p.title === title);
        return project ? { start: project.start, end: project.end } : null;
      });
    }
    if (expression.includes("return { value: t.el.value")) {
      const target = json(/findTarget\((\{.*?\})\);/s);
      const override = this.scenario.pageValue?.(target);
      if (override !== undefined) return override;
      return this.pageValue(target);
    }
    if (expression.includes('execCommand("insertText"')) {
      this.sets++;
      const marker = this.scenario.setText?.(this.sets);
      if (marker) return marker;
      const value = json(/var v = ("(?:[^"\\]|\\.)*");/s) as string;
      const target = json(/findTarget\((\{.*?\})\);/s);
      if (this.scenario.saves !== false) this.write(target, value);
      return "OK";
    }
    if (expression.includes('return "opened"')) {
      const target = json(/findTarget\((\{.*?\})\);/s);
      this.picking = { title: target.projectTitle, which: target.kind };
      return "opened";
    }
    if (expression.includes('return "year"')) {
      this.picking!.year = /=== "(\d{4})"/.exec(expression)![1];
      return "year";
    }
    if (expression.includes('return "month"')) {
      this.picking!.month = /=== "(\d{1,2})월"/.exec(expression)![1];
      return "month";
    }
    if (expression.includes('return "confirmed"')) {
      const { title, which, year, month } = this.picking!;
      const project = this.projects.find((p) => p.title === title)!;
      if (this.scenario.saves !== false) project[which] = `${year}-${month!.padStart(2, "0")}`;
      return "confirmed";
    }
    if (expression.includes("wds-z4o2j")) return true;
    throw new Error(`unknown expression: ${expression.slice(0, 80)}`);
  }

  private pageValue(target: { kind: string; projectTitle?: string }) {
    if (target.kind === "about") return { value: this.about };
    const project = this.projects.find((p) => p.title === target.projectTitle);
    if (!project) return "NO_ELEMENT";
    if (target.kind === "start" || target.kind === "end") return project[target.kind];
    return { value: project[target.kind as "title" | "description"] };
  }

  private write(target: { kind: string; projectTitle?: string }, value: string) {
    if (target.kind === "about") this.about = value;
    else {
      const project = this.projects.find((p) => p.title === target.projectTitle)!;
      project[target.kind as "title" | "description"] = value;
    }
  }
}

const timing: Timing = { budgetMs: 45_000, periodMs: 15_000, deadlineMs: 55_000, waitMs: 0, pollMs: 0, stepMs: 1_000 };
const tools = (world: World) => new WantedTools(world.link(), () => world.clock, timing);

// 대역의 처음 이력서에 get_wanted_resume 이 주는 label 이다.
const labels = new World().api();
const change = (field: string, value: string, before: string, label = labelOf(labels, parseField(field)!) ?? field) => ({
  field,
  label,
  before,
  value,
});
const run = (world: World, changes: ReturnType<typeof change>[]) =>
  tools(world).updateResume({ resumeKey: "KEY=1", resumeTitle: "기본 이력서", changes }) as Promise<{
    status: string;
    results: { field: string; state: string; reason?: string }[];
  }>;
const states = (result: { results: { state: string; reason?: string }[] }) =>
  result.results.map((r) => (r.reason ? `${r.state}:${r.reason}` : r.state));

async function codeOf(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof CareerError) return error.code;
    throw error;
  }
  return "no error";
}

test("이력서 하나면 칸 목록과 지문, 범위 밖 칸을 읽는다", async () => {
  const world = new World();
  const result = (await tools(world).getResume({})) as Record<string, any>;
  expect(result.status).toBe("ok");
  expect(result.resumeKey).toBe("KEY=1");
  expect(result.resumeTitle).toBe("기본 이력서");
  const end = result.fields.find((f: { field: string }) => f.field === "project:0:0:end");
  expect(end).toEqual({ field: "project:0:0:end", label: "가나다 / 주문 API / 종료월", value: "2026-10", fingerprint: fingerprint("2026-10") });
  expect(result.unreadable).toEqual([]);
  expect(result.outOfScope.skills).toEqual(["Java"]);
  expect(world.closed).toEqual(["T1"]);
});

test("이력서가 여럿이면 고르게 하고, 없으면 오류다", async () => {
  const many = new World({
    resumes: [
      { resumeKey: "A", title: "국문", updatedAt: "2026.10.01" },
      { resumeKey: "B", title: "영문", updatedAt: "2026.09.01" },
    ],
  });
  expect(await tools(many).getResume({})).toEqual({
    status: "choose",
    resumes: [
      { resumeKey: "A", title: "국문", updatedAt: "2026.10.01" },
      { resumeKey: "B", title: "영문", updatedAt: "2026.09.01" },
    ],
  });
  // 기다린 뒤에도 목록이 비었으면 이력서가 없는 것이 아니라 목록 화면이 바뀐 것이다.
  expect(await codeOf(tools(new World({ resumes: [] })).getResume({}))).toBe("CAREER_WANTED_PAGE_CHANGED");
  expect(await codeOf(tools(new World({ readResume: () => "NOT_FOUND" })).getResume({ resumeKey: "X" }))).toBe(
    "CAREER_WANTED_RESUME_NOT_FOUND",
  );
});

test("읽기 중 로그인 화면이면 넘기기를 내고 탭을 남겼다가 다음 호출이 닫는다", async () => {
  const world = new World({ state: () => "LOGIN" });
  const wanted = tools(world);
  const result = (await wanted.getResume({})) as Record<string, any>;
  expect(result).toMatchObject({ status: "HUMAN_ACTION_REQUIRED", handoff: { code: "CAREER_WANTED_LOGIN_REQUIRED", need: "login", tab: "T1" } });
  expect(world.closed).toEqual([]);
  await wanted.getResume({});
  expect(world.closed[0]).toBe("T1");
});

test("사람이 브라우저를 쥐면 읽기는 human_browser 넘기기다", async () => {
  const held = new World({ openFails: new CareerError("CAREER_BROWSER_IN_USE") });
  expect(await tools(held).getResume({})).toMatchObject({ handoff: { code: "CAREER_BROWSER_IN_USE", need: "human_browser" } });
  const taken = new World({ dropAt: 2 });
  expect(await tools(taken).getResume({})).toMatchObject({ handoff: { need: "human_browser", tab: "T1" } });
});

test("화면이 예상과 다르면 읽기는 CAREER_WANTED_PAGE_CHANGED 다", async () => {
  expect(await codeOf(tools(new World({ state: () => "OTHER" })).getResume({}))).toBe("CAREER_WANTED_PAGE_CHANGED");
});

test("중계 주소가 없으면 탭을 열지 않고 닿지 않음으로 끝난다", async () => {
  const wanted = new WantedTools(undefined);
  expect(await codeOf(wanted.getResume({}))).toBe("CAREER_BROWSER_UNREACHABLE");
  expect(
    await codeOf(wanted.updateResume({ resumeKey: "K", resumeTitle: "t", changes: [change("about", "x", fingerprint("y"))] })),
  ).toBe("CAREER_BROWSER_UNREACHABLE");
});

test("세 칸을 하나씩 넣고 서버에서 다시 읽어 모두 inserted 로 끝낸다", async () => {
  const world = new World();
  const result = await run(world, [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:description", "- 새 본문\n- 둘째 줄", fingerprint("- 주문을 나눴다")),
    change("project:0:0:end", "2026-11", fingerprint("2026-10")),
  ]);
  expect(result.status).toBe("done");
  expect(states(result)).toEqual(["inserted", "inserted", "inserted"]);
  expect(world.about).toBe("새 소개");
  expect(world.projects[0]!.end).toBe("2026-11");
  // 요청한 이력서의 편집 화면만 열고 탭을 닫는다.
  expect(new Set(world.urls)).toEqual(new Set(["https://www.wanted.co.kr/cv/KEY%3D1"]));
  expect(world.closed).toEqual(["T1"]);
});

test("제목을 바꾼 뒤 같은 프로젝트의 칸은 새 제목으로 찾는다", async () => {
  const world = new World();
  const result = await run(world, [
    change("project:0:0:title", "주문 API 개편", fingerprint("주문 API")),
    change("project:0:0:description", "- 새 본문", fingerprint("- 주문을 나눴다")),
  ]);
  expect(states(result)).toEqual(["inserted", "inserted"]);
  expect(world.projects[0]).toMatchObject({ title: "주문 API 개편", description: "- 새 본문" });
});

test("넣었는데 서버에 저장되지 않으면 그 칸은 failed 이고 뒤 칸은 넣지 않는다", async () => {
  const result = await run(new World({ saves: false }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:description", "- 새 본문", fingerprint("- 주문을 나눴다")),
  ]);
  expect(result.status).toBe("stopped");
  expect(states(result)).toEqual(["failed:not_saved", "not_inserted:stopped_earlier"]);
});

test("둘째 칸이 다른 곳에서 바뀌었으면 덮어쓰지 않고 멈춘다", async () => {
  const world = new World();
  const result = await run(world, [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:1:description", "- 새 본문", fingerprint("다른 값")),
    change("project:0:0:end", "2026-11", fingerprint("2026-10")),
  ]);
  expect(states(result)).toEqual(["inserted", "not_inserted:fingerprint_mismatch", "not_inserted:stopped_earlier"]);
  expect(world.projects[1]!.description).toBe("- 정산을 옮겼다");
});

test("화면 칸을 못 찾거나 넣기가 실패하면 page_changed 다", async () => {
  const missing = await run(new World({ pageValue: () => "NO_ELEMENT" }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
  ]);
  expect(states(missing)).toEqual(["not_inserted:page_changed"]);
  const failing = await run(new World({ setText: () => "INSERT_FAIL" }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
  ]);
  expect(states(failing)).toEqual(["failed:page_changed"]);
});

test("실행 중 로그인이 풀리면 넘기기 없이 멈춘 결과를 낸다", async () => {
  const result = await run(new World({ readResume: (call) => (call >= 4 ? "LOGIN" : undefined) }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:description", "- 새 본문", fingerprint("- 주문을 나눴다")),
    change("project:0:0:title", "새 제목", fingerprint("주문 API")),
  ]);
  expect(result.status).toBe("stopped");
  expect(result).not.toHaveProperty("handoff");
  expect(states(result)).toEqual(["inserted", "not_inserted:login_required", "not_inserted:login_required"]);
});

test("사람이 브라우저를 쥐고 있거나 가져가면 그에 맞는 이유로 멈춘다", async () => {
  const held = await run(new World({ openFails: new CareerError("CAREER_BROWSER_IN_USE") }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:end", "2026-11", fingerprint("2026-10")),
  ]);
  expect(states(held)).toEqual(["not_inserted:browser_in_use", "not_inserted:browser_in_use"]);
  // 실행 순서는 화면 상태, 준비, 첫 읽기, 칸 읽기, 칸 값이고 값 넣기가 여섯 번째다.
  const taken = await run(new World({ dropAt: 6 }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:end", "2026-11", fingerprint("2026-10")),
  ]);
  expect(states(taken)).toEqual(["failed:browser_taken", "not_inserted:stopped_earlier"]);
});

test("45초가 지나면 남은 칸을 time_limit 으로 돌려준다", async () => {
  const result = await run(new World({ tick: 16_000 }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:description", "- 새 본문", fingerprint("- 주문을 나눴다")),
    change("project:0:0:title", "새 제목", fingerprint("주문 API")),
  ]);
  expect(states(result)).toEqual(["inserted", "not_inserted:time_limit", "not_inserted:time_limit"]);
});

test("첫 칸 전 이력서가 없으면 오류, 둘째 칸 전 화면이 바뀌면 stopped 다", async () => {
  expect(
    await codeOf(run(new World({ readResume: () => "NOT_FOUND" }), [change("about", "x", fingerprint("백엔드 개발자입니다."))])),
  ).toBe("CAREER_WANTED_RESUME_NOT_FOUND");
  const result = await run(new World({ readResume: (call) => (call >= 4 ? "NOT_FOUND" : undefined) }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:description", "- 새 본문", fingerprint("- 주문을 나눴다")),
  ]);
  expect(states(result)).toEqual(["inserted", "not_inserted:page_changed"]);
});

test("등록한 도구는 넘기기와 멈춤을 isError 없이 돌려준다", async () => {
  const world = new World({ state: () => "LOGIN" });
  const career = new CareerTools(undefined, new WantedTools(world.link(), () => 0, timing));
  const read = await career.call("get_wanted_resume", {});
  expect(read.isError).toBeUndefined();
  expect(JSON.parse(read.content[0].text)).toMatchObject({ status: "HUMAN_ACTION_REQUIRED" });
  const write = await career.call("update_wanted_resume", {
    resumeKey: "KEY=1",
    resumeTitle: "기본 이력서",
    changes: [change("about", "새 소개", fingerprint("백엔드 개발자입니다."))],
  });
  expect(write.isError).toBeUndefined();
  expect(JSON.parse(write.content[0].text)).toMatchObject({ status: "stopped" });
  const unreachable = await new CareerTools({} as never).call("get_wanted_resume", {});
  expect(unreachable.isError).toBe(true);
  expect(JSON.parse(unreachable.content[0].text).error.code).toBe("CAREER_BROWSER_UNREACHABLE");
});

test("읽은 뒤 항목이 늘어 칸 이름이 다른 칸을 가리키면 아무것도 넣지 않는다", async () => {
  const world = new World();
  // 맨 위에 새 프로젝트가 생기면 자리가 밀려 옛 두 번째 프로젝트가 project:0:2 에 온다.
  world.projects.unshift({ title: "새 프로젝트", description: "", start: "2024-12", end: "2024-12" });
  const result = await run(world, [change("project:0:1:end", "2025-01", fingerprint("2024-12"), "가나다 / 정산 배치 / 종료월")]);
  expect(states(result)).toEqual(["not_inserted:page_changed"]);
  expect(world.projects.map((p) => p.end)).toEqual(["2024-12", "2026-10", "2024-12"]);
});

test("세 칸 중 둘째 칸이 실패하면 넣음, 실패, 넣지 않음이다", async () => {
  const result = await run(new World({ setText: (call) => (call === 2 ? "INSERT_FAIL" : undefined) }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:description", "- 새 본문", fingerprint("- 주문을 나눴다")),
    change("project:0:1:description", "- 또 새 본문", fingerprint("- 정산을 옮겼다")),
  ]);
  expect(result.status).toBe("stopped");
  expect(states(result)).toEqual(["inserted", "failed:page_changed", "not_inserted:stopped_earlier"]);
});

test("기간을 넣었는데 다시 연 화면에 반영되지 않으면 not_saved 다", async () => {
  const result = await run(new World({ saves: false }), [change("project:0:0:end", "2026-11", fingerprint("2026-10"))]);
  expect(states(result)).toEqual(["failed:not_saved"]);
});

test("예상 밖 모양의 응답도 칸별 결과로 멈춘다", async () => {
  // 네 번째 실행은 첫 칸 읽기이고 이력서 없이 OK 로 답한다.
  const result = await run(new World({ throwAt: 4 }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:end", "2026-11", fingerprint("2026-10")),
  ]);
  expect(states(result)).toEqual(["not_inserted:page_changed", "not_inserted:stopped_earlier"]);
});

test("편집 화면이 그려지지 않으면 읽기는 PAGE_CHANGED, 쓰기는 page_changed 로 멈춘다", async () => {
  expect(await codeOf(tools(new World({ ready: false })).getResume({ resumeKey: "KEY=1" }))).toBe("CAREER_WANTED_PAGE_CHANGED");
  const result = await run(new World({ ready: false }), [change("about", "x", fingerprint("백엔드 개발자입니다."))]);
  expect(states(result)).toEqual(["not_inserted:page_changed"]);
});

test("이력서 이름이 지금 이력서와 다르면 아무것도 넣지 않는다", async () => {
  const world = new World();
  const result = (await tools(world).updateResume({
    resumeKey: "KEY=1",
    resumeTitle: "다른 이력서",
    changes: [change("about", "새 소개", fingerprint("백엔드 개발자입니다."))],
  })) as { results: { state: string; reason?: string }[] };
  expect(states(result)).toEqual(["not_inserted:page_changed"]);
  expect(world.about).toBe("백엔드 개발자입니다.");
});

test("칸 이름이 하나만 어긋나면 그 칸에 이유를 붙이고 나머지는 기다린 것으로 적는다", async () => {
  const result = await run(new World(), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:end", "2026-11", fingerprint("2026-10"), "가나다 / 다른 제목 / 종료월"),
  ]);
  expect(states(result)).toEqual(["not_inserted:stopped_earlier", "not_inserted:page_changed"]);
});

test("입력 전에 거절된 칸은 건드리지 않은 것으로 적는다", async () => {
  const tooLong = await run(new World({ setText: () => "TOO_LONG" }), [change("about", "새 소개", fingerprint("백엔드 개발자입니다."))]);
  expect(states(tooLong)).toEqual(["not_inserted:too_long"]);
  const missing = await run(new World({ setText: () => "AMBIGUOUS" }), [change("about", "새 소개", fingerprint("백엔드 개발자입니다."))]);
  expect(states(missing)).toEqual(["not_inserted:page_changed"]);
});

test("실행 전체 마감을 넘기면 time_limit 으로 끝난다", async () => {
  // 서버를 읽을 때마다 시계가 27.5초 간다. 첫 칸은 45초 예산 안에 시작하지만 읽기가 55초에 끝나서
  // 다음 화면 단계에 남은 시간이 없다. 실행은 예산이 아니라 마감으로 멈춘다.
  const result = await run(new World({ tick: 27_500 }), [
    change("about", "새 소개", fingerprint("백엔드 개발자입니다.")),
    change("project:0:0:description", "- 새 본문", fingerprint("- 주문을 나눴다")),
  ]);
  expect(result.status).toBe("stopped");
  expect(states(result)).toEqual(["not_inserted:time_limit", "not_inserted:time_limit"]);
});

test("요청한 이력서의 화면만 연다", async () => {
  const world = new World();
  await tools(world).getResume({ resumeKey: "B" });
  expect(new Set(world.urls)).toEqual(new Set(["https://www.wanted.co.kr/cv/B"]));
});
