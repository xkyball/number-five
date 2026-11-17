import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Keep titles, canonical URLs and language links in the initial HTML head.
  // This small editorial site does not need streamed metadata; crawlers and
  // visitors should receive the same complete metadata without JavaScript.
  htmlLimitedBots: /.*/,
};

export default nextConfig;
