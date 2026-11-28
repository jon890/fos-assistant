import { CareerError } from "../errors.ts";
import type { BrowserLink, CdpSession } from "../browser/cdp.ts";
import {
  careersOf,
  fieldsFromResume,
  fingerprint,
  labelOf,
  normalizeMonth,
  normalizeText,
  outOfScopeFromResume,
  parseField,
  type GetWantedResumeArgs,
  type ParsedField,
  type ProjectPeriod,
  type ResumeApi,
  type UpdateWantedResumeArgs,
} from "./fields.ts";
import {
  editReadyScript,
  editUrl,
  fieldValueScript,
  listResumesScript,
  listUrl,
  pageStateScript,
  readPeriodsScript,
  readResumeScript,
  setPeriodSteps,
  setTextScript,
  type PageTarget,
} from "./page.ts";

// 원티드 도구 두 개다.
// 읽기 도구는 화면을 사람에게 넘길 수 있다. 쓰기 도구는 승인한 실행으로 돌고
// 그 자리에서는 fos-assistant 가 화면 넘기기 결과를 버리므로, 멈춤은 모두 정상 결과 `stopped` 로 낸다.

export type FieldState = "inserted" | "failed" | "not_inserted";
export type StopReason =
  | "fingerprint_mismatch"
  | "stopped_earlier"
  | "time_limit"
  | "page_changed"
  | "not_saved"
  | "login_required"
  | "browser_in_use"
  | "browser_taken"
  | "browser_unreachable"
  | "too_long";
export type FieldResult = { field: string; label: string; state: FieldState; reason?: StopReason };

type Handoff = {
  status: "HUMAN_ACTION_REQUIRED";
  message: string;
  handoff: { code: string; need: "login" | "human_browser"; tab?: string };
};

/**
 * fos-assistant 는 승인한 호출을 60초 기다리고, 그 뒤에 온 결과는 버린다.
 * `budgetMs` 는 글자 칸을 시작할 수 있는 마지막 시각이다. 기간 칸은 화면을 다시 열어 `periodMs` 가 더 든다.
 * `deadlineMs` 는 실행 전체의 한도다. 화면 단계마다 남은 시간까지만 주므로 멈춘 단계가 있어도 실행은 제때 끝난다.
 */
export type Timing = {
  budgetMs: number;
  periodMs: number;
  deadlineMs: number;
  waitMs: number;
  pollMs: number;
  stepMs: number;
};
const defaultTiming: Timing = {
  budgetMs: 45_000,
  periodMs: 15_000,
  deadlineMs: 55_000,
  waitMs: 5_000,
  pollMs: 250,
  stepMs: 10_000,
};

const pageChanged = () => new CareerError("CAREER_WANTED_PAGE_CHANGED");

/** 쓰기 반복 안의 멈춤이다. 지금 칸의 까닭과 그 칸을 쓰던 중이었는지를 담는다. */
class Stop {
  constructor(
    readonly reason: StopReason,
    readonly touched = false,
  ) {}
}

type ReadOk = { status: "OK"; resume: ResumeApi; ai: unknown };
type ReadResult = ReadOk | { status: "LOGIN" | "NOT_FOUND" | "ERROR" };

export class WantedTools {
  /** 읽기 도구가 화면 넘기기로 남긴 탭이다. 이 프로세스의 다음 호출이 닫는다. */
  private leftTab: string | undefined;
  /** 진행 중인 쓰기의 마감이다. 화면 단계는 이 시각을 넘겨 기다리지 않는다. 읽기에는 없다. */
  private deadline: number | undefined;

  constructor(
    private readonly browser: BrowserLink | undefined,
    private readonly now: () => number = Date.now,
    private readonly timing: Timing = defaultTiming,
  ) {}

  async getResume(args: GetWantedResumeArgs): Promise<unknown> {
    const browser = this.requireBrowser();
    await this.closeLeftTab(browser);
    let tab: { tabId: string; session: CdpSession };
    try {
      tab = await browser.openTab("about:blank");
    } catch (error) {
      if (isInUse(error)) return humanBrowser(tabOf(error));
      throw error;
    }
    let leave = false;
    try {
      const { session, tabId } = tab;
      let resumeKey = args.resumeKey;
      let listedTitle = "";
      if (!resumeKey) {
        await session.navigate(listUrl(), this.stepTime());
        const state = await this.value<string>(session, pageStateScript());
        if (state === "LOGIN") return ((leave = true), login(tabId));
        if (state !== "LIST") throw pageChanged();
        // 목록은 load 이벤트 뒤에 그려진다. 기다린 뒤에도 비었으면 "이력서 없음" 이 아니라 화면이 바뀐 것이다.
        let resumes: { resumeKey: string; title: string; updatedAt: string }[] = [];
        await this.poll(async () => {
          resumes = await this.value(session, listResumesScript());
          return Array.isArray(resumes) && resumes.length > 0;
        });
        if (!Array.isArray(resumes) || resumes.length === 0) throw pageChanged();
        if (resumes.length > 1) return { status: "choose", resumes };
        resumeKey = resumes[0]!.resumeKey;
        listedTitle = resumes[0]!.title;
      }
      await session.navigate(editUrl(resumeKey), this.stepTime());
      const state = await this.value<string>(session, pageStateScript());
      if (state === "LOGIN") return ((leave = true), login(tabId));
      if (state !== "EDIT") throw pageChanged();
      if (!(await this.poll(async () => (await this.value<boolean>(session, editReadyScript())) === true)))
        throw pageChanged();
      const read = await this.readServer(session, resumeKey);
      if (read.status === "LOGIN") return ((leave = true), login(tabId));
      if (read.status === "NOT_FOUND") throw new CareerError("CAREER_WANTED_RESUME_NOT_FOUND");
      if (read.status !== "OK") throw pageChanged();
      const careers = careersOf(read.resume);
      const titles = careers.flatMap((career) => career.projects.map((project) => project.title));
      const shown = await this.value<({ start: string; end: string } | null)[]>(session, readPeriodsScript(titles));
      if (!Array.isArray(shown) || shown.length !== titles.length) throw pageChanged();
      let index = 0;
      const periods: ProjectPeriod[] = careers.flatMap((career, c) =>
        career.projects.map((_, p) => {
          const period = shown[index++];
          return period ? { c, p, start: period.start, end: period.end } : null;
        }),
      );
      const { fields, unreadable } = fieldsFromResume(read.resume, periods);
      const apiTitle = typeof read.resume.resume?.title === "string" ? read.resume.resume.title : "";
      return {
        status: "ok",
        resumeKey,
        // API 의 이력서 이름을 먼저 쓴다. 쓰기 도구가 resumeTitle 을 이 값과 비교한다.
        resumeTitle: normalizeText(apiTitle || listedTitle) || "이력서",
        fields,
        unreadable,
        outOfScope: outOfScopeFromResume(read.resume, read.ai),
      };
    } catch (error) {
      if (isInUse(error)) return ((leave = true), humanBrowser(tabOf(error) ?? tab.tabId));
      throw error;
    } finally {
      tab.session.close();
      if (leave) this.leftTab = tab.tabId;
      else await browser.closeTab(tab.tabId);
    }
  }

  async updateResume(args: UpdateWantedResumeArgs): Promise<unknown> {
    const browser = this.requireBrowser();
    const start = this.now();
    this.deadline = start + this.timing.deadlineMs;
    const results: FieldResult[] = [];
    const finish = (stop?: Stop) => {
      const done = results.length;
      args.changes.slice(done).forEach((change, offset) => {
        // 까닭은 실행이 멈춘 칸의 것이다. 로그인, 시간, 사람이 쥔 브라우저는 남은 칸 모두에 해당하고
        // 그 밖의 까닭이면 나머지 칸은 멈춤 뒤에서 기다리기만 했다.
        const own = offset === 0 && stop;
        const shared = stop && ["login_required", "time_limit", "browser_in_use", "browser_unreachable"].includes(stop.reason);
        const state: FieldState = own && stop!.touched ? "failed" : "not_inserted";
        const reason: StopReason = own || shared ? stop!.reason : "stopped_earlier";
        results.push({ field: change.field, label: change.label, state, reason });
      });
      return {
        status: results.every((result) => result.state === "inserted") ? "done" : "stopped",
        resumeKey: args.resumeKey,
        results,
      };
    };

    let tab: { tabId: string; session: CdpSession };
    try {
      tab = await browser.openTab("about:blank");
    } catch (error) {
      if (isInUse(error)) return finish(new Stop("browser_in_use"));
      throw error;
    }
    const { session } = tab;
    let touching = false;
    try {
      await this.openEditPage(session, args.resumeKey);
      // 칸 식별자는 API 자리다. 무엇이든 건드리기 전에 모든 label 이 여전히 같은 칸을 가리켜야 한다.
      // 읽은 뒤 항목이 늘면 자리가 밀리고, 값이 같은 이웃 칸은 지문 검사를 통과해 버린다.
      const first = await this.readServer(session, args.resumeKey);
      if (first.status === "NOT_FOUND") throw new CareerError("CAREER_WANTED_RESUME_NOT_FOUND");
      const initial = expectRead(first, false);
      const apiTitle = typeof initial.resume?.title === "string" ? normalizeText(initial.resume.title) : "";
      const wrongResume = apiTitle !== "" && apiTitle !== normalizeText(args.resumeTitle);
      const shifted = args.changes.map(
        (change) => wrongResume || labelOf(initial, parseField(change.field)!) !== normalizeText(change.label),
      );
      if (shifted.some(Boolean)) {
        // 아무것도 건드리지 않았다. label 이 어긋난 칸에 까닭을 달고 나머지는 기다리기만 했다.
        for (const [index, change] of args.changes.entries())
          results.push({
            field: change.field,
            label: change.label,
            state: "not_inserted",
            reason: shifted[index] ? "page_changed" : "stopped_earlier",
          });
        return finish();
      }
      for (const change of args.changes) {
        const field = parseField(change.field)!;
        const isPeriod = field.kind === "project" && (field.part === "start" || field.part === "end");
        const latest = this.timing.budgetMs - (isPeriod ? this.timing.periodMs : 0);
        if (this.now() - start > latest) return finish(new Stop("time_limit"));
        const resume = expectRead(await this.readServer(session, args.resumeKey), false);
        const located = await this.currentValue(session, args.resumeKey, resume, field);
        if (fingerprint(located.value) !== change.before) return finish(new Stop("fingerprint_mismatch"));
        const shown = await this.value<unknown>(session, fieldValueScript(located.target));
        if (!sameOnPage(shown, located.value, located.period)) return finish(new Stop("page_changed"));
        touching = true;
        if (located.period) await this.setPeriod(session, args.resumeKey, located, change.value);
        else await this.setText(session, args.resumeKey, located, field, change.value);
        touching = false;
        results.push({ field: change.field, label: change.label, state: "inserted" });
      }
      return finish();
    } catch (error) {
      if (error instanceof Stop) return finish(error);
      if (isInUse(error)) return finish(new Stop(touching ? "browser_taken" : "browser_in_use", touching));
      if (error instanceof CareerError && error.code === "CAREER_WANTED_RESUME_NOT_FOUND") throw error;
      if (error instanceof CareerError && error.code === "CAREER_BROWSER_UNREACHABLE")
        return finish(new Stop(this.pastDeadline() ? "time_limit" : "browser_unreachable", touching));
      // 예상과 다른 화면이나 응답이 와도 오류 대신 칸별 결과를 남긴다.
      return finish(new Stop("page_changed", touching));
    } finally {
      this.deadline = undefined;
      session.close();
      await browser.closeTab(tab.tabId);
    }
  }

  private requireBrowser(): BrowserLink {
    if (!this.browser) throw new CareerError("CAREER_BROWSER_UNREACHABLE");
    return this.browser;
  }

  private async closeLeftTab(browser: BrowserLink) {
    const left = this.leftTab;
    this.leftTab = undefined;
    if (left) await browser.closeTab(left);
  }

  private async openEditPage(session: CdpSession, resumeKey: string, touched = false) {
    await session.navigate(editUrl(resumeKey), this.stepTime());
    const state = await this.value<string>(session, pageStateScript());
    if (state === "LOGIN") throw new Stop("login_required", touched);
    if (state !== "EDIT") throw new Stop("page_changed", touched);
    if (!(await this.poll(async () => (await this.value<boolean>(session, editReadyScript())) === true)))
      throw new Stop("page_changed", touched);
  }

  private async readServer(session: CdpSession, resumeKey: string): Promise<ReadResult> {
    const read = await this.value<ReadResult>(session, readResumeScript(resumeKey));
    if (!read || typeof read !== "object" || typeof read.status !== "string") throw pageChanged();
    if (read.status === "OK" && (!read.resume || typeof read.resume !== "object")) throw pageChanged();
    return read;
  }

  /** 칸의 서버 쪽 지금 값과 화면에서 찾는 방법이다. 기간은 화면에서 읽는다. */
  private async currentValue(
    session: CdpSession,
    resumeKey: string,
    resume: ResumeApi,
    field: ParsedField,
  ): Promise<{ value: string; target: PageTarget; period: boolean; projectTitle?: string }> {
    if (field.kind === "about") {
      const about = typeof resume.resume?.about === "string" ? resume.resume.about : "";
      return { value: about, target: { kind: "about" }, period: false };
    }
    const project = careersOf(resume)[field.c]?.projects[field.p];
    if (!project) throw new Stop("page_changed");
    const target: PageTarget = { kind: field.part, projectTitle: project.title };
    if (field.part === "title" || field.part === "description")
      return { value: project[field.part], target, period: false, projectTitle: project.title };
    // 기간은 API 에서 읽지 않는다(키가 문서에 없다). 편집 화면을 다시 열어 실행을 시작할 때 화면이 보인 값이 아니라
    // 서버의 지금 값을 읽는다.
    await this.openEditPage(session, resumeKey);
    const shown = await this.value<({ start: string; end: string } | null)[]>(session, readPeriodsScript([project.title]));
    const period = Array.isArray(shown) ? shown[0] : null;
    if (!period) throw new Stop("page_changed");
    return { value: period[field.part], target, period: true, projectTitle: project.title };
  }

  private async setText(
    session: CdpSession,
    resumeKey: string,
    located: { target: PageTarget },
    field: ParsedField,
    next: string,
  ) {
    const marker = await this.value<string>(session, setTextScript(located.target, next));
    // 이 표지들은 첫 입력 전에 오므로 칸은 그대로다.
    if (marker === "TOO_LONG") throw new Stop("too_long", false);
    if (marker === "NO_ELEMENT" || marker === "AMBIGUOUS" || marker === "BOUNDARY") throw new Stop("page_changed", false);
    if (marker !== "OK") throw new Stop("page_changed", true);
    // onBlur 는 PATCH 를 비동기로 보낸다. 서버가 그 값을 보이거나 시간이 다할 때까지 서버를 읽는다.
    const saved = await this.poll(async () => {
      const read = expectRead(await this.readServer(session, resumeKey), true);
      return normalizeText(serverText(read, field)) === normalizeText(next);
    });
    if (!saved) throw new Stop("not_saved", true);
  }

  private async setPeriod(
    session: CdpSession,
    resumeKey: string,
    located: { target: PageTarget; projectTitle?: string },
    next: string,
  ) {
    const month = normalizeMonth(next)!;
    const [year, monthNumber] = month.split("-").map(Number) as [number, number];
    const which = located.target.kind === "start" ? "start" : "end";
    for (const step of setPeriodSteps(located.projectTitle ?? "", which, year, monthNumber)) {
      if ((await this.value<string>(session, step.run)) !== step.expect) throw new Stop("page_changed", true);
      if (step.waitFor && !(await this.poll(async () => (await this.value<boolean>(session, step.waitFor!)) === true)))
        throw new Stop("page_changed", true);
    }
    // 기간의 API 키는 문서에 없다. 서버 값으로 그리는 편집 화면을 다시 열어 거기서 읽는다.
    const saved = await this.poll(async () => {
      await this.openEditPage(session, resumeKey, true);
      const shown = await this.value<({ start: string; end: string } | null)[]>(
        session,
        readPeriodsScript([located.projectTitle ?? ""]),
      );
      const period = Array.isArray(shown) ? shown[0] : null;
      return !!period && period[which] === month;
    });
    if (!saved) throw new Stop("not_saved", true);
  }

  /** 단계마다 시간 한도를 두고 화면 표현식을 실행한다. 멈춘 단계 하나가 실행 시간을 다 쓰지 못하게 한다. */
  private async value<T>(session: CdpSession, expression: string): Promise<T> {
    const result = await session.evaluate<T>(expression, this.stepTime());
    if (!result.ok) throw pageChanged();
    return result.value;
  }

  /** 화면 단계의 시간 한도다. 단계 한도와 쓰기 마감까지 남은 시간 중 작은 쪽이다. */
  private stepTime(): number {
    if (this.deadline === undefined) return this.timing.stepMs;
    const left = this.deadline - this.now();
    if (left <= 0) throw new CareerError("CAREER_BROWSER_UNREACHABLE");
    return Math.min(this.timing.stepMs, left);
  }

  private pastDeadline(): boolean {
    return this.deadline !== undefined && this.now() >= this.deadline;
  }

  private async poll(check: () => Promise<boolean>): Promise<boolean> {
    const deadline = this.now() + this.timing.waitMs;
    for (;;) {
      if (await check()) return true;
      if (this.now() >= deadline) return false;
      await Bun.sleep(this.timing.pollMs);
    }
  }
}

function expectRead(read: ReadResult, touched: boolean): ResumeApi {
  if (read.status === "LOGIN") throw new Stop("login_required", touched);
  if (read.status !== "OK") throw new Stop("page_changed", touched);
  return read.resume;
}

/** API 자리로 찾은 글자 칸의 서버 쪽 값이다. 고쳐도 API 순서는 바뀌지 않는다. */
function serverText(resume: ResumeApi, field: ParsedField): string {
  if (field.kind === "about") return typeof resume.resume?.about === "string" ? resume.resume.about : "";
  const project = careersOf(resume)[field.c]?.projects[field.p];
  if (!project || (field.part !== "title" && field.part !== "description")) return "";
  return project[field.part];
}

function sameOnPage(shown: unknown, current: string, period: boolean): boolean {
  if (period) return typeof shown === "string" && shown === current;
  const text = (shown as { value?: unknown } | null)?.value;
  return typeof text === "string" && normalizeText(text) === normalizeText(current);
}

function isInUse(error: unknown): error is CareerError {
  return error instanceof CareerError && error.code === "CAREER_BROWSER_IN_USE";
}

function tabOf(error: CareerError): string | undefined {
  const tab = error.details?.tab;
  return typeof tab === "string" ? tab : undefined;
}

function login(tab: string): Handoff {
  return {
    status: "HUMAN_ACTION_REQUIRED",
    message:
      "원티드 로그인이 필요합니다. 끝나지 않았고 결과가 없습니다. 사용자가 「내 브라우저」 에서 로그인하고 「다 했어요」 를 누를 때까지 다시 부르지 마세요.",
    handoff: { code: "CAREER_WANTED_LOGIN_REQUIRED", need: "login", tab },
  };
}

function humanBrowser(tab: string | undefined): Handoff {
  return {
    status: "HUMAN_ACTION_REQUIRED",
    message:
      "사용자가 「내 브라우저」 를 직접 쓰고 있습니다. 끝나지 않았고 결과가 없습니다. 「돌려주기」 를 누를 때까지 다시 부르지 마세요.",
    handoff: { code: "CAREER_BROWSER_IN_USE", need: "human_browser", ...(tab ? { tab } : {}) },
  };
}
