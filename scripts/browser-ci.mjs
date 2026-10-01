import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const LABEL = "브라우저 실패";

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
        body,
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
  const jobs = await api(
    "GET",
    `repos/${context.repository}/actions/runs/${context.runId}/attempts/${context.attempt}/jobs?per_page=100`,
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
  const failures = [];
  const names = await readdir(argument).catch((error) => {
    if (error.code === "ENOENT") return [];
    throw error;
  });
  for (const job of jobs.filter((item) =>
    /^browser-(mobile|desktop)-shard-\d+$/.test(item.name),
  )) {
    if (job.conclusion === "success") continue;
    const [, project, shard] = job.name.match(
      /^browser-(mobile|desktop)-shard-(\d+)$/,
    );
    const filename = `browser-report-${project}-${shard}.json`;
    let found = [];
    if (names.includes(filename)) {
      const report = JSON.parse(
        await readFile(resolve(argument, filename), "utf8"),
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
    }
    if (found.length === 0) {
      found.push({
        file: `shard-${shard}-setup`,
        project,
        title: "JSON 결과 없이 shard 실패 또는 취소",
        error: `job 결과: ${job.conclusion}`,
      });
    }
    failures.push(...found);
  }
  await publishFailures(failures, api, context);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  await main();
}
