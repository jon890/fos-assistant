import { ADDRESS, CONTROL, INVISIBLE, ENCODED_WORD, INVISIBLE_MARKS } from "./constants.ts";
import { GmailError } from "./errors.ts";
import { decodeHeader, encodeSubjectHeader } from "./message.ts";

/** 조립한 메일을 다시 읽어 승인 카드의 수신자와 제목이 바뀌지 않았는지 확인한다. */
export function confirmComposedMessage(
  raw: Uint8Array,
  subject: string,
  recipients: { To: string[]; Cc: string[]; Bcc: string[] },
) {
  const source = new TextDecoder("utf-8", { fatal: true }).decode(raw);
  const separator = source.indexOf("\r\n\r\n");
  if (separator < 0) throw new GmailError("GMAIL_INVALID_INPUT");

  const fields = new Map<string, string[]>();
  const unfoldedHeaders = source
    .slice(0, separator)
    .replace(/\r\n[ \t]+/g, " ");
  for (const line of unfoldedHeaders.split("\r\n")) {
    const matched = /^([^:\s]+):[ \t]*(.*)$/.exec(line);
    if (!matched) throw new GmailError("GMAIL_INVALID_INPUT");
    const name = matched[1].toLowerCase();
    const values = fields.get(name) ?? [];
    values.push(matched[2]);
    fields.set(name, values);
  }

  for (const [name, expected] of Object.entries(recipients)) {
    const values = fields.get(name.toLowerCase()) ?? [];
    const actual = values.flatMap((value) =>
      value.split(",").map((item) => item.trim()),
    );
    if (
      actual.length !== expected.length ||
      actual.some((value, index) => value !== expected[index])
    ) {
      throw new GmailError("GMAIL_INVALID_INPUT");
    }
  }

  const subjects = fields.get("subject") ?? [];
  if (
    subjects.length !== 1 ||
    decodeHeader(subjects[0]).trim() !== subject.trim()
  ) {
    throw new GmailError("GMAIL_INVALID_INPUT");
  }
}

const recipients = (value: string) => {
  if (CONTROL.test(value) || !value.trim())
    return value.trim()
      ? (() => {
          throw new GmailError("GMAIL_INVALID_INPUT");
        })()
      : [];
  return value
    .split(",")
    .map((item) => item.trim())
    .map((item) => {
      if (!item || !ADDRESS.test(item) || ENCODED_WORD.test(item))
        throw new GmailError("GMAIL_INVALID_INPUT");
      return item;
    });
};
const hasInvisibleSubject = (value: string) => {
  if (INVISIBLE.test(value)) return true;
  const points = Array.from(value);
  return points.some(
    (point, index) =>
      INVISIBLE_MARKS.has(point) &&
      !(
        point === "\ufe0f" &&
        index > 0 &&
        /[\p{So}\p{Sk}\p{Sm}\p{Po}\p{Nd}]/u.test(points[index - 1]!)
      ),
  );
};
export const compose = (
  input: any,
  reply?: { messageId: string; references: string },
) => {
  if (
    !input.subject.trim() ||
    !input.body.trim() ||
    CONTROL.test(input.subject) ||
    hasInvisibleSubject(input.subject) ||
    ENCODED_WORD.test(input.subject)
  )
    throw new GmailError("GMAIL_INVALID_INPUT");
  for (const point of input.body)
    if (INVISIBLE.test(point) && point !== "\u200c" && point !== "\u200d")
      throw new GmailError("GMAIL_INVALID_INPUT");
  const to = recipients(input.to);
  const cc = recipients(input.cc);
  const bcc = recipients(input.bcc);
  if (!to.length) throw new GmailError("GMAIL_INVALID_INPUT");
  const fields = [
    `To: ${to.join(", ")}`,
    ...(cc.length ? [`Cc: ${cc.join(", ")}`] : []),
    ...(bcc.length ? [`Bcc: ${bcc.join(", ")}`] : []),
    `Subject: ${encodeSubjectHeader(input.subject)}`,
    ...(reply?.messageId
      ? [
          `In-Reply-To: ${reply.messageId}`,
          `References: ${[reply.references, reply.messageId].filter(Boolean).join(" ")}`,
        ]
      : []),
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=utf-8",
    "Content-Transfer-Encoding: 8bit",
    "",
    input.body.replace(/\r?\n/g, "\r\n"),
  ];
  const source = fields.join("\r\n");
  const raw = new TextEncoder().encode(source);
  confirmComposedMessage(raw, input.subject, { To: to, Cc: cc, Bcc: bcc });
  return Uint8Array.from(raw).toBase64({
    alphabet: "base64url",
    omitPadding: true,
  });
};
