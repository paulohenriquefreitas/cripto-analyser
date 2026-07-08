import { CartesianGrid, Legend, ResponsiveContainer, Scatter, ScatterChart, Tooltip, XAxis, YAxis } from "recharts";

export default function RecurrenceCharts({ stats }) {
  return (
    <section className="charts-grid">
      <article className="panel chart-span">
        <div className="section-heading">
          <div>
            <h2>Ocorrências de sequências no tempo</h2>
            <p>
              As ocorrências agora são separadas por mês para manter a leitura útil em recortes
              longos.
            </p>
          </div>
        </div>
        <MonthlyOccurrenceCharts data={stats.byMonthOccurrences} fallbackData={stats.byTopOccurrences} />
      </article>
    </section>
  );
}

function OccurrenceChart({ data }) {
  const bullish = data?.bullish ?? [];
  const bearish = data?.bearish ?? [];

  if (!bullish.length && !bearish.length) {
    return <div className="empty-chart">Sem dados para exibir.</div>;
  }

  return (
    <div className="chart-box chart-box-tall">
      <ResponsiveContainer width="100%" height={360}>
        <ScatterChart margin={{ top: 16, right: 24, bottom: 24, left: 8 }}>
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="rgba(16, 24, 40, 0.08)" />
          <XAxis
            type="number"
            dataKey="timestamp"
            domain={["dataMin", "dataMax"]}
            tick={{ fill: "#556070", fontSize: 12 }}
            tickFormatter={formatAxisDate}
            name="Inicio"
          />
          <YAxis
            type="number"
            dataKey="length"
            allowDecimals={false}
            tick={{ fill: "#556070", fontSize: 12 }}
            name="Tamanho"
          />
          <Tooltip content={<OccurrenceTooltip />} />
          <Legend />
          <Scatter name="Bullish" data={bullish} fill="#0d8a6b" />
          <Scatter name="Bearish" data={bearish} fill="#c94343" />
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  );
}

function MonthlyOccurrenceCharts({ data, fallbackData }) {
  if (!data?.length) {
    return <OccurrenceChart data={fallbackData} />;
  }

  return (
    <div className="month-panels">
      {data.map((month) => (
        <article key={month.key} className="month-panel">
          <div className="month-panel-header">
            <strong>{month.label}</strong>
            <span>
              {month.displayedCount} de {month.totalCount} sequências
            </span>
          </div>
          <OccurrenceChart data={month.data} />
        </article>
      ))}
    </div>
  );
}

function OccurrenceTooltip({ active, payload }) {
  if (!active || !payload?.length) {
    return null;
  }

  const point = payload[0]?.payload;

  if (!point) {
    return null;
  }

  return (
    <div className="chart-tooltip">
      <strong>{point.direction === "BULLISH" ? "Bullish" : "Bearish"}</strong>
      <div>Dia: {point.dayLabel}</div>
      <div>Inicio: {point.startHourLabel}</div>
      <div>Fim: {point.endHourLabel}</div>
      <div>Sequência: {point.length} candles</div>
    </div>
  );
}

function formatAxisDate(value) {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(new Date(value));
}
