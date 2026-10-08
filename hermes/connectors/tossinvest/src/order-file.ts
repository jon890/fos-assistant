import { link, lstat, open, readdir, unlink, type FileHandle } from "node:fs/promises";
import { join } from "node:path";
import type { Tossinvest } from "./client.ts";
import {
  ORDER_FILE_DEADLINE_MS,
  ORDER_FILE_MAX_PAGES,
  ORDER_FILE_PAGE_PAUSE_MS,
  ORDER_FILE_RATE_LIMIT_PAUSE_MS,
  ORDER_FILE_TTL_MS,
  ORDERS_MAX,
} from "./constants.ts";
import { TossinvestError } from "./errors.ts";
import { isObject, orderRow } from "./orders.ts";

/** 시각, 쉼, 난수, 임시 파일 열기다. 시험은 이것을 고정해 쉼 없이 돌리고, 파일 쓰기의 실패를 흉내 낸다. */
export interface OrderFileHooks {
  now: () => Date;
  sleep: (ms: number) => Promise<void>;
  /** 파일 이름 꼬리에 붙는 8자 hex 다. */
  random: () => string;
  /** 임시 파일을 새로 만든다. 이미 있으면(링크 포함) 실패해야 한다. */
  open: (path: string) => Promise<FileHandle>;
}

export interface OrderFileResult {
  file: string;
  count: number;
  from: string | null;
  to: string | null;
  fields: string[];
}

const FINAL_NAME = /^orders-.*\.jsonl$/;
const TEMP_NAME = /^\.orders-.*\.tmp$/;

const hex8 = () =>
  Array.from(crypto.getRandomValues(new Uint8Array(4)), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");

export const defaultHooks: OrderFileHooks = {
  now: () => new Date(),
  sleep: (ms) => Bun.sleep(ms),
  random: hex8,
  open: (path) => open(path, "wx"),
};

const unavailable = () => new TossinvestError("TOSSINVEST_OUTPUT_UNAVAILABLE");

/** `2026-10-08T01:02:03.456Z` 를 `20261008T010203Z` 로 줄인다. */
const stamp = (date: Date) =>
  date.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");

/**
 * 출력 디렉터리가 쓸 수 있는 곳인지 본다. 커넥터는 실행 공간 밖에서 돌고 이 디렉터리는 실행 공간에 읽기 전용으로 붙는다.
 * 그래도 링크는 받지 않는다. 바인딩 설치가 링크 없이 만든 디렉터리만 쓴다.
 */
async function ensureDirectory(directory: string) {
  if (directory.trim() === "") throw unavailable();
  let stat;
  try {
    stat = await lstat(directory);
  } catch {
    throw unavailable();
  }
  if (stat.isSymbolicLink() || !stat.isDirectory()) throw unavailable();
}

/** 이 커넥터가 만든 이름 가운데 24시간 지난 정규 파일만 지운다. 다른 이름과 링크는 건드리지 않는다. */
async function removeExpired(directory: string, now: Date) {
  let names: string[];
  try {
    names = await readdir(directory);
  } catch {
    throw unavailable();
  }
  for (const name of names) {
    if (!FINAL_NAME.test(name) && !TEMP_NAME.test(name)) continue;
    const path = join(directory, name);
    try {
      const stat = await lstat(path);
      if (stat.isFile() && now.getTime() - stat.mtimeMs > ORDER_FILE_TTL_MS)
        await unlink(path);
    } catch {
      // 사이에 사라졌거나 지우지 못한 파일은 다음 쓰기와 운영의 정기 정리에 맡긴다.
    }
  }
}

/** 한 쪽을 받는다. 429 면 1초 쉬고 같은 쪽을 한 번만 다시 부른다. */
async function fetchPage(
  client: Tossinvest,
  query: URLSearchParams,
  hooks: OrderFileHooks,
): Promise<Record<string, any>> {
  const call = () => client.request("/api/v1/orders", { query, account: true });
  let data: any;
  try {
    data = await call();
  } catch (error) {
    if (!(error instanceof TossinvestError) || error.code !== "TOSSINVEST_RATE_LIMITED")
      throw error;
    await hooks.sleep(ORDER_FILE_RATE_LIMIT_PAUSE_MS);
    data = await call();
  }
  return isObject(data?.result) ? data.result : {};
}

/**
 * 기간 전체의 주문을 JSON Lines 파일 하나로 쓰고 경로와 건수, 기간, 칸 목록만 돌려준다.
 * 끝난 주문은 100건씩 최대 20쪽을 돌고, 미체결은 한 번에 전량이다. 어떤 실패든 임시 파일을 지우고 일부만 쓴 파일을 남기지 않는다.
 * 시작부터 `ORDER_FILE_DEADLINE_MS` 가 지나면 다음 쪽을 부르지 않고 `TOSSINVEST_UNAVAILABLE` 로 끝낸다.
 */
export async function writeOrdersFile(
  client: Tossinvest,
  directory: string,
  query: URLSearchParams,
  hooks: OrderFileHooks = defaultHooks,
): Promise<OrderFileResult> {
  // 계좌 순번이 틀리면 요청도 못 하므로 디렉터리를 만지기 전에 거절한다.
  client.accountSeq();
  await ensureDirectory(directory);
  const started = hooks.now();
  await removeExpired(directory, started);

  // 이름은 커넥터가 정한다. 임시 파일은 `wx` 로 새로 만들고, 최종 이름은 `link` 로 만들어 기존 파일을 덮지 않는다.
  const tail = `${stamp(started)}-${hooks.random()}`;
  const temp = join(directory, `.orders-${tail}.tmp`);
  const file = join(directory, `orders-${tail}.jsonl`);
  let handle: FileHandle;
  try {
    handle = await hooks.open(temp);
  } catch {
    throw unavailable();
  }

  const closed = query.get("status") === "CLOSED";
  const page = new URLSearchParams(query);
  if (closed) page.set("limit", String(ORDERS_MAX));
  let count = 0;
  try {
    for (let pages = 1; ; pages += 1) {
      const data = await fetchPage(client, page, hooks);
      const orders = Array.isArray(data.orders) ? data.orders.filter(isObject) : [];
      const lines = orders.map((row) => `${JSON.stringify(orderRow(row))}\n`).join("");
      try {
        // `write` 는 일부만 쓰고 돌아올 수 있다. `appendFile` 은 전체를 쓸 때까지 반복한다.
        if (lines) await handle.appendFile(lines);
      } catch {
        throw unavailable();
      }
      count += orders.length;
      if (!closed || data.hasNext !== true) break;
      if (pages >= ORDER_FILE_MAX_PAGES)
        throw new TossinvestError("TOSSINVEST_TOO_MANY_ORDERS");
      if (typeof data.nextCursor !== "string" || data.nextCursor === "")
        throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
      page.set("cursor", data.nextCursor);
      await hooks.sleep(ORDER_FILE_PAGE_PAUSE_MS);
      // 승인 호출의 60초 제한 안에 끝내지 못할 쪽은 부르지 않는다.
      if (hooks.now().getTime() - started.getTime() > ORDER_FILE_DEADLINE_MS)
        throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    }
    // 최종 이름을 만들기 전에 내용이 디스크에 닿게 한다. 닫기가 실패하면 쓰기가 끝났다고 볼 수 없다.
    try {
      await handle.datasync();
      await handle.close();
      await link(temp, file);
    } catch {
      throw unavailable();
    }
  } catch (error) {
    await handle.close().catch(() => {});
    await unlink(temp).catch(() => {});
    throw error;
  }
  // 최종 이름이 생겼으면 임시 이름은 더 필요 없다. 못 지워도 24시간 정리가 지운다.
  await unlink(temp).catch(() => {});

  return {
    file,
    count,
    from: query.get("from"),
    to: query.get("to"),
    fields: Object.keys(orderRow({})),
  };
}
