/** `index.html` 하나뿐인 폴더는 파일 이름 대신 폴더 이름이 그 결과물의 이름이다. */
const INDEX_SUFFIX = "/index.html";

function basePath(path: string): string {
  return path.endsWith(INDEX_SUFFIX) ? path.slice(0, -INDEX_SUFFIX.length) : path;
}

function lastSegment(path: string): string {
  return path.split("/").at(-1) ?? path;
}

/**
 * 결과물 줄과 패널 머리에 보일 이름이다.
 *
 * <p>경로가 `index.html` 로 끝나면 그 폴더 이름을, 아니면 파일 이름을 쓴다. 같은 이름이 둘 이상이고 앞 조각이
 * 있으면 마지막 두 조각을 붙여 구분한다. 답 아래 줄과 패널 머리가 같은 이름을 보이도록 이 함수 하나만 쓴다.
 */
export function artifactNames(paths: string[]): Map<string, string> {
  const counts = new Map<string, number>();
  for (const path of paths) {
    const name = lastSegment(basePath(path));
    counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  return new Map(
    paths.map((path) => {
      const base = basePath(path);
      const segments = base.split("/");
      const name = lastSegment(base);
      return [path, (counts.get(name) ?? 0) > 1 && segments.length > 1 ? segments.slice(-2).join("/") : name];
    }),
  );
}

/** 결과물 하나만 볼 때의 이름이다. */
export function artifactName(path: string): string {
  return artifactNames([path]).get(path) ?? path;
}
