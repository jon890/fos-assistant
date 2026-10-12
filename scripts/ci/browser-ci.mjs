import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const LABEL = "브라우저 실패";

export function latestJobs(jobs) {
  const grouped = Map.groupBy(jobs, (job) => job.name);
  return [...grouped.values()].flatMap((executions) => {
    const attempt = Math.max(...executions.map((job) => job.run_attempt));
    return executions.filter((job) => job.run_attempt === attempt);
  });
}

export function checkShards(jobs, project, total) {
  const expected = Array.from(
    { length: total },
    (_, index) => `browser-${project}-shard-${index + 1}`,
  );
  for (const name of expected) {
    const matching = jobs.filter((job) => job.name === name);
    if (matching.length !== 1 || matching[0].conclusion !== "success") {
      throw new Error(`${name}: 성공한 shard 결과가 없거나 중복됐다`);
    }
  }
}

// 이슈는 공개된다. 경로, 주소, 인증값과 오류에 인용된 데이터는 보내지 않는다.
export function publicError(message) {
  return String(message ?? "오류 내용 없음")
    .replace(/\u001b\[[0-9;]*m/g, "")
    .split("\n")
    .filter((line) => line.trim())
    .slice(0, 2)
    .map((line) =>
      line
        .replace(/https?:\/\/\S+/g, "[주소 생략]")
        .replace(/(?:[A-Za-z]:\\|\/)[^\s)]+/g, "[경로 생략]")
        .replace(
          /(?:Bearer\s+\S+|(?:token|secret|password|key)\s*[:=]\s*\S+)/gi,
          "[인증값 생략]",
        )
        .replace(/(["'`]).*?\1/g, "[인용값 생략]")
        .replace(/[<>]/g, "")
        .slice(0, 240),
    )
    .join("\n");
}

export function collectFailures(report, project) {
  const failures = [];
  function visit(suite, titles = []) {
    const ancestors = [...titles, suite.title].filter(Boolean);
    for (const spec of suite.specs ?? []) {
      const tests = (spec.tests ?? []).filter((test) =>
        ["unexpected", "flaky"].includes(test.status),
      );
      for (const test of tests) {
        const result = test.results?.find((item) =>
          ["failed", "timedOut", "interrupted"].includes(item.status),
        );
        const file = String(spec.file ?? suite.file).replaceAll("\\", "/");
        // JSON reporter 의 파일은 testDir 기준 상대 경로다. 절대 경로는 게시하지 않는다.
        if (
          file.startsWith("/") ||
          /^[A-Za-z]:/.test(file) ||
          file.includes("..")
        ) {
          throw new Error("브라우저 보고서 파일 경로가 상대 경로가 아니다");
        }
        failures.push({
          file,
          project,
          title: [...ancestors, spec.title].join(" › "),
          error: publicError(
            result?.error?.message ?? result?.errors?.[0]?.message,
          ),
        });
      }
    }
    for (const child of suite.suites ?? []) visit(child, ancestors);
  }
  for (const suite of report.suites ?? []) visit(suite);
  return failures;
}

export async function collectJobFailures(jobs, readReport) {
  const failures = [];
  const shards = jobs.filter((job) =>
    /^browser-(mobile|desktop)-shard-\d+$/.test(job.name),
  );
  for (const job of shards) {
    if (job.conclusion === "success") continue;
    const [, project, shard] = job.name.match(
      /^browser-(mobile|desktop)-shard-(\d+)$/,
    );
    let found = [];
    try {
      const report = await readReport(
        `browser-report-${project}-${shard}.json`,
      );
      found = collectFailures(report, project);
      if (found.length === 0 && report.errors?.length) {
        found.push({
          file: `shard-${shard}-setup`,
          project,
          title: "검사 기동 또는 전역 설정 실패",
          error: publicError(report.errors[0].message),
        });
      }
    } catch {
      // 취소 도중 잘린 JSON 도 다른 shard 의 실패 집계를 막지 않는다.
      found.push({
        file: `shard-${shard}-setup`,
        project,
        title: "JSON 결과가 없거나 읽을 수 없는 shard 실패",
        error: `job 결과: ${job.conclusion}`,
      });
    }
    if (found.length === 0) {
      found.push({
        file: `shard-${shard}-setup`,
        project,
        title: "실패한 테스트 기록 없이 shard 실패 또는 취소",
        error: `job 결과: ${job.conclusion}`,
      });
    }
    failures.push(...found);
  }
  return failures;
}

// 처리하는 사람이 이슈에서 바로 읽도록 새 이슈 본문 끝에 붙인다. 재발 댓글에는 붙이지 않는다.
export const HANDLING =
  "PR 에서 난 실패는 그 PR 에서 고친다. 이 이슈는 main 과 매일 실행에서 모인 실패다. 고치거나, 고치지 않는 까닭을 적어 닫는다.";

function markerFor(file, project) {
  const hash = createHash("sha256").update(`${project}:${file}`).digest("hex");
  return `<!-- browser-failure:${hash} -->`;
}

export async function publishFailures(failures, api, context) {
  if (failures.length === 0) return;
  const prefix = `repos/${context.repository}`;
  const labels = await api("GET", `${prefix}/labels?per_page=100`);
  if (!labels.some((label) => label.name === LABEL)) {
    await api("POST", `${prefix}/labels`, {
      name: LABEL,
      color: "D73A4A",
      description: "main 과 매일 실행에서 발견한 브라우저 검사 실패",
    });
  }
  const open = await api(
    "GET",
    `${prefix}/issues?state=open&labels=${encodeURIComponent(LABEL)}&per_page=100`,
  );
  const groups = Map.groupBy(failures, (failure) =>
    markerFor(failure.file, failure.project),
  );
  const occurrence = `<!-- browser-run:${context.runId}:${context.attempt} -->`;
  const link = `${context.serverUrl}/${context.repository}/actions/runs/${context.runId}/attempts/${context.attempt}`;
  for (const [marker, group] of groups) {
    const first = group[0];
    const details = group
      .map(
        (failure) =>
          `- 테스트: ${failure.title}\n\n\`\`\`text\n${failure.error.replaceAll("```", "")}\n\`\`\``,
      )
      .join("\n\n");
    const body = `${marker}\n${occurrence}\n\n- 파일: \`${first.file}\`\n- 폭: \`${first.project}\`\n- 실행: [CI 결과](${link})\n\n${details}`;
    const existing = open.find((issue) => issue.body?.includes(marker));
    if (existing) {
      const comments = await api(
        "GET",
        `${prefix}/issues/${existing.number}/comments?per_page=100`,
      );
      if (
        existing.body.includes(occurrence) ||
        comments.some((comment) => comment.body.includes(occurrence))
      )
        continue;
      const count =
        2 +
        comments.filter((comment) => comment.body.includes("<!-- browser-run:"))
          .length;
      await api("POST", `${prefix}/issues/${existing.number}/comments`, {
        body: `재발 ${count}회\n\n${body}`,
      });
    } else {
      const created = await api("POST", `${prefix}/issues`, {
        title: `[브라우저 실패] ${first.project}: ${first.file}`,
        labels: [LABEL],
        body: `${body}\n\n${HANDLING}`,
      });
      open.push(created);
    }
  }
}

export function githubApi(token, fetcher = fetch) {
  return async function api(method, path, body) {
    let url = `https://api.github.com/${path}`;
    const items = [];
    while (url) {
      const response = await fetcher(url, {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      if (!response.ok)
        throw new Error(`GitHub API ${method} 요청 실패: ${response.status}`);
      const data = await response.json();
      const page = Array.isArray(data) ? data : data.jobs;
      if (!page) return data;
      items.push(...page);
      url = response.headers.get("link")?.match(/<([^>]+)>; rel="next"/)?.[1];
    }
    return items;
  };
}

async function main() {
  const [command, argument, count] = process.argv.slice(2);
  const context = {
    repository: process.env.GITHUB_REPOSITORY,
    runId: process.env.GITHUB_RUN_ID,
    attempt: process.env.GITHUB_RUN_ATTEMPT,
    serverUrl: process.env.GITHUB_SERVER_URL,
  };
  const api = githubApi(process.env.GH_TOKEN);
  // 실패한 job 만 재실행하면 앞 attempt 에서 성공한 shard 는 다시 돌지 않는다.
  const jobs = latestJobs(
    await api(
      "GET",
      `repos/${context.repository}/actions/runs/${context.runId}/jobs?filter=all&per_page=100`,
    ),
  );
  if (command === "check") {
    checkShards(jobs, argument, Number(count));
    console.log(`${argument}: 모든 shard 성공`);
    return;
  }
  if (command !== "issues") throw new Error("지원하지 않는 명령");
  if (
    !["push", "schedule"].includes(process.env.GITHUB_EVENT_NAME) ||
    process.env.GITHUB_REF !== "refs/heads/main"
  ) {
    throw new Error("main push 와 schedule 에서만 실패 이슈를 등록한다");
  }
  const failures = await collectJobFailures(jobs, async (filename) =>
    JSON.parse(await readFile(resolve(argument, filename), "utf8")),
  );
  await publishFailures(failures, api, context);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  await main();
}
