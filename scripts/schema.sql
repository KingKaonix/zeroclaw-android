-- ZeroClaw License DB schema (Gumroad edition)
-- Run once after creating your D1 database:
--   npx wrangler d1 create zeroclaw-licenses
--   npx wrangler d1 execute zeroclaw-licenses --file=scripts/schema.sql --remote

CREATE TABLE IF NOT EXISTS licenses (
  email       TEXT    PRIMARY KEY,
  sale_id     TEXT    NOT NULL DEFAULT '',
  tier        TEXT    NOT NULL DEFAULT 'free',
  license_key TEXT,
  signature   TEXT,
  expires_at  INTEGER NOT NULL DEFAULT 0,   -- 0 = lifetime (Gumroad one-time)
  created_at  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_tier    ON licenses(tier);
CREATE INDEX IF NOT EXISTS idx_sale_id ON licenses(sale_id);
