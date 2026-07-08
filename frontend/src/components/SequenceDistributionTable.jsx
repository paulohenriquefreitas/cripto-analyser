export default function SequenceDistributionTable({ analysis }) {
  const rows = buildRows(analysis);

  return (
    <section className="panel">
      <div className="section-heading">
        <div>
          <h2>Repetição por tamanho de sequência</h2>
          <p>Mostra quantas vezes cada tamanho de sequência apareceu no recorte analisado.</p>
        </div>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Sequência</th>
              <th>Repetições bullish</th>
              <th>Repetições bearish</th>
              <th>Total</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan="4" className="empty-row">
                  Nenhuma sequência disponível para distribuição.
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr key={row.length}>
                  <td>{row.length} candle{row.length > 1 ? "s" : ""}</td>
                  <td>{formatNumber(row.bullish)}</td>
                  <td>{formatNumber(row.bearish)}</td>
                  <td>{formatNumber(row.total)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function buildRows(analysis) {
  const bullishDistribution = analysis?.bullishSequenceDistribution ?? {};
  const bearishDistribution = analysis?.bearishSequenceDistribution ?? {};
  const lengths = new Set([
    ...Object.keys(bullishDistribution).map(Number),
    ...Object.keys(bearishDistribution).map(Number)
  ]);

  return [...lengths]
    .filter((length) => Number.isFinite(length))
    .sort((left, right) => left - right)
    .map((length) => {
      const bullish = Number(bullishDistribution[length] ?? bullishDistribution[String(length)] ?? 0);
      const bearish = Number(bearishDistribution[length] ?? bearishDistribution[String(length)] ?? 0);

      return {
        length,
        bullish,
        bearish,
        total: bullish + bearish
      };
    });
}

function formatNumber(value) {
  return new Intl.NumberFormat("pt-BR").format(Number(value ?? 0));
}
