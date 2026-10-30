# Padrão de 3 Candles é infraestrutura isolada

Branch: `feat/three-candles-foundation`. Página: `/three-candles`.
Nenhuma condição Alvo/Nega/Testa, ordem, stop, alvo ou sinal operacional foi implementada.

## Inspeção e reutilização

- Dados: `GetMt5CandlesUseCase`, `Mt5ProcessClient`, `Mt5Symbol` e `Mt5Candle`.
- Python existente: `mt5_reader.py` para histórico recente; `mt5_tick_stream.py`
  mantém o stream compartilhado. Nenhum script ou processo persistente foi adicionado.
- `Ta4jMt5Sma9Adapter.candleSnapshot` já contém OHLC atualizado por todos os ticks
  recebidos pelo backend. O novo getter `Mt5TickWebSocketHandler.latestAvailableCandle`
  expõe uma fotografia imutével do tick e desse candle sob o lock existente.
- `IntrabarCandleSnapshot` é reutilizado no domínio. Não há novo modelo OHLC Java.
- Topos/fundos existentes: `mt5SwingPivots.ts` e `StructureReactionScanner`, pivôs
  estritos 2+2. Não executados nem desenhados pela nova página.
- Replay de ticks existente: `M5ReplaySession`, `Mt5HistoricalPriceSource`, Setup91
  e pesquisas. Não são executados nesta estratégia para evitar efeitos operacionais.
- Histórico local: caminho e manifestos do `ReplayHistoryProvider`, Header e barras
  oficiais do `Mt5HistoricalPriceSource`. Sem `ensureAvailable` e sem exportação automática.
- UI: MUI, Axios, Zod, ChartSink e Mt5CandlestickChart. A opção `candlesOnly`, cujo
  padrão é false, desliga indicadores, legendas, pivôs e setas somente nesta página.
  Não foi criada biblioteca, dependência ou módulo commons.

## Packages

`br.com.bauzin.market.panic.panicscanner.strategy.threecandles`:

- `domain/CandleUpdate`: candle reutilizado + período, disponibilidade e fechamento.
- `application/CandlePipeline`: upsert ordenado, deduplicação e ponto único de processamento.
- `application/ThreeCandlePatternDetector`: porta para futuras regras; nenhuma implementação especulativa.
- `application/ThreeCandleMarketFeed`: contrato comum aos feeds Live/Replay.
- `application/CandleAggregation`: M15 somente de trincas M5 consecutivas completas;
  distingue também grupos ainda em formação. Nunca preenche barras ausentes.
- `infrastructure/ThreeCandleLive`: consome o stream existente e reconcilia candles oficiais.
- `infrastructure/ThreeCandleHistory`: catálogo local, validação SHA-256 e leitura de barras oficiais.
- `replay/ThreeCandleReplay`: motor puro, sem React, Spring, processo MT5 ou relógio global.
- `api/ThreeCandlesController`: configuração, snapshot live e sessões/comandos replay.

Ambos os modos chamam `CandlePipeline.accept(CandleUpdate)` antes de expor os dados.
O futuro detector pode assinar essa pipeline e não recebe a origem. Reset ou recuperação
após desconexão inicia um novo estado causal; regras futuras deverão reaquecer esse estado.

## API

- `GET /api/three-candles/config`: contrato live, períodos 5/15, zona e sessões locais.
- `GET /api/three-candles/live?symbol=WINZ26&timeframeMinutes=5`: snapshot causal atual.
- `POST /api/three-candles/replays`:
  `{ "symbol":"WIN$N", "date":"2026-10-20", "startTime":"09:00", "timeframeMinutes":5, "timeBasis":"MT5_WALL_CLOCK" }`.
- `GET /api/three-candles/replays/{id}`: somente candles já disponibilizados.
- `POST /api/three-candles/replays/{id}/commands`:
  `{ "action":"PLAY|PAUSE|NEXT|RESET|SPEED", "speed":2 }` (speed somente para SPEED).
- `DELETE /api/three-candles/replays/{id}`: libera sessão.

Snapshot: symbol, timeframeMinutes, status e candles; cada item contém candle
(symbol, bucketStartTimeMsc, OHLC), closed e availableAtMsc. Replay inclui id/speed;
live inclui lastTickMsc. Dados futuros ficam privados ao motor e não são serializados.
Até 64 sessões em memória; sessões antigas são descartadas ao abrir novas (30 minutos).

## Fluxos

Live: consulta inicial pelo serviéo existente, atualizações do OHLC compartilhado,
reconciliação oficial no novo bucket M5 ou reconexão. O frontend consulta snapshots a
cada segundo e atualiza/apende a série sem recriar o gráfico. Não há outro socket ou
processo Python de streaming. A consulta inicial/rollover reutiliza o leitor Python
já existente: não significa que seja uma fonte sem nenhuma chamada pontual ao MT5.
Perda de stream expõe UNAVAILABLE e mantém candles. Falhas de consulta preservam a
última imagem e são repetidas com intervalo, sem inventar barras.

Replay: usa somente arquivos locais COMPLETE com hash correspondente e barras oficiais.
O arquivo de ticks é lido apenas para validar seu hash; OHLC é extraído do header.
A criação verifica o arquivo, sem alterá-lo. Candles aparecem somente após seu fechamento.
Reiniciar restaura o recorte até o instante escolhido. Play em 1x libera um candle por
segundo; 2x/5x/10x aceleram essa cadência. Não é simulação temporal intrabar. Next pausa
e libera exatamente o próximo candle. Reset cria nova pipeline e não retém candidatos.

## Convenção temporal e limites

Timestamps numéricos originais são preservados. A página oferece duas referências:
`MT5_WALL_CLOCK` (padrão) mostra os campos civis do terminal sem conversão de fuso;
UTC é usado somente como decodificador desses campos numéricos, sem afirmar que
o timestamp represente um instante UTC real. A hora inicial usa a mesma escala.
`UTC_INSTANT` interpreta os números como instantes UTC, exibe em
America/Sao_Paulo e converte a hora inicial de Brasília para essa escala.
Nenhum valor de candle é reescrito. A equivalência entre o relógio do terminal
e o UTC real continua exigindo validação independente.

O arquivo WIN$N de 20/10/2026 começa com candle oficial bruto 09:00. A conversão
incondicional para Brasília o exibia como 06:00 e fazia a seleção das 09:00 usar
12:00 na escala bruta. A seleção explícita de referência corrige tanto a
visualização quanto o recorte, sem somar três horas aos dados.

Live permite somente o contrato configurado pelo backend (hoje WINZ26), pois o stream
existente é de um só ativo. Replay permite trocar entre contratos realmente presentes
no catálogo, sem recarregar a página. M1 não pode ser reconstruído de M5; portanto
períodos disponíveis nesta etapa são M5 e M15. Contratos sem histórico retornam 404;
manifesto INCOMPLETE não é carregado. Lacunas permanecem lacunas e a UI informa sua
quantidade (inclusive intervalos noturnos). M15 incompleto/sem continuidade é omitido.

O catálogo segue o layout existente `{symbol}/{date}.ndjson`; templates customizados
sem esses diretórios requerem um adaptador de catálogo. Manifestos descrevem sessões
completas, mas o replay de candles não representa o caminho intrabar nem garante
cobertura de todos os negócios. Não existe DOM, agressão ou Volume Profile neste módulo.

## Validação

Testes Java cobrem pipeline comum, confirmação/fechamento, ordem, duplicação, isolamento
de ativo/período, agregação, play/pause/next/reset/end, SHA-256, erros e endpoints MockMvc.
Testes frontend executam o componente real de gráfico com API gráfica substituída,
preservação do modo antigo, ausência de indicadores/overlays no modo limpo e updates
incrementais/reset. Nenhum teste envia ordens ou inicia um terminal MT5.

Smoke offline opcional com o dataset real:

```powershell
$env:JAVA_HOME='C:/Users/paulo/.jdks/corretto-21.0.4'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
mvn -pl panic-scanner -am test '-Dtest=ThreeCandleCoreTest,ThreeCandleAdaptersTest,ThreeCandleLocalHistorySmokeTest' '-Dthreecandles.historyRoot=C:/Users/paulo/project/cripto-analyser/historical-data' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Dataset WIN$N de 20/10/2026: 1113 barras M5 (incluindo warmup), 1036 disponíveis no
recorte inicial das 09h; 363 barras M15, 338 no recorte inicial. Os restantes foram
liberados um a um pelo motor até COMPLETED, sem antecipar candles ao consumidor.
O mercado ao vivo não foi validado em pregão nesta entrega; testes Live usam o
adaptador real com fixtures do leitor e do snapshot compartilhado.

Resultado da entrega: 43 testes Java selecionados passaram (13 do módulo e 30 de
regressão), 93 testes frontend passaram, TypeScript e build Vite passaram. O build
foi gravado em diretório temporário, preservando o `frontend/dist` local existente.
As consultas curtas ao backend local na porta 8080 não responderam em 3 segundos;
nenhum backend ou stream foi reiniciado. A aceitação visual com o processo real
continua pendente após carregar esta branch no backend e abrir a nova rota.

Arquivos compartilhados modificados: `AppRouter.tsx`, `AppLayout.tsx`,
`Mt5CandlestickChart.tsx`, `replayMarkerPersistence.test.mjs` e
`Mt5TickWebSocketHandler.java`. Os demais arquivos deste trabalho são novos dentro
do package `strategy.threecandles`, de `frontend/src/features/threecandles`, dos
testes `threeCandles.test.mjs`/`threeCandlesPage.test.mjs` e deste documento.

Próxima etapa: validar a convenééo temporal da fonte e, com o caderno das aulas,
definir as regras do detector e seus testes antes de qualquer estudo operacional.
