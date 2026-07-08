const cardItems = [
  { key: "totalCandles", label: "Total de candles" },
  { key: "totalSequences", label: "Total de sequências" },
  { key: "largestBullishSequence", label: "Maior sequência bullish", tone: "bullish" },
  { key: "largestBearishSequence", label: "Maior sequência bearish", tone: "bearish" },
  { key: "bullishCandles", label: "Total bullish", tone: "bullish" },
  { key: "bearishCandles", label: "Total bearish", tone: "bearish" },
  { key: "dojiCandles", label: "Total doji", tone: "neutral" }
];

export default function SummaryCards({ analysis, filteredCount }) {
  return (
    <section className="summary-section">
      <div className="summary-header">
        <div>
          <h2>Resumo</h2>
          <p>Visão geral da resposta da API e da quantidade de sequências filtradas.</p>
        </div>
        <div className="summary-pill">Sequências visíveis: {filteredCount}</div>
      </div>

      <div className="summary-grid">
        {cardItems.map((item) => (
          <article key={item.key} className={`summary-card ${item.tone ?? ""}`}>
            <span>{item.label}</span>
            <strong>{formatNumber(analysis[item.key])}</strong>
          </article>
        ))}
      </div>
    </section>
  );
}

function formatNumber(value) {
  return new Intl.NumberFormat("pt-BR").format(Number(value ?? 0));
}
