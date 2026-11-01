declare namespace Cloudflare {
  interface Env {
    DB?: D1Database;
    BUCKET?: R2Bucket;
    OWNER_EMAIL?: string;
    CONTACT_SECRET?: string;
  }
}
