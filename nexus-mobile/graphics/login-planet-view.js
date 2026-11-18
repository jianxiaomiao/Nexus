// This module is imported only by renderjs. Credentials and uni APIs stay outside it.
let loadingRuntime;

function loadRuntime(src) {
  if (window.NexusLoginPlanet?.start) return Promise.resolve(window.NexusLoginPlanet);
  if (loadingRuntime) return loadingRuntime;
  loadingRuntime = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    let timer;
    const fail = () => {
      clearTimeout(timer);
      script.onload = script.onerror = null;
      script.remove();
      loadingRuntime = undefined;
      reject(new Error('Planet runtime unavailable'));
    };
    script.src = src;
    script.async = true;
    script.onload = () => {
      if (!window.NexusLoginPlanet?.start) return fail();
      clearTimeout(timer);
      script.onload = script.onerror = null;
      resolve(window.NexusLoginPlanet);
    };
    script.onerror = fail;
    timer = setTimeout(fail, 10000);
    document.head.appendChild(script);
  });
  return loadingRuntime;
}

export function createPlanetView(notify) {
  let job;
  let config;

  function stop() {
    const previous = job;
    job = undefined;
    if (!previous) return;
    clearTimeout(previous.timeout);
    cancelAnimationFrame(previous.frame);
    previous.observer?.disconnect();
    previous.canvas?.removeEventListener('webglcontextlost', previous.onContextLost);
    previous.controller.abort();
    previous.dispose?.();
    previous.canvas?.remove();
    if (previous.host) previous.host.dataset.planetState = 'paused';
  }

  function fallback(current) {
    if (job !== current) return;
    const host = current.host;
    stop();
    if (host) host.dataset.planetState = 'fallback';
    notify('fallback');
  }

  async function initialize(current) {
    if (job !== current) return;
    const host = document.getElementById(current.hostId);
    const reset = document.getElementById(current.hostId + '-reset');
    if (!host || !reset) {
      current.frame = requestAnimationFrame(() => initialize(current));
      return;
    }
    current.host = host;
    host.dataset.planetState = 'loading';
    current.observer = new MutationObserver(() => {
      if (!host.isConnected && job === current) stop();
    });
    // Watch ancestor removal too: renderjs has no beforeUnmount/unmounted hooks.
    for (let node = host; node.parentNode; node = node.parentNode) {
      current.observer.observe(node.parentNode, { childList: true });
    }
    try {
      const runtime = await loadRuntime(current.src);
      if (job !== current || current.controller.signal.aborted) return;
      const canvas = document.createElement('canvas');
      canvas.setAttribute('tabindex', '0');
      canvas.setAttribute('aria-label', '左右拖动旋转小猫星球，方向键调整视角');
      Object.assign(canvas.style, { display: 'block', width: '100%', height: '100%', touchAction: 'pan-y pinch-zoom' });
      current.canvas = canvas;
      current.onContextLost = () => fallback(current);
      canvas.addEventListener('webglcontextlost', current.onContextLost);
      host.appendChild(canvas);
      const dispose = await runtime.start(canvas, reset, current.controller.signal);
      if (job !== current || current.controller.signal.aborted) { dispose(); return; }
      current.dispose = dispose;
      clearTimeout(current.timeout);
      host.dataset.planetState = 'ready';
      notify('ready');
    } catch {
      fallback(current);
    }
  }

  return {
    update(next) {
      if (!next) return;
      if (config && config.active === next.active && config.hostId === next.hostId && config.src === next.src) return;
      config = next;
      stop();
      if (!next.active) { notify('paused'); return; }
      notify('loading');
      const current = { hostId: next.hostId, src: next.src, controller: new AbortController(), frame: 0 };
      job = current;
      current.timeout = setTimeout(() => fallback(current), 15000);
      // Give the login form and poster a paint before parsing/rendering the 3D scene.
      current.frame = requestAnimationFrame(() => {
        current.frame = requestAnimationFrame(() => initialize(current));
      });
    },
    dispose: stop,
  };
}
