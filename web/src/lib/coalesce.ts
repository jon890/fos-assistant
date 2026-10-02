/**
 * 비동기 읽기를 겹쳐 내지 않게 묶는다.
 *
 * <p>읽는 중에 다시 불리면 새 요청을 내지 않고 진행 중인 읽기에 얹힌다. 다만 진행 중인 읽기는 두 번째 호출이 알리려는 변경보다
 * 먼저 나갔을 수 있어, 끝난 뒤 정확히 한 번 더 읽는다. 몇 번을 불러도 읽기는 진행 중 하나와 뒤따르는 하나, 많아야 둘이다.
 * 돌려주는 약속은 호출 시점 이후의 읽기가 끝나면 풀린다.
 */
export function coalesce(read: () => Promise<void>): () => Promise<void> {
  let running: { rerun: boolean; done: Promise<void> } | null = null;
  return () => {
    if (running) {
      running.rerun = true;
      return running.done;
    }
    const state = { rerun: false, done: Promise.resolve() };
    running = state;
    state.done = (async () => {
      try {
        do {
          state.rerun = false;
          await read();
        } while (state.rerun);
      } finally {
        running = null;
      }
    })();
    return state.done;
  };
}
