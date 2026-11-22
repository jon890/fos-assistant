import type { MessageArtifact } from "@/components/chat/message-types";

/**
 * 아직 답에 묶이지 않은 그림 목록 응답을 경로 목록으로 읽는다. 꼴이 맞지 않으면 null 이다
 * (generated-image 설계의 「PR7: 나오는 즉시 보이기」).
 */
export function parseLiveImages(body: unknown): string[] | null {
  if (!Array.isArray(body)) return null;
  return body.filter((path): path is string => typeof path === "string");
}

/**
 * 화면에 이미 든 답에 묶인 그림을 뺀다. 답이 끝나 이력을 다시 읽으면 그 답의 그림은 목록에서 빠진다.
 */
export function unboundLiveImages(
  livePaths: string[],
  turns: { artifacts?: MessageArtifact[] }[],
): string[] {
  const bound = new Set(
    turns.flatMap((turn) =>
      (turn.artifacts ?? []).map((artifact) => artifact.path),
    ),
  );
  return livePaths.filter((path) => !bound.has(path));
}

/**
 * 흐르는 답의 그림 목록이다. 답에 이미 든 그림 뒤에, 아직 답에 묶이지 않은 임시 그림을 저장한 순서대로 붙인다.
 *
 * <p>임시 그림은 저장한 답이 아니어서 지시문과 보관 상태가 없다. 답이 끝나 이력을 다시 읽으면 저장된 그림으로 바뀐다.
 */
export function withLiveImages(
  artifacts: MessageArtifact[],
  livePaths: string[],
): MessageArtifact[] {
  const known = new Set(artifacts.map((artifact) => artifact.path));
  const live: MessageArtifact[] = livePaths
    .filter((path) => !known.has(path))
    .map((path) => ({ path, byteSize: 0, deleted: false, kind: "IMAGE" }));
  return [...artifacts, ...live];
}
