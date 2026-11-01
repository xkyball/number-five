async function no5PasswordPrehash(password) {
  if (!password || password.length > 512) throw Error("Gateway-Zugang fehlt oder ist zu lang.");
  const response = await fetch("/auth/v1/parameters", {cache:"no-store", redirect:"error", credentials:"omit"});
  if (!response.ok) throw Error("Passwort-Zugang nicht verfügbar.");
  const p = await response.json();
  if (p.version !== 1 || p.algorithm !== "PBKDF2-HMAC-SHA256" || p.iterations !== 600000 ||
      !/^[0-9a-f]{64}$/.test(p.salt)) throw Error("Gateway-Zugang ist nicht verfügbar.");
  const salt = Uint8Array.from(p.salt.match(/../g), n => parseInt(n,16));
  const bytes = new TextEncoder().encode(password);
  try {
    const key = await crypto.subtle.importKey("raw", bytes, "PBKDF2", false, ["deriveBits"]);
    const derived = new Uint8Array(await crypto.subtle.deriveBits({name:"PBKDF2", hash:"SHA-256", salt,
      iterations:p.iterations}, key, 256));
    try { return Array.from(derived, n => n.toString(16).padStart(2,"0")).join(""); }
    finally { derived.fill(0); }
  } finally { bytes.fill(0); }
}
