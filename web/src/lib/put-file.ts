// 답 아래 파일 줄의 순수 함수다. 넣기 도구가 「내 파일」 에 넣은 파일(`kind: "FILE"`)을 그린다.
// 종류는 보관함이 받는 형식(`VaultFileType`)과 같은 묶음이다.
// `web/test/unit/put-files.test.ts` 가 `node --test` 로 직접 읽으므로 import 는 상대 경로만 쓴다.
import { artifactName } from "./artifact-name.ts";

const KINDS: Record<string, "그림" | "문서" | "표"> = {
  png: "그림",
  jpg: "그림",
  jpeg: "그림",
  gif: "그림",
  webp: "그림",
  html: "문서",
  css: "문서",
  md: "문서",
  txt: "문서",
  pdf: "문서",
  docx: "문서",
  pptx: "문서",
  csv: "표",
  tsv: "표",
  xlsx: "표",
};

/** 확장자로 정한 종류 글자다. 모르는 확장자면 「파일」 이다. */
export function putFileKind(path: string): string {
  const name = path.slice(path.lastIndexOf("/") + 1);
  const dot = name.lastIndexOf(".");
  if (dot < 0) return "파일";
  return KINDS[name.slice(dot + 1).toLowerCase()] ?? "파일";
}

/** 보이는 이름이다. 제목이 있으면 제목, 없으면 파일 이름이다. */
export function putFileName(file: {
  path: string;
  title?: string | null;
}): string {
  const title = file.title?.trim();
  return title ? title : artifactName(file.path);
}
