import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

// 본문과 도구 입력은 공개 로그에 남기지 않는다.
export function summarizeExecution(messages) {
  const tools = Object.create(null);
  let assistantCount = 0;
  let lastMessageLength = 0;
  let stopReason = null;
  let turns = null;

  for (const entry of messages) {
    if (entry.type === "result" && Number.isInteger(entry.num_turns)) {
      turns = entry.num_turns;
    }
    if (entry.type !== "assistant") continue;

    assistantCount += 1;
    const content = entry.message?.content ?? [];
    lastMessageLength = content
      .filter(
        (block) => block.type === "text" && typeof block.text === "string",
      )
      .reduce((length, block) => length + [...block.text].length, 0);

    for (const block of content) {
      if (block.type !== "tool_use") continue;
      const name = /^[A-Za-z][A-Za-z0-9_]{0,79}$/.test(block.name ?? "")
        ? block.name
        : "other";
      tools[name] = (tools[name] ?? 0) + 1;
    }
    const reason = entry.message?.stop_reason;
    if (reason != null) {
      const knownReasons = [
        "end_turn",
        "max_tokens",
        "stop_sequence",
        "tool_use",
        "pause_turn",
        "refusal",
      ];
      stopReason = knownReasons.includes(reason) ? reason : "other";
    }
  }
  return {
    turns: turns ?? assistantCount,
    tools,
    lastMessageLength,
    stopReason,
  };
}

export function readExecution(source) {
  try {
    const value = JSON.parse(source);
    return Array.isArray(value) ? value : [value];
  } catch {
    return source
      .split(/\r?\n/)
      .filter((line) => line.trim())
      .map((line) => JSON.parse(line));
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  try {
    const messages = readExecution(
      readFileSync(process.env.EXECUTION_FILE, "utf8"),
    );
    console.log(JSON.stringify(summarizeExecution(messages)));
  } catch {
    // 예외 메시지에도 실행 본문이 포함될 수 있어 고정 문구만 남긴다.
    console.log("리뷰 실행 진단 정보를 읽지 못했습니다.");
  }
}
