#!/usr/bin/env node
// main 에 없던 SQL 은 UTC 시각 버전을 쓴다. 기존 파일의 체크섬 검사는 별도로 유지한다.
import { execFileSync } from "node:child_process";
import { readdirSync } from "node:fs";
import { basename, resolve } from "node:path";

const directory = "backend/src/main/resources/db/migration";
const base = process.argv[2] ?? "origin/main";

try {
  const existing = new Set(
    execFileSync("git", ["ls-tree", "-r", "--name-only", "-z", base, "--", directory], {
      encoding: "utf8",
    }).split("\0"),
  );
  const files = readdirSync(resolve(directory), { recursive: true })
    .filter((name) => name.endsWith(".sql"))
    .map((name) => `${directory}/${name}`);
  const versions = new Map();
  const errors = [];
  const latest = Date.now() + 24 * 60 * 60 * 1000;
  for (const file of files) {
    const name = basename(file);
    const version = /^V(.+?)__/.exec(name)?.[1];
    if (version && /^\d+(?:[._]\d+)*$/.test(version)) {
      // Flyway 는 선행 0 과 끝의 0 구간을 버리고 점과 밑줄을 같은 구분자로 읽는다.
      const parts = version.split(/[._]/).map((part) => BigInt(part).toString());
      while (parts.length > 1 && parts.at(-1) === "0") parts.pop();
      const key = parts.join(".");
      if (versions.has(key)) errors.push(`버전 중복: ${versions.get(key)}, ${file}`);
      else versions.set(key, file);
    }
    if (existing.has(file)) continue;
    const match = /^V([1-9]\d{13})__.+\.sql$/.exec(name);
    if (!match) {
      errors.push(`새 파일은 V<YYYYMMDDHHMMSS>__<설명>.sql 형식이어야 한다: ${file}`);
      continue;
    }
    const stamp = match[1];
    const iso = `${stamp.slice(0, 4)}-${stamp.slice(4, 6)}-${stamp.slice(6, 8)}T${stamp.slice(8, 10)}:${stamp.slice(10, 12)}:${stamp.slice(12, 14)}Z`;
    const instant = new Date(iso);
    if (!Number.isFinite(instant.getTime()) || instant.toISOString().replace(/\D/g, "").slice(0, 14) !== stamp) {
      errors.push(`올바른 UTC 날짜와 시각이 아니다: ${file}`);
    } else if (instant.getTime() > latest) {
      errors.push(`시각이 지금보다 1일 넘게 미래다: ${file}`);
    }
  }
  if (errors.length) throw new Error(errors.join("\n"));
  console.log(`마이그레이션 버전 검사 통과 (기준: ${base})`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
