import type { NextConfig } from "next";

const ICON_FONT =
  "./node_modules/pretendard/dist/public/static/Pretendard-Bold.otf";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Ships only the server files the app actually needs, which keeps the image small on a host
  // that is already close to its memory limit.
  output: "standalone",
  // 홈 화면 아이콘이 첫 글자를 그릴 한글 글꼴이다. 코드가 경로로 읽어 추적에 잡히지 않으므로 standalone 결과에 직접 넣는다.
  outputFileTracingIncludes: {
    "/app-icon/[size]": [ICON_FONT],
    "/apple-icon": [ICON_FONT],
  },
  // ViewTransition은 Next.js에 포함된 React가 제공한다. 화면 쪽의 환경 변수로 CSS 전환을 고를 수 있다.
};

export default nextConfig;
