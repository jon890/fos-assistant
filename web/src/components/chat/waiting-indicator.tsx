const DOT = "size-1.5 animate-pulse rounded-full bg-foreground-soft";

/** 첫 사건을 기다리는 동안 답이 들어올 자리에 두는 점 셋이다. 높이는 답 본문 한 줄과 같다. */
export function WaitingIndicator() {
  return (
    <div
      role="status"
      aria-label="비서의 답을 기다리는 중"
      data-testid="waiting-indicator"
      className="flex min-h-7 items-center text-sm text-muted-foreground"
    >
      <span
        className="flex items-center gap-1 motion-reduce:hidden"
        aria-hidden="true"
      >
        <span className={DOT} />
        <span className={`${DOT} [animation-delay:150ms]`} />
        <span className={`${DOT} [animation-delay:300ms]`} />
      </span>
      <span className="hidden motion-reduce:inline">
        비서가 답을 준비하고 있어요.
      </span>
    </div>
  );
}
