/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // typedRoutes generates compile-time links so a typo in a `Link` href
  // fails at build time rather than at runtime.
  typedRoutes: true,
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'X-Frame-Options', value: 'DENY' },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
        ],
      },
    ];
  },
  webpack: (config, { isServer }) => {
    // react-diff-viewer-continued and other browser-only deps occasionally
    // reference Node globals (process.platform, Buffer) when bundled for
    // the client. Tell webpack they don't exist so the bundle compiles.
    if (!isServer) {
      config.resolve = config.resolve || {};
      config.resolve.fallback = {
        ...(config.resolve.fallback || {}),
        process: false,
        buffer: false,
        stream: false,
      };
    }
    return config;
  },
};

export default nextConfig;
