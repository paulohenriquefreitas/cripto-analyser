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

form.addEventListener("submit", async (event) => {
    event.preventDefault();

    const formData = new FormData(form);
    const symbol = normalizeText(formData.get("symbol"), "BTCUSDT");
    const interval = normalizeText(formData.get("interval"), "1m");
    const requestedCandles = normalizeNumber(formData.get("requestedCandles"), 1440);
    const baseUrl = normalizeBaseUrl(formData.get("baseUrl"));

    const params = new URLSearchParams({
        symbol,
        interval,
        requestedCandles: String(requestedCandles)
    });

    const endpoint = `${baseUrl}/analysis/sumarry?${params.toString()}`;

    setLoading(true);
    setStatus(`Consultando ${endpoint}`);

    try {
        const response = await fetch(endpoint);

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
