import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Ships only the server files the app actually needs, which keeps the image small on a host
  // that is already close to its memory limit.
  output: "standalone",
  // 화면을 옮길 때의 움직임에 쓰는 실험 기능이다. 빌드할 때 `NEXT_PUBLIC_VIEW_TRANSITION=off` 를 주면 끈다.
  experimental: {
    viewTransition: process.env.NEXT_PUBLIC_VIEW_TRANSITION !== "off",
  },
};

export default nextConfig;
