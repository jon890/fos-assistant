import { connection } from "next/server";
import { appIcon } from "@/lib/app-icon";

export const size = { width: 180, height: 180 };
export const contentType = "image/png";

/** iOS 홈 화면 아이콘이다. Next 가 `<link rel="apple-touch-icon">` 을 붙인다. */
export default async function AppleIcon() {
  // 앱 이름은 실행할 때의 환경에서 읽는다. 빌드할 때 굳지 않게 요청마다 만든다.
  await connection();
  return appIcon(size.width);
}
