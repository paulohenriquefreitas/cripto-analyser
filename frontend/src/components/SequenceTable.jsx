export default function SequenceTable({ sequences }) {
  return (
    <section className="panel">
      <div className="section-heading">
        <div>
          <h2>Tabela de sequências</h2>
          <p>Lista das sequências retornadas pela API após aplicação dos filtros.</p>
        </div>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Direction</th>
              <th>Length</th>
              <th>Start Time</th>
              <th>End Time</th>
              <th>Start Index</th>
              <th>End Index</th>
            </tr>
          </thead>
          <tbody>
            {sequences.length === 0 ? (
              <tr>
                <td colSpan="6" className="empty-row">
                  Nenhuma sequência corresponde aos filtros atuais.
                </td>
              </tr>
            ) : (
              sequences.map((sequence) => (
                <tr key={`${sequence.direction}-${sequence.startIndex}-${sequence.endIndex}`}>
                  <td>
                    <span className={`direction-pill ${sequence.direction?.toLowerCase()}`}>
                      {sequence.direction}
                    </span>
                  </td>
                  <td>{sequence.length}</td>
                  <td>{formatDate(sequence.startTime)}</td>
                  <td>{formatDate(sequence.endTime)}</td>
                  <td>{sequence.startIndex}</td>
                  <td>{sequence.endIndex}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function formatDate(value) {
  if (!value) {
    return "-";
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "medium"
  }).format(new Date(value));
}
