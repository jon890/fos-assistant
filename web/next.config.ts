import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Ships only the server files the app actually needs, which keeps the image small on a host
  // that is already close to its memory limit.
  output: "standalone",
};

export default nextConfig;
