(() => {
  "use strict";
  const form = document.getElementById("login");
  const key = document.getElementById("gateway-key");
  const status = document.getElementById("status");

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const gatewayKey = key.value;
    if (!gatewayKey) return;
    status.textContent = "Anmeldung wird geprüft …";
    try {
      const response = await fetch("/camilladsp/session", {
        method: "POST",
        credentials: "same-origin",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({gatewayKey}),
      });
      key.value = "";
      if (!response.ok) {
        status.textContent = response.status === 401
          ? "Anmeldung abgelehnt." : "Anmeldung derzeit nicht verfügbar.";
        return;
      }
      window.location.replace("/camilladsp/");
    } catch (_) {
      key.value = "";
      status.textContent = "Verbindung fehlgeschlagen.";
    }
  });
})();
