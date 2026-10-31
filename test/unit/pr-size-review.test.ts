import assert from "node:assert/strict";
import test from "node:test";
import { LARGE_THRESHOLD, MAX_LINES } from "../../scripts/pr/pr-size.mjs";
import { publishSizeReview, sizeFinding } from "../../scripts/pr/pr-size-review.mjs";

const candidate = (kind = "area", name = "web", production = 100) => ({ kind, name, counts: { production } });
const size = (production, split_candidates = []) => ({
  counts: { production }, large_threshold: LARGE_THRESHOLD, max_lines: MAX_LINES, split_candidates,
});

test("큰 PR 기준 이하는 게시하지 않고 상한까지 P3 지적을 만든다", () => {
  for (const lines of [0, LARGE_THRESHOLD]) assert.equal(sizeFinding(size(lines)), null);
  for (const lines of [LARGE_THRESHOLD + 1, MAX_LINES]) {
    const body = sizeFinding(size(lines));
    assert.match(body, /🟡 P3 보통: PR 이 크다/);
    assert.ok(body.includes(`운영 코드 변경이 ${lines}줄`));
  }
  assert.equal(sizeFinding(size(MAX_LINES + 1)), null);
});

test("경계 후보는 순서를 유지해 일부만 보여 주고 후보가 없으면 직접 확인을 안내한다", () => {
  const body = sizeFinding(size(LARGE_THRESHOLD + 1, [candidate("phase", "phase"), candidate("move", "before → after", 0), candidate(), candidate("area", "backend")]));
  for (const kind of ["phase", "move", "area"]) assert.ok(body.includes(`- ${kind}:`));
  assert.ok(!body.includes("backend"));
  assert.match(body, /후보끼리는 겹칠 수/);
  assert.match(sizeFinding(size(LARGE_THRESHOLD + 1)), /경계 후보가 없습니다/);
  assert.match(sizeFinding(size(LARGE_THRESHOLD + 1, [candidate("area", "test", 0)])), /경계 후보가 없습니다/);
  const escaped = sizeFinding(size(LARGE_THRESHOLD + 1, [candidate("area", "<script>\n<!-- hidden -->")]));
  assert.ok(!escaped.includes("<script>"));
  assert.ok(escaped.includes("&lt;script&gt;"));
});

test("현재 head에 COMMENT 리뷰를 게시하고 같은 head의 기존 크기 지적은 중복 게시하지 않는다", () => {
  const calls = [];
  const api = (args, input) => {
    calls.push({ args, input });
    if (args[0] === "pr") return { isCrossRepository: false, headRefOid: "head" };
    if (args.includes("--slurp")) return [[], []];
    return {};
  };
  const large = size(LARGE_THRESHOLD + 1);
  assert.equal(publishSizeReview(large, "owner/repo", "1", "head", api), true);
  const payload = JSON.parse(calls.at(-1).input);
  assert.equal(payload.event, "COMMENT");
  assert.equal(payload.commit_id, "head");
  assert.deepEqual(payload.comments, []);
  assert.match(payload.body, /P3 보통/);
  const duplicate = (args) => args[0] === "pr"
    ? { headRefOid: "head" }
    : [[{ user: { login: "github-actions[bot]" }, commit_id: "head", body: payload.body }]];
  assert.equal(publishSizeReview(large, "owner/repo", "1", "head", duplicate), false);
});

test("작은 PR·상한 초과·fork·head 변경·권한 부족은 조용히 건너뛴다", () => {
  const fail = () => { throw new Error("403"); };
  for (const lines of [LARGE_THRESHOLD, MAX_LINES + 1]) {
    assert.equal(publishSizeReview(size(lines), "owner/repo", "1", "head", () => assert.fail("API를 호출하지 않아야 함")), false);
  }
  for (const pr of [{ isCrossRepository: true, headRefOid: "head" }, { headRefOid: "new-head" }]) {
    assert.equal(publishSizeReview(size(LARGE_THRESHOLD + 1), "owner/repo", "1", "head", () => pr), false);
  }
  assert.equal(publishSizeReview(size(LARGE_THRESHOLD + 1), "owner/repo", "1", "head", fail), false);
  for (const failAt of [2, 3]) {
    let calls = 0;
    const api = () => { if (++calls === failAt) return fail(); return calls === 1 ? { headRefOid: "head" } : [[]]; };
    assert.equal(publishSizeReview(size(LARGE_THRESHOLD + 1), "owner/repo", "1", "head", api), false);
  }
});

test("워크플로는 기본 브랜치 도구로 JSON을 얻고 모델 리뷰 뒤에 크기 리뷰를 게시한다", async () => {
  const { readFileSync } = await import("node:fs");
  const { spawnSync } = await import("node:child_process");
  const workflow = readFileSync(".github/workflows/claude-code-review.yml", "utf8");
  const step = workflow.split("- name: 큰 PR 에 P3 크기 리뷰 게시")[1].split("      # 실행 기록")[0];
  assert.match(step, /if: steps.review.outcome == 'success'/);
  assert.match(step, /continue-on-error: true/);
  assert.match(step, /SIZE_SCRIPT=.review-tools\/scripts\/pr\/pr-size.mjs/);
  assert.match(step, /REVIEW_SCRIPT=.review-tools\/scripts\/pr\/pr-size-review.mjs/);
  const script = step.split("run: |\n")[1].split("\n").map(line => line.replace(/^          /, "")).join("\n");
  // 실제 checkout은 건드리지 않고 shell의 명령 인자 전달을 확인한다.
  const result = spawnSync("bash", ["-euc", `
    gh() { echo base-sha; }
    git() { if [ "$1" = rev-parse ]; then echo false; fi; }
    node() {
      if [[ "$1" == *pr-size.mjs ]]; then
        [ "$2 $3 $4" = "base-sha head-sha --json" ] || exit 9
        echo '{"counts":{"production":401}}'
        return 1
      fi
      [ "$3 $4 $5" = "owner/repo 1 head-sha" ] || exit 10
      cat "$2"
    }
    ${script.replace('if [ ! -f "$SIZE_SCRIPT" ] || [ ! -f "$REVIEW_SCRIPT" ]; then exit 0; fi', '')}
  `], { encoding: "utf8", env: { ...process.env, PR_NUMBER: "1", REPO: "owner/repo", REVIEW_HEAD: "head-sha", RUNNER_TEMP: process.env.TMPDIR || "/tmp" } });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /"production":401/);
});
