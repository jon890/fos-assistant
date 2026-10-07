import { HEADER_MAX_CHARS, ATTACHMENTS_MAX, FILENAME_MAX_CHARS } from "./constants.ts";
import { codePoints, truncateCodePoints } from "./errors.ts";
import { plainHtml } from "./html.ts";

export function headers(part: any): Record<string, string> {
  const found: Record<string, string> = {};
  for (const header of part?.headers ?? [])
    if (
      typeof header?.name === "string" &&
      typeof header?.value === "string" &&
      found[header.name.toLowerCase()] === undefined
    )
      found[header.name.toLowerCase()] = header.value;
  return found;
}

/** RFC 2047 Q 구문의 ASCII와 `=HH`를 UTF-8 문자열로 바꾸기 전에 원래 바이트로 복원한다. */
function decodeQuotedPrintableWord(value: string) {
  const bytes: number[] = [];
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]!;
    if (character === "_") {
      bytes.push(0x20);
      continue;
    }
    if (
      character === "=" &&
      /^[0-9a-f]{2}$/i.test(value.slice(index + 1, index + 3))
    ) {
      bytes.push(Number.parseInt(value.slice(index + 1, index + 3), 16));
      index += 2;
      continue;
    }
    const code = character.charCodeAt(0);
    if (code > 0x7f) throw new Error("RFC2047_Q_NON_ASCII");
    bytes.push(code);
  }
  return Uint8Array.from(bytes);
}

export function decodeHeader(value: string) {
  // RFC 2047은 인접한 encoded-word 사이의 접힌 공백을 표시하지 않는다.
  const joined = value.replace(/\?=[ \t\r\n]+(?==\?)/g, "?=");
  return joined.replace(
    /=\?([^?\s]+)\?([bqBQ])\?([^?]*)\?=/g,
    (_word, charset, encoding, text) => {
      try {
        const bytes =
          encoding.toUpperCase() === "B"
            ? Uint8Array.fromBase64(text, {
                alphabet: "base64",
                lastChunkHandling: "loose",
              })
            : decodeQuotedPrintableWord(text);
        return new TextDecoder(charset).decode(bytes);
      } catch {
        try {
          return new TextDecoder().decode(
            Uint8Array.fromBase64(text, {
              alphabet: "base64",
              lastChunkHandling: "loose",
            }),
          );
        } catch {
          return text;
        }
      }
    },
  );
}

/** UTF-8 제목을 RFC 2047 B encoded-word로 만들되 Unicode code point를 중간에서 나누지 않는다. */
export function encodeSubjectHeader(subject: string) {
  if (/^[\x20-\x7e]*$/.test(subject)) return subject;
  const words: string[] = [];
  let bytes: number[] = [];
  for (const point of Array.from(subject)) {
    const encoded = Array.from(new TextEncoder().encode(point));
    if (bytes.length && bytes.length + encoded.length > 45) {
      words.push(
        `=?UTF-8?B?${Uint8Array.from(bytes).toBase64({ alphabet: "base64" })}?=`,
      );
      bytes = [];
    }
    bytes.push(...encoded);
  }
  if (bytes.length)
    words.push(
      `=?UTF-8?B?${Uint8Array.from(bytes).toBase64({ alphabet: "base64" })}?=`,
    );
  return words.join("\r\n ");
}

function header(part: any, name: string) {
  return truncateCodePoints(
    decodeHeader(headers(part)[name] ?? ""),
    HEADER_MAX_CHARS,
  );
}
function decodePart(part: any) {
  const value = part?.body?.data;
  if (typeof value !== "string" || !value) return "";
  try {
    const bytes = Uint8Array.fromBase64(
      value.replace(/-/g, "+").replace(/_/g, "/"),
      { alphabet: "base64", lastChunkHandling: "loose" },
    );
    const contentType = headers(part)["content-type"] ?? "";
    const charset =
      /charset\s*=\s*[\"']?([^;\s\"']+)/i.exec(contentType)?.[1] ?? "utf-8";
    try {
      return new TextDecoder(charset).decode(bytes);
    } catch {
      return new TextDecoder().decode(bytes);
    }
  } catch {
    return "";
  }
}
function collect(
  part: any,
  found: { plain?: any; html?: any; attachments: any[] },
) {
  if (!part || typeof part !== "object") return;
  const mime =
    typeof part.mimeType === "string" ? part.mimeType.toLowerCase() : "";
  if (typeof part.filename === "string" && part.filename) {
    if (found.attachments.length < ATTACHMENTS_MAX)
      found.attachments.push({
        filename: truncateCodePoints(part.filename, FILENAME_MAX_CHARS),
        mime_type: truncateCodePoints(mime, HEADER_MAX_CHARS),
        size: typeof part.body?.size === "number" ? part.body.size : 0,
      });
    return;
  }
  if (mime.startsWith("multipart/"))
    for (const child of part.parts ?? []) collect(child, found);
  else if (mime === "text/plain" && !found.plain) found.plain = part;
  else if (mime === "text/html" && !found.html) found.html = part;
}
export function labels(resource: any) {
  return Array.isArray(resource?.labelIds)
    ? resource.labelIds.filter(
        (value: unknown): value is string => typeof value === "string",
      )
    : [];
}
export function message(resource: any, limit: number) {
  const found: { plain?: any; html?: any; attachments: any[] } = {
    attachments: [],
  };
  collect(resource?.payload, found);
  const body = found.plain
    ? decodePart(found.plain)
    : found.html
      ? plainHtml(decodePart(found.html))
      : "";
  return {
    id: resource?.id,
    thread_id: resource?.threadId,
    from: header(resource?.payload, "from"),
    to: header(resource?.payload, "to"),
    cc: header(resource?.payload, "cc"),
    subject: header(resource?.payload, "subject"),
    date: header(resource?.payload, "date"),
    labels: labels(resource),
    body: truncateCodePoints(body, limit),
    body_truncated: codePoints(body) > limit,
    attachments: found.attachments,
  };
}
export function summary(resource: any) {
  return {
    id: resource?.id,
    thread_id: resource?.threadId,
    from: header(resource?.payload, "from"),
    to: header(resource?.payload, "to"),
    subject: header(resource?.payload, "subject"),
    date: header(resource?.payload, "date"),
    snippet: typeof resource?.snippet === "string" ? resource.snippet : "",
    labels: labels(resource),
  };
}
