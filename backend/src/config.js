const env = process.env;
const int = (v, d) => (Number.isFinite(parseInt(v, 10)) ? parseInt(v, 10) : d);

export function loadConfig(overrides = {}) {
  return {
    port: int(env.PORT, 3000),
    authHost: env.AUTH_HOST || 'billy.example.com',
    basePath: (env.BASE_PATH ?? '/api').replace(/\/$/, ''),
    dbPath: env.DB_PATH || './billy.db',
    loginTokenTtlMs: int(env.LOGIN_TOKEN_TTL_MINUTES, 10) * 60_000,
    sessionTtlMs: int(env.SESSION_TTL_DAYS, 30) * 86_400_000,
    trustProxy: env.TRUST_PROXY === '1',
    twilio: {
      sid: env.TWILIO_ACCOUNT_SID,
      token: env.TWILIO_AUTH_TOKEN,
      from: env.TWILIO_FROM,
      messagingServiceSid: env.TWILIO_MESSAGING_SERVICE_SID,
    },
    androidPackage: env.ANDROID_PACKAGE || 'com.example.billy',
    androidCertSha256: env.ANDROID_SHA256_CERT_FINGERPRINT || '',
    // Rate limits
    maxSmsPerPhonePerHour: 5,
    maxSmsPerIpPerHour: 20,
    ...overrides,
  };
}
