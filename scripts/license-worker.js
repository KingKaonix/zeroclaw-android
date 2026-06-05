// ZeroClaw License Server — Cloudflare Worker
// npx wrangler deploy
// Secrets: ZEROCLAW_PRIVATE_KEY (PKCS8 DER base64), ZEROCLAW_PUBLIC_KEY (SPKI DER base64), ADMIN_KEY

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method;
    const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET,POST,OPTIONS", "Access-Control-Allow-Headers": "Content-Type" };

    if (method === "OPTIONS") return new Response(null, { headers: cors });

    try {
      // POST /activate — issue license after payment verification
      if (url.pathname === "/activate" && method === "POST") {
        const { email, purchaseId } = await request.json();
        if (!email || !purchaseId) return json({ error: "email and purchaseId required" }, 400, cors);

        // TODO: verify purchaseId with Gumroad/lemonsqueezy API
        // For now: accept any 8+ char purchaseId
        if (purchaseId.length < 8) return json({ error: "invalid purchase" }, 402, cors);

        const privKey = await importPrivateKey(env.ZEROCLAW_PRIVATE_KEY);
        const licenseKey = `ZCLAW-1:${email}:0`; // 0 = lifetime
        const sig = await crypto.subtle.sign({ name: "Ed25519" }, privKey, new TextEncoder().encode(licenseKey));

        return json({ success: true, licenseKey, signature: b64(sig), tier: "pro" }, 200, cors);
      }

      // GET /validate — verify license sig + expiry
      if (url.pathname === "/validate" && method === "GET") {
        const key = url.searchParams.get("key");
        const sig = url.searchParams.get("sig");
        if (!key || !sig) return json({ valid: false, error: "missing params" }, 400, cors);

        const parts = key.split(":");
        const expiry = parseInt(parts[2] || "0");
        if (expiry > 0 && Date.now() > expiry) return json({ valid: false, error: "expired" }, 200, cors);

        const valid = await verifyWithPublicKey(key, sig, env.ZEROCLAW_PUBLIC_KEY);
        return json({ valid, tier: valid ? "pro" : "invalid" }, 200, cors);
      }

      // GET /keygen?auth=...&email=...&expiry=0 — admin key generation
      if (url.pathname === "/keygen" && method === "GET") {
        if (url.searchParams.get("auth") !== env.ADMIN_KEY) return json({ error: "unauthorized" }, 403, cors);
        const email = url.searchParams.get("email");
        if (!email) return json({ error: "email required" }, 400, cors);
        const expiry = url.searchParams.get("expiry") || "0";

        const privKey = await importPrivateKey(env.ZEROCLAW_PRIVATE_KEY);
        const licenseKey = `ZCLAW-1:${email}:${expiry}`;
        const sig = await crypto.subtle.sign({ name: "Ed25519" }, privKey, new TextEncoder().encode(licenseKey));

        return json({ licenseKey, signature: b64(sig), tier: "pro", expiresAt: expiry === "0" ? null : new Date(parseInt(expiry)).toISOString() }, 200, cors);
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
