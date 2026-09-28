/**
 * 에이전트가 답 끝에 둔 `<ask>` 블록을 읽어 선택 카드로 그릴 모양으로 바꾼다.
 *
 * 형식은 Control Plane 이 실행마다 instructions 에 적어 준다(`AskFormat`). 두 곳이 같은 형식을 말해야 한다.
 *
 * 에이전트의 답은 믿지 않는 글이다(ADR-009). 태그를 HTML 로 읽지 않고 정해 둔 모양만 글자로 골라낸다.
 * 모양이 어긋나면 카드로 그리지 않고 원문을 코드 블록으로 보인다. 틀린 카드보다 읽을 수 있는 글이 낫다.
 */

export type AskOption = { label: string; description: string | null };
export type AskQuestion = { header: string | null; text: string; multiple: boolean; options: AskOption[] };
export type Ask = { questions: AskQuestion[] };

export type AnswerSegment = { kind: "markdown"; text: string } | { kind: "ask"; ask: Ask };

export const MAX_QUESTIONS = 4;
export const MAX_OPTIONS = 6;
const MAX_HEADER = 40;
const MAX_TEXT = 400;
const MAX_LABEL = 120;
const MAX_DESCRIPTION = 240;

const OPEN = "<ask>";
const CLOSE = "</ask>";

/**
 * 답을 마크다운 조각과 카드 조각으로 나눈다.
 *
 * 코드 블록 안의 `<ask>` 는 예시로 적은 것이라 카드로 읽지 않는다.
 * 스트리밍 중에 닫히지 않은 `<ask>` 는 그 자리부터 그리지 않는다. 반쯤 온 태그가 글자로 번쩍이지 않게 한다.
 */
export function splitAnswer(content: string, streaming = false): AnswerSegment[] {
  const segments: AnswerSegment[] = [];
  const lines = content.split("\n");
  let markdown: string[] = [];
  let block: string[] | null = null;
  let fence: string | null = null;

  const flushMarkdown = () => {
    const text = markdown.join("\n");
    if (text.trim().length > 0) segments.push({ kind: "markdown", text });
    markdown = [];
  };

  for (const line of lines) {
    const trimmed = line.trim();
    if (block !== null) {
      block.push(line);
      if (trimmed === CLOSE) {
        flushMarkdown();
        const raw = block.join("\n");
        const ask = parseAsk(raw);
        segments.push(ask ? { kind: "ask", ask } : { kind: "markdown", text: asCodeBlock(raw) });
        block = null;
      }
      continue;
    }
    const fenceMark = /^(`{3,}|~{3,})/.exec(trimmed)?.[1];
    if (fenceMark) {
      // 여는 펜스는 뒤에 언어 이름이 붙어도 된다. 닫는 펜스는 같은 문자로 같거나 긴 펜스만 있는 줄이다.
      // 열린 블록 안의 "```js" 는 CommonMark 에서 코드 블록의 내용이다.
      if (fence === null) fence = fenceMark;
      else if (/^(`{3,}|~{3,})\s*$/.test(trimmed) && fenceMark.startsWith(fence)) fence = null;
      markdown.push(line);
      continue;
    }
    // 네 칸 이상 들여쓴 줄은 마크다운의 코드 블록이라 예시로 본다.
    const indent = line.length - line.trimStart().length;
    if (fence === null && trimmed === OPEN && indent <= 3) {
      block = [line];
      continue;
    }
    markdown.push(line);
  }

  // 스트리밍 중 마지막 줄이 `<ask>` 의 앞부분까지만 왔으면 그 줄을 아직 그리지 않는다.
  const tail = markdown.at(-1)?.trim() ?? "";
  if (streaming && block === null && fence === null && tail.length > 0 && OPEN.startsWith(tail)) {
    markdown.pop();
  }

  if (block !== null && !streaming) {
    // 끝까지 닫히지 않은 블록은 에이전트가 형식을 어긴 것이다. 버리지 않고 원문을 보인다.
    markdown.push(asCodeBlock(block.join("\n")));
  }
  flushMarkdown();
  return segments;
}

/** `<ask>` 부터 `</ask>` 까지를 읽는다. 모양이 어긋나면 null 이다. */
export function parseAsk(raw: string): Ask | null {
  const lines = raw.split("\n").map((line) => line.trim()).filter((line) => line.length > 0);
  if (lines[0] !== OPEN || lines.at(-1) !== CLOSE) return null;

  const questions: AskQuestion[] = [];
  for (const line of lines.slice(1, -1)) {
    const question = /^<question((?:\s+[a-z]+="[^"]*")*)\s*>(.*)<\/question>$/.exec(line);
    if (question) {
      const attributes = readAttributes(question[1]);
      const text = unescape(question[2]).trim();
      if (text.length === 0 || text.length > MAX_TEXT) return null;
      const header = attributes.header?.trim() || null;
      if (header !== null && header.length > MAX_HEADER) return null;
      questions.push({ header, text, multiple: attributes.multiple === "true", options: [] });
      continue;
    }
    const option = /^<option((?:\s+[a-z]+="[^"]*")*)\s*>(.*)<\/option>$/.exec(line);
    if (option) {
      const current = questions.at(-1);
      if (!current) return null;
      const label = unescape(option[2]).trim();
      if (label.length === 0 || label.length > MAX_LABEL) return null;
      const description = readAttributes(option[1]).description?.trim() || null;
      if (description !== null && description.length > MAX_DESCRIPTION) return null;
      current.options.push({ label, description });
      continue;
    }
    return null;
  }

  if (questions.length === 0 || questions.length > MAX_QUESTIONS) return null;
  if (questions.some((question) => question.options.length > MAX_OPTIONS)) return null;
  // 같은 이름의 선택지가 둘이면 어느 것을 골랐는지 구분할 수 없다.
  if (questions.some((question) => new Set(question.options.map((option) => option.label)).size !== question.options.length)) {
    return null;
  }
  return { questions };
}

/**
 * 고른 답을 다음 메시지로 보낼 글로 만든다.
 *
 * 질문마다 한 줄이다. 이름표가 있으면 이름표를, 없으면 질문을 앞에 둔다. 그래야 에이전트가 무엇에 대한 답인지 안다.
 */
export function formatAnswers(ask: Ask, answers: string[][]): string {
  return ask.questions
    .map((question, index) => `${question.header ?? question.text}: ${answers[index].join(", ")}`)
    .join("\n");
}

/** 바로 다음 사용자 메시지가 이 카드의 답이면 질문 순서대로 고른 값을 되찾는다. */
export function recoverAnswers(ask: Ask, content: string): string[][] | null {
  const lines = content.split("\n");
  if (lines.length !== ask.questions.length) return null;

  const answers: string[][] = [];
  for (const [index, question] of ask.questions.entries()) {
    const prefix = `${question.header ?? question.text}: `;
    const line = lines[index];
    if (!line.startsWith(prefix) || line.length === prefix.length) return null;
    const value = line.slice(prefix.length);
    if (!question.multiple) {
      answers.push([value]);
      continue;
    }

    const picked: string[] = [];
    let remaining = value;
    let lastOption = -1;
    while (remaining.length > 0) {
      // 긴 이름을 먼저 본다. 쉼표가 든 선택지를 둘로 잘못 나누지 않기 위해서다.
      const match = question.options
        .map((option, optionIndex) => ({ label: option.label, optionIndex }))
        .filter(({ label, optionIndex }) => optionIndex > lastOption
          && (remaining === label || remaining.startsWith(`${label}, `)))
        .sort((left, right) => right.label.length - left.label.length)[0];
      if (!match) {
        picked.push(remaining); // 목록에 없는 나머지는 직접 입력한 답이다.
        break;
      }
      picked.push(match.label);
      lastOption = match.optionIndex;
      remaining = remaining === match.label ? "" : remaining.slice(match.label.length + 2);
      if (remaining.length === 0 && value.endsWith(", ")) return null;
    }
    answers.push(picked);
  }
  return answers;
}

function readAttributes(source: string): Record<string, string> {
  const attributes: Record<string, string> = {};
  for (const match of source.matchAll(/([a-z]+)="([^"]*)"/g)) {
    attributes[match[1]] = unescape(match[2]);
  }
  return attributes;
}

function unescape(value: string): string {
  return value.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&quot;", "\"").replaceAll("&amp;", "&");
}

/** 원문 안에 펜스가 있어도 일찍 닫히지 않게 안쪽의 가장 긴 백틱보다 하나 더 긴 펜스로 감싼다. */
function asCodeBlock(raw: string): string {
  const longest = Math.max(2, ...Array.from(raw.matchAll(/`+/g), (match) => match[0].length));
  const fence = "`".repeat(longest + 1);
  return `${fence}text\n${raw}\n${fence}`;
}
