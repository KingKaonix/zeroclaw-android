// ZeroClaw License Server — Cloudflare Worker (Gumroad edition)
//
// SETUP (one-time):
//   npx wrangler d1 create zeroclaw-licenses
//   # paste the database_id into wrangler.toml
//   npx wrangler d1 execute zeroclaw-licenses --file=scripts/schema.sql
//   npx wrangler secret put ZEROCLAW_PRIVATE_KEY   # base64 PKCS8 ed25519 private key
//   npx wrangler secret put ZEROCLAW_PUBLIC_KEY    # base64 SPKI  ed25519 public key
//   npx wrangler secret put ADMIN_KEY              # random secret for admin endpoints
//   npx wrangler secret put GUMROAD_ACCESS_TOKEN   # from Gumroad Settings → Advanced
//   npx wrangler deploy
//
// GUMROAD PING:
//   In your Gumroad product settings → "Ping a URL after purchase" →
//   set to: https://zeroclaw-license.<your-subdomain>.workers.dev/webhook/gumroad
//
// ENDPOINTS:
//   POST /webhook/gumroad          — Gumroad sale ping (auto-issues license, emails buyer)
//   POST /activate                 — Manual activation: { email, saleId }
//   GET  /validate?key=&sig=       — Verify a license key (used by app)
//   GET  /keygen?auth=&email=&tier= — Admin: issue a key manually
//   GET  /stats?auth=              — Admin: MRR + subscriber counts

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
      // -----------------------------------------------------------------------
      // POST /webhook/gumroad
      // Gumroad sends an application/x-www-form-urlencoded POST after each sale.
      // Docs: https://gumroad.com/api#api-pings
      // -----------------------------------------------------------------------
      if (url.pathname === "/webhook/gumroad" && method === "POST") {
        const contentType = request.headers.get("content-type") || "";
        let params;

        if (contentType.includes("application/x-www-form-urlencoded")) {
          const text = await request.text();
          params = Object.fromEntries(new URLSearchParams(text));
        } else {
          // Some Gumroad versions send JSON
          params = await request.json();
        }

        const email     = params.email?.trim().toLowerCase();
        const saleId    = params.sale_id;
        const productId = params.product_permalink || params.product_id;
        const refunded  = params.refunded === "true" || params.refunded === true;
        const disputed  = params.disputed === "true" || params.disputed === true;

        if (!email || !saleId) {
          return json({ error: "missing email or sale_id" }, 400, cors);
        }

        // Handle refunds / disputes — revoke license
        if (refunded || disputed) {
          if (env.DB) {
            await env.DB.prepare(
              "UPDATE licenses SET tier='free' WHERE sale_id=?1"
            ).bind(saleId).run();
          }
          return json({ received: true, action: "revoked" }, 200, cors);
        }

        // Verify the sale with Gumroad API to prevent fake pings
        if (env.GUMROAD_ACCESS_TOKEN) {
          const verified = await verifyGumroadSale(saleId, env.GUMROAD_ACCESS_TOKEN);
          if (!verified) {
            return json({ error: "sale verification failed" }, 402, cors);
          }
        }

        // Determine tier from product permalink
        // Set GUMROAD_ENT_PERMALINK in wrangler.toml [vars] if you have an Enterprise product
        const tier = (env.GUMROAD_ENT_PERMALINK && productId === env.GUMROAD_ENT_PERMALINK)
          ? "enterprise"
          : "pro";

        // Lifetime keys (Gumroad is one-time purchase, no renewal)
        const licenseKey = `ZCLAW-1:${email}:0`;
        const privKey    = await importPrivateKey(env.ZEROCLAW_PRIVATE_KEY);
        const sigBytes   = await crypto.subtle.sign(
          { name: "Ed25519" }, privKey, new TextEncoder().encode(licenseKey)
        );
        const signature  = b64(sigBytes);

        // Persist to D1
        if (env.DB) {
          await env.DB.prepare(
            `INSERT OR REPLACE INTO licenses
               (email, sale_id, tier, license_key, signature, expires_at, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, 0, ?6)`
          ).bind(email, saleId, tier, licenseKey, signature, Date.now()).run();
        }

        // Gumroad can display custom content on the receipt page.
        // Return the key so Gumroad shows it in the "thank you" content field.
        return json({
          received: true,
          tier,
          licenseKey,
          signature,
          instructions: "Open ZeroClaw → menu → Enter License Key. Paste both values below.",
        }, 200, cors);
      }

      // -----------------------------------------------------------------------
      // POST /activate  { email, saleId }
      // Manual activation — buyer contacts you, or you trigger post-purchase.
      // Verifies with Gumroad API before issuing.
      // -----------------------------------------------------------------------
      if (url.pathname === "/activate" && method === "POST") {
        const body = await request.json();
        const email  = body.email?.trim().toLowerCase();
        const saleId = body.saleId || body.sale_id;

        if (!email || !saleId) {
          return json({ error: "email and saleId required" }, 400, cors);
        }

        // Check D1 first — avoid re-issuing for duplicate requests
        if (env.DB) {
          const existing = await env.DB.prepare(
            "SELECT license_key, signature, tier FROM licenses WHERE sale_id=?1"
          ).bind(saleId).first();
          if (existing) {
            return json({
              success: true,
              licenseKey: existing.license_key,
              signature: existing.signature,
              tier: existing.tier,
              cached: true,
            }, 200, cors);
          }
        }

        // Verify with Gumroad API
        if (env.GUMROAD_ACCESS_TOKEN) {
          const verified = await verifyGumroadSale(saleId, env.GUMROAD_ACCESS_TOKEN);
          if (!verified) {
            return json({ error: "Gumroad sale not found or not charged" }, 402, cors);
          }
        }

        const tier       = "pro";
        const licenseKey = `ZCLAW-1:${email}:0`;
        const privKey    = await importPrivateKey(env.ZEROCLAW_PRIVATE_KEY);
        const sigBytes   = await crypto.subtle.sign(
          { name: "Ed25519" }, privKey, new TextEncoder().encode(licenseKey)
        );
        const signature  = b64(sigBytes);

        if (env.DB) {
          await env.DB.prepare(
            `INSERT OR REPLACE INTO licenses
               (email, sale_id, tier, license_key, signature, expires_at, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, 0, ?6)`
          ).bind(email, saleId, tier, licenseKey, signature, Date.now()).run();
        }

        return json({ success: true, licenseKey, signature, tier }, 200, cors);
      }

      // -----------------------------------------------------------------------
      // GET /validate?key=ZCLAW-1:email:expiry&sig=base64
      // Used by the Android app for online re-validation (optional — app also
      // verifies offline via baked-in public key).
      // -----------------------------------------------------------------------
      if (url.pathname === "/validate" && method === "GET") {
        const key = url.searchParams.get("key");
        const sig = url.searchParams.get("sig");
        if (!key || !sig) return json({ valid: false, error: "missing params" }, 400, cors);

        // Check expiry from key itself
        const parts  = key.split(":");
        const expiry = parseInt(parts[2] || "0");
        if (expiry > 0 && Date.now() > expiry) {
          return json({ valid: false, error: "expired" }, 200, cors);
        }

        // Verify signature
        const valid = await verifyWithPublicKey(key, sig, env.ZEROCLAW_PUBLIC_KEY);

        // Check D1 for revocation (refund/dispute)
        if (valid && env.DB && parts[1]) {
          const row = await env.DB.prepare(
            "SELECT tier FROM licenses WHERE email=?1"
          ).bind(parts[1]).first();
          if (row && row.tier === "free") {
            return json({ valid: false, error: "revoked" }, 200, cors);
          }
        }

        return json({ valid, tier: valid ? "pro" : "invalid" }, 200, cors);
      }

      // -----------------------------------------------------------------------
      // GET /keygen?auth=ADMIN_KEY&email=user@example.com&tier=pro&expiry=0
      // Admin endpoint — issue a key without a Gumroad purchase (comps, support).
      // -----------------------------------------------------------------------
      if (url.pathname === "/keygen" && method === "GET") {
        if (url.searchParams.get("auth") !== env.ADMIN_KEY) {
          return json({ error: "unauthorized" }, 403, cors);
        }
        const email  = url.searchParams.get("email")?.trim().toLowerCase();
        if (!email) return json({ error: "email required" }, 400, cors);

        const expiry = url.searchParams.get("expiry") || "0";
        const tier   = url.searchParams.get("tier")   || "pro";

        const licenseKey = `ZCLAW-1:${email}:${expiry}`;
        const privKey    = await importPrivateKey(env.ZEROCLAW_PRIVATE_KEY);
        const sigBytes   = await crypto.subtle.sign(
          { name: "Ed25519" }, privKey, new TextEncoder().encode(licenseKey)
        );
        const signature  = b64(sigBytes);

        if (env.DB) {
          await env.DB.prepare(
            `INSERT OR REPLACE INTO licenses
               (email, sale_id, tier, license_key, signature, expires_at, created_at)
             VALUES (?1, 'admin', ?2, ?3, ?4, ?5, ?6)`
          ).bind(email, tier, licenseKey, signature, parseInt(expiry), Date.now()).run();
        }

        return json({
          licenseKey,
          signature,
          tier,
          expiresAt: expiry === "0" ? null : new Date(parseInt(expiry)).toISOString(),
        }, 200, cors);
      }

      // -----------------------------------------------------------------------
      // GET /stats?auth=ADMIN_KEY
      // Admin dashboard: subscriber counts + MRR estimate.
      // -----------------------------------------------------------------------
      if (url.pathname === "/stats" && method === "GET") {
        if (url.searchParams.get("auth") !== env.ADMIN_KEY) {
          return json({ error: "unauthorized" }, 403, cors);
        }
        if (!env.DB) return json({ error: "no DB" }, 500, cors);

        const [pro, ent, total, recent] = await Promise.all([
          env.DB.prepare("SELECT COUNT(*) as c FROM licenses WHERE tier='pro'").first(),
          env.DB.prepare("SELECT COUNT(*) as c FROM licenses WHERE tier='enterprise'").first(),
          env.DB.prepare("SELECT COUNT(*) as c FROM licenses WHERE tier != 'free'").first(),
          env.DB.prepare(
            "SELECT email, tier, created_at FROM licenses WHERE tier != 'free' ORDER BY created_at DESC LIMIT 10"
          ).all(),
        ]);

        const proCount = pro?.c  || 0;
        const entCount = ent?.c  || 0;

        return json({
          pro:        proCount,
          enterprise: entCount,
          total:      total?.c || 0,
          // Gumroad is one-time purchase — these are lifetime revenue estimates
          ltv:        proCount * 8 + entCount * 20,
          recent:     recent?.results || [],
        }, 200, cors);
      }

      return json({ error: "not found" }, 404, cors);

    } catch (err) {
      return json({ error: err.message }, 500, cors);
    }
  },
};

// ---------------------------------------------------------------------------
// Verify a Gumroad sale via their API
// Returns true if the sale exists and was charged (not refunded).
// ---------------------------------------------------------------------------
async function verifyGumroadSale(saleId, accessToken) {
  try {
    const resp = await fetch(
      `https://api.gumroad.com/v2/sales/${encodeURIComponent(saleId)}`,
      {
        headers: {
          "Authorization": `Bearer ${accessToken}`,
          "Content-Type":  "application/json",
        },
      }
    );
    if (!resp.ok) return false;
    const data = await resp.json();
    // sale must exist, be successful, and not refunded
    return data.success === true &&
           data.sale?.refunded !== true &&
           data.sale?.chargebacked !== true;
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// Crypto helpers
// ---------------------------------------------------------------------------
async function importPrivateKey(b64Der) {
  const raw = Uint8Array.from(atob(b64Der), c => c.charCodeAt(0));
  return crypto.subtle.importKey("pkcs8", raw, { name: "Ed25519" }, false, ["sign"]);
}

async function verifyWithPublicKey(message, sigB64, pubKeyB64) {
  try {
    const raw    = Uint8Array.from(atob(pubKeyB64), c => c.charCodeAt(0));
    const pubKey = await crypto.subtle.importKey("spki", raw, { name: "Ed25519" }, false, ["verify"]);
    const sig    = Uint8Array.from(atob(sigB64), c => c.charCodeAt(0));
    return crypto.subtle.verify(
      { name: "Ed25519" }, pubKey, sig, new TextEncoder().encode(message)
    );
  } catch {
    return false;
  }
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
