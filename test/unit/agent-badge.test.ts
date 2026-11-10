import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import {
  AGENT_COLORS,
  AGENT_DESCRIPTION_MAX_CHARS,
  AGENT_ICONS,
  AGENT_NAME_MAX_CHARS,
  autoColor,
  badgeColorClass,
  capabilitiesLine,
  initialOf,
} from "../../web/src/lib/agent-badge.ts";

const AGENT_DOMAIN = join(
  import.meta.dirname,
  "../../backend/src/main/java/com/bifos/assistant/agent/domain",
);

test("색 이름은 backend 의 AgentColor 와 같은 순서로 같다", async () => {
  const source = await readFile(join(AGENT_DOMAIN, "type/AgentColor.java"), "utf8");
  const body = source.slice(source.indexOf("{", source.indexOf("enum")) + 1, source.lastIndexOf("}"));
  const names = body.split(",").map((part) => part.trim()).filter(Boolean);
  assert.deepEqual(names, [...AGENT_COLORS]);
});

test("아이콘 목록과 소개 상한은 backend 의 AgentIntroduction 과 같다", async () => {
  const source = await readFile(join(AGENT_DOMAIN, "AgentIntroduction.java"), "utf8");
  const list = source.slice(source.indexOf("ICONS = List.of("), source.indexOf(");", source.indexOf("ICONS = List.of(")));
  const icons = [...list.matchAll(/"([^"]+)"/g)].map((match) => match[1]);
  assert.deepEqual(icons, [...AGENT_ICONS]);
  assert.match(source, new RegExp(`MAX_DESCRIPTION_CHARS = ${AGENT_DESCRIPTION_MAX_CHARS};`));
  assert.match(source, new RegExp(`MAX_NAME_CHARS = ${AGENT_NAME_MAX_CHARS};`));
});

test("자동 색은 같은 코드에 늘 같고 코드가 없으면 회색이다", () => {
  assert.equal(autoColor("a1b2c3"), autoColor("a1b2c3"));
  assert.ok(AGENT_COLORS.includes(autoColor("a1b2c3")));
  assert.equal(autoColor(null), "SLATE");
});

test("고른 색이 있으면 그 색을, 모르는 값이면 자동 색을 쓴다", () => {
  assert.equal(badgeColorClass("ROSE", "x"), "bg-agent-rose-soft text-agent-rose");
  assert.equal(badgeColorClass("PINK", "x"), badgeColorClass(null, "x"));
});

test("머리글자와 할 수 있는 일 줄", () => {
  assert.equal(initialOf(" 비서"), "비");
  assert.equal(initialOf(null), "?");
  assert.equal(capabilitiesLine({ connectors: ["네이버 쇼핑"], skills: ["장보기"] }), "네이버 쇼핑 · 장보기");
  assert.equal(capabilitiesLine(undefined), "");
});
