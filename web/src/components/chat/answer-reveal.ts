import {
  fenceAt,
  nextFence,
  nextUnit,
  type FenceState,
} from "./reveal-units.ts";

/** 기본으로 보이는 속도다. 초당 글자 수다. */
export const REVEAL_RATE = 55;
/** 받은 글이 이 안에 모두 보이도록 속도를 올린다. */
export const CATCH_UP_MS = 250;
/** 끝 사건이 오면 남은 글이 이 안에 모두 보인다. */
export const FLUSH_MS = 150;
/** 새로 보인 낱말이 흐릿했다가 또렷해지는 시간이다. */
export const FADE_MS = 250;

/** 새로 보인 글 한 덩어리다. `from` 과 `to` 는 보인 글의 글자 위치이고 `progress` 는 0 에서 1 로 또렷해진다. */
export type RevealFade = { from: number; to: number; progress: number };
export type RevealSnapshot = { text: string; fades: RevealFade[] };

export type AnswerRevealOptions = {
  now?(): number;
  /** 다음 갱신을 예약하고 취소 함수를 돌려준다. 기본은 화면 새로 고침 주기다. */
  schedule?(callback: () => void): () => void;
  reducedMotion?(): boolean;
};

type Birth = { from: number; to: number; at: number };
type Deadline = { upTo: number; at: number };

/**
 * 탭이 뒤에 있으면 브라우저가 화면 새로 고침 호출을 멈춘다. 같은 시각에 타이머도 걸어 먼저 오는 쪽으로 돌아온다.
 * 돌아오면 흐른 시간만큼 밀린 글이 한꺼번에 보인다.
 */
function scheduleFrame(callback: () => void): () => void {
  let done = false;
  const run = () => {
    if (done) return;
    done = true;
    cancelAnimationFrame(frame);
    clearTimeout(timer);
    callback();
  };
  const frame = requestAnimationFrame(run);
  const timer = setTimeout(run, CATCH_UP_MS);
  return () => {
    done = true;
    cancelAnimationFrame(frame);
    clearTimeout(timer);
  };
}

function prefersReducedMotion(): boolean {
  return (
    typeof matchMedia === "function" &&
    matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

/**
 * 받은 답 글을 낱말 단위로 고른 속도로 풀어 보이는 장치다. 받은 글은 그대로 쌓고 보인 글만 따라간다.
 *
 * 서버가 보낸 조각이 들쭉날쭉해도 화면은 일정하게 움직이고, 마크다운은 조각마다가 아니라 화면 새로 고침 주기에
 * 한 번만 다시 그린다. 보낸 turn, 다시 시도, 대화 단위 SSE 의 자동 turn 이 같은 장치를 쓴다.
 */
export class AnswerReveal {
  private received = "";
  private shown = 0;
  private fence: FenceState = null;
  private births: Birth[] = [];
  private deadlines: Deadline[] = [];
  private credit = 0;
  private lastTick: number | null = null;
  private lastReceived = 0;
  private finishing = false;
  private cancelTick: (() => void) | null = null;
  private waiters: Array<() => void> = [];
  private listeners = new Set<() => void>();
  private snapshot: RevealSnapshot = { text: "", fades: [] };

  private readonly options: AnswerRevealOptions;

  constructor(options: AnswerRevealOptions = {}) {
    this.options = options;
  }

  private now(): number {
    return this.options.now ? this.options.now() : performance.now();
  }

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  readonly getSnapshot = (): RevealSnapshot => this.snapshot;

  /** 새로 받은 조각을 받은 글 끝에 붙인다. */
  receive(text: string) {
    if (text.length === 0) return;
    const now = this.now();
    this.received += text;
    this.lastReceived = now;
    this.deadlines.push({ upTo: this.received.length, at: now + CATCH_UP_MS });
    this.kick();
  }

  /** 막혀서 넘어간 시도의 글이다. 받은 글과 보인 글을 모두 비운다. */
  reset() {
    this.cancelTick?.();
    this.cancelTick = null;
    this.received = "";
    this.shown = 0;
    this.fence = null;
    this.births = [];
    this.deadlines = [];
    this.credit = 0;
    this.lastTick = null;
    this.finishing = false;
    this.publish(this.now());
    this.settle();
  }

  /**
   * 받은 글이 `text` 로 줄었다. 중간 안내로 다시 갈라진 글이다.
   * 보인 글이 받은 글보다 길어지지 않게 바로 맞추고, 이미 보인 앞부분은 그대로 둔다.
   */
  replace(text: string) {
    this.received = text;
    this.shown = Math.min(this.shown, text.length);
    this.fence = fenceAt(text, this.shown);
    this.births = this.births.flatMap((birth) =>
      birth.from >= this.shown
        ? []
        : [{ ...birth, to: Math.min(birth.to, this.shown) }],
    );
    this.deadlines = this.deadlines.filter(
      (deadline) => deadline.upTo <= text.length,
    );
    this.credit = 0;
    this.publish(this.now());
    this.settle();
    this.kick();
  }

  /** 마지막 조각이 왔다. 남은 글을 `FLUSH_MS` 안에 모두 보이고, 다 보이면 이행한다. */
  finish(): Promise<void> {
    this.finishing = true;
    if (this.shown >= this.received.length) return Promise.resolve();
    const now = this.now();
    this.deadlines.push({ upTo: this.received.length, at: now + FLUSH_MS });
    return new Promise<void>((resolve) => {
      this.waiters.push(resolve);
      this.kick();
    });
  }

  private kick() {
    if (this.cancelTick !== null) return;
    this.lastTick = this.now();
    this.arm();
  }

  private arm() {
    const schedule = this.options.schedule ?? scheduleFrame;
    this.cancelTick = schedule(() => {
      this.cancelTick = null;
      this.tick();
    });
  }

  private tick() {
    const now = this.now();
    const elapsed = Math.max(0, now - (this.lastTick ?? now));
    this.lastTick = now;
    this.deadlines = this.deadlines.filter(
      (deadline) => deadline.upTo > this.shown,
    );
    // 마감이 지난 글은 속도와 상관없이 보이고, 남은 마감은 그 시각까지 다 보이는 속도를 요구한다.
    let rate = REVEAL_RATE;
    let overdue = 0;
    for (const deadline of this.deadlines) {
      const left = deadline.at - now;
      if (left <= 0) overdue = Math.max(overdue, deadline.upTo);
      else rate = Math.max(rate, ((deadline.upTo - this.shown) * 1000) / left);
    }
    this.credit += (rate * elapsed) / 1000;
    const hold = this.advance(now, overdue);
    this.publish(now);
    this.settle();
    const stalled = this.shown >= this.received.length;
    if (hold === "marker" || (stalled && this.births.length === 0)) {
      this.credit = 0;
      this.lastTick = null;
      return;
    }
    this.arm();
  }

  /** 모은 글자 수만큼 낱말 덩어리를 보인다. 멈춘 까닭을 돌려준다. */
  private advance(now: number, overdue: number): "partial" | "marker" | null {
    const allowPartial =
      this.finishing || now - this.lastReceived >= CATCH_UP_MS;
    while (
      this.shown < this.received.length &&
      (this.credit > 0 || this.shown < overdue)
    ) {
      const unit = nextUnit(
        this.received,
        this.shown,
        this.fence,
        allowPartial,
        this.finishing,
      );
      if ("hold" in unit) {
        this.credit = 0;
        return unit.hold;
      }
      if (unit.line !== undefined)
        this.fence = nextFence(this.fence, unit.line);
      this.credit -= unit.end - this.shown;
      this.markBirth(this.shown, unit.end, now);
      this.shown = unit.end;
    }
    if (this.shown >= this.received.length) this.credit = 0;
    return null;
  }

  private markBirth(from: number, to: number, at: number) {
    if (this.options.reducedMotion?.() ?? prefersReducedMotion()) return;
    const last = this.births.at(-1);
    if (last !== undefined && last.at === at && last.to === from) last.to = to;
    else this.births.push({ from, to, at });
  }

  private publish(now: number) {
    this.births = this.births.filter((birth) => now - birth.at < FADE_MS);
    const text = this.received.slice(0, this.shown);
    const fades = this.births.map((birth) => ({
      from: birth.from,
      to: birth.to,
      progress: Math.min(1, (now - birth.at) / FADE_MS),
    }));
    if (
      text === this.snapshot.text &&
      fades.length === 0 &&
      this.snapshot.fades.length === 0
    )
      return;
    this.snapshot = { text, fades };
    for (const listener of [...this.listeners]) listener();
  }

  private settle() {
    if (this.shown < this.received.length) return;
    const waiters = this.waiters;
    this.waiters = [];
    for (const resolve of waiters) resolve();
  }
}
