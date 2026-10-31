#!/usr/bin/env node
// 크기 JSON을 P3 일반 지적으로 바꾸고 모델 리뷰와 별도로 게시한다.
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const MARKER = "<!-- pr-size-finding -->";
const code = (value) => `<code>${String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("\n", " ").replaceAll("\r", " ")}</code>`;

export function sizeFinding(size) {
  const production = size.counts.production;
  if (production <= size.large_threshold || production > size.max_lines) return null;
  const candidates = size.split_candidates
    .filter(candidate => candidate.counts.production > 0 || candidate.kind === "move")
    .slice(0, 3);
  const boundaries = candidates.length
    ? candidates.map(candidate => `- ${candidate.kind}: ${code(candidate.name)} (운영 코드 ${candidate.counts.production}줄)`).join("\n")
    : "- 자동으로 찾은 경계 후보가 없습니다. 변경 목적에 따라 나눌 곳을 확인하세요.";
  return `${MARKER}
## 코드 리뷰: PR 크기

| 등급 | 건수 |
| --- | --- |
| 🟡 P3 보통 | 1 |

### 🟡 P3 보통: PR 이 크다

문제: 운영 코드 변경이 ${production}줄로 큰 PR 기준 ${size.large_threshold}줄을 넘었습니다. 한 번에 검토할 범위가 커져 변경 사이의 회귀를 놓치기 쉽습니다.
수정: 다음 경계 후보를 참고해 단계별 PR로 나누세요. 후보끼리는 겹칠 수 있으며 독립 PR 확정안은 아닙니다.

${boundaries}
`;
}

function gh(args, input) {
  return JSON.parse(execFileSync("gh", args, { encoding: "utf8", input, stdio: ["pipe", "pipe", "pipe"] }));
}

// 권한이 없거나 리뷰 도중 head가 바뀌면 모델 리뷰의 성공에는 영향을 주지 않는다.
export function publishSizeReview(size, repo, number, head, api = gh) {
  const body = sizeFinding(size);
  try {
    const pr = api(["pr", "view", number, "--repo", repo, "--json", "isCrossRepository,headRefOid"]);
    if (pr.isCrossRepository || pr.headRefOid !== head) return false;
    const endpoint = `repos/${repo}/pulls/${number}/reviews`;
    const reviews = api(["api", "--paginate", "--slurp", `${endpoint}?per_page=100`]).flat();
    const previous = reviews.filter(review => review.user?.login === "github-actions[bot]" && review.body?.includes(MARKER));
    const current = body && previous.find(review => review.commit_id === head && review.body === body);
    // 작은 PR로 줄였을 때도 이전 P3 지적이 현재 지적으로 남지 않게 한다.
    for (const review of previous) {
      if (review === current) continue;
      api(["api", "graphql", "-f", `query=mutation($id: ID!) {
        minimizeComment(input: {subjectId: $id, classifier: OUTDATED}) {
          minimizedComment { isMinimized }
        }
      }`, "-f", `id=${review.node_id}`]);
    }
    if (!body || current) return false;
    api(["api", endpoint, "--method", "POST", "--input", "-"], JSON.stringify({ event: "COMMENT", commit_id: head, body, comments: [] }));
    return true;
  } catch {
    return false;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [file, repo, number, head] = process.argv.slice(2);
    publishSizeReview(JSON.parse(readFileSync(file, "utf8")), repo, number, head);
  } catch {
    // 크기 JSON을 얻지 못해도 기존 리뷰를 실패시키지 않는다.
  }
}
