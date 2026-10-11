# Replay Engine

## Setup91 visual validation

M5 Replay reuses `Setup91` and `Setup91Breakout` directly, with Batch's closed
warm-up candles and original LAST sequence numbers. `ReplayHistoryProvider`
uses a valid COMPLETE `historical-data/{symbol}/{date}.ndjson` cache without
invoking MT5/Python. No Setup91 rules or Batch processing are changed.

The existing `REPLAY_SETUP_EVENT` WebSocket channel carries only the six Setup91 BUY/SELL
ARMED, TRIGGERED and CANCELLED events, the signal candle, EMA diagnostics and
breakout LAST. ARMED is drawn on the signal/reversal candle, only once its
closure is observed. TRIGGERED and CANCELLED use the event's M5 bucket.
Same-timestamp LASTs retain their original order; there is no synthetic final
candle closure. EMA9 (purple) is supplied by the backend for closed candles only.

Small circles (`91 A`) mark ARMED, arrows (`91 T`) mark TRIGGERED, and subtle
gray dots mark CANCELLED. Hover a candle to see all its Setup91 event details
in UTC below the chart. Nonempty history updates and chart remounts retain the
markers; explicit replay close/reset/new load clears them and their details.
The frontend only presents backend values. This adds no execution, stops,
targets, MFE/MAE or profitability calculations.

## Objetivo e arquitetura

O Trade Replay transmite eventos normalizados para os mesmos motores usados no
LIVE. O Replay visual M5 também publica marcadores estruturais e o setup
Setup91 descrito acima; nenhum desses caminhos executa ordens.

```text
Historical Source
       │
       ▼
normalized domain event
       │
       ├── MarketTrade ──────────────> TradeFlowEngine
       │
       └── MarketStructureSample ────> MarketStructureEngine
```

`ReplaySource<T>` separa aquisição e execução. A implementação inicial,
`Mt5HistoricalTradeSource`, pode ser substituída futuramente por um Historical
Store local sem alterar runner ou engine.

## Event time e determinismo

O replay não dorme entre eventos. `MarketTrade.timeMsc` e
`MarketStructureSample.timeMsc` determinam toda a semântica das janelas e das
interações. Relógio de parede mede apenas duração e eventos por segundo.

As fontes devem entregar ordem não decrescente. Eventos no mesmo milissegundo e
eventos integralmente idênticos são preservados. Não há `Set`, `distinct` ou
deduplicação por conteúdo. Ordem inválida, símbolo divergente, evento fora do
intervalo e mensagem inválida interrompem a sessão explicitamente.

## Trade Replay

```text
copy_ticks_range(COPY_TICKS_ALL)
       ↓ chunks de cinco minutos
mt5_trade_history.py
       ↓ NDJSON incremental
Mt5HistoricalTradeSource
       ↓ MarketTrade
TradeReplayRunner
       ↓
TradeFlowEngine existente
```

Python reutiliza `trade_rows` e `normalize_trade` do Trade Stream LIVE. Assim,
LAST/VOLUME, BUY/SELL/AMBIGUOUS e `volume_real` possuem uma única normalização.
O processo envia uma linha por negócio e o Java consome sincronamente; o pipe
fornece backpressure natural. Cada chunk é fechado por milissegundo e o filtro
inclusivo global é aplicado sem duplicar fronteiras.

Somente `WINV26` é aceito pela fonte nesta versão. `WIN$N` é rejeitado porque o
agressor histórico já foi observado corrompido. Nenhuma inferência por bid/ask,
tick rule ou movimento de preço é feita.

`TradeReplayObserver` recebe trades e, por opt-in, os quatro snapshots. Sem
opt-in, o runner não cria quatro snapshots por negócio e não acumula histórico
em memória.

## Sessão e warm-up

`M5ReplaySession` usa limites inclusivos para análise:

```text
[loadStart, analysisStart)  WARMUP
[analysisStart, analysisEnd] ANALYSIS
```

Eventos de warm-up alimentam o engine, mas não entram nas estatísticas nem nos
callbacks. O runner chama `reset()` antes de cada sessão.

No replay estrutural, uma interação iniciada no warm-up e concluída durante a
análise é excluída. Essa política conservadora evita analisar uma interação cujo
início completo não pertence à janela.

## Resultado

`ReplayRunResult` não guarda eventos. Ele contém sessão, eventos lidos,
processados, warm-up/análise, duração de mercado, duração de parede, taxa e:

```text
trades e lados
volumes por lado
knownDelta
primeiro/último/mínimo/máximo preço
primeiro/último timeMsc
```

## Validação histórica e desempenho

Replay real de 30 minutos de WINV26 em 22/09/2026:

```text
loadStart:     1790100116055
analysisStart: 1790100126055
analysisEnd:   1790101926055

eventsRead:   12966
warmupEvents:   139
trades:       12827
BUY:           6391
SELL:          6006
AMBIGUOUS:      430
volume:       117121
knownDelta:    22443
firstPrice:   188765
lastPrice:    188560
minPrice:     188560
maxPrice:     188860

marketDuration: 30 minutos
wallClock:      7,256 s
eventsPerSecond: 1786,86
```

Um acumulador Python independente sobre o mesmo intervalo retornou exatamente
os mesmos 12.827 trades, lados, volumes, delta e preços. A duração inclui seis
consultas MT5, serialização NDJSON, pipe, parsing Java e processamento do motor.

## MarketStructure Replay

`MarketStructureReplayRunner` está pronto para fontes de
`MarketStructureSample` e foi validado deterministicamente. A sequência
`+50,+35,+20,+8,+2,-5,+4,+15,+25` produz exatamente o mesmo
`MovingAverageInteraction` por chamada direta e por replay. SMA móvel por
amostra e trackers SMA9/SMA21 independentes também foram testados.

### O COPY_TICKS_ALL reproduz fielmente o Quote Stream atual?

**PARCIALMENTE.**

O histórico contém `time_msc`, `last`, bid, ask e flags em ordem e permite criar
uma sequência determinística de mudanças de mercado. Porém ele não permite
reproduzir exatamente a sequência hoje observada por `symbol_info_tick()`:

1. o Quote Stream consulta um snapshot a cada 20 ms e pode pular estados entre
   duas consultas;
2. ele emite somente quando `time_msc` muda, enquanto `COPY_TICKS_ALL` pode ter
   vários eventos no mesmo milissegundo;
3. o histórico não registra qual dos estados disponíveis o polling de parede
   observou;
4. `Ta4jMt5Sma9Adapter` atualiza somente o último bar já sincronizado e retorna
   vazio quando o tick passa para outro bucket M5. O LIVE depende da
   sincronização externa de candles para avançar a série.

Uma sonda de 20 segundos após o fechamento encontrou somente o estado final
estático (`polled=1`) e nenhum evento novo, portanto não forneceu uma segunda
prova LIVE. A diferença semântica acima é suficiente para impedir alegação de
paridade exata.

O Replay visual M5 consome o histórico `LAST` pelo `IntrabarM5Processor` e
envia as amostras causais ao `MarketStructureEngine`; isso não altera nem
substitui a fonte LIVE. A ocorrência visual
`SMA21_SAME_SIDE_MOVE_AWAY` usa o mesmo classificador do
`CanonicalStructuralOutcomeReplayRunner` e é publicada separadamente dos
`MARKET_STATE` coalescidos. O `LAST` que conclui `INTERACTION_COMPLETED` define
o instante e o preço da ocorrência.

### Pullback09 research compatibility

M5 Replay no longer publishes CRZ09, RJ09 or PULLB09 events in either direction.
Its frontend event store accepts only Setup91 markers; no Pullback09 marker
labels or styling remain. The shared causal processor and research exports are
preserved, as are all Pullback09 strategy, research and Batch classes. See
[pullback09-batch-one-day.md](pullback09-batch-one-day.md) for independent research.

## Execução manual

```powershell
java -cp "target/classes;target/dependency/*" `
  br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TradeReplayDiagnostics `
  --symbol WINV26 `
  --load-start-msc 1790100116055 `
  --analysis-start-msc 1790100126055 `
  --analysis-end-msc 1790101926055
```

## Memória, falhas e cancelamento

Python mantém somente um chunk de cinco minutos e Java processa uma linha por
vez. Runners e resultados mantêm acumuladores constantes. Observers escolhem se
armazenam alguma coisa.

Erro do MT5, símbolo não validado, exit code Python, stderr, NDJSON inválido e
falha do consumidor são propagados. O source encerra somente o processo que
criou, com término forçado limitado como fallback.

## Limites desta versão

Não existem Historical Store, alertas, confluência, CVD, MFE/MAE, target/stop,
custos, slippage ou execução de ordens no Replay visual. Ele exibe candles e
marcadores das ocorrências estruturais e do setup Pullback09 ↑ experimental.
