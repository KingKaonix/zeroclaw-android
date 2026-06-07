-- ZeroClaw License DB schema
-- Run: npx wrangler d1 execute zeroclaw-licenses --file=schema.sql

CREATE TABLE IF NOT EXISTS licenses (
  email TEXT PRIMARY KEY,
  subscription_id TEXT,
  tier TEXT NOT NULL DEFAULT 'free',
  license_key TEXT,
  signature TEXT,
  expires_at INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_tier ON licenses(tier);
CREATE INDEX IF NOT EXISTS idx_sub ON licenses(subscription_id);
