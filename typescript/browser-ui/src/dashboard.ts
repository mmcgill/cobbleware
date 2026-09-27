// @ts-nocheck
/// <reference lib="dom" />

import './styles.css';
import { fromBinary, isFieldSet } from '@bufbuild/protobuf';
import * as Internal from '@cobbleware/core/internal';
import { markBundledUi } from './build-metadata.ts';

markBundledUi(document.documentElement);

(() => {
  'use strict';

  const elements = {
    pageStatus: document.querySelector('#page-status'),
    playerProfileName: document.querySelector('#player-profile-name'),
    logoutButton: document.querySelector('#logout-button'),
    activeProgramSection: document.querySelector('#active-program-section'),
    browseButton: document.querySelector('#browse-button'),
    fileInput: document.querySelector('#file-input'),
    selectedFile: document.querySelector('#selected-file'),
    uploadStatus: document.querySelector('#upload-status'),
    refreshButton: document.querySelector('#refresh-button'),
    programsLoading: document.querySelector('#programs-loading'),
    emptyState: document.querySelector('#empty-state'),
    programsTable: document.querySelector('#programs-table'),
    programsBody: document.querySelector('#programs-body'),
    startStatesLoading: document.querySelector('#start-states-loading'),
    startStatesEmpty: document.querySelector('#start-states-empty'),
    startStatesList: document.querySelector('#start-states-list'),
    statsStatus: document.querySelector('#stats-status'),
    statsDetails: document.querySelector('#stats-details'),
    statsRetryButton: document.querySelector('#stats-retry-button'),
    statsActivityGraph: document.querySelector('#stats-activity-graph'),
    statsGraphTooltip: document.querySelector('#stats-graph-tooltip'),
    statsSampledAt: document.querySelector('#stats-sampled-at'),
    statsWindow: document.querySelector('#stats-window'),
    statsMemoryProgress: document.querySelector('#stats-memory-progress'),
    statsMemoryText: document.querySelector('#stats-memory-text'),
    statsEventsPerSync: document.querySelector('#stats-events-per-sync'),
    statsEvents: document.querySelector('#stats-events'),
    statsEffects: document.querySelector('#stats-effects'),
    statsTab: document.querySelector('#stats-tab'),
    logsTab: document.querySelector('#logs-tab'),
    limitsTab: document.querySelector('#limits-tab'),
    statsPanel: document.querySelector('#stats-panel'),
    logsPanel: document.querySelector('#logs-panel'),
    limitsPanel: document.querySelector('#limits-panel'),
    logsStatus: document.querySelector('#logs-status'),
    logsRetryButton: document.querySelector('#logs-retry-button'),
    logsAutoScroll: document.querySelector('#logs-auto-scroll'),
    logsEmpty: document.querySelector('#logs-empty'),
    logsFeed: document.querySelector('#logs-feed'),
    logsBody: document.querySelector('#logs-body'),
    limitsStatus: document.querySelector('#limits-status'),
    limitsDetails: document.querySelector('#limits-details'),
    limitsRetryButton: document.querySelector('#limits-retry-button'),
    limitsRunningExecutionsProgress: document.querySelector('#limits-running-executions-progress'),
    limitsRunningExecutionsText: document.querySelector('#limits-running-executions-text'),
    limitsTotalExecutionStateProgress: document.querySelector('#limits-total-execution-state-progress'),
    limitsTotalExecutionStateText: document.querySelector('#limits-total-execution-state-text'),
    limitsUpdatedAt: document.querySelector('#limits-updated-at'),
  };

  const byteFormatter = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
  const dateFormatter = new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  const logTimestampFormatter = new Intl.DateTimeFormat(undefined, {
    dateStyle: 'short',
    timeStyle: 'medium',
  });
  const STATS_RETENTION_TICKS = 600;
  const STATS_GRAPH_HEIGHT = 200;
  const DUPLICATE_STATS_STREAM_CODE = 'execution-stats-stream-already-open';
  const LOG_RETENTION_EVENTS = 10000;
  const STATS_GRAPH_COLORS = {
    accepted: '#78c091',
    dropped: '#ff9c9c',
    succeeded: '#78aee8',
    failed: '#ffb36b',
    capped: '#f9d65c',
    divider: '#405666',
  };

  let programList = null;
  let startStates = null;
  let selectedFile = null;
  let operation = 'idle';
  let statsSocket = null;
  let statsTicks = [];
  let statsGapObserved = false;
  let statsLastReceivedAt = 0;
  let statsClosingForLogout = false;
  let activeObservabilityTab = 'stats';
  let logsSocket = null;
  let logsClosingForLogout = false;
  let logEntries = [];
  let limits = null;
  let limitsLoading = false;
  let limitsReloadRequested = false;
  let limitsGeneration = 0;

  function formatBytes(bytes) {
    if (bytes === 0) {
      return '0 B';
    }
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    const unitIndex = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    return `${byteFormatter.format(bytes / (1024 ** unitIndex))} ${units[unitIndex]}`;
  }

  function formatDate(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : dateFormatter.format(date);
  }

  function setMessage(message, kind = '') {
    elements.pageStatus.textContent = message;
    elements.pageStatus.className = `status${kind ? ` ${kind}` : ''}`;
  }

  function setUploadMessage(message) {
    elements.uploadStatus.textContent = message;
  }

  function makeError(message, status = 0, redirected = false) {
    const error = new Error(message);
    error.status = status;
    error.redirected = redirected;
    return error;
  }

  function redirectToAuthentication() {
    window.location.assign('/auth.html');
    throw makeError('Authentication required.', 401, true);
  }

  async function errorFromResponse(response) {
    let message = '';
    try {
      const body = await response.json();
      if (body && typeof body.error === 'string') {
        message = body.error;
      }
    } catch {
      // Use the status-specific fallback below when the body is not JSON.
    }

    if (message) {
      return makeError(message, response.status);
    }

    const fallbacks = {
      400: 'The upload request or program name was invalid.',
      403: 'The server rejected this same-origin request.',
      409: 'Another program operation is in progress. Please try again shortly.',
      413: 'The program is larger than your storage quota.',
      415: 'The upload must be sent as a multipart form.',
      500: 'The server could not complete the request.',
    };
    return makeError(
      fallbacks[response.status] || `The server returned HTTP ${response.status}.`,
      response.status,
    );
  }

  async function request(url, options = {}) {
    let response;
    try {
      response = await fetch(url, { credentials: 'same-origin', ...options });
    } catch {
      throw makeError(
        'The request could not reach the server. Check your connection and try again.',
      );
    }

    if (response.status === 401) {
      redirectToAuthentication();
    }
    if (!response.ok) {
      throw await errorFromResponse(response);
    }
    return response;
  }

  function parseProgramList(body) {
    if (!body || !Array.isArray(body.programs)) {
      throw makeError('The server returned an invalid program list.');
    }
    if (body.programs.length > 1) {
      throw makeError('The server returned more than one active program.');
    }
    const numericFields = ['quotaBytes', 'usedBytes', 'remainingBytes'];
    if (numericFields.some((field) => !Number.isInteger(body[field]) || body[field] < 0)) {
      throw makeError('The server returned invalid program quota data.');
    }
    for (const program of body.programs) {
      if (
        !program ||
        typeof program.id !== 'string' ||
        typeof program.name !== 'string' ||
        typeof program.uploadedAt !== 'string' ||
        !Number.isInteger(program.sizeBytes) ||
        program.sizeBytes < 0
      ) {
        throw makeError('The server returned invalid program metadata.');
      }
    }
    return body;
  }

  function parsePlayerIdentity(body) {
    if (!body || typeof body.playerProfileName !== 'string') {
      throw makeError('The server returned an invalid player identity.');
    }
    return body;
  }

  function renderPrograms() {
    elements.programsLoading.hidden = programList !== null || operation !== 'loading';
    elements.programsBody.replaceChildren();

    if (programList === null) {
      elements.programsTable.hidden = true;
      elements.emptyState.hidden = true;
      return;
    }

    const programs = programList.programs;
    elements.emptyState.hidden = programs.length !== 0;
    elements.programsTable.hidden = programs.length === 0;

    for (const program of programs) {
      const row = document.createElement('tr');

      const idCell = document.createElement('td');
      const idCode = document.createElement('code');
      idCode.textContent = program.id;
      idCell.append(idCode);
      row.append(idCell);

      const nameCell = document.createElement('td');
      nameCell.textContent = program.name;
      row.append(nameCell);

      const dateCell = document.createElement('td');
      const date = document.createElement('time');
      date.dateTime = program.uploadedAt;
      date.textContent = formatDate(program.uploadedAt);
      date.title = program.uploadedAt;
      dateCell.append(date);
      row.append(dateCell);

      const sizeCell = document.createElement('td');
      sizeCell.textContent = formatBytes(program.sizeBytes);
      row.append(sizeCell);

      elements.programsBody.append(row);
    }
  }

  function renderStartStates() {
    elements.startStatesList.replaceChildren();
    const hasActiveProgram = programList !== null && programList.programs.length === 1;
    elements.startStatesLoading.hidden = !(hasActiveProgram && startStates === null);
    elements.startStatesEmpty.hidden = !hasActiveProgram || startStates === null ||
      startStates.length !== 0;
    elements.startStatesList.hidden = !hasActiveProgram || startStates === null ||
      startStates.length === 0;

    if (startStates === null) {
      return;
    }
    for (const stateName of startStates) {
      const item = document.createElement('li');
      item.textContent = stateName;
      elements.startStatesList.append(item);
    }
  }

  function renderControls() {
    const busy = operation !== 'idle';
    elements.logoutButton.disabled = busy;
    elements.logoutButton.textContent = operation === 'logging-out' ? 'Logging out...' : 'Log out';
    elements.browseButton.disabled = busy;
    elements.fileInput.disabled = busy;
    elements.refreshButton.disabled = busy;
  }

  function renderSelectedFile() {
    if (selectedFile === null) {
      elements.selectedFile.textContent = '';
      return;
    }
    elements.selectedFile.textContent = `Selected: ${selectedFile.name} (${
      formatBytes(selectedFile.size)
    })`;
  }

  function render() {
    renderPrograms();
    renderStartStates();
    renderControls();
    renderSelectedFile();
  }

  function setStatsStatus(message, kind = '') {
    elements.statsStatus.textContent = message;
    elements.statsStatus.className = `muted${kind ? ` ${kind}` : ''}`;
  }

  function setLogsStatus(message, kind = '') {
    elements.logsStatus.textContent = message;
    elements.logsStatus.className = `muted${kind ? ` ${kind}` : ''}`;
  }

  function setLimitsStatus(message, kind = '') {
    elements.limitsStatus.textContent = message;
    elements.limitsStatus.className = `muted${kind ? ` ${kind}` : ''}`;
  }

  function selectObservabilityTab(tab, focus = false) {
    activeObservabilityTab = tab;
    const statsSelected = tab === 'stats';
    const logsSelected = tab === 'logs';
    const limitsSelected = tab === 'limits';
    elements.statsTab.setAttribute('aria-selected', String(statsSelected));
    elements.statsTab.tabIndex = statsSelected ? 0 : -1;
    elements.logsTab.setAttribute('aria-selected', String(logsSelected));
    elements.logsTab.tabIndex = logsSelected ? 0 : -1;
    elements.limitsTab.setAttribute('aria-selected', String(limitsSelected));
    elements.limitsTab.tabIndex = limitsSelected ? 0 : -1;
    elements.statsPanel.hidden = !statsSelected;
    elements.logsPanel.hidden = !logsSelected;
    elements.limitsPanel.hidden = !limitsSelected;
    if (logsSelected) {
      renderLogs();
      if (elements.logsAutoScroll.checked) scrollLogsToBottom();
    }
    if (focus) {
      ({ stats: elements.statsTab, logs: elements.logsTab, limits: elements.limitsTab })[tab]
        .focus();
    }
  }

  function renderLimits() {
    elements.limitsDetails.hidden = limits === null;
    if (limits === null) return;
    const { used, limit } = limits.runningExecutions;
    elements.limitsRunningExecutionsProgress.max = limit;
    elements.limitsRunningExecutionsProgress.value = used;
    elements.limitsRunningExecutionsText.textContent =
      `${used.toLocaleString()} of ${limit.toLocaleString()} running executions`;
    const stateBytes = limits.totalExecutionStateBytes;
    elements.limitsTotalExecutionStateProgress.max = stateBytes.limit;
    elements.limitsTotalExecutionStateProgress.value = stateBytes.used;
    elements.limitsTotalExecutionStateText.textContent =
      `${formatBytes(stateBytes.used)} of ${formatBytes(stateBytes.limit)} execution state`;
    elements.limitsUpdatedAt.textContent = formatDate(limits.updatedAt);
    elements.limitsUpdatedAt.title = limits.updatedAt;
  }

  function parseLimits(body) {
    const runningExecutions = body?.runningExecutions;
    const totalExecutionStateBytes = body?.totalExecutionStateBytes;
    if (
      !runningExecutions || !Number.isInteger(runningExecutions.used) ||
      !Number.isInteger(runningExecutions.limit) || runningExecutions.used < 0 ||
      runningExecutions.limit <= 0 || runningExecutions.used > runningExecutions.limit ||
      !totalExecutionStateBytes || !Number.isSafeInteger(totalExecutionStateBytes.used) ||
      !Number.isSafeInteger(totalExecutionStateBytes.limit) || totalExecutionStateBytes.used < 0 ||
      totalExecutionStateBytes.limit <= 0 ||
      typeof totalExecutionStateBytes.updatedAt !== 'string' ||
      typeof body.updatedAt !== 'string'
    ) {
      throw makeError('The server returned invalid limit data.');
    }
    return body;
  }

  async function loadLimits() {
    if (limitsLoading) {
      limitsReloadRequested = true;
      return;
    }
    limitsLoading = true;
    elements.limitsRetryButton.hidden = true;
    setLimitsStatus(limits === null ? 'Loading limits...' : 'Refreshing limits...');
    do {
      limitsReloadRequested = false;
      const generation = limitsGeneration;
      try {
        const response = await request('/playerapi/v1/limits');
        const loadedLimits = parseLimits(await response.json());
        if (generation === limitsGeneration) {
          limits = loadedLimits;
          setLimitsStatus('Current enforced limits.');
        }
      } catch (error) {
        if (!error.redirected && generation === limitsGeneration) {
          setLimitsStatus(error.message || 'Could not load limits.', 'error');
          elements.limitsRetryButton.hidden = false;
        }
      }
    } while (limitsReloadRequested);
    limitsLoading = false;
    renderLimits();
  }

  function selectedLogLevels() {
    return new Set(
      [...document.querySelectorAll('[data-log-level]:checked')].map((input) =>
        input.dataset.logLevel
      ),
    );
  }

  function selectedLogTypes() {
    return new Set(
      [...document.querySelectorAll('[data-log-type]:checked')].map((input) =>
        input.dataset.logType
      ),
    );
  }

  function matchesLogFilters(entry, levels, types) {
    if (!types.has(entry.type)) return false;
    return entry.type !== 'log' || levels.has(entry.level);
  }

  function renderLogs() {
    const levels = selectedLogLevels();
    const types = selectedLogTypes();
    const entries = logEntries.filter((entry) => matchesLogFilters(entry, levels, types));
    elements.logsBody.replaceChildren(...entries.map(renderLogEntry));
    elements.logsEmpty.hidden = entries.length !== 0;
    elements.logsEmpty.textContent = logEntries.length === 0
      ? 'No log events received yet.'
      : 'No log events match the selected filters.';
  }

  function renderLogEntry(entry) {
    const item = document.createElement('tr');
    const timestamp = document.createElement('td');
    const time = document.createElement('time');
    const receivedAt = new Date(entry.receivedAt);
    time.dateTime = receivedAt.toISOString();
    time.textContent = logTimestampFormatter.format(receivedAt);
    time.title = receivedAt.toISOString();
    timestamp.append(time);
    const type = document.createElement('td');
    type.textContent = entry.typeLabel;
    const level = document.createElement('td');
    level.textContent = entry.level ? entry.level.toUpperCase() : '';
    const messageCell = document.createElement('td');
    const message = document.createElement('pre');
    message.className = 'log-message';
    message.textContent = entry.message;
    messageCell.append(message);
    const metadata = document.createElement('td');
    metadata.className = 'log-metadata';
    metadata.textContent = entry.metadata;
    item.append(timestamp, type, level, messageCell, metadata);
    return item;
  }

  function appendLogEntry(entry) {
    logEntries.push({ ...entry, receivedAt: Date.now() });
    if (logEntries.length > LOG_RETENTION_EVENTS) logEntries.shift();
    renderLogs();
    if (activeObservabilityTab === 'logs' && elements.logsAutoScroll.checked) scrollLogsToBottom();
  }

  function scrollLogsToBottom() {
    elements.logsFeed.scrollTop = elements.logsFeed.scrollHeight;
  }

  function formatBigInt(value) {
    return value.toLocaleString();
  }

  function formatStatBytes(value) {
    return value <= BigInt(Number.MAX_SAFE_INTEGER)
      ? formatBytes(Number(value))
      : `${formatBigInt(value)} B`;
  }

  function formatAverage(total, count, suffix = '') {
    if (count === 0n) {
      return `0${suffix}`;
    }
    const tenths = (total * 10n + count / 2n) / count;
    return `${tenths / 10n}${tenths % 10n === 0n ? '' : `.${tenths % 10n}`}${suffix}`;
  }

  function formatNanos(value) {
    if (value < 1000n) return `${formatBigInt(value)} ns`;
    if (value < 1000000n) return formatAverage(value, 1000n, ' µs');
    if (value < 1000000000n) return formatAverage(value, 1000000n, ' ms');
    return formatAverage(value, 1000000000n, ' s');
  }

  function renderStats() {
    if (statsTicks.length === 0) {
      elements.statsDetails.hidden = true;
      return;
    }
    const { sampledAt, player } = aggregateStatsTicks();
    const sampledAtNumber = Number(sampledAt);
    const captured = new Date(sampledAtNumber);
    elements.statsSampledAt.textContent =
      Number.isSafeInteger(sampledAtNumber) && !Number.isNaN(captured.getTime())
        ? dateFormatter.format(captured)
        : 'Invalid sample time';
    elements.statsWindow.textContent =
      `${statsTicks.length.toLocaleString()} of ${STATS_RETENTION_TICKS.toLocaleString()} received ticks${
        statsGapObserved ? ' (gaps observed)' : ''
      }`;
    renderStatsMemory(statsTicks.at(-1));
    elements.statsEventsPerSync.textContent = `${
      formatBigInt(player.eventsPerSyncCount)
    } records; average ${
      formatAverage(player.eventsPerSyncTotal, player.eventsPerSyncCount)
    }; maximum ${formatBigInt(player.eventsPerSyncMax)}`;
    elements.statsEvents.textContent = `${formatBigInt(player.eventsAccepted)} accepted; ${
      formatBigInt(player.eventsDropped)
    } dropped`;
    elements.statsEffects.textContent = `${formatBigInt(player.effectsAttempted)} attempted; ${
      formatBigInt(player.effectsFailed)
    } failed; average ${
      formatNanos(
        player.effectsAttempted === 0n ? 0n : player.effectsTotalNanos / player.effectsAttempted,
      )
    }; maximum ${formatNanos(player.effectsMaxNanos)}`;
    elements.statsDetails.hidden = false;
    renderStatsGraphs();
  }

  function renderStatsMemory(tick) {
    const { usedBytes, maxBytes } = tick;
    if (maxBytes === 0n) {
      elements.statsMemoryProgress.max = 1;
      elements.statsMemoryProgress.value = 0;
      elements.statsMemoryProgress.classList.remove('over-limit');
      elements.statsMemoryProgress.setAttribute('aria-label', 'Player memory usage is unavailable');
      elements.statsMemoryText.textContent = 'Unavailable';
      return;
    }

    const displayedValue = usedBytes > maxBytes ? maxBytes : usedBytes;
    elements.statsMemoryProgress.max = Number(maxBytes);
    elements.statsMemoryProgress.value = Number(displayedValue);
    elements.statsMemoryProgress.classList.toggle('over-limit', usedBytes >= maxBytes);
    const text = `${formatStatBytes(usedBytes)} of ${formatStatBytes(maxBytes)}`;
    elements.statsMemoryProgress.setAttribute('aria-label', `Player memory usage: ${text}`);
    elements.statsMemoryText.textContent = text;
  }

  function renderStatsGraphs() {
    if (statsTicks.length === 0) return;
    const graphTicks = statsTicks.map((tick) => tick.graph);
    renderStatsGraph(elements.statsActivityGraph, graphTicks);

    const latest = graphTicks.at(-1);
    elements.statsActivityGraph.setAttribute(
      'aria-label',
      `Events and effects per received tick. Latest tick: ${
        formatBigInt(latest.eventsAccepted)
      } accepted and ${formatBigInt(latest.eventsDropped)} dropped events; ${
        formatBigInt(latest.effectsSucceeded)
      } successful and ${
        formatBigInt(latest.effectsFailed)
      } failed effects. Events extend upward from the middle,
          effects downward, and yellow marks a capped column.`,
    );
  }

  function renderStatsGraph(canvas, graphTicks) {
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (width === 0 || height === 0) return;

    const devicePixelRatio = window.devicePixelRatio || 1;
    const backingWidth = Math.round(width * devicePixelRatio);
    const backingHeight = Math.round(height * devicePixelRatio);
    if (canvas.width !== backingWidth || canvas.height !== backingHeight) {
      canvas.width = backingWidth;
      canvas.height = backingHeight;
    }

    const context = canvas.getContext('2d');
    context.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
    context.clearRect(0, 0, width, height);
    const middle = Math.floor(height / 2);
    context.fillStyle = STATS_GRAPH_COLORS.divider;
    context.fillRect(0, middle, width, 1);

    const graphWidth = Math.floor(width);
    const firstTick = Math.max(0, graphTicks.length - graphWidth);
    let x = graphWidth - (graphTicks.length - firstTick);
    for (let index = firstTick; index < graphTicks.length; index += 1, x += 1) {
      drawStatsGraphColumn(context, x, middle, graphTicks[index]);
    }
  }

  function drawStatsGraphColumn(context, x, middle, tick) {
    drawStatsGraphStack(
      context,
      x,
      middle,
      -1,
      tick.eventsAccepted,
      tick.eventsDropped,
      STATS_GRAPH_COLORS.accepted,
      STATS_GRAPH_COLORS.dropped,
    );
    drawStatsGraphStack(
      context,
      x,
      middle,
      1,
      tick.effectsSucceeded,
      tick.effectsFailed,
      STATS_GRAPH_COLORS.succeeded,
      STATS_GRAPH_COLORS.failed,
    );
  }

  function drawStatsGraphStack(
    context,
    x,
    middle,
    direction,
    primary,
    secondary,
    primaryColor,
    secondaryColor,
  ) {
    const cap = BigInt(middle);
    const total = primary + secondary;
    const secondaryHeight = Number(secondary > cap ? cap : secondary);
    const primaryHeight = Number(
      primary > cap - BigInt(secondaryHeight) ? cap - BigInt(secondaryHeight) : primary,
    );
    if (secondaryHeight > 0) {
      context.fillStyle = secondaryColor;
      const y = direction < 0 ? middle - secondaryHeight : middle;
      context.fillRect(x, y, 1, secondaryHeight);
    }
    if (primaryHeight > 0) {
      context.fillStyle = primaryColor;
      const y = direction < 0 ? middle - secondaryHeight - primaryHeight : middle + secondaryHeight;
      context.fillRect(x, y, 1, primaryHeight);
    }
    if (total > cap) {
      context.fillStyle = STATS_GRAPH_COLORS.capped;
      context.fillRect(x, direction < 0 ? 0 : middle * 2 - 1, 1, 1);
    }
  }

  function updateStatsGraphTooltip(event) {
    const canvas = elements.statsActivityGraph;
    const bounds = canvas.getBoundingClientRect();
    const graphHeight = canvas.clientHeight;
    if (graphHeight === 0) return;

    const y = (event.clientY - bounds.top) * graphHeight / bounds.height;
    const middle = graphHeight / 2;
    const events = y < middle;
    const value = Math.ceil(events ? middle - y : y - middle);
    elements.statsGraphTooltip.textContent = `${events ? 'Events' : 'Effects'}: ${value}`;
    elements.statsGraphTooltip.hidden = false;

    const offset = 12;
    const right = window.innerWidth - elements.statsGraphTooltip.offsetWidth - 8;
    const bottom = window.innerHeight - elements.statsGraphTooltip.offsetHeight - 8;
    elements.statsGraphTooltip.style.left = `${Math.min(event.clientX + offset, right)}px`;
    elements.statsGraphTooltip.style.top = `${Math.min(event.clientY + offset, bottom)}px`;
  }

  function hideStatsGraphTooltip() {
    elements.statsGraphTooltip.hidden = true;
  }

  function updateStatsStaleness() {
    if (statsTicks.length === 0 || statsLastReceivedAt === 0 || statsSocket === null) return;
    if (Date.now() - statsLastReceivedAt > 3000) {
      setStatsStatus('Statistics may be stale; no update has arrived recently.', 'error');
    } else {
      setStatsStatus('Live execution statistics.');
    }
  }

  function statsConnectionError(message, canRetry = true) {
    statsSocket = null;
    setStatsStatus(message, 'error');
    elements.statsRetryButton.hidden = !canRetry;
  }

  function connectStats() {
    if (statsSocket !== null) return;
    statsClosingForLogout = false;
    elements.statsRetryButton.hidden = true;
    setStatsStatus('Connecting to statistics...');
    let socket;
    try {
      socket = new WebSocket('/playerapi/v1/execution-stats');
    } catch {
      statsConnectionError('Could not open the statistics connection.');
      return;
    }
    statsSocket = socket;
    let rejectedByServer = false;
    socket.binaryType = 'arraybuffer';
    socket.onopen = () => {
      if (statsTicks.length === 0) setStatsStatus('No statistics are available yet.');
    };
    socket.onmessage = (event) => {
      try {
        if (typeof event.data === 'string') {
          const error = decodeStatsStreamError(event.data);
          rejectedByServer = true;
          statsConnectionError(error.error, false);
          return;
        }
        const tick = decodeExecutionStatsTick(new Uint8Array(event.data));
        appendStatsTick(tick);
        updateLimitsFromStats(tick);
        statsLastReceivedAt = Date.now();
        renderStats();
        updateStatsStaleness();
      } catch {
        statsConnectionError('The server sent invalid execution statistics.');
        socket.close();
      }
    };
    socket.onerror = () => {
      // The close handler supplies the actionable state after the browser finishes the socket.
    };
    socket.onclose = () => {
      if (!statsClosingForLogout && !rejectedByServer) {
        statsConnectionError('The statistics connection closed. Retry to reconnect.');
      }
    };
  }

  function decodeStatsStreamError(message) {
    const error = JSON.parse(message);
    if (
      !error ||
      (error.code !== DUPLICATE_STATS_STREAM_CODE && typeof error.code !== 'string') ||
      typeof error.error !== 'string'
    ) {
      throw new Error('invalid execution-stats stream error');
    }
    return error;
  }

  function logsConnectionError(message, canRetry = true) {
    logsSocket = null;
    setLogsStatus(message, 'error');
    elements.logsRetryButton.hidden = !canRetry;
  }

  function connectLogs() {
    if (logsSocket !== null) return;
    logsClosingForLogout = false;
    elements.logsRetryButton.hidden = true;
    setLogsStatus('Connecting to logs...');
    let socket;
    try {
      socket = new WebSocket('/playerapi/v1/logs');
    } catch {
      logsConnectionError('Could not open the logs connection.');
      return;
    }
    logsSocket = socket;
    let terminal = false;
    socket.binaryType = 'arraybuffer';
    socket.onopen = () => {
      if (logEntries.length === 0) setLogsStatus('No log events received yet.');
      else setLogsStatus('Live execution logs.');
    };
    socket.onmessage = (event) => {
      try {
        if (typeof event.data === 'string') {
          const error = decodeLogsStreamError(event.data);
          terminal = true;
          logsConnectionError(error.error, false);
          return;
        }
        if (!(event.data instanceof ArrayBuffer)) throw new Error('expected a binary log frame');
        const decoded = decodeExecutionLogEntry(new Uint8Array(event.data));
        appendLogEntry(decoded);
        if (decoded.type === 'transition') updateLimitsFromTransition(decoded);
        setLogsStatus('Live execution logs.');
      } catch {
        terminal = true;
        logsConnectionError('The server sent an invalid execution log.');
        socket.close();
      }
    };
    socket.onclose = () => {
      if (!logsClosingForLogout && !terminal) {
        logsConnectionError('The logs connection closed. Retry to reconnect.');
      }
    };
  }

  function decodeLogsStreamError(message) {
    const error = JSON.parse(message);
    if (!error || typeof error.code !== 'string' || typeof error.error !== 'string') {
      throw new Error('invalid execution-logs stream error');
    }
    return error;
  }

  function decodeExecutionLogEntry(bytes) {
    const entry = fromBinary(Internal.ExecutionLogEntrySchema, bytes);
    if (!isFieldSet(entry, Internal.ExecutionLogEntrySchema.field.playerId)) {
      throw new Error('missing player identifier');
    }
    switch (entry.inner.case) {
      case 'log': {
        const level = logLevelName(entry.inner.value.level);
        return {
          type: 'log',
          typeLabel: 'Program log',
          level,
          message: entry.inner.value.msg,
          metadata: '',
        };
      }
      case 'exception':
        return {
          type: 'exception',
          typeLabel: 'Exception',
          message: entry.inner.value.msg,
          metadata: entry.inner.value.cpuTimeUsed === 0n
            ? ''
            : `CPU time used: ${formatNanos(entry.inner.value.cpuTimeUsed)}`,
        };
      case 'limited': {
        const { reason, cpuTimeUsed, totalMemUsed } = entry.inner.value;
        if (reason !== 'CPUTime' && reason !== 'Memory' && reason !== 'TotalExecutionStateSize') {
          throw new Error('invalid limit reason');
        }
        const details = [];
        if (cpuTimeUsed !== 0n) details.push(`CPU time used: ${formatNanos(cpuTimeUsed)}`);
        if (totalMemUsed !== 0n) details.push(`Memory used: ${formatStatBytes(totalMemUsed)}`);
        return {
          type: 'limited',
          typeLabel: 'Limit exceeded',
          message: reason === 'TotalExecutionStateSize'
            ? 'Total execution-state size limit exceeded.'
            : `${reason} limit exceeded.`,
          metadata: details.join('; '),
        };
      }
      case 'transition': {
        const value = entry.inner.value;
        if (
          !isFieldSet(value, Internal.ExecutionTransitionLogEntrySchema.field.executionId) ||
          value.executionId === undefined || !Number.isInteger(value.executionCount) ||
          !Number.isInteger(value.executionLimit) || value.executionCount < 0 ||
          value.executionLimit <= 0 || value.executionCount > value.executionLimit ||
          value.totalExecutionStateLimit <= 0n ||
          value.totalExecutionStateLimit > BigInt(Number.MAX_SAFE_INTEGER)
        ) {
          throw new Error('invalid execution transition');
        }
        return {
          type: 'transition',
          typeLabel: 'Execution transition',
          message: `Execution ${uuidString(value.executionId)} ${
            executionTransitionName(value.transition)
          }.`,
          metadata:
            `${value.executionCount.toLocaleString()} of ${value.executionLimit.toLocaleString()} running executions; ` +
            `${formatStatBytes(value.totalExecutionStateLimit)} total execution-state limit`,
          executionCount: value.executionCount,
          executionLimit: value.executionLimit,
          totalExecutionStateLimit: Number(value.totalExecutionStateLimit),
        };
      }
      default:
        throw new Error('missing execution-log entry');
    }
  }

  function logLevelName(level) {
    switch (level) {
      case Internal.LogLevel.DEBUG:
        return 'debug';
      case Internal.LogLevel.INFO:
        return 'info';
      case Internal.LogLevel.WARN:
        return 'warn';
      case Internal.LogLevel.ERROR:
        return 'error';
      default:
        throw new Error('invalid log level');
    }
  }

  function executionTransitionName(transition) {
    switch (transition) {
      case Internal.ExecutionTransition.STARTED:
        return 'started';
      case Internal.ExecutionTransition.STOPPED:
        return 'stopped';
      case Internal.ExecutionTransition.RESUMED:
        return 'resumed';
      case Internal.ExecutionTransition.CRASHED:
        return 'crashed';
      default:
        throw new Error('invalid execution transition');
    }
  }

  function uuidString(value) {
    const hex = `${value.high.toString(16).padStart(16, '0')}${
      value.low.toString(16).padStart(16, '0')
    }`;
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${
      hex.slice(20)
    }`;
  }

  function updateLimitsFromTransition(entry) {
    limitsGeneration += 1;
    limits = {
      runningExecutions: { used: entry.executionCount, limit: entry.executionLimit },
      totalExecutionStateBytes: {
        used: limits?.totalExecutionStateBytes.used ?? 0,
        limit: entry.totalExecutionStateLimit,
        updatedAt: limits?.totalExecutionStateBytes.updatedAt ?? new Date().toISOString(),
      },
      updatedAt: new Date().toISOString(),
    };
    elements.limitsRetryButton.hidden = true;
    setLimitsStatus('Current enforced limits.');
    renderLimits();
  }

  function decodeExecutionStatsTick(bytes) {
    const fields = readProtoFields(bytes);
    const playerBytes = fields.get(3) || [];
    if (playerBytes.length !== 1) throw new Error('expected exactly one player stats record');
    const sampledAt = protoVarint(fields, 1);
    const sequence = protoVarint(fields, 2);
    if (sampledAt === 0n || sequence === 0n) {
      throw new Error('invalid stats snapshot');
    }
    const playerFields = readProtoFields(playerBytes[0]);
    if ((playerFields.get(1) || []).length !== 1) throw new Error('missing player identifier');
    requiredMessage(playerFields, 1);
    const events = (playerFields.get(2) || []).map(decodeEventMeasurement);
    const syncs = (playerFields.get(3) || []).map(decodeEventsPerSyncMeasurement);
    const effects = (playerFields.get(4) || []).map(decodeEffectMeasurement);
    const stateBytes = (playerFields.get(5) || []).reduce(
      (total, bytes) => total + decodeCobbleputerState(bytes),
      0n,
    );
    const usedBytes = protoVarint(playerFields, 6);
    const maxBytes = protoVarint(playerFields, 7);
    return {
      sampledAt,
      sequence,
      events,
      syncs,
      effects,
      usedBytes,
      maxBytes,
      stateBytes,
      graph: summarizeStatsTickGraph(events, effects),
    };
  }

  function decodeCobbleputerState(bytes) {
    const fields = readProtoFields(bytes);
    requiredMessage(fields, 1);
    return protoVarint(fields, 3);
  }

  function decodeEventMeasurement(bytes) {
    const fields = readProtoFields(bytes);
    requiredMessage(fields, 1);
    const type = protoNumber(fields, 2);
    if (type < 1 || type > 6) throw new Error('invalid event type');
    return { accepted: protoVarint(fields, 3), dropped: protoVarint(fields, 4) };
  }

  function decodeEventsPerSyncMeasurement(bytes) {
    const fields = readProtoFields(bytes);
    requiredMessage(fields, 1);
    const count = protoVarint(fields, 2);
    const total = protoVarint(fields, 3);
    const max = protoVarint(fields, 4);
    if (
      (count === 0n && (total !== 0n || max !== 0n)) || max > total ||
      (count !== 0n && total > count * max)
    ) {
      throw new Error('inconsistent events-per-sync measurement');
    }
    return { count, total, max };
  }

  function decodeEffectMeasurement(bytes) {
    const fields = readProtoFields(bytes);
    requiredMessage(fields, 1);
    const type = protoNumber(fields, 2);
    if (type < 1 || type > 3) throw new Error('invalid effect type');
    const attempted = protoVarint(fields, 3);
    const failed = protoVarint(fields, 4);
    if (failed > attempted) throw new Error('inconsistent effect measurement');
    return {
      attempted,
      failed,
      totalNanos: protoVarint(fields, 5),
      maxNanos: protoVarint(fields, 6),
    };
  }

  function requiredMessage(fields, field) {
    const values = fields.get(field) || [];
    if (values.length !== 1 || !(values[0] instanceof Uint8Array)) {
      throw new Error('missing protobuf message');
    }
    return values[0];
  }

  function appendStatsTick(tick) {
    const last = statsTicks.at(-1);
    if (last !== undefined && tick.sequence === last.sequence) return;
    if (last !== undefined && tick.sequence < last.sequence) {
      statsTicks = [tick];
      statsGapObserved = false;
      return;
    }
    if (last !== undefined && tick.sequence > last.sequence + 1n) statsGapObserved = true;
    statsTicks.push(tick);
    if (statsTicks.length > STATS_RETENTION_TICKS) statsTicks.shift();
  }

  function updateLimitsFromStats(tick) {
    if (limits === null || tick.stateBytes > BigInt(Number.MAX_SAFE_INTEGER)) return;
    limits.totalExecutionStateBytes.used = Number(tick.stateBytes);
    limits.totalExecutionStateBytes.updatedAt = new Date(Number(tick.sampledAt)).toISOString();
    renderLimits();
  }

  function summarizeStatsTickGraph(events, effects) {
    const graph = {
      eventsAccepted: 0n,
      eventsDropped: 0n,
      effectsSucceeded: 0n,
      effectsFailed: 0n,
    };
    for (const event of events) {
      graph.eventsAccepted += event.accepted;
      graph.eventsDropped += event.dropped;
    }
    for (const effect of effects) {
      graph.effectsSucceeded += effect.attempted - effect.failed;
      graph.effectsFailed += effect.failed;
    }
    return graph;
  }

  function aggregateStatsTicks() {
    const latest = statsTicks.at(-1);
    const player = {
      eventsPerSyncCount: 0n,
      eventsPerSyncTotal: 0n,
      eventsPerSyncMax: 0n,
      eventsAccepted: 0n,
      eventsDropped: 0n,
      effectsAttempted: 0n,
      effectsFailed: 0n,
      effectsTotalNanos: 0n,
      effectsMaxNanos: 0n,
    };
    for (const tick of statsTicks) {
      for (const event of tick.events) {
        player.eventsAccepted += event.accepted;
        player.eventsDropped += event.dropped;
      }
      for (const sync of tick.syncs) {
        player.eventsPerSyncCount += sync.count;
        player.eventsPerSyncTotal += sync.total;
        player.eventsPerSyncMax = player.eventsPerSyncMax > sync.max
          ? player.eventsPerSyncMax
          : sync.max;
      }
      for (const effect of tick.effects) {
        player.effectsAttempted += effect.attempted;
        player.effectsFailed += effect.failed;
        player.effectsTotalNanos += effect.totalNanos;
        player.effectsMaxNanos = player.effectsMaxNanos > effect.maxNanos
          ? player.effectsMaxNanos
          : effect.maxNanos;
      }
    }
    return { sampledAt: latest.sampledAt, player };
  }

  function readProtoFields(bytes) {
    const fields = new Map();
    let offset = 0;
    while (offset < bytes.length) {
      const key = readVarint(bytes, offset);
      offset = key.next;
      const field = Number(key.value >> 3n);
      const wireType = Number(key.value & 7n);
      if (field === 0) throw new Error('invalid protobuf field');
      let value;
      if (wireType === 0) {
        const decoded = readVarint(bytes, offset);
        offset = decoded.next;
        value = decoded.value;
      } else if (wireType === 2) {
        const length = readVarint(bytes, offset);
        offset = length.next;
        if (length.value > BigInt(bytes.length - offset)) {
          throw new Error('invalid protobuf length');
        }
        value = bytes.slice(offset, offset + Number(length.value));
        offset += Number(length.value);
      } else if (wireType === 1) {
        if (offset + 8 > bytes.length) throw new Error('truncated protobuf');
        value = bytes.slice(offset, offset + 8);
        offset += 8;
      } else if (wireType === 5) {
        if (offset + 4 > bytes.length) throw new Error('truncated protobuf');
        value = bytes.slice(offset, offset + 4);
        offset += 4;
      } else {
        throw new Error('unsupported protobuf field type');
      }
      const values = fields.get(field) || [];
      values.push(value);
      fields.set(field, values);
    }
    return fields;
  }

  function readVarint(bytes, offset) {
    let value = 0n;
    for (let shift = 0n; shift < 70n; shift += 7n) {
      if (offset >= bytes.length) throw new Error('truncated protobuf');
      const byte = bytes[offset++];
      value |= BigInt(byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return { value, next: offset };
    }
    throw new Error('invalid protobuf varint');
  }

  function protoVarint(fields, field) {
    const values = fields.get(field) || [];
    if (values.length > 1 || (values.length === 1 && typeof values[0] !== 'bigint')) {
      throw new Error('invalid protobuf number');
    }
    return values.length === 0 ? 0n : values[0];
  }

  function protoNumber(fields, field) {
    const value = protoVarint(fields, field);
    if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('invalid protobuf number');
    return Number(value);
  }

  function displayError(error) {
    if (error.redirected) {
      return;
    }
    if (error.status === 413) {
      setMessage('This program exceeds your storage quota.', 'error');
    } else if (error.status === 409) {
      setMessage('Another program operation is in progress. Please try again shortly.', 'error');
    } else {
      setMessage(error.message || 'The request failed. Please try again.', 'error');
    }
  }

  async function loadPrograms() {
    operation = 'loading';
    startStates = null;
    render();
    try {
      const response = await request('/playerapi/v1/programs');
      programList = parseProgramList(await response.json());
      if (programList.programs.length === 1) {
        const program = programList.programs[0];
        const startStatesResponse = await request(
          `/playerapi/v1/programs/${encodeURIComponent(program.id)}/startStates`,
        );
        startStates = parseStartStates(await startStatesResponse.json());
      } else {
        startStates = [];
      }
      return true;
    } catch (error) {
      displayError(error);
      return false;
    } finally {
      operation = 'idle';
      render();
    }
  }

  function parseStartStates(body) {
    if (
      !body || !Array.isArray(body.startStates) ||
      body.startStates.some((name) => typeof name !== 'string')
    ) {
      throw makeError('The server returned invalid start states.');
    }
    return body.startStates;
  }

  async function loadPlayerIdentity() {
    operation = 'loading';
    render();
    try {
      const response = await request('/playerapi/v1/whoami');
      const identity = parsePlayerIdentity(await response.json());
      elements.playerProfileName.textContent = identity.playerProfileName;
      return true;
    } catch (error) {
      displayError(error);
      return false;
    } finally {
      operation = 'idle';
      render();
    }
  }

  function selectFile(file) {
    if (operation !== 'idle') {
      return;
    }
    selectedFile = file;
    render();
    uploadProgram();
  }

  function selectDroppedFiles(files) {
    if (files.length !== 1) {
      setMessage('Please choose exactly one program file.', 'error');
      return;
    }
    selectFile(files[0]);
  }

  async function uploadProgram() {
    if (selectedFile === null) {
      setMessage('Choose a program file before uploading.', 'error');
      return;
    }

    const form = new FormData();
    form.append('program', selectedFile, selectedFile.name);

    operation = 'uploading';
    setUploadMessage('Uploading program...');
    setMessage('');
    render();
    try {
      const response = await request('/playerapi/v1/programs', {
        method: 'POST',
        body: form,
      });
      if (response.status !== 200 && response.status !== 201) {
        throw await errorFromResponse(response);
      }
      await response.json();
      selectedFile = null;
      elements.fileInput.value = '';
      const refreshed = await loadPrograms();
      if (refreshed) {
        setUploadMessage('Uploaded!');
        setMessage('Program uploaded and activated successfully.', 'success');
      } else {
        setUploadMessage('Upload completed, but the program list could not be refreshed.');
      }
    } catch (error) {
      displayError(error);
      selectedFile = null;
      elements.fileInput.value = '';
      setUploadMessage('Upload failed. Choose or drop a file to try again.');
    } finally {
      operation = 'idle';
      render();
    }
  }

  async function logout() {
    if (operation !== 'idle') {
      return;
    }

    operation = 'logging-out';
    setMessage('Logging out...');
    render();
    try {
      const response = await request('/playerapi/v1/logout', { method: 'POST' });
      if (response.status !== 204) {
        throw await errorFromResponse(response);
      }
      statsClosingForLogout = true;
      logsClosingForLogout = true;
      statsSocket?.close();
      logsSocket?.close();
      window.location.assign('/auth.html');
    } catch (error) {
      displayError(error);
    } finally {
      operation = 'idle';
      render();
    }
  }

  elements.browseButton.addEventListener('click', () => elements.fileInput.click());
  elements.fileInput.addEventListener('change', () => {
    if (elements.fileInput.files.length === 1) {
      selectFile(elements.fileInput.files[0]);
    }
  });
  elements.activeProgramSection.addEventListener('dragover', (event) => {
    event.preventDefault();
    elements.activeProgramSection.classList.add('drag-over');
  });
  elements.activeProgramSection.addEventListener('dragleave', () => {
    elements.activeProgramSection.classList.remove('drag-over');
  });
  elements.activeProgramSection.addEventListener('drop', (event) => {
    event.preventDefault();
    elements.activeProgramSection.classList.remove('drag-over');
    selectDroppedFiles(event.dataTransfer.files);
  });
  elements.refreshButton.addEventListener('click', () => loadPrograms());
  elements.logoutButton.addEventListener('click', () => logout());
  elements.statsRetryButton.addEventListener('click', () => connectStats());
  elements.logsRetryButton.addEventListener('click', () => connectLogs());
  elements.limitsRetryButton.addEventListener('click', () => loadLimits());
  elements.statsTab.addEventListener('click', () => selectObservabilityTab('stats'));
  elements.logsTab.addEventListener('click', () => selectObservabilityTab('logs'));
  elements.limitsTab.addEventListener('click', () => selectObservabilityTab('limits'));
  const observabilityTabs = ['stats', 'logs', 'limits'];
  for (const tab of [elements.statsTab, elements.logsTab, elements.limitsTab]) {
    tab.addEventListener('keydown', (event) => {
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        event.preventDefault();
        const direction = event.key === 'ArrowLeft' ? -1 : 1;
        const next = (observabilityTabs.indexOf(activeObservabilityTab) + direction +
          observabilityTabs.length) %
          observabilityTabs.length;
        selectObservabilityTab(observabilityTabs[next], true);
      }
    });
  }
  document.querySelectorAll('[data-log-level], [data-log-type]').forEach((input) => {
    input.addEventListener('change', () => {
      renderLogs();
      if (activeObservabilityTab === 'logs' && elements.logsAutoScroll.checked) {
        scrollLogsToBottom();
      }
    });
  });
  elements.logsAutoScroll.addEventListener('change', () => {
    if (elements.logsAutoScroll.checked) scrollLogsToBottom();
  });
  elements.statsActivityGraph.addEventListener('pointermove', updateStatsGraphTooltip);
  elements.statsActivityGraph.addEventListener('pointerleave', hideStatsGraphTooltip);
  window.addEventListener('resize', renderStatsGraphs);
  render();
  loadPlayerIdentity().then((identityLoaded) => {
    if (identityLoaded) {
      loadPrograms();
      loadLimits();
      connectStats();
      connectLogs();
    }
  });
  setInterval(updateStatsStaleness, 1000);
})();
