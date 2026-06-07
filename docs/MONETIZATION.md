# Monetization Strategy

## ✅ Implemented

| Component | Status | File |
|-----------|--------|------|
| License validation (ed25519, offline) | ✅ Done | `LicenseValidator.kt` |
| License key gen (offline) | ✅ Done | `scripts/keygen.js` |
| Cloudflare Worker license server | ✅ Done | `scripts/license-worker.js` |
| LemonSqueezy webhook integration | ✅ Done | `/webhook/lemonsqueezy` endpoint |
| LemonSqueezy API purchase verification | ✅ Done | `/activate` endpoint |
| D1 license database | ✅ Done | `scripts/schema.sql` |
| License revocation | ✅ Done | D1 tier=free check on /validate |
| Admin keygen endpoint | ✅ Done | `/keygen?auth=...` |
| Admin stats endpoint | ✅ Done | `/stats?auth=...` → MRR calc |
| Free/Pro UI gating | ✅ Done | watermark, menu, feature checks |
| Pro tier → Gumroad link | ✅ Done | `MainActivity.kt` menu |

## 🔲 To Set Up (one-time, manual)

1. **LemonSqueezy account** → create store + 2 products (Pro $8/mo, Enterprise $20/mo)
2. **Cloudflare deployment**:
   ```bash
   npx wrangler d1 create zeroclaw-licenses
   # Update database_id in wrangler.toml
   npx wrangler d1 execute zeroclaw-licenses --file=scripts/schema.sql
   npx wrangler secret put ZEROCLAW_PRIVATE_KEY
   npx wrangler secret put ZEROCLAW_PUBLIC_KEY
   npx wrangler secret put ADMIN_KEY
   npx wrangler secret put LEMONSQUEEZY_API_KEY
   npx wrangler secret put LEMONSQUEEZY_WEBHOOK_SECRET
   npx wrangler deploy
   ```
3. **LemonSqueezy webhook** → point to `https://license.zeroclaw.app/webhook/lemonsqueezy`
4. **Update Android app** → change Gumroad URL to LemonSqueezy checkout URL in `MainActivity.kt`
5. **Generate keypair** → `node scripts/keygen.js` (ed25519), put public key in `LicenseValidator.kt`

## Tiers

### Free ($0)
- Basic chat agent via WebView dashboard
- 1 channel (Telegram)
- 3 skills
- Bring your own API key
- "UNLICENSED" watermark in WebView corner

### Pro ($8/month)
- All channels (Telegram, Discord, WhatsApp, Signal, Matrix, email, webhook, CLI)
- All skills + cloud sync
- Managed LLM via `api.zeroclaw.app/chat` (OpenRouter, ~$0.15/M tokens)
- No watermark
- Priority support (48hr)

### Enterprise ($20/month)
- White-label
- Custom channels + SLA (99.9%)
- Bulk 10+ seat discounts

## Payment Flow

```
Customer taps "Get Pro"
  → LemonSqueezy checkout (Pro or Enterprise)
  → Payment processed (Stripe/PayPal)
  → LemonSqueezy webhook → /webhook/lemonsqueezy
  → CF Worker issues ed25519-signed license key
  → Stored in D1 + emailed to customer
  → Customer enters key in app
  → App verifies signature locally (offline-capable)
  → Pro features unlocked, watermark removed
```

## Revenue Math

| Metric | Value |
|--------|-------|
| Pro price | $8/mo |
| Ent price | $20/mo |
| LemonSqueezy cut | 5% + $0.50 |
| Net Pro | $7.10/mo per sub |
| Net Ent | $18.50/mo per sub |
| Managed LLM cost | ~$0.75/mo avg user |
| **Net margin Pro** | **~$6.35/mo** |
| Break-even | ~250 Pro subs ≈ $2,000 MRR |

## Marketing ($0 budget)

| Channel | What | When |
|---------|------|------|
| HN | Show HN: ZeroClaw Android | Launch |
| r/selfhosted, r/LocalLLaMA | Port announcement | Launch+1 |
| r/androiddev | Cross-compiling Rust case study | Week 2 |
| X/Twitter | Build in public | Ongoing |
| zeroclaw-labs Discord | Be helpful, mention naturally | Ongoing |

## Year 1 Projection

| Month | Users | Pro | Ent | MRR |
|-------|-------|-----|-----|-----|
| 1 | 20 | 2 | 0 | $16 |
| 3 | 100 | 20 | 1 | $180 |
| 6 | 500 | 100 | 5 | $900 |
| 9 | 1,250 | 250 | 12 | $2,240 |
| 12 | 2,000 | 400 | 20 | **$3,600** |

Break-even (replaces income): **Month 8-9**

## Expenses

| Item | Cost |
|------|------|
| zeroclaw.app domain | $12/yr |
| Cloudflare Workers | $0 (free tier) |
| GitHub | $0 (public) |
| LemonSqueezy | 5% + $0.50/sale |
| OpenRouter | Pay per token |
| Play Store | $25 one-time (when ready) |
| **Fixed** | **~$1/mo** |
