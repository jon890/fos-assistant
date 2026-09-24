export function WaitingIndicator() {
  return (
    <li aria-label="비서의 답을 기다리는 중"
      className="grid grid-cols-[2rem_minmax(0,1fr)] gap-2 text-sm text-muted">
      <span aria-hidden="true" className="flex h-8 w-8 items-center justify-center rounded-full bg-brand text-sm font-semibold text-on-brand">비</span>
      <span className="flex min-h-8 items-center gap-1 motion-reduce:hidden" aria-hidden="true">
        <span className="mr-1 font-medium text-foreground">비서</span>
        <span className="inline-block animate-pulse text-brand">●</span>
        <span className="inline-block animate-pulse text-brand [animation-delay:150ms]">●</span>
        <span className="inline-block animate-pulse text-brand [animation-delay:300ms]">●</span>
      </span>
      <span className="hidden min-h-8 items-center motion-reduce:flex">비서가 답을 준비하고 있다.</span>
    </li>
  );
}
