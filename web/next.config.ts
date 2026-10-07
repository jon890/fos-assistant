import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Ships only the server files the app actually needs, which keeps the image small on a host
  // that is already close to its memory limit.
  output: "standalone",
  // ViewTransition은 Next.js에 포함된 React가 제공한다. 화면 쪽의 환경 변수로 CSS 전환을 고를 수 있다.
};

export default nextConfig;
