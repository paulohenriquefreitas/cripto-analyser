import { buildEmptyAnalysis, normalizeAnalysisPayload } from "../utils/sequenceStats";

export async function fetchSequenceAnalysis(query) {
  const baseUrl = normalizeBaseUrl(query.baseUrl);
  const endpointPath = normalizeEndpointPath(query.endpointPath);
  const interval = normalizeText(query.interval, "1m");
  const params = new URLSearchParams({
    symbol: normalizeText(query.symbol, "BTCUSDT"),
    interval,
    requestedCandles: normalizeText(query.requestedCandles, "1440")
  });

  const requestUrl = `${baseUrl}${endpointPath}?${params.toString()}`;
  let response;
  const headers = {};

  if (normalizeOptionalText(query.loxCookie)) {
    headers["X-Lox-Cookie"] = normalizeOptionalText(query.loxCookie);
  }

  try {
    response = await fetch(requestUrl, { headers });
  } catch (error) {
    throw new Error(
      "Falha de rede ao acessar a API. Verifique se o backend esta rodando em http://localhost:8080 e se o frontend foi iniciado pelo Vite."
    );
  }

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const rawPayload = await response.json();

  return {
    requestUrl,
    payload: normalizeAnalysisPayload(rawPayload ?? buildEmptyAnalysis())
  };
}

function normalizeBaseUrl(baseUrl) {
  const normalized = String(baseUrl ?? "").trim();
  return normalized.endsWith("/") ? normalized.slice(0, -1) : normalized;
}

function normalizeEndpointPath(endpointPath) {
  const normalized = normalizeText(endpointPath, "/analysis/sumarry");
  return normalized.startsWith("/") ? normalized : `/${normalized}`;
}

function normalizeText(value, fallback) {
  return String(value ?? "").trim() || fallback;
}

function normalizeOptionalText(value) {
  return String(value ?? "").trim();
}
