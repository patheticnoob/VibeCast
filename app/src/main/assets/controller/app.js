const socketState = document.getElementById('socket-state');
const playbackPhase = document.getElementById('playback-phase');
const mediaUrl = document.getElementById('media-url');
const formatSelect = document.getElementById('format-select');
const playBtn = document.getElementById('play-btn');
const pauseBtn = document.getElementById('pause-btn');
const resumeBtn = document.getElementById('resume-btn');
const stopBtn = document.getElementById('stop-btn');
const seekSlider = document.getElementById('seek-slider');
const timeReadout = document.getElementById('time-readout');
const volumeSlider = document.getElementById('volume-slider');
const volumeReadout = document.getElementById('volume-readout');
const httpUrl = document.getElementById('http-url');
const wsUrl = document.getElementById('ws-url');
const clientCount = document.getElementById('client-count');
const currentMedia = document.getElementById('current-media');

let socket;
let latestState = {
  durationMs: 0,
  currentPositionMs: 0,
  volume: 1,
};
let isDraggingSeek = false;
const params = new URLSearchParams(location.search);
const pendingAutoPlayUrl = params.get('play') || params.get('url') || '';

function connect() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const endpoint = `${protocol}//${location.host}/ws`;
  socket = new WebSocket(endpoint);

  socket.addEventListener('open', () => {
    socketState.textContent = 'Connected';
    socketState.classList.remove('muted');
    send({ action: 'get_state' });
    if (pendingAutoPlayUrl) {
      mediaUrl.value = pendingAutoPlayUrl;
      send({ action: 'play', url: pendingAutoPlayUrl });
    }
  });

  socket.addEventListener('message', (event) => {
    try {
      const payload = JSON.parse(event.data);
      if (payload.type === 'state') {
        applyState(payload);
      }
    } catch (error) {
      console.error('Invalid state payload', error);
    }
  });

  socket.addEventListener('close', () => {
    socketState.textContent = 'Disconnected';
    socketState.classList.add('muted');
    setTimeout(connect, 1500);
  });

  socket.addEventListener('error', () => {
    socketState.textContent = 'Socket error';
    socketState.classList.add('muted');
  });
}

function send(payload) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return;
  }
  socket.send(JSON.stringify(payload));
}

function applyState(state) {
  latestState = state;
  playbackPhase.textContent = capitalize(state.playbackPhase || 'idle');
  httpUrl.textContent = state.connectionUrl || '-';
  wsUrl.textContent = state.webSocketUrl || '-';
  clientCount.textContent = String(state.clientCount || 0);
  currentMedia.textContent = state.currentMediaUrl || 'Nothing loaded yet.';

  if (!isDraggingSeek) {
    updateSeekSlider(state.currentPositionMs || 0, state.durationMs || 0);
  }

  const volumePercent = Math.round((state.volume || 0) * 100);
  volumeSlider.value = String(volumePercent);
  volumeReadout.textContent = `${volumePercent}%`;
}

function updateSeekSlider(positionMs, durationMs) {
  const hasDuration = durationMs > 0;
  seekSlider.disabled = !hasDuration;
  if (hasDuration) {
    seekSlider.value = String(Math.round((positionMs / durationMs) * 1000));
  } else {
    seekSlider.value = '0';
  }
  timeReadout.textContent = `${formatTime(positionMs)} / ${formatTime(durationMs)}`;
}

function formatTime(milliseconds) {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }

  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function capitalize(value) {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

playBtn.addEventListener('click', () => {
  const url = mediaUrl.value.trim();
  if (!url) {
    mediaUrl.focus();
    return;
  }
  localStorage.setItem('vibe_cast_last_media_url', url);
  const format = formatSelect.value;
  send({
    action: 'play',
    url,
    ...(format && format !== 'auto' ? { format } : {}),
  });
});

pauseBtn.addEventListener('click', () => send({ action: 'pause' }));
resumeBtn.addEventListener('click', () => send({ action: 'resume' }));
stopBtn.addEventListener('click', () => send({ action: 'stop' }));

document.querySelectorAll('[data-seek]').forEach((button) => {
  button.addEventListener('click', () => {
    const deltaSeconds = Number(button.dataset.seek || 0);
    const nextPosition = Math.max(0, (latestState.currentPositionMs || 0) + deltaSeconds * 1000);
    send({ action: 'seek', positionMs: nextPosition });
  });
});

seekSlider.addEventListener('input', () => {
  isDraggingSeek = true;
  const duration = latestState.durationMs || 0;
  const previewPosition = duration * (Number(seekSlider.value) / 1000);
  timeReadout.textContent = `${formatTime(previewPosition)} / ${formatTime(duration)}`;
});

seekSlider.addEventListener('change', () => {
  const duration = latestState.durationMs || 0;
  const positionMs = duration * (Number(seekSlider.value) / 1000);
  send({ action: 'seek', positionMs: Math.round(positionMs) });
  isDraggingSeek = false;
});

volumeSlider.addEventListener('input', () => {
  volumeReadout.textContent = `${volumeSlider.value}%`;
});

volumeSlider.addEventListener('change', () => {
  send({ action: 'volume', value: Number(volumeSlider.value) });
});

mediaUrl.value = localStorage.getItem('vibe_cast_last_media_url') || '';
if (pendingAutoPlayUrl) {
  mediaUrl.value = pendingAutoPlayUrl;
  localStorage.setItem('vibe_cast_last_media_url', pendingAutoPlayUrl);
}

connect();
