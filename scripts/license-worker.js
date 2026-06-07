// ZeroClaw License Server — Cloudflare Worker
// npx wrangler deploy
// Secrets: ZEROCLAW_PRIVATE_KEY, ZEROCLAW_PUBLIC_KEY, ADMIN_KEY, LEMONSQUEEZY_API_KEY
// Vars: LEMONSQUEEZY_STORE_ID, LEMONSQUEEZY_PRO_VARIANT_ID, LEMONSQUEEZY_ENT_VARIANT_ID

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method;
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type,Authorization",
    };

    if (method === "OPTIONS") return new Response(null, { headers: cors });

    try {
      // POST /webhook/lemonsqueezy — LemonSqueezy subscription webhook
      if (url.pathname === "/webhook/lemonsqueezy" && method === "POST") {
        const body = await request.text();
        const signature = request.headers.get("x-signature") || "";
        // Verify HMAC signature
        const key = await crypto.subtle.importKey(
          "raw", new TextEncoder().encode(env.LEMONSQUEEZY_WEBHOOK_SECRET || ""),
          { name: "HMAC", hash: "SHA-256" }, false, ["verify"]
        );
        const valid = await crypto.subtle.verify(
          { name: "HMAC", hash: "SHA-256" }, key,
          Uint8Array.from(atob(signature), c => c.charCodeAt(0)),
          new TextEncoder().encode(body)
        );
        if (!valid && env.LEMONSQUEEZY_WEBHOOK_SECRET) return json({ error: "invalid signature" }, 403, cors);

        const event = JSON.parse(body);
        const { meta, data } = event;

        if (meta?.event_name === "subscription_created" || meta?.event_name === "subscription_updated") {
          const email = data.attributes.user_email;
          const status = data.attributes.status;
          const variantId = data.attributes.variant_id?.toString();
          const subId = data.id;

          if (status !== "active") return json({ received: true }, 200, cors);

          // Determine tier from variant
          const tier = variantId === env.LEMONSQUEEZY_ENT_VARIANT_ID ? "enterprise" : "pro";
          const expiryMs = data.attributes.renews_at
            ? new Date(data.attributes.renews_at).getTime()
            : 0;

          // Issue license
          const privKey = await importPrivateKey(env.ZEROCLAW_PRIVATE_KEY);
          const licenseKey = `ZCLAW-1:${email}:${expiryMs}`;
          const sig = await crypto.subtle.sign({ name: "Ed25519" }, privKey, new TextEncoder().encode(licenseKey));

          // Store in D1
          if (env.DB) {
            await env.DB.prepare(
              "INSERT OR REPLACE INTO licenses (email, subscription_id, tier, license_key, signature, expires_at, created_at) VALUES (?1,?2,?3,?4,?5,?6,?7)"
            ).bind(email, subId, tier, licenseKey, b64(sig), expiryMs, Date.now()).run();
          }

          return json({ received: true, tier, licenseKey, signature: b64(sig) }, 200, cors);
        }

        if (meta?.event_name === "subscription_cancelled" || meta?.event_name === "subscription_expired") {
          const email = data.attributes.user_email;
          if (env.DB) {
            await env.DB.prepare("UPDATE licenses SET tier='free', expires_at=?1 WHERE email=?2")
              .bind(Date.now(), email).run();
          }
          return json({ received: true, downgraded: true }, 200, cors);
        }

        return json({ received: true }, 200, cors);
      }

      // POST /activate — manual activation (admin or after payment)
      if (url.pathname === "/activate" && method === "POST") {
        const { email, purchaseId, tier: reqTier } = await request.json();
        if (!email || !purchaseId) return json({ error: "email and purchaseId required" }, 400, cors);

        // Verify purchase with LemonSqueezy API
        if (env.LEMONSQUEEZY_API_KEY) {
          const verifyResp = await fetch(`https://api.lemonsqueezy.com/v1/subscriptions/${purchaseId}`, {
            headers: { "Authorization": `Bearer ${env.LEMONSQUEEZY_API_KEY}`, "Accept": "application/json" },
          });
          if (!verifyResp.ok) return json({ error: "purchase verification failed" }, 402, cors);
          const purchaseData = await verifyResp.json();
          if (purchaseData.data?.attributes?.status !== "active") {
            return json({ error: "purchase not active" }, 402, cors);
          }
        } else {
          // Fallback: accept any 8+ char purchaseId (dev mode)
          if (purchaseId.length < 8) return json({ error: "invalid purchase" }, 402, cors);
        }

        const tier = reqTier || "pro";
        const privKey = await importPrivateKey(env.ZEROCLAW_PRIVATE_KEY);
        const licenseKey = `ZCLAW-1:${email}:0`;
        const sig = await crypto.subtle.sign({ name: "Ed25519" }, privKey, new TextEncoder().encode(licenseKey));

        if (env.DB) {
          await env.DB.prepare(
            "INSERT OR REPLACE INTO licenses (email, subscription_id, tier, license_key, signature, expires_at, created_at) VALUES (?1,?2,?3,?4,?5,?6,?7)"
          ).bind(email, purchaseId, tier, licenseKey, b64(sig), 0, Date.now()).run();
        }

        return json({ success: true, licenseKey, signature: b64(sig), tier }, 200, cors);
      }

      // GET /validate — verify license sig + expiry (device-side)
      if (url.pathname === "/validate" && method === "GET") {
        const key = url.searchParams.get("key");
        const sig = url.searchParams.get("sig");
        if (!key || !sig) return json({ valid: false, error: "missing params" }, 400, cors);

        const parts = key.split(":");
        const expiry = parseInt(parts[2] || "0");
        if (expiry > 0 && Date.now() > expiry) return json({ valid: false, error: "expired" }, 200, cors);

        const valid = await verifyWithPublicKey(key, sig, env.ZEROCLAW_PUBLIC_KEY);

        // Check D1 for revocation
        if (valid && env.DB && parts[1]) {
          const row = await env.DB.prepare("SELECT tier FROM licenses WHERE email=?1").bind(parts[1]).first();
          if (row && row.tier === "free") return json({ valid: false, error: "revoked" }, 200, cors);
        }

        return json({ valid, tier: valid ? "pro" : "invalid" }, 200, cors);
      }

      // GET /keygen?auth=...&email=...&expiry=0&tier=pro — admin key generation
      if (url.pathname === "/keygen" && method === "GET") {
        if (url.searchParams.get("auth") !== env.ADMIN_KEY) return json({ error: "unauthorized" }, 403, cors);
        const email = url.searchParams.get("email");
        if (!email) return json({ error: "email required" }, 400, cors);
        const expiry = url.searchParams.get("expiry") || "0";
        const tier = url.searchParams.get("tier") || "pro";

        const privKey = await importPrivateKey(env.ZEROCLAW_PRIVATE_KEY);
        const licenseKey = `ZCLAW-1:${email}:${expiry}`;
        const sig = await crypto.subtle.sign({ name: "Ed25519" }, privKey, new TextEncoder().encode(licenseKey));

        if (env.DB) {
          await env.DB.prepare(
            "INSERT OR REPLACE INTO licenses (email, subscription_id, tier, license_key, signature, expires_at, created_at) VALUES (?1,?2,?3,?4,?5,?6,?7)"
          ).bind(email, "admin", tier, licenseKey, b64(sig), parseInt(expiry), Date.now()).run();
        }

        return json({
          licenseKey, signature: b64(sig), tier,
          expiresAt: expiry === "0" ? null : new Date(parseInt(expiry)).toISOString(),
        }, 200, cors);
      }

      // GET /stats?auth=... — admin dashboard stats
      if (url.pathname === "/stats" && method === "GET") {
        if (url.searchParams.get("auth") !== env.ADMIN_KEY) return json({ error: "unauthorized" }, 403, cors);
        if (!env.DB) return json({ error: "no DB" }, 500, cors);
        const pro = await env.DB.prepare("SELECT COUNT(*) as c FROM licenses WHERE tier='pro'").first();
        const ent = await env.prepare("SELECT COUNT(*) as c FROM licenses WHERE tier='enterprise'").first();
        const total = await env.DB.prepare("SELECT COUNT(*) as c FROM licenses").first();
        return json({ pro: pro?.c || 0, enterprise: ent?.c || 0, total: total?.c || 0, mrr: ((pro?.c || 0) * 8 + (ent?.c || 0) * 20) }, 200, cors);
      }

      return json({ error: "not found" }, 404, cors);
    } catch (err) {
      return json({ error: err.message }, 500, cors);
    }
  },
};

async function importPrivateKey(b64Der) {
  const raw = Uint8Array.from(atob(b64Der), c => c.charCodeAt(0));
  return await crypto.subtle.importKey("pkcs8", raw, { name: "Ed25519" }, false, ["sign"]);
}

async function verifyWithPublicKey(key, sigB64, pubKeyB64) {
  try {
    const raw = Uint8Array.from(atob(pubKeyB64), c => c.charCodeAt(0));
    const pubKey = await crypto.subtle.importKey("spki", raw, { name: "Ed25519" }, false, ["verify"]);
    const sig = Uint8Array.from(atob(sigB64), c => c.charCodeAt(0));
    return await crypto.subtle.verify({ name: "Ed25519" }, pubKey, sig, new TextEncoder().encode(key));
  } catch { return false; }
}

function b64(buf) {
  return btoa(String.fromCharCode(...new Uint8Array(buf)));
}

function json(data, status, cors) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...cors },
  });
}
