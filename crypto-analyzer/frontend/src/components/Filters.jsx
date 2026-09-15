const directionOptions = ["ALL", "BULLISH", "BEARISH"];
const dayOptions = [
  "ALL",
  "SUNDAY",
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY"
];
const intervalOptions = ["1m", "5m", "15m", "1h", "4h", "1d"];
const assetOptions = ["BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT"];

export default function Filters({
  query,
  filters,
  loading,
  onQueryChange,
  onFiltersChange,
  onSearch
}) {
  function handleSubmit(event) {
    event.preventDefault();
    void onSearch();
  }

  function handleQueryFieldChange(event) {
    const { name, value } = event.target;
    onQueryChange((current) => ({ ...current, [name]: value }));
  }

  function handleFilterFieldChange(event) {
    const { name, value } = event.target;
    onFiltersChange((current) => ({ ...current, [name]: value }));
  }

  return (
    <section className="panel">
      <div className="section-heading">
        <div>
          <h2>Consulta e filtros</h2>
          <p>
            Ajuste a consulta da Lox Broker e refine as sequências por direção, tamanho, dia e
            hora. Para janelas longas, aumente `Length mínimo` para destacar as sequências grandes.
          </p>
        </div>
      </div>

      <form className="filters-layout" onSubmit={handleSubmit}>
        <label>
          <span>Base URL</span>
          <input
            name="baseUrl"
            value={query.baseUrl}
            onChange={handleQueryFieldChange}
            placeholder="vazio = proxy local do Vite"
          />
        </label>

        <label>
          <span>Endpoint</span>
          <input
            name="endpointPath"
            value={query.endpointPath}
            onChange={handleQueryFieldChange}
            placeholder="/analysis/sumarry"
          />
        </label>

        <label>
          <span>Ativo</span>
          <select name="symbol" value={query.symbol} onChange={handleQueryFieldChange}>
            {assetOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <label>
          <span>Interval</span>
          <select name="interval" value={query.interval} onChange={handleQueryFieldChange}>
            {intervalOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <label>
          <span>Requested candles</span>
          <input
            name="requestedCandles"
            type="number"
            min="1"
            value={query.requestedCandles}
            onChange={handleQueryFieldChange}
          />
        </label>

        <label className="analysis-wide-field">
          <span>Lox Cookie</span>
          <input
            name="loxCookie"
            value={query.loxCookie}
            onChange={handleQueryFieldChange}
            placeholder="ex: sessionid=...; other_cookie=..."
          />
        </label>

        <button type="submit" disabled={loading}>
          {loading ? "Buscando..." : "Buscar"}
        </button>
      </form>

      <div className="filters-grid">
        <label>
          <span>Direction</span>
          <select name="direction" value={filters.direction} onChange={handleFilterFieldChange}>
            {directionOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <label>
          <span>Length mínimo</span>
          <input
            name="minLength"
            type="number"
            min="1"
            value={filters.minLength}
            onChange={handleFilterFieldChange}
          />
        </label>

        <label>
          <span>Dia da semana</span>
          <select name="weekDay" value={filters.weekDay} onChange={handleFilterFieldChange}>
            {dayOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <label>
          <span>Hora do dia</span>
          <input
            name="hour"
            type="number"
            min="0"
            max="23"
            value={filters.hour === "ALL" ? "" : filters.hour}
            onChange={(event) =>
              onFiltersChange((current) => ({
                ...current,
                hour: event.target.value === "" ? "ALL" : event.target.value
              }))
            }
            placeholder="ALL"
          />
        </label>
      </div>
    </section>
  );
}
