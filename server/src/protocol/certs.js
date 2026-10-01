import crypto from 'node:crypto';
import forge from 'node-forge';

/**
 * Creates a self-signed RSA-2048 client certificate. The TV remembers this
 * certificate after pairing, so it must be persisted and reused.
 */
export function generateClientCertificate(commonName = 'kalimote') {
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const keyPem = privateKey.export({ type: 'pkcs1', format: 'pem' });
  const forgeKey = forge.pki.privateKeyFromPem(keyPem);

  const cert = forge.pki.createCertificate();
  cert.publicKey = forge.pki.setRsaPublicKey(forgeKey.n, forgeKey.e);
  cert.serialNumber = '01' + crypto.randomBytes(8).toString('hex');
  cert.validity.notBefore = new Date(Date.now() - 24 * 3600 * 1000);
  cert.validity.notAfter = new Date();
  cert.validity.notAfter.setFullYear(cert.validity.notBefore.getFullYear() + 20);
  const attrs = [
    { name: 'commonName', value: commonName },
    { name: 'organizationName', value: 'Kalimote' },
  ];
  cert.setSubject(attrs);
  cert.setIssuer(attrs);
  cert.setExtensions([{ name: 'basicConstraints', cA: false }]);
  cert.sign(forgeKey, forge.md.sha256.create());

  return { key: keyPem, cert: forge.pki.certificateToPem(cert) };
}

/** Returns the RSA modulus and exponent as minimal big-endian byte arrays. */
export function rsaPublicParts(certPemOrDer) {
  const x509 = new crypto.X509Certificate(certPemOrDer);
  const jwk = x509.publicKey.export({ format: 'jwk' });
  if (jwk.kty !== 'RSA') throw new Error('Certificate does not contain an RSA key');
  return { n: Buffer.from(jwk.n, 'base64url'), e: Buffer.from(jwk.e, 'base64url') };
}

/**
 * Computes the pairing secret: SHA-256(client n, client e, server n,
 * server e, last 2 bytes of code). The first byte of the code is a checksum
 * that must equal the first byte of the hash.
 */
export function computePairingSecret(clientCert, serverCert, code) {
  const normalized = String(code).trim().toUpperCase();
  if (!/^[0-9A-F]{6}$/.test(normalized)) {
    throw new Error('Pairing code must be 6 hexadecimal characters');
  }
  const c = rsaPublicParts(clientCert);
  const s = rsaPublicParts(serverCert);
  const hash = crypto
    .createHash('sha256')
    .update(c.n)
    .update(c.e)
    .update(s.n)
    .update(s.e)
    .update(Buffer.from(normalized.slice(2), 'hex'))
    .digest();
  if (hash[0] !== parseInt(normalized.slice(0, 2), 16)) {
    throw new Error('Pairing code is incorrect');
  }
  return hash;
}
