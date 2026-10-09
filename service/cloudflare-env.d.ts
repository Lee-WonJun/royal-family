declare namespace Cloudflare {
  interface Env {
    DB?: D1Database;
    BUCKET?: R2Bucket;
    AI_UNLOCK_CODE?: string;
    OPENAI_API_KEY?: string;
    RF_FORCE_MOCK?: string;
  }
}
