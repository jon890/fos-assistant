import { z } from "zod";
import { GmailError, id, strings, blocked } from "./errors.ts";
import type { Gmail } from "./gmail-client.ts";
import type { RegisterTool } from "./tool-registration.ts";
import { ID } from "./constants.ts";

function filterCriteria(input: any) {
  if (input.forward) throw new GmailError("GMAIL_INVALID_INPUT");
  if (input.has_attachment && !["true", "false"].includes(input.has_attachment))
    throw new GmailError("GMAIL_INVALID_INPUT");
  if (
    input.size_comparison &&
    !["larger", "smaller"].includes(input.size_comparison)
  )
    throw new GmailError("GMAIL_INVALID_INPUT");
  if (Boolean(input.size) !== Boolean(input.size_comparison))
    throw new GmailError("GMAIL_INVALID_INPUT");
  if (input.size) {
    if (!/^[0-9]+$/.test(input.size))
      throw new GmailError("GMAIL_INVALID_INPUT");
    const size = Number(input.size);
    if (!Number.isSafeInteger(size) || size < 0)
      throw new GmailError("GMAIL_INVALID_INPUT");
  }
  const criteria: Record<string, unknown> = {};
  const map: Record<string, string> = {
    from: "from",
    to: "to",
    subject: "subject",
    query: "query",
    negated_query: "negatedQuery",
    has_attachment: "hasAttachment",
    size: "size",
    size_comparison: "sizeComparison",
  };
  for (const [key, target] of Object.entries(map)) {
    if (!input[key]) continue;
    if (key === "has_attachment") {
      criteria[target] = input[key] === "true";
      continue;
    }
    if (key === "size") {
      criteria[target] = Number(input[key]);
      continue;
    }
    criteria[target] = input[key];
  }
  if (!Object.keys(criteria).length)
    throw new GmailError("GMAIL_INVALID_INPUT");
  return criteria;
}

export function registerFilterTools(register: RegisterTool, gmail: Gmail) {
  register("list_filters", {}, { readOnlyHint: true }, async () => {
    try {
      return { filters: (await gmail.api("/settings/filters")).filter ?? [] };
    } catch (error) {
      if (error instanceof GmailError && error.code === "GMAIL_FORBIDDEN")
        throw new GmailError("GMAIL_FILTER_SCOPE_REQUIRED");
      throw error;
    }
  });
  register(
    "create_filter",
    {
      from: z.string().default(""),
      to: z.string().default(""),
      subject: z.string().default(""),
      query: z.string().default(""),
      negated_query: z.string().default(""),
      has_attachment: z.string().default(""),
      size: z.string().default(""),
      size_comparison: z.string().default(""),
      add_labels: z.string().default(""),
      remove_labels: z.string().default(""),
      forward: z.string().default(""),
    },
    { readOnlyHint: false, destructiveHint: false },
    async (input) => {
      const criteria = filterCriteria(input);
      const add = strings(input.add_labels),
        remove = strings(input.remove_labels);
      if (!add.length && !remove.length)
        throw new GmailError("GMAIL_INVALID_INPUT");
      if (blocked([...add, ...remove]))
        throw new GmailError("GMAIL_INVALID_INPUT");
      const accessToken = await gmail.token();
      const all = await gmail.labelList(accessToken);
      const addIds = await gmail.resolve(all, add),
        removeIds = await gmail.resolve(all, remove);
      if (blocked([...addIds, ...removeIds]))
        throw new GmailError("GMAIL_INVALID_INPUT");
      try {
        return await gmail.api(
          "/settings/filters",
          "POST",
          undefined,
          {
            criteria,
            action: { addLabelIds: addIds, removeLabelIds: removeIds },
          },
          accessToken,
        );
      } catch (error) {
        if (error instanceof GmailError && error.code === "GMAIL_FORBIDDEN")
          throw new GmailError("GMAIL_FILTER_SCOPE_REQUIRED");
        throw error;
      }
    },
  );
  register(
    "delete_filter",
    { filter_id: z.string().default("") },
    { readOnlyHint: false, destructiveHint: true },
    async ({ filter_id }) => {
      try {
        await gmail.api(`/settings/filters/${id(filter_id)}`, "DELETE");
        return { deleted: true, filter_id };
      } catch (error) {
        if (error instanceof GmailError && error.code === "GMAIL_FORBIDDEN")
          throw new GmailError("GMAIL_FILTER_SCOPE_REQUIRED");
        throw error;
      }
    },
  );
  register(
    "apply_labels_to_query",
    {
      query: z.string().default(""),
      add_labels: z.string().default(""),
      remove_labels: z.string().default(""),
      expected_count: z.string().default(""),
    },
    { readOnlyHint: false, destructiveHint: false },
    async (input) => {
      if (
        !input.query.trim() ||
        !/^(?:0|[1-9][0-9]{0,2})$/.test(input.expected_count)
      )
        throw new GmailError("GMAIL_INVALID_INPUT");
      const expected = Number(input.expected_count);
      if (expected > 500) throw new GmailError("GMAIL_INVALID_INPUT");
      const add = strings(input.add_labels),
        remove = strings(input.remove_labels);
      if ((!add.length && !remove.length) || blocked([...add, ...remove]))
        throw new GmailError("GMAIL_INVALID_INPUT");
      const accessToken = await gmail.token();
      const all = await gmail.labelList(accessToken);
      const addIds = await gmail.resolve(all, add),
        removeIds = await gmail.resolve(all, remove);
      if (blocked([...addIds, ...removeIds]))
        throw new GmailError("GMAIL_INVALID_INPUT");
      const ids: string[] = [];
      const seenIds = new Set<string>();
      const seenPageTokens = new Set<string>();
      let pageToken = "";
      let pages = 0;
      do {
        if (pages++ >= 501 || (pageToken && seenPageTokens.has(pageToken))) {
          throw new GmailError("GMAIL_UNAVAILABLE");
        }
        if (pageToken) seenPageTokens.add(pageToken);
        const query = new URLSearchParams({
          q: input.query,
          maxResults: "500",
        });
        if (pageToken) query.set("pageToken", pageToken);
        const listed = await gmail.api(
          "/messages",
          "GET",
          query,
          undefined,
          accessToken,
        );
        if (!Array.isArray(listed.messages) && listed.messages !== undefined) {
          throw new GmailError("GMAIL_UNAVAILABLE");
        }
        for (const item of listed.messages ?? []) {
          if (
            typeof item?.id !== "string" ||
            !ID.test(item.id) ||
            seenIds.has(item.id)
          ) {
            throw new GmailError("GMAIL_UNAVAILABLE");
          }
          seenIds.add(item.id);
          ids.push(item.id);
        }
        pageToken =
          typeof listed.nextPageToken === "string" ? listed.nextPageToken : "";
        if (ids.length > 500) break;
      } while (pageToken);
      if (ids.length > 500) throw new GmailError("GMAIL_INVALID_INPUT");
      const actual = ids.length;
      if (actual !== expected)
        throw new GmailError("GMAIL_TARGET_COUNT_CHANGED", {
          actual_count: actual,
        });
      if (!ids.length) {
        return {
          count: 0,
          query: input.query,
          add_label_ids: addIds,
          remove_label_ids: removeIds,
        };
      }
      await gmail.api(
        "/messages/batchModify",
        "POST",
        undefined,
        { ids, addLabelIds: addIds, removeLabelIds: removeIds },
        accessToken,
      );
      return {
        count: ids.length,
        query: input.query,
        add_label_ids: addIds,
        remove_label_ids: removeIds,
      };
    },
  );
}
