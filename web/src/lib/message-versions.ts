export type VersionedMessage = {
  id: number;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  replacesMessageId: number | null;
  /** 도는 실행에 끼워 넣은 사용자 글(steer 말풍선)이면 찬다. 그런 `USER` 는 turn 을 열지 않는다 */
  steer?: unknown;
};

/** 판 사슬의 첫 메시지 번호와 현재 판의 자리다. */
export type VersionSlot = { slotId: number; index: number; count: number };

export type FoldedTurn<T extends VersionedMessage> = {
  /** turn 을 여는 줄이다. `role` 이 `SYSTEM` 이면 질문이 아니라 알림 줄이다 */
  user: T;
  userVersion: VersionSlot;
  /**
   * 이 turn 사이에 끼워 넣은 steer 말풍선이다. 행 번호 순서다. 화면은 답과 행 번호로 섞어 그 답 바로 위에 그린다
   * (ADR-20261116 / run-steer).
   */
  steers: T[];
  /** `latestId` 는 그 판 사슬의 마지막 행 번호다. 어느 판을 고르든 steer 와의 순서를 같게 정할 때 쓴다 */
  answers: { message: T; version: VersionSlot; latestId: number }[];
};

/**
 * 대신된 판까지 포함한 메시지를 현재 화면에 보일 turn 으로 접는다.
 *
 * <p>`USER` 와 `SYSTEM` 이 turn 을 연다. `SYSTEM` 은 고쳐 쓸 수 없는 알림 줄이라 판 사슬을 만들지 않고 줄마다
 * turn 하나를 연다. steer 말풍선인 `USER` 는 turn 을 열지 않고 그 turn 의 `steers` 로 간다.
 */
export function foldVersions<T extends VersionedMessage>(
  messages: T[],
  selected: Record<number, number>,
): FoldedTurn<T>[] {
  const ordered = [...messages].sort((left, right) => left.id - right.id);
  const openers = ordered.filter(
    (message) =>
      (message.role === "USER" && !isSteer(message)) ||
      message.role === "SYSTEM",
  );
  const openerChains = [
    ...chainsOf(openers.filter((message) => message.role === "USER")),
    ...openers
      .filter((message) => message.role === "SYSTEM")
      .map((message) => [message]),
  ].sort((left, right) => left[0].id - right[0].id);

  return openerChains.map((chain) => {
    const userIndex = chosenIndex(chain, selected[chain[0].id]);
    const user = chain[userIndex];
    const nextOpenerId =
      openers.find((message) => message.id > user.id)?.id ?? Infinity;
    const between = (message: T) =>
      message.id > user.id && message.id < nextOpenerId;
    const answers = ordered.filter(
      (message) => message.role === "ASSISTANT" && between(message),
    );

    return {
      user,
      userVersion: {
        slotId: chain[0].id,
        index: userIndex,
        count: chain.length,
      },
      steers: ordered.filter(
        (message) =>
          message.role === "USER" && isSteer(message) && between(message),
      ),
      answers: chainsOf(answers).map((answerChain) => {
        const index = chosenIndex(answerChain, selected[answerChain[0].id]);
        return {
          message: answerChain[index],
          version: {
            slotId: answerChain[0].id,
            index,
            count: answerChain.length,
          },
          latestId: answerChain[answerChain.length - 1].id,
        };
      }),
    };
  });
}

/** 마지막 turn 과 그 마지막 답이 모두 최신 판인지 본다. */
export function isLatestView<T extends VersionedMessage>(
  folded: FoldedTurn<T>[],
): boolean {
  const last = folded.at(-1);
  if (!last || last.userVersion.index !== last.userVersion.count - 1)
    return false;
  const answer = last.answers.at(-1);
  return !answer || answer.version.index === answer.version.count - 1;
}

function isSteer(message: VersionedMessage): boolean {
  return message.steer !== undefined && message.steer !== null;
}

function chosenIndex<T>(chain: T[], requested: number | undefined): number {
  return Number.isInteger(requested) &&
    requested! >= 0 &&
    requested! < chain.length
    ? requested!
    : chain.length - 1;
}

function chainsOf<T extends VersionedMessage>(messages: T[]): T[][] {
  const chains = new Map<number, T[]>();
  const slotByMessageId = new Map<number, number>();
  for (const message of messages) {
    const parentSlot =
      message.replacesMessageId === null
        ? undefined
        : slotByMessageId.get(message.replacesMessageId);
    const slotId = parentSlot ?? message.id;
    const chain = chains.get(slotId) ?? [];
    chain.push(message);
    chains.set(slotId, chain);
    slotByMessageId.set(message.id, slotId);
  }
  return [...chains.values()];
}
