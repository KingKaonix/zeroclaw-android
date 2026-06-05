// ZeroClaw License Key Generator
// Run: node scripts/keygen.js <email> [expiry_timestamp]
// 
// Generates ed25519-signed license keys offline.
// The signature goes into the app, verified by baked-in public key.
//
// Examples:
//   node scripts/keygen.js kaos@example.com          # lifetime
//   node scripts/keygen.js user@foo.com 1893456000000 # expires 2030-01-01

const crypto = require('crypto');

// PKCS8 DER base64 — generated once, keep secret
const PRIVATE_KEY_B64 = 'MC4CAQAwBQYDK2VwBCIEIDWCmk2QAmFq6gWfb5ZdrQtdsTm1sgHskc/QtCoDTX1e';
const PUBLIC_KEY_B64 = 'MCowBQYDK2VwAyEAdiz2HLYTOBmE5Fwy1Kpn8LmrRuOutg5u/2qPvNDjhAo=';

function main() {
  let email = process.argv[2];
  const expiry = process.argv[3] || '0';

  if (!email) {
    console.error('Usage: node keygen.js <email> [expiry_epoch_ms]');
    console.error('');
    console.error('Public key (embed in app):');
    console.error(PUBLIC_KEY_B64);
    process.exit(1);
  }

  // Strip mailto: if present
  email = email.replace(/^mailto:/, '');

  const licenseKey = `ZCLAW-1:${email}:${expiry}`;

  const privateKey = crypto.createPrivateKey({
    key: Buffer.from(PRIVATE_KEY_B64, 'base64'),
    format: 'der',
    type: 'pkcs8',
  });

  const signature = crypto.sign(null, Buffer.from(licenseKey), privateKey);
  const sigB64 = signature.toString('base64');

  // Verify the signature against public key
  const publicKey = crypto.createPublicKey({
    key: Buffer.from(PUBLIC_KEY_B64, 'base64'),
    format: 'der',
    type: 'spki',
  });
  const isValid = crypto.verify(null, Buffer.from(licenseKey), publicKey, signature);

  console.log('');
  console.log('=== LICENSE KEY ===');
  console.log(licenseKey);
  console.log('');
  console.log('=== SIGNATURE ===');
  console.log(sigB64);
  console.log('');
  console.log(`✓ Valid: ${isValid}`);
  if (expiry !== '0') {
    console.log(`  Expires: ${new Date(parseInt(expiry)).toISOString()}`);
  } else {
    console.log('  Expires: never (lifetime)');
  }
  console.log('');
  console.log('Embed public key in app (LicenseValidator.kt):');
  console.log(PUBLIC_KEY_B64);
}

main();
