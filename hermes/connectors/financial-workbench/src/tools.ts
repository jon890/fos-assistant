import { z } from "zod";
import {
  LEVELS,
  MARKETS,
  PAGE_SIZE_MAX,
  QUERY_MAX_CHARS,
  TICKER,
  TYPE_KEY,
  UUID,
} from "./constants.ts";
import { WorkbenchError } from "./errors.ts";
import type { RegisterTool } from "./tool-registration.ts";
import type { Workbench } from "./workbench-client.ts";

const invalid = () => new WorkbenchError("WORKBENCH_INVALID_INPUT");

/** 경로에 넣는 값이므로 UUID 모양만 받는다. 다른 경로로 새지 않게 한다. */
function nodeId(value: string) {
  const id = value.trim();
  if (!UUID.test(id)) throw invalid();
  return id;
}

/** 쉼표로 이은 값을 나눠 각각 검사하고 빈 조각을 뺀 채 다시 잇는다. */
function list(value: string, accept: (item: string) => boolean) {
  const items = value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  if (!items.every(accept)) throw invalid();
  return items.join(",");
}

function paging(query: URLSearchParams, page: number, size: number) {
  if (page < 0 || size < 1 || size > PAGE_SIZE_MAX) throw invalid();
  query.set("page", String(page));
  query.set("size", String(size));
}

const pageSchema = {
  page: z.number().int().default(0),
  size: z.number().int().default(20),
};

export function registerTools(register: RegisterTool, workbench: Workbench) {
  const read = { readOnlyHint: true };

  register("get_connection", {}, read, async () => {
    const value = await workbench.get("/me");
    return {
      user: value.user,
      token: value.token,
      rateLimitPerMinute: value.rateLimitPerMinute,
    };
  });

  register("get_ontology", {}, read, () => workbench.get("/ontology"));

  register(
    "search_nodes",
    { q: z.string().default(""), types: z.string().default(""), ...pageSchema },
    read,
    async ({ q, types, page, size }) => {
      const query = new URLSearchParams();
      const text = q.trim();
      if (Array.from(text).length > QUERY_MAX_CHARS) throw invalid();
      if (text) query.set("q", text);
      const typeKeys = list(types, (item) => TYPE_KEY.test(item));
      if (typeKeys) query.set("types", typeKeys);
      paging(query, page, size);
      return workbench.get("/nodes", query);
    },
  );

  register("get_node", { id: z.string() }, read, ({ id }) =>
    workbench.get(`/nodes/${nodeId(id)}`, undefined, "WORKBENCH_NOT_FOUND"),
  );

  register("trace_node", { id: z.string() }, read, ({ id }) =>
    workbench.get(
      `/nodes/${nodeId(id)}/trace`,
      undefined,
      "WORKBENCH_NOT_FOUND",
    ),
  );

  register(
    "get_neighborhood",
    { id: z.string(), hops: z.number().int().default(1) },
    read,
    ({ id, hops }) => {
      if (hops !== 1 && hops !== 2) throw invalid();
      return workbench.get(
        "/graph/neighborhood",
        new URLSearchParams({ id: nodeId(id), hops: String(hops) }),
        "WORKBENCH_NOT_FOUND",
      );
    },
  );

  register(
    "lookup_security",
    { ticker: z.string(), market: z.string().default("") },
    read,
    ({ ticker, market }) => {
      const code = ticker.trim();
      if (!TICKER.test(code)) throw invalid();
      const query = new URLSearchParams({ ticker: code });
      const marketCode = market.trim().toUpperCase();
      if (marketCode) {
        if (!MARKETS.has(marketCode)) throw invalid();
        query.set("market", marketCode);
      }
      return workbench.get("/securities/lookup", query);
    },
  );

  register(
    "list_my_knowledge",
    { level: z.string().default(""), ...pageSchema },
    read,
    ({ level, page, size }) => {
      const query = new URLSearchParams();
      const levels = list(level.toUpperCase(), (item) => LEVELS.has(item));
      if (levels) query.set("level", levels);
      paging(query, page, size);
      return workbench.get("/me/knowledge", query);
    },
  );
}
