# Monetization Strategy

## ✅ Implemented

| Component | Status | File |
|-----------|--------|------|
| License validation (ed25519, offline) | ✅ Done | `LicenseValidator.kt` |
| License key gen (offline CLI) | ✅ Done | `scripts/keygen.js` |
| Cloudflare Worker license server | ✅ Done | `scripts/license-worker.js` |
| Gumroad sale ping webhook | ✅ Done | `POST /webhook/gumroad` |
| Gumroad API sale verification | ✅ Done | verifyGumroadSale() |
| Refund / chargeback revocation | ✅ Done | refunded/disputed ping handling |
| D1 license database | ✅ Done | `scripts/schema.sql` |
| License revocation | ✅ Done | D1 tier=free check on /validate |
| Admin keygen endpoint | ✅ Done | `GET /keygen?auth=...` |
| Admin stats endpoint | ✅ Done | `GET /stats?auth=...` |
| In-app auto-activation (sale ID) | ✅ Done | `MainActivity.kt` |
| Manual key paste activation | ✅ Done | `MainActivity.kt` |
| Watermark for free tier | ✅ Done | WebView JS injection |

## 🔲 To Set Up (one-time, manual)

### 1. Gumroad
1. Create account at gumroad.com
2. New Product → "ZeroClaw Android Pro" → $8 (or $5 alpha)
3. Set the permalink slug (e.g. `zeroclaw-android`) — this becomes your store URL:
   `https://kaonixx.gumroad.com/l/zeroclaw-android`
4. Settings → Advanced → Applications → **Create an application** → copy the Access Token
5. Product settings → **Ping a URL**: `https://zeroclaw-license.kaonixx.workers.dev/webhook/gumroad`

### 2. Cloudflare Worker
```bash
# Create the D1 database
npx wrangler d1 create zeroclaw-licenses

# Paste the database_id into wrangler.toml [[d1_databases]]

# Run the schema
npx wrangler d1 execute zeroclaw-licenses --file=scripts/schema.sql --remote

# Set secrets (never put these in wrangler.toml)
npx wrangler secret put ZEROCLAW_PRIVATE_KEY   # from: node scripts/keygen.js (PKCS8 base64)
npx wrangler secret put ZEROCLAW_PUBLIC_KEY    # from: node scripts/keygen.js (SPKI base64)
npx wrangler secret put ADMIN_KEY              # any random secret string
npx wrangler secret put GUMROAD_ACCESS_TOKEN   # from Gumroad Settings → Advanced

# Deploy
npx wrangler deploy
```

### 3. Update Android app
In `MainActivity.kt` companion object, update the two constants to match your actual URLs:
```kotlin
private const val GUMROAD_URL        = "https://YOUR_NAME.gumroad.com/l/YOUR_PERMALINK"
private const val LICENSE_SERVER_URL = "https://zeroclaw-license.YOUR_SUBDOMAIN.workers.dev"
```

### 4. Generate keypair
```bash
node scripts/keygen.js
# (no args — prints usage and the public key)
# Paste PRIVATE_KEY_B64 when prompted for ZEROCLAW_PRIVATE_KEY wrangler secret
# The PUBLIC_KEY_B64 is already baked into LicenseValidator.kt
```

---

## How It Works

### Purchase flow
```
Buyer clicks "Get Pro" in app
  → Gumroad checkout page ($8 one-time)
  → Payment processed
  → Gumroad pings POST /webhook/gumroad
  → Worker verifies sale via Gumroad API
  → Issues ed25519-signed license key
  → Stores in D1
  → Returns key in ping response (Gumroad shows it on receipt page)
```

### Activation flow
```
Buyer opens app → menu → "Activate License"
  Option A (auto): enters email + Gumroad sale ID from receipt
    → App calls POST /activate on license server
    → Server verifies + returns key
    → App activates offline

  Option B (manual): pastes license key + signature
    → App verifies locally against baked-in public key
    → Activates offline, no network needed
```

### Refund flow
```
Buyer refunds on Gumroad
  → Gumroad pings /webhook/gumroad with refunded=true
  → Worker sets tier='free' in D1
  → Next time app calls /validate, returns revoked
  → App degrades to free tier on next restart
```

---

## Tiers

### Free ($0)
- ZeroClaw agent via WebView dashboard
- Bring your own API key
- "UNLICENSED" watermark in WebView corner

### Pro ($8 one-time)
- No watermark
- Receipt shows license key immediately
- Auto-activate via email + sale ID

---

## Revenue Math

| Metric | Value |
|--------|-------|
| Price | $8 one-time |
| Gumroad cut | 10% |
| Net per sale | ~$7.20 |
| 100 sales | ~$720 |
| 500 sales | ~$3,600 |

Gumroad charges a flat 10% on free-tier accounts (or ~3.5% + $0.30 if on Gumroad Pro at $10/mo).

## Admin endpoints

```bash
# Issue a comp key
curl "https://zeroclaw-license.YOUR_SUBDOMAIN.workers.dev/keygen?auth=YOUR_ADMIN_KEY&email=user@example.com&tier=pro"

# View stats
curl "https://zeroclaw-license.YOUR_SUBDOMAIN.workers.dev/stats?auth=YOUR_ADMIN_KEY"

# Verify a key
curl "https://zeroclaw-license.YOUR_SUBDOMAIN.workers.dev/validate?key=ZCLAW-1:...&sig=..."
```
