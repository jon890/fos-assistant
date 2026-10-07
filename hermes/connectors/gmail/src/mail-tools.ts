import { z } from "zod";
import { GmailError, id } from "./errors.ts";
import type { Gmail } from "./gmail-client.ts";
import type { RegisterTool } from "./tool-registration.ts";
import { compose } from "./compose.ts";
import { headers } from "./message.ts";

const mailSchema = {
  to: z.string().default(""),
  subject: z.string().default(""),
  body: z.string().default(""),
  cc: z.string().default(""),
  bcc: z.string().default(""),
};

function replyContextFactory(gmail: Gmail) {
  const replyContext = async (messageId: string, accessToken?: string) => {
    const original = await gmail.api(
      `/messages/${id(messageId)}`,
      "GET",
      new URLSearchParams([
        ["format", "metadata"],
        ["metadataHeaders", "Message-ID"],
        ["metadataHeaders", "References"],
      ]),
      undefined,
      accessToken,
    );
    const originalHeaders = headers(original.payload);
    const safe = (name: string) => {
      const value = originalHeaders[name] ?? "";
      const parts = value.split(/\s+/).filter(Boolean);
      return /^[\t\r\n\x20-\x7e]*$/.test(value) &&
        parts.length &&
        parts.every((part) => /^<[^<>\s]{1,250}>$/.test(part))
        ? parts.join(" ")
        : "";
    };
    return {
      threadId: typeof original.threadId === "string" ? original.threadId : "",
      messageId: safe("message-id"),
      references: safe("references"),
    };
  };
  return replyContext;
}

export function registerDraftTool(register: RegisterTool, gmail: Gmail) {
  const replyContext = replyContextFactory(gmail);
  register(
    "create_draft",
    { ...mailSchema, reply_to_message_id: z.string().default("") },
    { readOnlyHint: false, destructiveHint: false },
    async (input) => {
      // 원래 메일을 읽기 전에 새 메일 인자를 먼저 검증한다.
      compose(input);
      const replyMessageId = input.reply_to_message_id
        ? id(input.reply_to_message_id)
        : "";
      const accessToken = await gmail.token();
      const context = replyMessageId
        ? await replyContext(replyMessageId, accessToken)
        : undefined;
      const message: any = { raw: compose(input, context) };
      if (context?.threadId) message.threadId = context.threadId;
      const value = await gmail.api(
        "/drafts",
        "POST",
        undefined,
        { message },
        accessToken,
      );
      return {
        draft_id: value.id,
        message_id: value.message?.id,
        thread_id: value.message?.threadId,
      };
    },
  );
}

export function registerSendTools(register: RegisterTool, gmail: Gmail) {
  const replyContext = replyContextFactory(gmail);
  register(
    "send_message",
    mailSchema,
    { readOnlyHint: false, destructiveHint: false, openWorldHint: true },
    async (input) => {
      const value = await gmail.api("/messages/send", "POST", undefined, {
        raw: compose(input),
      });
      if (typeof value.id !== "string" || !value.id) {
        throw new GmailError("GMAIL_SEND_UNKNOWN");
      }
      return { id: value.id, thread_id: value.threadId };
    },
  );
  register(
    "reply_to_message",
    {
      message_id: z.string().default(""),
      to: z.string().default(""),
      subject: z.string().default(""),
      body: z.string().default(""),
      cc: z.string().default(""),
    },
    { readOnlyHint: false, destructiveHint: false, openWorldHint: true },
    async (input) => {
      compose({ ...input, bcc: "" });
      const messageId = id(input.message_id);
      const accessToken = await gmail.token();
      const context = await replyContext(messageId, accessToken);
      const request: any = { raw: compose({ ...input, bcc: "" }, context) };
      if (context.threadId) request.threadId = context.threadId;
      const value = await gmail.api(
        "/messages/send",
        "POST",
        undefined,
        request,
        accessToken,
      );
      if (typeof value.id !== "string" || !value.id) {
        throw new GmailError("GMAIL_SEND_UNKNOWN");
      }
      return { id: value.id, thread_id: value.threadId };
    },
  );
}
