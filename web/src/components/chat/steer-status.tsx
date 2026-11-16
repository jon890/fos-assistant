import type { SteerInfo } from "@/lib/chat-event";
import { STEER_CHILD_NOTICE, STEER_STATE_TEXT } from "./steer";

/**
 * steer 말풍선 아래의 상태 글과 자식 안내 줄이다(ADR-20261116 / run-steer).
 *
 * <p>대기 칸과 다른 자리에서 상태를 보여 대기 메시지와 헷갈리지 않게 한다. 안내 줄은 받은 순간 하위 에이전트나
 * 맡긴 실행이 돌았을 때만 붙는다.
 */
export function SteerStatus({ steer }: { steer: SteerInfo }) {
  return (
    <div className="mt-1 flex max-w-full flex-col items-end gap-0.5 text-xs text-muted-foreground">
      <p data-testid="steer-state" data-state={steer.state}>
        {STEER_STATE_TEXT[steer.state]}
      </p>
      {steer.child ? (
        <p data-testid="steer-child-notice">
          {STEER_CHILD_NOTICE[steer.child]}
        </p>
      ) : null}
    </div>
  );
}
