type Props = {
  waiting: boolean;
  toolEvents: string[];
};

export function RunStatus({ waiting, toolEvents }: Props) {
  const latestTool = toolEvents.at(-1);
  return (
    <>
      {latestTool ? (
        <li className="text-xs text-muted" aria-live="polite">
          {latestTool}
        </li>
      ) : null}
      {waiting ? (
        <li
          aria-label="비서의 답을 기다리는 중"
          className="rounded-md border border-border px-3 py-2 text-sm text-muted"
        >
          <span className="motion-reduce:hidden" aria-hidden="true">
            비서&nbsp;
            <span className="inline-block animate-pulse">●</span>
            <span className="inline-block animate-pulse [animation-delay:150ms]">●</span>
            <span className="inline-block animate-pulse [animation-delay:300ms]">●</span>
          </span>
          <span className="hidden motion-reduce:inline">비서가 답을 준비하고 있다.</span>
        </li>
      ) : null}
    </>
  );
}
