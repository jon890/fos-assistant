/** `POST /v1/runs` 의 `input` 이 가질 수 있는 두 모양이다. 글 한 덩어리이거나 메시지 목록이다. */
export type SubmittedInput =
  | string
  | {
      role?: string;
      content?: string | { type?: string; text?: string; image_url?: { url?: string } }[];
    }[];

export type SubmittedImage = { label: string; url: string };

/**
 * 시나리오 분기와 검사가 읽는 입력 글이다.
 * 목록이면 마지막 항목의 `content` 를 본다. `content` 가 글이면 그것, 파트 목록이면 첫 글 파트다.
 * 이름표 글 파트를 이어 붙이지 않는다. 이어 붙이면 `endsWith` 같은 검사와 분기 조건이 달라진다.
 */
export function submittedText(input: SubmittedInput | undefined): string {
  if (input === undefined) return "";
  if (typeof input === "string") return input;
  const content = input.at(-1)?.content;
  if (typeof content === "string") return content;
  return content?.find((part) => part.type === "text")?.text ?? "";
}

/** 마지막 항목의 이미지 파트마다 이름표와 주소를 꺼낸다. 이름표는 바로 앞 파트가 글일 때만 있다. */
export function submittedImages(input: SubmittedInput | undefined): SubmittedImage[] {
  if (input === undefined || typeof input === "string") return [];
  const content = input.at(-1)?.content;
  if (!Array.isArray(content)) return [];
  const images: SubmittedImage[] = [];
  content.forEach((part, index) => {
    if (part.type !== "image_url") return;
    const previous = content[index - 1];
    images.push({
      label: previous?.type === "text" ? (previous.text ?? "") : "",
      url: part.image_url?.url ?? "",
    });
  });
  return images;
}
