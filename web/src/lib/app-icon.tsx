import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { ImageResponse } from "next/og";
import { appName } from "@/lib/app-name";

/** 아이콘 바탕색이다. 단색 바탕에 첫 글자 하나만 둔다(스펙 DECISION11). 나중에 그림 파일로 바꿀 수 있다. */
const BACKGROUND = "#18181b";
const FOREGROUND = "#fafafa";

/**
 * 첫 글자를 그릴 글꼴이다. `next/og` 의 기본 글꼴은 한글이 없어 한글 앱 이름이 빈 아이콘이 된다.
 *
 * <p>standalone 결과에 이 파일이 들어가도록 `next.config.ts` 의 `outputFileTracingIncludes` 가 함께 가리킨다.
 */
const FONT_FILE = join(
  process.cwd(),
  "node_modules/pretendard/dist/public/static/Pretendard-Bold.otf",
);

let font: Promise<Buffer> | null = null;

function loadFont(): Promise<Buffer> {
  font ??= readFile(FONT_FILE).catch((error: unknown) => {
    // 실패를 기억하지 않는다. 다음 요청이 다시 읽는다.
    font = null;
    throw error;
  });
  return font;
}

/** 앱 이름의 첫 글자다. 이모지처럼 두 code unit 인 글자도 한 글자로 센다. */
export function appInitial(name: string): string {
  return Array.from(name)[0]?.toUpperCase() ?? "A";
}

/**
 * 요청할 때의 앱 이름으로 정사각형 PNG 홈 화면 아이콘을 만든다(docs/features/attention/spec.md 의 REQ4).
 * manifest 의 192, 512 와 iOS 의 180 이 부른다.
 */
export async function appIcon(size: number): Promise<ImageResponse> {
  // next/og 는 Tailwind 클래스를 읽지 않고 인라인 스타일만 그린다. 글자 크기도 아이콘 크기에 따라 이어지는 값이다.
  const style = {
    width: "100%",
    height: "100%",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    background: BACKGROUND,
    color: FOREGROUND,
    fontSize: Math.round(size * 0.56),
    fontFamily: "Pretendard",
  };
  return new ImageResponse(<div style={style}>{appInitial(appName())}</div>, {
    width: size,
    height: size,
    fonts: [{ name: "Pretendard", data: await loadFont(), weight: 700 }],
    // 앱 이름이 실행 환경 값이라 오래 묶어 두지 않는다.
    headers: { "Cache-Control": "public, max-age=3600" },
  });
}
