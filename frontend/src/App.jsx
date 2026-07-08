import { useEffect, useState } from "react";
import { fetchSequenceAnalysis } from "./api/candleApi";
import Filters from "./components/Filters";
import RecurrenceCharts from "./components/RecurrenceCharts";
import SequenceDistributionTable from "./components/SequenceDistributionTable";
import SummaryCards from "./components/SummaryCards";
import {
  applySequenceFilters,
  buildEmptyAnalysis,
  buildRecurrenceStats
} from "./utils/sequenceStats";

const initialQuery = {
  baseUrl: "",
  endpointPath: "/analysis/sumarry",
  symbol: "BTCUSDT",
  interval: "1m",
  requestedCandles: "1440",
  loxCookie: ""
};

const initialFilters = {
  direction: "ALL",
  minLength: "1",
  weekDay: "ALL",
  hour: "ALL"
};

export default function App() {
  const [query, setQuery] = useState(initialQuery);
  const [filters, setFilters] = useState(initialFilters);
  const [analysis, setAnalysis] = useState(buildEmptyAnalysis());
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [lastRequestUrl, setLastRequestUrl] = useState("");

  useEffect(() => {
    void handleSearch();
  }, []);

  async function handleSearch() {
    setLoading(true);
    setError("");

    try {
      const response = await fetchSequenceAnalysis(query);
      setAnalysis(response.payload);
      setLastRequestUrl(response.requestUrl);
    } catch (requestError) {
      setAnalysis(buildEmptyAnalysis());
      setError(requestError.message);
      setLastRequestUrl("");
    } finally {
      setLoading(false);
    }
  }

  const filteredSequences = applySequenceFilters(analysis.sequences, filters);
  const recurrenceStats = buildRecurrenceStats(filteredSequences);

  return (
    <div className="app-shell">
      <>
      <header className="hero">
        <div>
          <p className="eyebrow">React + Vite</p>
          <h1>Sequências de candles com recorrência por dia e horário.</h1>
          <p className="hero-copy">
            O frontend consome as sequências calculadas a partir dos candles salvos no banco e
            monta visões rápidas de frequência para BULLISH e BEARISH.
          </p>
        </div>
        <div className="hero-panel">
          <span className="hero-label">Endpoint atual</span>
          <code>{lastRequestUrl || "Nenhuma consulta executada."}</code>
          <span className={`hero-status ${loading ? "loading" : "ready"}`}>
            {loading ? "Buscando dados..." : "Pronto"}
          </span>
        </div>
      </header>

      <Filters
        query={query}
        filters={filters}
        loading={loading}
        onQueryChange={setQuery}
        onFiltersChange={setFilters}
        onSearch={handleSearch}
      />

      {error ? <div className="error-banner">Erro ao consultar API: {error}</div> : null}

      <SummaryCards analysis={analysis} filteredCount={filteredSequences.length} />

      <SequenceDistributionTable analysis={analysis} />

      <RecurrenceCharts stats={recurrenceStats} />
      </>
    </div>
  );
}
