# ZeroClaw Android — Product & Marketing Assets

## Gumroad Product Page Copy

**Title:** ZeroClaw Android — AI Agent on Your Phone
**Price:** $5 (one-time) — Alpha access
**Tags:** AI, agent, Android, automation, open source

**Description:**

ZeroClaw brings a local AI agent to your Android device. Runs entirely on-device — no cloud dependency. Think of it as a personal AI assistant that's always available, works with any LLM provider, and respects your privacy.

### What you get
- 📱 **One-tap install APK** — no ADB, no terminal
- 🧠 **28+ LLM providers** (OpenAI, Anthropic, Ollama, OpenRouter…)
- 🔧 **Agent with tools** — filesystem, web, shell (sandboxed)
- 🌐 **Local Web dashboard** at http://127.0.0.1:18789
- 🔒 **Runs in foreground** — stays alive, starts on boot
- 🆓 **Also available as free binary** — this is the convenience package

### This is an ALPHA
- ARM64 only (most modern phones)
- Debug-signed APK
- You're an early adopter — rough edges expected, updates frequent
- Support via GitHub Issues

### Free vs Pro
| Feature | Free (OSS) | Pro ($5) |
|---------|-----------|----------|
| ZeroClaw binary | ✅ Download from GitHub | ✅ One-tap APK |
| Android service | ❌ ADB needed | ✅ Install & run |
| Dashboard | ✅ | ✅ No watermark |
| Auto-start | ❌ | ✅ |
| Early updates | ❌ | ✅ Direct APK |
| License | ❌ Watermark shown | ✅ Clean |

### Links
- GitHub: https://github.com/KingKaonix/zeroclaw-android
- Website: https://kingkaonix.github.io/knet/

---

## Hacker News Post Draft

**Title:** Show HN: ZeroClaw for Android — I compiled a Rust AI agent (31.7k⭐) to run on your phone

**Body:**

I've been following ZeroClaw (zeroclaw-labs/zeroclaw) — an open-source AI agent framework written in Rust that's been blowing up. 31.7k stars, 28+ LLM providers, sandboxed tool execution.

Problem: It's a CLI tool. Great for servers, useless on mobile.

So I cross-compiled it to aarch64-linux-android, wrapped it in a foreground service + WebView, and shipped an APK.

**What it does:**
- Runs ZeroClaw daemon on your Android device
- Web dashboard at localhost:18789
- Any LLM provider — bring your own key or use the managed tier
- Foreground service, survives screen off, optional boot start
- Full tool execution (sandboxed filesystem, web fetch, etc.)

**Stack:**
- Rust → ARM64 via NDK clang + GitHub Actions CI
- Kotlin foreground service
- WebView dashboard
- ed25519 license gating (free tier = watermark, $5 Pro)

**Links:**
- APK: https://github.com/KingKaonix/zeroclaw-android/releases
- Source: https://github.com/KingKaonix/zeroclaw-android
- $5 alpha: (Gumroad link TBD)

Would love feedback — especially on the license gating approach, the WebView UX, and whether there's demand for a managed cloud gateway tier ($8/mo).

---

## Reddit Post (r/selfhosted)

**Title:** I turned an open-source Rust AI agent (31.7k⭐) into an Android APK

**Body:**

ZeroClaw is a killer AI agent framework — but it's CLI-only. I wanted it on my phone.

After a week of fighting cross-compilation (thanks NDK), I've got a working APK:

- ZeroClaw daemon running as a foreground service
- WebView dashboard
- 28+ LLM providers
- Sandboxed tool execution
- ~8MB binary, ~17MB APK

Free version on GitHub (with watermark), $5 one-time for clean APK.

https://github.com/KingKaonix/zeroclaw-android

Yes it's alpha. Yes there will be bugs. But it WORKS. 🚀

---

## License Key Generation (for you)

```bash
# Generate a lifetime key for a user
node scripts/keygen.js user@email.com

# Generate a key that expires Jan 1 2030
node scripts/keygen.js user@email.com 1893456000000
```

Output gives you:
- LicenseKey: ZCLAW-1:email:expiry
- Signature: base64 sig
- Paste both into the app's "Enter License Key" dialog
