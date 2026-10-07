import { HTML_NAMED_ENTITIES } from "./html-entities.ts";

export function plainHtml(value: string) {
  const hidden: string[] = [];
  const pieces: string[] = [];
  let rawTextElement: "script" | "style" | undefined;
  const voidElements = new Set([
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
  ]);
  for (const token of value.match(
    /<!--[\s\S]*?-->|<(?:"[^"]*"|'[^']*'|[^'">])*>|[^<]+/g,
  ) ?? []) {
    if (!token.startsWith("<")) {
      if (!rawTextElement && !hidden.length) pieces.push(token);
      continue;
    }
    const closing = /^<\//.test(token);
    const name = /^<\/?\s*([a-z0-9]+)/i.exec(token)?.[1]?.toLowerCase();
    if (!name) continue;

    // script/style 안의 `<...>`는 태그가 아닌 원문이다. 실제 닫는 태그만 처리한다.
    if (rawTextElement && (!closing || name !== rawTextElement)) continue;
    if (closing) {
      const index = hidden.lastIndexOf(name);
      if (index >= 0) hidden.splice(index, 1);
      if (name === rawTextElement) rawTextElement = undefined;
      else if (/^(br|p|div|tr|li|h[1-6]|blockquote)$/.test(name))
        pieces.push("\n");
      continue;
    }
    if (name === "body" && hidden.length === 1 && hidden[0] === "head")
      hidden.length = 0;
    const attributes = token.slice(token.indexOf(name) + name.length);
    const attributeNames = attributes.replace(/"[^"]*"|'[^']*'/g, '""');
    const hiddenAttribute = /(?:^|\s)hidden(?:\s|=|>|\/)/i.test(attributeNames);
    const canContainText = !voidElements.has(name) && !/\/\s*>$/.test(token);
    if (canContainText && (name === "script" || name === "style")) {
      rawTextElement = name;
    }
    if (hidden.length && canContainText) {
      hidden.push(name);
    } else if (
      canContainText &&
      (/^(script|style|title|head|template|noscript)$/.test(name) ||
        hiddenAttribute)
    ) {
      hidden.push(name);
    } else if (/^(br|p|div|tr|li|h[1-6]|blockquote)$/.test(name))
      pieces.push("\n");
  }
  return decodeHtmlEntities(pieces.join(""))
    .split(/\r?\n/)
    .map((line) => line.replace(/\s+/g, " ").trim())
    .filter(Boolean)
    .join("\n");
}

/** HTML5 문자 참조는 한 번만 풀어 이중 해석을 막는다. */
const HTML_CHARACTER_REFERENCE =
  /&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\t\n\f <&#;]{1,32};?)/g;
const C1_CHARACTER_REPLACEMENTS: Readonly<Record<number, string>> = {
  0: "\ufffd",
  13: "\r",
  128: "€",
  129: "\x81",
  130: "‚",
  131: "ƒ",
  132: "„",
  133: "…",
  134: "†",
  135: "‡",
  136: "ˆ",
  137: "‰",
  138: "Š",
  139: "‹",
  140: "Œ",
  141: "\x8d",
  142: "Ž",
  143: "\x8f",
  144: "\x90",
  145: "‘",
  146: "’",
  147: "“",
  148: "”",
  149: "•",
  150: "–",
  151: "—",
  152: "˜",
  153: "™",
  154: "š",
  155: "›",
  156: "œ",
  157: "\x9d",
  158: "ž",
  159: "Ÿ",
};

/** Python html.unescape와 같은 HTML5 named/numeric 문자 참조 처리다. */
function decodeHtmlEntities(value: string) {
  return value.replace(
    HTML_CHARACTER_REFERENCE,
    (source, reference: string) => {
      if (!reference.startsWith("#")) {
        if (Object.hasOwn(HTML_NAMED_ENTITIES, reference)) {
          return HTML_NAMED_ENTITIES[reference]!;
        }

        for (let end = reference.length - 1; end > 1; end -= 1) {
          const prefix = reference.slice(0, end);
          if (Object.hasOwn(HTML_NAMED_ENTITIES, prefix)) {
            return HTML_NAMED_ENTITIES[prefix]! + reference.slice(end);
          }
        }
        return source;
      }

      const hexadecimal = reference[1]?.toLowerCase() === "x";
      const digits = reference.slice(hexadecimal ? 2 : 1).replace(/;$/, "");
      const codePoint = Number.parseInt(digits, hexadecimal ? 16 : 10);
      const replacement = C1_CHARACTER_REPLACEMENTS[codePoint];
      if (replacement !== undefined) return replacement;
      if (codePoint >= 0xd800 && codePoint <= 0xdfff) return "\ufffd";
      if (codePoint > 0x10ffff) return "\ufffd";
      if (
        codePoint === 1 ||
        (codePoint >= 2 && codePoint <= 8) ||
        (codePoint >= 11 && codePoint <= 12) ||
        (codePoint >= 14 && codePoint <= 31) ||
        (codePoint >= 127 && codePoint <= 159) ||
        (codePoint >= 0xfdd0 && codePoint <= 0xfdef) ||
        (codePoint & 0xffff) === 0xfffe ||
        (codePoint & 0xffff) === 0xffff
      ) {
        return "";
      }
      return String.fromCodePoint(codePoint);
    },
  );
}
