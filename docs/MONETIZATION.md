# Monetization Strategy

## Goal

Replace Kaos's current income with recurring revenue from ZeroClaw Android.

## Tiers

### Free ($0)
- Basic chat agent via the WebView dashboard
- 1 channel (Telegram)
- 3 skills (from the registry)
- Bring your own API key
- "Unlicensed" watermark in bottom corner of WebView
- No cloud sync

### Pro ($8/month)
- All channels (Telegram, Discord, WhatsApp, Signal, Matrix, email, webhook, CLI)
- All skills
- Full sandbox configuration
- Managed LLM access — your prompts route through `api.zeroclaw.app/chat`
  - You pay ~$0.15/M tokens via OpenRouter
  - You charge $8/mo flat. Heavy users rate-limited to 5M tokens/mo
  - Margin on heavy users: $8 - (5M × $0.15/M) = $0.75 profit
  - Most users use way less, so margin is higher
- Cloud skill sync (skills sync across devices)
- No watermark
- Priority support (email, 48hr response)

### Enterprise ($20/month)
- White-label: branded as the company's own assistant
- Custom channel support (integrate their internal tools)
- SLA: 99.9% gateway uptime
- Dedicated config templates
- Priority support (24hr response)
- Bulk license discounts at 10+ seats

## Payment Infrastructure

### Direct Sales (primary)
- **LemonSqueezy** (formerly Paddle) — handles tax/VAT globally, 5% + $0.50
- No monthly fee. You only pay per sale.
- They handle EU VAT, US sales tax, etc.
- Checkout page: `zeroclaw.lemonsqueezy.com/checkout/buy/xxx`

### Play Store (secondary, after traction)
- 30% cut, but discovery is real
- List Free tier only
- Pro tier requires in-app purchase via Play Billing (Google takes 30%)
- **Play Store is customer acquisition, not your primary revenue channel**

### Payment flow

```
Customer clicks "Buy Pro $8/mo"
  → LemonSqueezy checkout
  → Stripe/PayPal payment
  → LemonSqueezy webhook POST to api.zeroclaw.app/webhook
  → Cloudflare Worker stores license_key + device_id in KV
  → Customer gets license key via email
  → Customer enters key in app Settings
  → App validates against api.zeroclaw.app/verify
  → App unlocks Pro features
```

## Licensing (Anti-Piracy)

### V1 (launch): Honor system + watermark
- APK is the same for everyone
- Free tier watermarked
- Pro users get a license key. Enter it → watermark gone
- No phone-home. License key is a signed JWT baked into the app
- Key format: `ZCLAW-XXXX-XXXX-XXXX`
- Simple ed25519 signature verification (public key in app, private key on your laptop)
- **Why V1 is enough:** At launch you have zero users. Nobody will crack it. By the time you have enough users for cracking to matter, you build V2.

### V2 (scale): Phone-home server
Same licensing but adds periodic online verification:
- Cloudflare Worker validates license key from Stripe/LemonSqueezy
- Returns signed JWT with 7-day expiry
- App caches JWT, re-verifies weekly
- If verify fails → degrade to Free (keep working, just watermarked)

### V3 (hardening): Feature server
- Premium skills and channel adapters are cryptographically signed blobs
- Your server signs them per-license
- Can't run premium features without your server's signature
- **Strongest protection** — even a cracked APK can't load the features

## Marketing Channels ($0 budget)

| Channel | Strategy | Timeline |
|---------|----------|----------|
| **HN** | "Show HN: ZeroClaw for Android — Open-source AI assistant on your phone" | Launch day |
| **r/selfhosted** | "I ported the ZeroClaw AI agent to Android. Here's how." | Launch day+1 |
| **r/androiddev** | "Cross-compiling Rust for Android: ZeroClaw case study" | Week 2 |
| **r/LocalLLaMA** | "On-device AI agent running on your phone" | Week 2 |
| **Discord** | Hang in zeroclaw-labs Discord, be helpful, mention your app naturally | Ongoing |
| **X/Twitter** | Build in public. Daily updates on cross-compilation journey | Starting now |
| **YouTube** | Screen recording of ZeroClaw running on a phone | Week 3 |
| **GitHub** | README shows stars. More stars = more virality | Organic |

## Financial Model

### Year 1 Projection (Conservative)

| Month | Users | Pro Subs ($8/mo) | Enterprise ($20/mo) | Revenue |
|-------|-------|------------------|---------------------|---------|
| 1 | 20 | 2 | 0 | $16 |
| 2 | 50 | 8 | 0 | $64 |
| 3 | 100 | 20 | 1 | $180 |
| 4 | 200 | 40 | 2 | $360 |
| 5 | 350 | 70 | 3 | $620 |
| 6 | 500 | 100 | 5 | $900 |
| 7 | 750 | 150 | 7 | $1,340 |
| 8 | 1,000 | 200 | 10 | $1,800 |
| 9 | 1,250 | 250 | 12 | $2,240 |
| 10 | 1,500 | 300 | 15 | $2,700 |
| 11 | 1,750 | 350 | 17 | $3,140 |
| 12 | 2,000 | 400 | 20 | $3,600 |

Break-even (replacement income): **Month 8-9** at ~250-300 Pro subs.

## Expenses ($0/mo until scale)

| Item | Cost | Notes |
|------|------|-------|
| Domain (zeroclaw.app) | $12/yr | Namecheap |
| Cloudflare Workers | $0 | Free tier: 100k req/day |
| GitHub | $0 | Public repo |
| LemonSqueezy | $0/mo | 5% + $0.50 per sale |
| OpenRouter (managed LLM) | Variable | Pay per token. First $5 credit free. |
| Play Store | $25 one-time | Developer account fee. Pay when ready. |
| **Total fixed** | **$1/mo** | |

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| zeroclaw-labs makes their own Android app | Fork now, establish brand first. Your app is the "official unofficial" Android client. By the time they move, you have users. |
| Play Store rejects the app | Distribute via GitHub Releases + F-Droid + direct APK on your site. Play Store is optional. |
| Users don't pay | Free tier is generous. Freemium conversion is 2-5%. Need 10k downloads → 250 Pro subs. Focus on distribution. |
| Too hard to set up | Onboarding wizard on first launch. Default config works out of box with managed LLM tier. |
| Battery drain | Aggressive Doze handling. Polling pauses when screen off and no active channels. |

## Immediate Next Steps

1. [ ] Install Rust + Android targets
2. [ ] Fork zeroclaw-labs/zeroclaw
3. [ ] Cross-compile zeroclaw for aarch64-linux-android
4. [ ] Wire up subprocess launch on device
5. [ ] Ship functional APK
6. [ ] Set up LemonSqueezy product
7. [ ] Post on HN
8. [ ] Iterate based on feedback
