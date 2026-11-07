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
};

export default nextConfig;
