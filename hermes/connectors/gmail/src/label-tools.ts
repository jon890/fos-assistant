import { z } from "zod";
import { GmailError, id, strings, blocked } from "./errors.ts";
import type { Gmail } from "./gmail-client.ts";
import type { RegisterTool } from "./tool-registration.ts";
import { CONTROL, SYSTEM_LABELS, COLORS } from "./constants.ts";
import { codePoints } from "./errors.ts";
import { labels } from "./message.ts";

function validateLabel(
  name: string,
  textColor: string,
  backgroundColor: string,
) {
  if (
    !name.trim() ||
    codePoints(name) > 225 ||
    CONTROL.test(name) ||
    SYSTEM_LABELS.has(name.toUpperCase())
  )
    throw new GmailError("GMAIL_INVALID_INPUT");
  if (
    (textColor || backgroundColor) &&
    (!textColor ||
      !backgroundColor ||
      !COLORS.has(textColor) ||
      !COLORS.has(backgroundColor))
  )
    throw new GmailError("GMAIL_INVALID_INPUT");
}

function validateLabelVisibility(
  labelListVisibility: string,
  messageListVisibility: string,
) {
  if (
    labelListVisibility &&
    !["labelShow", "labelShowIfUnread", "labelHide"].includes(
      labelListVisibility,
    )
  ) {
    throw new GmailError("GMAIL_INVALID_INPUT");
  }
  if (
    messageListVisibility &&
    !["show", "hide"].includes(messageListVisibility)
  ) {
    throw new GmailError("GMAIL_INVALID_INPUT");
  }
}
export function registerModifyLabelsTool(register: RegisterTool, gmail: Gmail) {
  register(
    "modify_labels",
    {
      message_id: z.string().default(""),
      add_labels: z.string().default(""),
      remove_labels: z.string().default(""),
    },
    { readOnlyHint: false, destructiveHint: false },
    async (input) => {
      const add = strings(input.add_labels),
        remove = strings(input.remove_labels);
      id(input.message_id);
      if ((!add.length && !remove.length) || blocked([...add, ...remove]))
        throw new GmailError("GMAIL_INVALID_INPUT");
      const accessToken = await gmail.token();
      const all = await gmail.labelList(accessToken);
      const addIds = await gmail.resolve(all, add),
        removeIds = await gmail.resolve(all, remove);
      if (blocked([...addIds, ...removeIds]))
        throw new GmailError("GMAIL_INVALID_INPUT");
      const value = await gmail.api(
        `/messages/${input.message_id}/modify`,
        "POST",
        undefined,
        { addLabelIds: addIds, removeLabelIds: removeIds },
        accessToken,
      );
      return { id: value.id, labels: labels(value) };
    },
  );
}

export function registerLabelTools(register: RegisterTool, gmail: Gmail) {
  register(
    "create_label",
    {
      name: z.string().default(""),
      text_color: z.string().default(""),
      background_color: z.string().default(""),
      label_list_visibility: z.string().default(""),
      message_list_visibility: z.string().default(""),
    },
    { readOnlyHint: false, destructiveHint: false },
    async (input) => {
      validateLabel(input.name, input.text_color, input.background_color);
      validateLabelVisibility(
        input.label_list_visibility,
        input.message_list_visibility,
      );
      const body: any = { name: input.name };
      if (input.text_color)
        body.color = {
          textColor: input.text_color,
          backgroundColor: input.background_color,
        };
      if (input.label_list_visibility)
        body.labelListVisibility = input.label_list_visibility;
      if (input.message_list_visibility)
        body.messageListVisibility = input.message_list_visibility;
      return gmail.api("/labels", "POST", undefined, body);
    },
  );
  register(
    "update_label",
    {
      label: z.string().default(""),
      name: z.string().default(""),
      text_color: z.string().default(""),
      background_color: z.string().default(""),
    },
    { readOnlyHint: false, destructiveHint: false },
    async (input) => {
      if (!input.label.trim()) throw new GmailError("GMAIL_INVALID_INPUT");
      if (SYSTEM_LABELS.has(input.label.toUpperCase()))
        throw new GmailError("GMAIL_INVALID_INPUT");
      if (!input.name && !input.text_color && !input.background_color)
        throw new GmailError("GMAIL_INVALID_INPUT");
      if (input.name)
        validateLabel(input.name, input.text_color, input.background_color);
      else if (
        !input.text_color ||
        !input.background_color ||
        !COLORS.has(input.text_color) ||
        !COLORS.has(input.background_color)
      )
        throw new GmailError("GMAIL_INVALID_INPUT");
      const accessToken = await gmail.token();
      const all = await gmail.labelList(accessToken);
      const [labelId] = await gmail.resolve(all, [input.label]);
      const selected = all.find((label: any) => label.id === labelId);
      if (String(selected?.type).toLowerCase() === "system")
        throw new GmailError("GMAIL_INVALID_INPUT");
      const body: any = {};
      if (input.name) body.name = input.name;
      if (input.text_color)
        body.color = {
          textColor: input.text_color,
          backgroundColor: input.background_color,
        };
      return gmail.api(
        `/labels/${id(labelId)}`,
        "PATCH",
        undefined,
        body,
        accessToken,
      );
    },
  );
}
