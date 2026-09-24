export type VersionedMessage = {
  id: number;
  role: "USER" | "ASSISTANT";
  replacesMessageId: number | null;
};

/** 판 사슬의 첫 메시지 번호와 현재 판의 자리다. */
export type VersionSlot = { slotId: number; index: number; count: number };

export type FoldedTurn<T extends VersionedMessage> = {
  user: T;
  userVersion: VersionSlot;
  answers: { message: T; version: VersionSlot }[];
};

/** 대신된 판까지 포함한 메시지를 현재 화면에 보일 turn 으로 접는다. */
export function foldVersions<T extends VersionedMessage>(
  messages: T[],
  selected: Record<number, number>,
): FoldedTurn<T>[] {
  const ordered = [...messages].sort((left, right) => left.id - right.id);
  const users = ordered.filter((message) => message.role === "USER");
  const userChains = chainsOf(users);

  return userChains.map((chain) => {
    const userIndex = chosenIndex(chain, selected[chain[0].id]);
    const user = chain[userIndex];
    const nextUserId = users.find((message) => message.id > user.id)?.id ?? Infinity;
    const answers = ordered.filter((message) =>
      message.role === "ASSISTANT" && message.id > user.id && message.id < nextUserId);

    return {
      user,
      userVersion: { slotId: chain[0].id, index: userIndex, count: chain.length },
      answers: chainsOf(answers).map((answerChain) => {
        const index = chosenIndex(answerChain, selected[answerChain[0].id]);
        return {
          message: answerChain[index],
          version: { slotId: answerChain[0].id, index, count: answerChain.length },
        };
      }),
    };
  });
}

/** 마지막 turn 과 그 마지막 답이 모두 최신 판인지 본다. */
export function isLatestView<T extends VersionedMessage>(folded: FoldedTurn<T>[]): boolean {
  const last = folded.at(-1);
  if (!last || last.userVersion.index !== last.userVersion.count - 1) return false;
  const answer = last.answers.at(-1);
  return !answer || answer.version.index === answer.version.count - 1;
}

function chosenIndex<T>(chain: T[], requested: number | undefined): number {
  return Number.isInteger(requested) && requested! >= 0 && requested! < chain.length
    ? requested!
    : chain.length - 1;
}

function chainsOf<T extends VersionedMessage>(messages: T[]): T[][] {
  const chains = new Map<number, T[]>();
  const slotByMessageId = new Map<number, number>();
  for (const message of messages) {
    const parentSlot = message.replacesMessageId === null
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
