import type { MessageArtifact } from "@/components/chat/message-types";

/**
 * 대화 단위 SSE 의 `image` 사건으로 받은 그림 경로를 받은 순서대로 쌓는다. 같은 경로는 한 번만 둔다
 * (generated-image 설계의 PR7).
 */
export function receiveLiveImage(previous: string[], path: string): string[] {
  return previous.includes(path) ? previous : [...previous, path];
}

/**
 * 흐르는 답의 그림 목록이다. 답에 이미 든 그림 뒤에, 아직 답에 묶이지 않은 임시 그림을 받은 순서대로 붙인다.
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
