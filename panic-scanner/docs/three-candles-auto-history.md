# Histórico automático do Replay de three-candles

Integração isolada em `ThreeCandleHistoryLoader`, usando o serviço existente
`Mt5ProcessClient.readCandles(symbol, "M5", from, to)` e `mt5_reader.py`.
Nenhum serviço compartilhado ou stream MT5 foi alterado/iniciado.

## Carregamento

1. Validar símbolo exato e data histórica encerrada.
2. Reutilizar o cache M5 validado ou o NDJSON existente com aquecimento suficiente.
3. Quando ausente, incompleto ou inválido, consultar M5 dos 28 dias anteriores até
   o fim da data selecionada, pelo mesmo símbolo, sem fallback para outro contrato.
4. Validar cobertura, timestamps M5, ordem, OHLC, volumes e três pregões anteriores
   com aquecimento H1. Ausência do dia gera 404; falta de contexto suficiente gera
   422; falhas de comunicação trazem o erro do serviço Python em `detail`.
5. Persistir em `historical-data/{symbol}/three-candles-m5/{date}.json`, com versão,
   identidade da série, M5, intervalo solicitado, instante de recuperação e todos
   os candles originais. Manifesto SHA-256 ao lado; gravações por arquivo temporário
   e rename. Cache corrompido/incompleto não é reutilizado. Os NDJSON com ticks
   permanecem separados.
6. Preparar o plano H1 antes da abertura. O cursor só entrega candles fechados
   com disponibilidade até o instante simulado. Os três pregões anteriores são
   contexto conhecido; aquecimento adicional fica interno. NEXT/PLAY/RESET não
   consultam MT5.

O limite de busca é 28 dias corridos. Não há preenchimento de lacunas. Um intervalo
solicitado não certifica que o broker forneceu cada negociação; falta de três
sessões ou de aquecimento é reportada, sem salvar o conjunto como pronto.

`WIN$N` é identificado como `MT5_CONTINUOUS_UNMAPPED`: a série contínua fornecida
pelo terminal, sem inferência de contrato subjacente nem ajuste de preços.
Contratos com vencimento usam `EXACT_CONTRACT`, em diretórios próprios.

## Validação de 16/10/2026

Consultas finitas ao terminal real, sem streaming:

- WIN$N, M5, 18/09 a 16/10: 2.262 candles.
- Pregões de referência: 13/10, 14/10 e 15/10; 12/10 não forneceu sessão.
- Persistência e releitura com outro loader: OHLC/timestamps idênticos; uma única
  chamada do cliente no teste de cache vazio e segunda execução.
- H1 nativo: 200 candles no intervalo; nove pivôs pré-abertura, sem diferenças
  de preço, origem ou confirmação na comparação com o detector estrito.
- WINZ26, 13/10 a 16/10: 451 candles; 113 OHLC divergentes de WIN$N em timestamps
  coincidentes. As séries não são intercambiáveis.
- Testes cobrem corrupção do cache, OHLC inválido, data sem candles, contexto
  insuficiente, falha de conexão/símbolo, isolamento de contrato, progressão sem
  lookahead e endpoints de preparação/criação/avanço.

Os dados nativos de validação e o preview ficam em `panic-scanner/target/`.
O preview visual usa dados reais exportados e API interceptada; não representa
um teste contra o backend em execução. Para disponibilizar os novos endpoints,
reiniciar o backend com o código compilado desta branch.
