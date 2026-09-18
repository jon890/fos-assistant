/** 흐름의 한 단계가 어디까지 갔는지. 아직 소식이 없는 단계는 `pending` 이다. */
export type FlowStepState = "pending" | "started" | "completed" | "failed";

/** 단계 이름과 그 상태. Control Plane 이 보내는 `step` 사건이 이 표를 채운다. */
export type FlowStepStates = Record<string, FlowStepState>;

/** 단계 차례다. 화면은 실제로 돈 순서가 아니라 이 차례로 보인다. */
const ORDER = ["chief", "researcher", "engineer", "synthesizer"] as const;

/** 단계 이름은 한국어로 보인다. `chief` 를 그대로 보이지 않는다. */
const LABELS: Record<string, string> = {
  chief: "정리",
  researcher: "조사",
  engineer: "구현",
  synthesizer: "합치기",
};

const MARKS: Record<FlowStepState, string> = {
  pending: "·",
  started: "⟳",
  completed: "✓",
  failed: "!",
};

/** 표시가 글자 하나뿐이라 읽어 주는 화면을 위해 상태를 따로 적는다. */
const SPOKEN: Record<FlowStepState, string> = {
  pending: "아직",
  started: "도는 중",
  completed: "끝남",
  failed: "실패",
};

type Props = {
  states: FlowStepStates;
  /** 지금 도는 단계 옆에 붙는 도구 이름. 알려 온 것이 없으면 null 이다 */
  latestTool: string | null;
};

/**
 * 여러 에이전트가 도는 동안 무엇이 어디까지 갔는지 보인다.
 *
 * <p>네 단계의 답을 모두 흘리지 않는다. 조사와 구현의 중간 산출물까지 흘리면 읽을 수 없어서,
 * 그 둘은 도는 중과 끝남만 보인다.
 *
 * <p>답이 흘러나오기 시작하면 부르는 쪽이 이 목록을 접는다. 글자가 늘어나는 것 자체가 진행이고,
 * 그 옆에 진행 표시를 함께 두면 둘 중 무엇을 봐야 할지 모른다.
 */
export function FlowProgress({ states, latestTool }: Props) {
  return (
    <li className="grid grid-cols-[2rem_minmax(0,1fr)] gap-2">
      <span aria-hidden="true" />
      <ol
        data-testid="flow-progress"
        aria-label="흐름 진행"
        aria-live="polite"
        className="flex min-w-0 flex-col gap-1 rounded-md border border-border px-3 py-2 text-xs"
      >
        {ORDER.map((name) => {
          const state = states[name] ?? "pending";
          return (
            <li
              key={name}
              data-testid={`flow-step-${name}`}
              data-state={state}
              className={`flex min-w-0 items-center gap-2 ${state === "pending" ? "text-muted" : ""}`}
            >
              <span aria-hidden="true" className="w-4 shrink-0 text-center">
                {MARKS[state]}
              </span>
              <span className="shrink-0">{LABELS[name]}</span>
              {state === "started" && latestTool ? (
                <span className="truncate text-muted">· {latestTool}</span>
              ) : null}
              <span className="sr-only">{SPOKEN[state]}</span>
            </li>
          );
        })}
      </ol>
    </li>
  );
}
