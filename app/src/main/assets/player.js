(() => {
  if (window.__moviePlayer) return window.__moviePlayer.inspect();
  const root = document.documentElement;
  const style = document.createElement('style');
  style.textContent = `
    html, body { margin: 0 !important; padding: 0 !important; width: 100% !important;
      min-width: 0 !important; height: 100% !important; overflow: hidden !important;
      background: #000 !important; }
    [data-movie-path] > :not([data-movie-path]):not([data-movie-player]) {
      display: none !important;
    }
    body [data-movie-path] { display: contents !important; }
    [data-movie-player] { display: grid !important; place-items: center;
      position: fixed !important; inset: 0 !important; height: 100% !important;
      width: 100% !important; margin: 0 !important; background: #000 !important; }
    [data-movie-player] aa-videoplayer { display: block;
      width: min(100vw,177.777vh) !important; height: min(56.25vw,100vh) !important; }
    [data-movie-player] .video-container { position: relative !important;
      inset: auto !important; transform: none !important; margin: 0 !important;
      width: 100% !important; height: 100% !important; }
    [data-movie-player] vg-player { width: 100% !important; height: 100% !important; }
    [data-movie-player] vg-pause-f, [data-movie-player] vg-overlay-play .pause-ad,
    [data-movie-player] .player-title-bar, [data-movie-player] vg-overlay-danmu,
    [data-movie-player] [id="coin-or-upgrade-to-skip-ad"], [data-movie-player] .quanlityDiv {
      display: none !important;
    }
    [data-movie-player] vg-player:has(.is-playing-ads, .publicbox)::after,
    [data-movie-masked]::after {
      content: '广告播放中'; position: absolute; inset: 0; display: grid;
      place-items: center; z-index: 2147483647; background: #000; color: #eee;
      font: 14px system-ui, sans-serif; pointer-events: auto;
    }
    [data-movie-manual]::after { content: '已遮挡'; }
  `;
  document.head.append(style);

  let shell;
  let player;
  let primary;
  let mainSource = '';
  let adSource = '';
  let settleUntil = 0;
  let manualUntil = 0;
  let ad = false;
  const savedMute = new Map();

  function unmuteRemoved(activeMedia) {
    for (const [media, wasMuted] of savedMute) {
      if (activeMedia.has(media)) continue;
      savedMute.delete(media);
      media.muted = wasMuted;
    }
  }

  function state() {
    if (!player) return { ready: false, ad: false };
    const now = Date.now();
    const currentSource = primary?.currentSrc || '';
    const signalled = player.matches('.is-playing-ads,[data-ad-playing=true]') ||
      !!player.querySelector('.is-playing-ads,.publicbox,[data-ad-playing=true]');
    if (signalled) {
      settleUntil = now + 500;
      if (currentSource && currentSource !== mainSource) adSource = currentSource;
    }
    const switching = !!adSource && (!currentSource || currentSource === adSource);
    ad = signalled || now < settleUntil || switching;
    if (!ad && currentSource) {
      mainSource = currentSource;
      adSource = '';
    }

    const manual = now < manualUntil;
    player.toggleAttribute('data-movie-masked', ad || manual);
    player.toggleAttribute('data-movie-manual', manual && !ad);
    const activeMedia = new Set(ad || manual ? player.querySelectorAll('video,audio') : []);
    unmuteRemoved(activeMedia);
    for (const media of activeMedia) {
      if (!savedMute.has(media)) savedMute.set(media, media.muted);
      if (!media.muted) media.muted = true;
    }
    const rectangle = player.getBoundingClientRect();
    return { ready: !!primary && rectangle.width > 0 && rectangle.height > 0, ad, manual };
  }

  function inspect() {
    const nextShell = document.querySelector('.aa-videoplayer-wrap');
    const nextPlayer = nextShell?.querySelector('#main-player, vg-player');
    if (!nextPlayer) return { ready: false, ad: false };
    if (nextShell !== shell || nextPlayer !== player) {
      unmuteRemoved(new Set());
      for (const element of document.querySelectorAll('[data-movie-path],[data-movie-player]')) {
        element.removeAttribute('data-movie-path');
        element.removeAttribute('data-movie-player');
      }
      shell = nextShell;
      player = nextPlayer;
      mainSource = adSource = '';
      settleUntil = 0;
      shell.setAttribute('data-movie-player', '');
      for (let node = shell.parentElement; node; node = node.parentElement) {
        node.setAttribute('data-movie-path', '');
        if (node === document.body) break;
      }
    }
    primary = player.querySelector('#video_player,video[controls]');
    return state();
  }

  let pending = false;
  function schedule() {
    if (pending) return;
    pending = true;
    queueMicrotask(() => {
      pending = false;
      inspect();
    });
  }

  const observer = new MutationObserver(schedule);
  observer.observe(root, { childList: true, subtree: true, attributes: true,
    attributeFilter: ['class','src','data-ad-playing'] });
  for (const event of ['loadstart','loadedmetadata','durationchange','playing','ended','volumechange']) {
    document.addEventListener(event, schedule, true);
  }
  const timer = setInterval(inspect, 150);
  window.addEventListener('pagehide', () => {
    observer.disconnect();
    clearInterval(timer);
    unmuteRemoved(new Set());
  }, { once: true });
  window.__moviePlayer = {
    inspect,
    manual() {
      manualUntil = manualUntil > Date.now() ? 0 : Date.now() + 20000;
      state();
      return manualUntil > Date.now();
    },
  };
  return inspect();
})();
