import { createPrivateKey, createPublicKey } from "node:crypto";

/** Derives the base64url X25519 public key for a raw 32-byte private key (base64url). */
export function x25519PublicFromPrivate(privateB64Url: string): string {
  // Node needs the public half in the JWK too; get it by importing a PKCS8 wrapper of the raw seed.
  const der = Buffer.concat([
    Buffer.from("302e020100300506032b656e04220420", "hex"),
    Buffer.from(privateB64Url, "base64url"),
  ]);
  const priv = createPrivateKey({ key: der, format: "der", type: "pkcs8" });
  return (createPublicKey(priv).export({ format: "jwk" }) as { x: string }).x;
}
