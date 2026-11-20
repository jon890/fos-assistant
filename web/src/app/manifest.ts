import type { MetadataRoute } from "next";
import { connection } from "next/server";
import { appName } from "@/lib/app-name";

/**
 * 홈 화면에 추가할 때 읽는 웹 앱 설정이다(docs/features/attention/spec.md 의 REQ4). 아이폰은 홈 화면 앱에서만 웹 푸시를 받는다.
 */
export default async function manifest(): Promise<MetadataRoute.Manifest> {
  // 앱 이름은 실행할 때의 환경에서 읽는다. 빌드할 때 굳지 않게 요청마다 만든다.
  await connection();
  const name = appName();
  return {
    name,
    short_name: name,
    start_url: "/",
    scope: "/",
    display: "standalone",
    background_color: "#18181b",
    theme_color: "#18181b",
    icons: [
      { src: "/app-icon/192", sizes: "192x192", type: "image/png" },
      { src: "/app-icon/512", sizes: "512x512", type: "image/png" },
    ],
  };
}
