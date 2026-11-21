/** 푸시 구독이 제한 시간 안에 끝나지 않았다 */
export class PushSubscribeTimeoutError extends Error {
  constructor() {
    super("push subscribe timeout");
    this.name = "PushSubscribeTimeoutError";
  }
}

/** `work` 가 `timeoutMs` 안에 끝나지 않으면 `PushSubscribeTimeoutError` 로 거절한다. */
export function withTimeout<T>(
  work: Promise<T>,
  timeoutMs: number,
): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () => reject(new PushSubscribeTimeoutError()),
      timeoutMs,
    );
  });
  return Promise.race([work, deadline]).finally(() => clearTimeout(timer));
}
