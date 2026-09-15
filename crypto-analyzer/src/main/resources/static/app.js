const form = document.getElementById("analysis-form");
const submitButton = document.getElementById("submit-button");
const statusMessage = document.getElementById("status-message");

const totalCandles = document.getElementById("total-candles");
const totalSequences = document.getElementById("total-sequences");
const largestBullish = document.getElementById("largest-bullish");
const largestBearish = document.getElementById("largest-bearish");
const bullishCount = document.getElementById("bullish-count");
const bearishCount = document.getElementById("bearish-count");

const bullishDistribution = document.getElementById("bullish-distribution");
const bearishDistribution = document.getElementById("bearish-distribution");
const sequencesBody = document.getElementById("sequences-body");

const alertInterval = document.getElementById("alert-interval");
const alertWindow = document.getElementById("alert-window");
const startAlertMonitor = document.getElementById("start-alert-monitor");
const stopAlertMonitor = document.getElementById("stop-alert-monitor");
const enableBrowserNotifications = document.getElementById("enable-browser-notifications");
const enableAlertSound = document.getElementById("enable-alert-sound");
const alertMonitorState = document.getElementById("alert-monitor-state");
const activeAlerts = document.getElementById("active-alerts");
const alertToastStack = document.getElementById("alert-toast-stack");

const seenAlertKeys = new Set();
let alertMonitorTimer = null;
let alertAudioContext = null;
let soundEnabled = false;

form.addEventListener("submit", async (event) => {
    event.preventDefault();

    const formData = new FormData(form);
    const symbol = normalizeText(formData.get("symbol"), "BTCUSDT");
    const interval = normalizeText(formData.get("interval"), "1m");
    const requestedCandles = normalizeNumber(formData.get("requestedCandles"), 1440);
    const baseUrl = normalizeBaseUrl(formData.get("baseUrl"));
    const loxCookie = normalizeText(formData.get("loxCookie"), "");

    const params = new URLSearchParams({
        symbol,
        interval,
        requestedCandles: String(requestedCandles)
    });

    const endpoint = `${baseUrl}/analysis/sumarry?${params.toString()}`;

    setLoading(true);
    setStatus(`Consultando ${endpoint}`);

    try {
        const response = await fetch(endpoint, buildRequestOptions(loxCookie));

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const payload = await response.json();
        renderSummary(payload);
        renderDistribution(bullishDistribution, payload.bullishSequenceDistribution, "bullish");
        renderDistribution(bearishDistribution, payload.bearishSequenceDistribution, "bearish");
        renderSequences(payload.sequences || []);
        setStatus(`Consulta concluida para ${symbol} em ${interval}.`);
    } catch (error) {
        clearSummary();
        renderErrorState(error);
        setStatus(`Falha ao consultar endpoint: ${error.message}`);
    } finally {
        setLoading(false);
    }
});

startAlertMonitor.addEventListener("click", () => {
    if (alertMonitorTimer) {
        return;
    }

    startAlertMonitor.disabled = true;
    stopAlertMonitor.disabled = false;
    alertMonitorState.textContent = "monitorando";
    pollReversalAlerts();
    alertMonitorTimer = window.setInterval(pollReversalAlerts, 5000);
});

stopAlertMonitor.addEventListener("click", () => {
    window.clearInterval(alertMonitorTimer);
    alertMonitorTimer = null;
    startAlertMonitor.disabled = false;
    stopAlertMonitor.disabled = true;
    alertMonitorState.textContent = "parado";
});

enableBrowserNotifications.addEventListener("click", async () => {
    if (!("Notification" in window)) {
        showToast("Notificacoes indisponiveis", "Este navegador nao suporta notificacoes do sistema.");
        return;
    }

    const permission = await Notification.requestPermission();
    enableBrowserNotifications.textContent = permission === "granted"
        ? "Notificacoes ativas"
        : "Notificacao bloqueada";
});

enableAlertSound.addEventListener("click", () => {
    const AudioContext = window.AudioContext || window.webkitAudioContext;

    if (!AudioContext) {
        showToast("Som indisponivel", "Este navegador nao suporta Web Audio.");
        return;
    }

    alertAudioContext = alertAudioContext || new AudioContext();
    soundEnabled = true;
    enableAlertSound.textContent = "Som ativo";
    playAlertSound();
});

function renderSummary(payload) {
    totalCandles.textContent = safeNumber(payload.totalCandles);
    totalSequences.textContent = safeNumber(payload.totalSequences);
    largestBullish.textContent = safeNumber(payload.largestBullishSequence);
    largestBearish.textContent = safeNumber(payload.largestBearishSequence);
    bullishCount.textContent = `${safeNumber(payload.bullishCandles)} candles`;
    bearishCount.textContent = `${safeNumber(payload.bearishCandles)} candles`;
}

function clearSummary() {
    totalCandles.textContent = "-";
    totalSequences.textContent = "-";
    largestBullish.textContent = "-";
    largestBearish.textContent = "-";
    bullishCount.textContent = "0 candles";
    bearishCount.textContent = "0 candles";
}

function renderDistribution(container, distribution, tone) {
    const entries = Object.entries(distribution || {})
        .map(([length, count]) => [Number(length), Number(count)])
        .sort((left, right) => left[0] - right[0]);

    if (entries.length === 0) {
        container.className = "distribution-list empty-state";
        container.innerHTML = "Nenhuma sequencia nessa direcao.";
        return;
    }

    const maxCount = Math.max(...entries.map(([, count]) => count), 1);
    container.className = "distribution-list";
    container.innerHTML = entries.map(([length, count]) => `
        <div class="distribution-item">
            <strong>${length} candles</strong>
            <div class="distribution-track">
                <div class="distribution-bar ${tone}" style="width:${(count / maxCount) * 100}%"></div>
            </div>
            <span class="distribution-value">${count}</span>
        </div>
    `).join("");
}

function renderSequences(sequences) {
    if (!sequences.length) {
        sequencesBody.innerHTML = `
            <tr>
                <td colspan="7" class="empty-row">Nenhuma sequencia retornada.</td>
            </tr>
        `;
        return;
    }

    sequencesBody.innerHTML = sequences.map((sequence, index) => `
        <tr>
            <td>${index + 1}</td>
            <td>${renderDirection(sequence.direction)}</td>
            <td>${safeNumber(sequence.length)}</td>
            <td>${safeNumber(sequence.startIndex)}</td>
            <td>${safeNumber(sequence.endIndex)}</td>
            <td>${formatDate(sequence.startTime)}</td>
            <td>${formatDate(sequence.endTime)}</td>
        </tr>
    `).join("");
}

function renderDirection(direction) {
    const normalized = normalizeText(direction, "DOJI");
    const tone = normalized === "BULLISH" ? "bullish" : normalized === "BEARISH" ? "bearish" : "doji";
    return `<span class="pill ${tone}">${normalized}</span>`;
}

function renderErrorState(error) {
    bullishDistribution.className = "distribution-list empty-state";
    bearishDistribution.className = "distribution-list empty-state";
    bullishDistribution.textContent = "Nao foi possivel montar a distribuicao.";
    bearishDistribution.textContent = "Nao foi possivel montar a distribuicao.";
    sequencesBody.innerHTML = `
        <tr>
            <td colspan="7" class="empty-row">Erro: ${escapeHtml(error.message)}</td>
        </tr>
    `;
}

function setLoading(isLoading) {
    submitButton.disabled = isLoading;
    submitButton.textContent = isLoading ? "Consultando..." : "Analisar";
}

function setStatus(message) {
    statusMessage.textContent = message;
}

function normalizeText(value, fallback) {
    return String(value || "").trim() || fallback;
}

function normalizeNumber(value, fallback) {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function normalizeBaseUrl(value) {
    const normalized = String(value || "").trim();
    return normalized.endsWith("/") ? normalized.slice(0, -1) : normalized;
}

function safeNumber(value) {
    return value ?? "-";
}

function formatDate(value) {
    if (!value) {
        return "-";
    }

    return new Date(value).toLocaleString("pt-BR");
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

async function pollReversalAlerts() {
    const checkedAt = new Date();
    const formData = new FormData(form);
    const baseUrl = normalizeBaseUrl(formData.get("baseUrl"));
    const loxCookie = normalizeText(formData.get("loxCookie"), "");
    const params = new URLSearchParams({
        interval: normalizeText(alertInterval.value, "1m"),
        alertWindowSeconds: String(normalizeNumber(alertWindow.value, 20))
    });
    const endpoint = `${baseUrl}/alerts/reversal?${params.toString()}`;

    try {
        const response = await fetch(endpoint, buildRequestOptions(loxCookie));

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const alerts = await response.json();
        renderActiveAlerts(alerts);
        alertMonitorState.textContent = `monitorando ${checkedAt.toLocaleTimeString("pt-BR")}`;

        for (const alert of alerts) {
            if (seenAlertKeys.has(alert.alertKey)) {
                continue;
            }

            seenAlertKeys.add(alert.alertKey);
            notifyAlert(alert);
        }
    } catch (error) {
        activeAlerts.className = "active-alerts empty-state";
        activeAlerts.textContent = `Falha no monitor: ${error.message}`;
        alertMonitorState.textContent = "erro";
    }
}

function buildRequestOptions(loxCookie) {
    if (!loxCookie) {
        return {};
    }

    return {
        headers: {
            "X-Lox-Cookie": loxCookie
        }
    };
}

function renderActiveAlerts(alerts) {
    if (!alerts.length) {
        activeAlerts.className = "active-alerts empty-state";
        activeAlerts.textContent = "Nenhum alerta ativo.";
        return;
    }

    activeAlerts.className = "active-alerts";
    activeAlerts.innerHTML = alerts.map((alert) => {
        const tone = normalizeText(alert.direction, "DOJI").toLowerCase();
        const candleDetails = renderSignalCandles(alert.signalCandles || []);
        return `
            <article class="alert-card ${tone}">
                <strong>${escapeHtml(alert.message)}</strong>
                <span class="alert-meta">${escapeHtml(alert.symbol)} | ${escapeHtml(alert.suggestedAction)} | sinal ha ${safeNumber(alert.secondsSinceSignal)}s | ${formatDate(alert.signalCandleCloseTime)}</span>
                ${candleDetails}
            </article>
        `;
    }).join("");
}

function renderSignalCandles(candles) {
    if (!candles.length) {
        return "";
    }

    return `
        <div class="alert-candles">
            ${candles.map((candle, index) => `
                <span>
                    C${index + 1}: ${escapeHtml(candle.direction)}
                    ${formatPrice(candle.open)} -> ${formatPrice(candle.close)}
                </span>
            `).join("")}
        </div>
    `;
}

function notifyAlert(alert) {
    const title = `Alerta ${alert.assetName || alert.symbol}`;
    const body = buildAlertBody(alert);

    showToast(title, body);
    playAlertSound();

    if ("Notification" in window && Notification.permission === "granted") {
        new Notification(title, { body });
    }
}

function buildAlertBody(alert) {
    const closeTime = formatDate(alert.signalCandleCloseTime);
    const direction = normalizeText(alert.direction, "-");
    const message = alert.message || "Padrao de reversao detectado.";

    return `${message} Direcao: ${direction}. Sinal: ${closeTime}.`;
}

function formatPrice(value) {
    const parsed = Number(value);

    if (!Number.isFinite(parsed)) {
        return "-";
    }

    return parsed.toLocaleString("pt-BR", {
        minimumFractionDigits: 2,
        maximumFractionDigits: 6
    });
}

function showToast(title, message) {
    const toast = document.createElement("div");
    toast.className = "alert-toast";
    toast.innerHTML = `
        <strong>${escapeHtml(title)}</strong>
        <span>${escapeHtml(message)}</span>
    `;

    alertToastStack.appendChild(toast);
    window.setTimeout(() => toast.remove(), 8000);
}

function playAlertSound() {
    if (!soundEnabled || !alertAudioContext) {
        return;
    }

    const startAt = alertAudioContext.currentTime;
    const tones = [
        { frequency: 740, offset: 0 },
        { frequency: 1040, offset: 0.18 },
        { frequency: 620, offset: 0.36 }
    ];

    for (const tone of tones) {
        playTone(tone.frequency, startAt + tone.offset, 0.12);
    }
}

function playTone(frequency, startAt, duration) {
    const oscillator = alertAudioContext.createOscillator();
    const gain = alertAudioContext.createGain();

    oscillator.type = "square";
    oscillator.frequency.setValueAtTime(frequency, startAt);
    gain.gain.setValueAtTime(0.001, startAt);
    gain.gain.exponentialRampToValueAtTime(0.18, startAt + 0.015);
    gain.gain.exponentialRampToValueAtTime(0.001, startAt + duration);

    oscillator.connect(gain);
    gain.connect(alertAudioContext.destination);
    oscillator.start(startAt);
    oscillator.stop(startAt + duration + 0.02);
}
