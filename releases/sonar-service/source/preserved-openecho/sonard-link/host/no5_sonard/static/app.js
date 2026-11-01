(() => {
  const HEADER_LEN = 40;
  // M1 carries the Pico's 12-bit ADC codes in uint16 containers.
  const ADC_MAX = 4095;
  const canvas = document.getElementById('echogram');
  const ctx = canvas.getContext('2d', { alpha: false });
  const depthEl = document.getElementById('depth');
  const confidenceEl = document.getElementById('confidence');
  const pingEl = document.getElementById('ping');
  const rateEl = document.getElementById('rate');
  const connEl = document.getElementById('connection');
  const paletteEl = document.getElementById('palette');
  const gainEl = document.getElementById('gain');
  const gammaEl = document.getElementById('gamma');
  const pauseEl = document.getElementById('pause');
  const axisEl = document.getElementById('signal-axis');
  let axisRange = null;

  let paused = false;
  let connected = false;
  let lastPingAt = 0;
  let measuredRate = 0;

  function resize() {
    const rect = canvas.getBoundingClientRect();
    const w = Math.max(320, Math.floor(rect.width));
    const h = Math.max(300, Math.floor(rect.height));
    if (canvas.width !== w || canvas.height !== h) {
      const old = document.createElement('canvas');
      old.width = canvas.width || w;
      old.height = canvas.height || h;
      old.getContext('2d').drawImage(canvas, 0, 0);
      canvas.width = w;
      canvas.height = h;
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, w, h);
      if (old.width > 1 && old.height > 1) ctx.drawImage(old, 0, 0, old.width, old.height, 0, 0, w, h);
    }
  }
  addEventListener('resize', resize);
  resize();

  function colorClassic(v) {
    const x = Math.max(0, Math.min(1, v));
    if (x < .18) return [0, Math.floor(25 + x * 220), Math.floor(45 + x * 480)];
    if (x < .45) { const q=(x-.18)/.27; return [0, Math.floor(65+170*q), Math.floor(140-90*q)]; }
    if (x < .70) { const q=(x-.45)/.25; return [Math.floor(30+220*q), Math.floor(235-30*q), 25]; }
    if (x < .90) { const q=(x-.70)/.20; return [255, Math.floor(205-165*q), Math.floor(20+15*q)]; }
    const q=(x-.90)/.10; return [255, Math.floor(40+215*q), Math.floor(35+220*q)];
  }
  function colorAmber(v) {
    const x = Math.max(0, Math.min(1, v));
    return [Math.floor(255*x), Math.floor(175*Math.pow(x,.72)), Math.floor(55*Math.pow(x,1.7))];
  }
  function colorGray(v) {
    const x = Math.floor(255 * Math.max(0, Math.min(1, v)));
    return [x,x,x];
  }
  function colorFor(v) {
    if (paletteEl.value === 'amber') return colorAmber(v);
    if (paletteEl.value === 'gray') return colorGray(v);
    return colorClassic(v);
  }

  function renderFrame(buffer) {
    if (paused) return;
    const view = new DataView(buffer);
    if (buffer.byteLength < HEADER_LEN) return;
    const version = view.getUint8(0);
    const type = view.getUint8(1);
    const headerLen = view.getUint16(2, true);
    if (version !== 1 || type !== 1 || headerLen !== HEADER_LEN) return;
    const pingId = Number(view.getBigUint64(4, true));
    const maxDepth = view.getFloat32(20, true);
    const bottomDepth = view.getFloat32(24, true);
    const confidence = view.getFloat32(28, true);
    const binCount = view.getUint16(36, true);
    const flags = view.getUint32(32, true);
    const validRange = Number.isFinite(maxDepth) && maxDepth > 0 && (flags & 1) && !(flags & 32);
    if (buffer.byteLength !== headerLen + binCount * 2) return;
    const nextRange = validRange ? maxDepth : null;
    if (axisRange !== nextRange) {
      axisRange = nextRange;
      axisEl.title = validRange ? 'Tiefe ab bestätigtem Sendezeitpunkt' : 'Position im aufgenommenen Signalfenster';
      Array.from(axisEl.children).forEach((label, i, labels) => {
        const fraction = i / (labels.length - 1);
        label.textContent = validRange ? `${(maxDepth * fraction).toFixed(1)} m` : `${Math.round(100 * fraction)} %`;
      });
    }

    resize();
    const now = performance.now();
    if (lastPingAt) measuredRate = measuredRate * .8 + (1000 / Math.max(1, now - lastPingAt)) * .2;
    lastPingAt = now;

    connEl.textContent = validRange ? 'live' : 'Signal · TX-Bezug offen';
    depthEl.textContent = validRange && Number.isFinite(bottomDepth) ? `${bottomDepth.toFixed(2)} m` : '—';
    confidenceEl.textContent = `${Math.round(confidence * 100)}%`;
    pingEl.textContent = pingId.toLocaleString();
    rateEl.textContent = measuredRate ? `${measuredRate.toFixed(1)} Hz` : '—';

    ctx.drawImage(canvas, 1, 0, canvas.width - 1, canvas.height, 0, 0, canvas.width - 1, canvas.height);
    const column = ctx.createImageData(1, canvas.height);
    const gain = parseFloat(gainEl.value);
    const gamma = parseFloat(gammaEl.value);
    for (let y = 0; y < canvas.height; y++) {
      const bin = Math.min(binCount - 1, Math.floor((y / Math.max(1, canvas.height - 1)) * (binCount - 1)));
      const raw = view.getUint16(headerLen + bin * 2, true) / ADC_MAX;
      const mapped = Math.pow(Math.max(0, Math.min(1, raw * gain)), gamma);
      const [r,g,b] = colorFor(mapped);
      const i = y * 4;
      column.data[i] = r; column.data[i+1] = g; column.data[i+2] = b; column.data[i+3] = 255;
    }
    ctx.putImageData(column, canvas.width - 1, 0);

    // Depth grid remains subtle so it does not destroy the underlying echogram.
    ctx.fillStyle = 'rgba(255,255,255,.07)';
    for (let d = 5; d < maxDepth; d += 5) {
      const y = Math.round((d / maxDepth) * canvas.height);
      ctx.fillRect(canvas.width - 2, y, 2, 1);
    }
  }

  pauseEl.addEventListener('click', () => {
    paused = !paused;
    pauseEl.textContent = paused ? 'Resume display' : 'Pause display';
  });

  setInterval(() => {
    if (connected && !paused && (!lastPingAt || performance.now() - lastPingAt > 2000)) {
      connEl.textContent = 'Verbunden · warte auf Signal';
      rateEl.textContent = '—';
    }
  }, 500);

  function connect() {
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${scheme}//${location.host}/ws/v1/echogram`);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => {
      connected = true;
      connEl.textContent='Verbunden · warte auf Signal';
      connEl.className='connection online';
    };
    ws.onmessage = ev => renderFrame(ev.data);
    ws.onclose = () => {
      connected = false;
      connEl.textContent='reconnecting'; connEl.className='connection offline';
      setTimeout(connect, 1200);
    };
    ws.onerror = () => ws.close();
  }
  connect();
})();
