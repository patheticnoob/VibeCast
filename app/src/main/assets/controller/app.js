const socketState = document.getElementById('socket-state');
const playbackPhase = document.getElementById('playback-phase');
const titleInput = document.getElementById('title-input');
const mediaUrl = document.getElementById('media-url');
const subtitleUrl = document.getElementById('subtitle-url');
const subtitleType = document.getElementById('subtitle-type');
const subtitleLanguage = document.getElementById('subtitle-language');
const formatSelect = document.getElementById('format-select');
const playBtn = document.getElementById('play-btn');
const pauseBtn = document.getElementById('pause-btn');
const resumeBtn = document.getElementById('resume-btn');
const stopBtn = document.getElementById('stop-btn');
const audioTrackSelect = document.getElementById('audio-track-select');
const subtitleTrackSelect = document.getElementById('subtitle-track-select');
const audioTrackReadout = document.getElementById('audio-track-readout');
const subtitleTrackReadout = document.getElementById('subtitle-track-readout');
const seekSlider = document.getElementById('seek-slider');
const timeReadout = document.getElementById('time-readout');
const volumeSlider = document.getElementById('volume-slider');
const volumeReadout = document.getElementById('volume-readout');
const httpUrl = document.getElementById('http-url');
const wsUrl = document.getElementById('ws-url');
const clientCount = document.getElementById('client-count');
const currentTitle = document.getElementById('current-title');
const currentMedia = document.getElementById('current-media');

const OFF_TRACK_ID = '__off__';
const params = new URLSearchParams(location.search);
const pendingAutoPlayUrl = params.get('play') || params.get('url') || '';

let socket;
let isDraggingSeek = false;
let isApplyingTrackOptions = false;
let latestState = {
  durationMs: 0,
  currentPositionMs: 0,
  volume: 1,
  audioTracks: [],
  subtitleTracks: [],
};

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
      sendPlay();
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

function sendPlay() {
  const url = mediaUrl.value.trim();
  if (!url) {
    mediaUrl.focus();
    return;
  }

  const payload = {
    action: 'play',
    url,
  };

  const title = titleInput.value.trim();
  const format = formatSelect.value;
  const subtitle = subtitleUrl.value.trim();
  const subtitleMimeType = subtitleType.value.trim();
  const subtitleLang = subtitleLanguage.value.trim();

  if (title) {
    payload.title = title;
  }

  if (format && format !== 'auto') {
    payload.format = format;
  }

  if (subtitle) {
    payload.subtitleUrl = subtitle;
    if (subtitleMimeType) {
      payload.subtitleMimeType = subtitleMimeType;
    }
    if (subtitleLang) {
      payload.subtitleLanguage = subtitleLang;
    }
  }

  localStorage.setItem('vibe_cast_last_media_url', url);
  localStorage.setItem('vibe_cast_last_title', title);
  localStorage.setItem('vibe_cast_last_subtitle_url', subtitle);
  localStorage.setItem('vibe_cast_last_subtitle_type', subtitleMimeType);
  localStorage.setItem('vibe_cast_last_subtitle_language', subtitleLang);

  send(payload);
}

function applyState(state) {
  latestState = state;
  playbackPhase.textContent = capitalize(state.playbackPhase || 'idle');
  httpUrl.textContent = state.connectionUrl || '-';
  wsUrl.textContent = state.webSocketUrl || '-';
  clientCount.textContent = String(state.clientCount || 0);
  currentTitle.textContent = state.title || 'Nothing loaded yet.';
  currentMedia.textContent = state.currentMediaUrl || 'Nothing loaded yet.';

  if (!isDraggingSeek) {
    updateSeekSlider(state.currentPositionMs || 0, state.durationMs || 0);
  }

  const volumePercent = Math.round((state.volume || 0) * 100);
  volumeSlider.value = String(volumePercent);
  volumeReadout.textContent = `${volumePercent}%`;

  applyAudioTrackOptions(state.audioTracks || [], state.selectedAudioTrackId || '');
  applySubtitleTrackOptions(state.subtitleTracks || [], state.selectedSubtitleTrackId || '');
}

function applyAudioTrackOptions(tracks, selectedId) {
  isApplyingTrackOptions = true;
  audioTrackSelect.innerHTML = '';

  if (!tracks.length) {
    audioTrackSelect.innerHTML = '<option value="">Waiting for stream</option>';
    audioTrackSelect.disabled = true;
    audioTrackReadout.textContent = 'Default';
    isApplyingTrackOptions = false;
    return;
  }

  tracks.forEach((track) => {
    const option = document.createElement('option');
    option.value = track.id;
    option.textContent = track.label;
    option.selected = track.id === selectedId;
    audioTrackSelect.appendChild(option);
  });

  audioTrackSelect.disabled = false;
  audioTrackReadout.textContent = findSelectedLabel(tracks, selectedId) || tracks[0].label;
  isApplyingTrackOptions = false;
}

function applySubtitleTrackOptions(tracks, selectedId) {
  isApplyingTrackOptions = true;
  subtitleTrackSelect.innerHTML = '';

  const offOption = document.createElement('option');
  offOption.value = OFF_TRACK_ID;
  offOption.textContent = 'Off';
  offOption.selected = !selectedId;
  subtitleTrackSelect.appendChild(offOption);

  tracks.forEach((track) => {
    const option = document.createElement('option');
    option.value = track.id;
    option.textContent = track.label;
    option.selected = track.id === selectedId;
    subtitleTrackSelect.appendChild(option);
  });

  subtitleTrackSelect.disabled = !tracks.length;
  subtitleTrackReadout.textContent = selectedId
    ? (findSelectedLabel(tracks, selectedId) || 'Subtitle selected')
    : (tracks.length ? 'Off' : 'No subtitles');
  isApplyingTrackOptions = false;
}

function findSelectedLabel(tracks, selectedId) {
  return tracks.find((track) => track.id === selectedId)?.label || '';
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

playBtn.addEventListener('click', sendPlay);
pauseBtn.addEventListener('click', () => send({ action: 'pause' }));
resumeBtn.addEventListener('click', () => send({ action: 'resume' }));
stopBtn.addEventListener('click', () => send({ action: 'stop' }));

audioTrackSelect.addEventListener('change', () => {
  if (isApplyingTrackOptions || !audioTrackSelect.value) {
    return;
  }
  send({ action: 'set_audio_track', id: audioTrackSelect.value });
});

subtitleTrackSelect.addEventListener('change', () => {
  if (isApplyingTrackOptions) {
    return;
  }
  send({ action: 'set_subtitle_track', id: subtitleTrackSelect.value });
});

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
titleInput.value = localStorage.getItem('vibe_cast_last_title') || '';
subtitleUrl.value = localStorage.getItem('vibe_cast_last_subtitle_url') || '';
subtitleType.value = localStorage.getItem('vibe_cast_last_subtitle_type') || '';
subtitleLanguage.value = localStorage.getItem('vibe_cast_last_subtitle_language') || '';

if (pendingAutoPlayUrl) {
  mediaUrl.value = pendingAutoPlayUrl;
  localStorage.setItem('vibe_cast_last_media_url', pendingAutoPlayUrl);
}

connect();
