// 이관 스크립트가 파일을 만들기 전에 쓰는 경로 검사다(ADR-058).
// 보고서, 결정 파일, 묶음은 민감 본문을 담을 수 있어 git 작업 디렉터리 안에 두지 못한다.
import fs from "node:fs";
import path from "node:path";

// 아직 없는 경로여도 가장 가까운 조상의 실제 경로(심볼릭 링크를 푼 것)에서 위로 올라가며 `.git` 을 찾는다.
export function insideGitWorkTree(target: string): boolean {
  let current = path.resolve(target);
  while (!fs.existsSync(current)) {
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
  current = fs.realpathSync(current);
  for (;;) {
    if (fs.existsSync(path.join(current, ".git"))) return true;
    const parent = path.dirname(current);
    if (parent === current) return false;
    current = parent;
  }
}

// 표준 출력에는 개수만 JSON 한 줄로 낸다.
export function emit(stream: NodeJS.WriteStream, value: unknown): void {
  stream.write(`${JSON.stringify(value)}\n`);
}
