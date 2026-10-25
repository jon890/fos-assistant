import { z } from "zod";
import { PAGE_SIZE_MAX } from "./constants.ts";
import { WorkbenchError } from "./errors.ts";

export const invalid = () => new WorkbenchError("WORKBENCH_INVALID_INPUT");

export function paging(query: URLSearchParams, page: number, size: number) {
  if (page < 0 || size < 1 || size > PAGE_SIZE_MAX) throw invalid();
  query.set("page", String(page));
  query.set("size", String(size));
}

export const pageSchema = {
  page: z.number().int().default(0),
  size: z.number().int().default(20),
};
