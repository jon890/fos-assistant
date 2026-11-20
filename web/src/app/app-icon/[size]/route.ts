import { connection } from "next/server";
import { appIcon } from "@/lib/app-icon";

type RouteContext = { params: Promise<{ size: string }> };

/** manifest 가 가리키는 홈 화면 아이콘이다. 192 와 512 만 있다. */
export async function GET(_request: Request, context: RouteContext) {
  // 앱 이름은 실행할 때의 환경에서 읽는다. 빌드할 때 굳지 않게 요청마다 만든다.
  await connection();
  const { size } = await context.params;
  if (size !== "192" && size !== "512") {
    return new Response(null, { status: 404 });
  }
  return appIcon(Number(size));
}
