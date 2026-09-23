# Canonical intrabar pipeline

## Mapa anterior à implementação

Investigação estática concluída antes da implementação do novo núcleo:

1. `scripts/mt5_tick_stream.py` mantém uma sessão MT5, consulta
   `symbol_info_tick("WINV26")` a cada aproximadamente 20 ms e publica NDJSON
   somente quando `time_msc` muda. Não publica flags.
2. `Mt5TickStreamClient` desserializa `Mt5Tick`; `Mt5TickStreamLifecycle`
   encaminha para `Mt5TickWebSocketHandler.broadcast`.
3. O handler chama `Ta4jMt5Sma9Adapter.onTick`, entrega preço/médias ao
   `MarketStructureLiveObserver` e enfileira mensagens WebSocket.
4. O frontend (`mt5Intrabar.ts`) pede `/api/mt5/candles` inicialmente e quando
   um tick pertence a um bucket posterior; enquanto espera, não cria candle.
5. `Mt5CandlesController` chama `GetMt5CandlesUseCase` serializado, que chama
   `Mt5ProcessClient` → `mt5_reader.py` → `copy_rates_from_pos(M5, 0, 1000)`.
   Inclui o candle corrente aberto. **MT5 rates é a autoridade de criação M5.**
6. O use case chama `Ta4jMt5Sma9Adapter.synchronize`. O converter cria uma
   BarSeries limitada a 1000 barras, com begin = time oficial e end = begin+5min.
   O adapter troca a série, reaplica o último tick compatível e devolve os últimos
   100 candles para o frontend. O controller solicita refresh do último tick WS.
7. No mesmo bucket, `Bar.addPrice(tick.last())` preserva OPEN e volume, amplia
   HIGH/LOW e muda CLOSE. O frontend aplica a mesma extensão ao candle oficial.
   Ambos usam **LAST**, não BID/ASK. Não há filtro LAST nas mensagens atuais.
8. O adapter antigo não cria barras durante `onTick`. Novo bucket retorna null
   até REST fornecer a barra oficial. O frontend solicita resync e tenta novamente
   após 5 s se necessário. Não há fabricação de candles vazios.
9. Bucket anterior: floor(epochSeconds/300)*300; timestamps MT5 crus.
   `Mt5TimestampDiagnostics` só apresenta Instant/UTC/America_Sao_Paulo;
   não modifica timestamps. Não há offset no domínio.
10. SMA9 e SMA21 usam `SMAIndicator(ClosePriceIndicator(series), period)` TA4J
    0.22.6, incluindo o close corrente, sem arredondamento à grade. Exigem 9/21
    barras; caches do último candle são invalidados pelo TA4J quando close muda.
11. VWAP usa `Mt5SessionVwap`/`AnchoredVWAPIndicator`, HLC3 ponderado por
    `realVolume` oficial (não tickVolume nem volume do snapshot). Âncora por
    mudança de data UTC do feed; primeira sessão incompleta é ocultada.
    VWAP é publicado na consulta REST, não recalculado/publicado por tick.

## Problema e limites da comparação

Polling perde estados intermediários e eventos diferentes no mesmo milissegundo.
COPY_TICKS_ALL preserva uma sequência que não identifica quais snapshots foram
observados pelo polling. Portanto igualdade da sequência canônica prova
determinismo do processamento, não equivalência das duas fontes reais atuais.
O LIVE existente permanece autoridade do frontend e do observer de estrutura.

## Decisão de semântica

Evento canônico mínimo: símbolo, timeMsc, preço LAST positivo e finito.
Bid/ask não formam esse candle; flags pertencem à seleção na fonte; volume fica
fora do escopo. O evento representa uma observação de preço, não um negócio nem
volume negociado. Nenhum evento entregue ao processor é deduplicado.

Fonte LIVE de compatibilidade: transforma cada Mt5Tick válido recebido em uma
observação LAST, inclusive snapshots causados por mudanças de quote.
Fonte histórica: seleciona `(flags & TICK_FLAG_LAST) != 0` de COPY_TICKS_ALL,
preservando ordem original e multiplicidade. Linhas BID/ASK/VOLUME sem LAST não
movem o preço. Isso é deliberadamente diferente do polling; não se amostra 20 ms.
A documentação oficial distingue flags de mudança e campos de estado carregados
em cada linha: [copy_ticks_range](https://www.mql5.com/en/docs/python_metatrader5/mt5copyticksrange_py)
e [copy_ticks_from](https://www.mql5.com/en/docs/python_metatrader5/mt5copyticksfrom_py).
Essa seleção precisa de confronto OHLC real; igualdade oficial não é presumida.

## Implementação e contrato

```text
Mt5Tick → Mt5CanonicalPriceMapper.liveSnapshot ──────────────┐
                                                           │
COPY_TICKS_ALL → mt5_price_history.py → NDJSON                │
    → Mt5HistoricalPriceSource → mapper.historical (LAST) ────┤
                                                           ▼
                                                CanonicalPriceEvent
                                                           ↓
                                                IntrabarM5Processor
                                                           ↓
                                                IntrabarMarketState
                                                           ↓
                                              state.structureSample()
                                                           ↓
                                                MarketStructureEngine
```

O processor é Java sem Spring, IO ou relógio. Uma instância tem um único dono
(serialização das chamadas é responsabilidade do chamador) e aceita um símbolo
normalizado por vez. Não conhece Python, MT5, WebSocket ou modo de execução.

`M5Bucket.start(timeMsc)` é a regra compartilhada pelo processor e pelo adapter
Java antigo: `floor(timeMsc / 300000) * 300000`. O frontend legado continua
usando sua regra equivalente em segundos, preservado porque não foi migrado.
Não existe um segundo cálculo de bucket no source histórico ou no diagnóstico
Java. O exportador usa janelas M5 apenas para limitar aquisição; não forma OHLC.
Um futuro consumidor visual deve usar `state.candle.bucketStartTimeMsc`, sem
reconstruir candles ou recalcular médias no navegador.

Para um bucket novo: primeiro preço vira OPEN/HIGH/LOW/CLOSE. No mesmo bucket,
`Bar.addPrice` preserva OPEN, amplia HIGH/LOW e atualiza CLOSE. A diferença para
o LIVE anterior é explícita: anteriormente OPEN/HIGH/LOW já vinham do snapshot
oficial; agora são reconstruídos da sequência observada desde o início do bucket.
Começar no meio de um bucket produz uma barra parcial, salvo baseline explícita.

Na primeira observação de um bucket posterior, `completedCandle` contém o último
candle e uma nova barra é criada automaticamente. Gaps pulam buckets sem preços
artificiais. SMA usa barras existentes, não cinco minutos de tempo corrido por
posição. A ausência de eventos não encerra barras por relógio de parede.
O diagnóstico pode comparar a última barra quando conhece o fim exclusivo da
janela e sabe que seu bucket terminou; isso não injeta eventos no processor.

`timeMsc == anterior` é válido e mantém ordem de entrega; eventos idênticos
também retornam estado. `timeMsc < anterior`, evento anterior a analysisStart ou
símbolo divergente falham antes de qualquer alteração do candle/indicadores.
Preço inválido, símbolo vazio e timestamp inválido são rejeitados pelo record.
Um `reset()` apaga série, warm-up, símbolo, candle, timestamp e analysisStart.

### Warm-up e SMA

`warmUp(symbol, analysisStart, closedCandles)` exige processor vazio e valida
toda a lista antes de aplicar: símbolo igual, ordem estritamente crescente,
OHLC consistente, início alinhado e buckets anteriores ao bucket de análise.
Não aceita o candle aberto como histórico fechado. Não precisa de ticks antigos.
Pode haver gaps no warm-up. Até 1000 barras são retidas; dados mais antigos são
descartados depois da validação. É possível iniciar sem warm-up.

Há uma única BarSeries no processor, compartilhada pelo candle e pelas duas
instâncias TA4J `SMAIndicator` sobre `ClosePriceIndicator`. Reutiliza a mesma
implementação TA4J e numFactory do adapter validado, sem fórmula paralela nem
arredondamento à grade. A SMA9 exige 9 barras e a SMA21 exige 21; antes disso o
respectivo campo é null. Vinte barras fechadas mais a corrente bastam para ambas.
`structureSample()` fica vazio até ambas existirem e então apenas copia os
valores já calculados, sem recalcular média.

Não houve reescrita do adapter antigo. Ele permanece autoridade LIVE; ganhou a
regra comum de bucket e uma leitura imutável do candle real para diagnóstico.
Durante shadow há duas séries **intencionalmente independentes para comparação**;
o novo pipeline não tem séries distintas para gráfico, estrutura e replay.

Sem volume no evento, snapshot ou estado. O armazenamento TA4J usa zero nos
campos de volume/amount obrigatórios, sem significado de volume observado.
Esses campos não são publicados, comparados ou usados para VWAP. O VWAP antigo,
seu `realVolume` e os snapshots REST permanecem intactos.

Trabalho por evento: atualização de uma barra + cálculo limitado às janelas
9/21; não reconstrói histórico. Memória do processor: até 1000 barras, caches
TA4J limitados pela série, candle corrente e timestamp. Não acumula ticks.

### Fontes

`Mt5CanonicalPriceMapper.liveSnapshot` é a adaptação LIVE de compatibilidade.
Preserva LAST e verifica consistência entre time/timeMsc. Não consegue recuperar
eventos que o polling já descartou e não deve ser interpretado como fonte LAST
determinística completa. A migração futura deve adquirir a mesma seleção LAST
no LIVE, mantendo ordem/multiplicidade, antes de afirmar paridade entre fontes.

`mt5_price_history.py` exporta COPY_TICKS_ALL em chunks de cinco minutos,
com warm-up fechado e candles oficiais para comparação. O diagnóstico exige
intervalo alinhado `[startMsc, endMsc)` para não comparar barras parciais.
`Mt5HistoricalPriceSource` lê uma linha por vez; valida cabeçalho, range, ordem
de todas as linhas (inclusive quotes descartadas), tipos e trailer com contagem.
Truncamento ou erro não vira sucesso silencioso. O chamador controla o Reader;
arquivos e futuramente pipes podem usar o mesmo adapter. Não há ligação com
TradeReplayRunner, replay visual ou engine de trades.

A seleção é exclusivamente `flags & 8 != 0`, inclusive flags combinadas.
LAST marcado não significa necessariamente preço numericamente diferente da
linha anterior: trades ao mesmo preço e eventos repetidos continuam presentes.
BID/ASK/VOLUME sem LAST não geram eventos. Não se deduplica conteúdo nem se
infere LAST comparando valores entre linhas.

### Fronteira Python/MT5 investigada

O primeiro exportador usou `datetime(chunkEnd - 1ms)`. Uma consulta controlada
mostrou que a ponte deste terminal trunca o limite para segundos inteiros:

```text
fim solicitado: 1790100299999
último evento retornado: 1790100298817

fim solicitado: 1790100300000, filtrado time_msc < fim
último evento retornado: 1790100299936
```

Isso omitia o último segundo e causou diferenças CLOSE de +10/-10/-5/+10 em
quatro barras. A correção foi consultar a fronteira inteira e filtrar o intervalo
semiaberto por time_msc. Nenhum preço foi ajustado para coincidir com candles.
Um teste simula exatamente a perda de precisão da ponte e verifica inclusão do
último segundo e ausência de duplicação na fronteira seguinte. A correção está
somente no novo exportador; a infraestrutura Trade Stream/Replay não foi alterada.

## Shadow mode

`CanonicalIntrabarShadow` é um executável opt-in separado, sem publicação WS.
Usa `Mt5TickStreamClient` existente e entrega cada snapshot recebido ao adapter
antigo e ao novo processor. Os dois começam com o mesmo histórico oficial e
baseline de candle corrente (`seedCurrentCandle`, permitida somente imediatamente
após warm-up). A seed é uma ferramenta de comparação, não um candle fechado de
warm-up e não um substituto de eventos históricos ausentes.

Compara OHLC da série antiga real, SMA9 e SMA21 com igualdade exata. Registra
divergências limitadas a uma mensagem por segundo e resumo final; contabiliza
todas, mesmo as não impressas. No rollover o antigo pode retornar null; o
diagnóstico consulta REST para ele, sem sobrescrever o candle canônico para
forçar igualdade. A fila é limitada e overflow aborta explicitamente.

Execução por 20 segundos após fechamento retornou:

```text
samples=1 divergences=1 legacyUnavailable=1
firstTimeMsc=lastTimeMsc=1790105400003
activeMarketEvidence=false
```

O último candle oficial tinha início `1790101800000`; o snapshot polled tinha
bucket `1790105400000`. O adapter antigo não aceitou esse bucket sem barra
oficial; o canônico criou uma barra para a observação LAST. Essa divergência
estrutural foi preservada. Não houve fluxo ativo, logo esse teste **não valida
paridade LIVE**. Também mostra por que quotes polled fora de atividade não podem
ser confundidas com a seleção histórica LAST, mesmo mantendo o mesmo preço.
Há necessidade de repetir em mercado aberto, incluindo transições M5 e resync.

## Evidência histórica real

WINV26, 22/09/2026, timestamps crus `1790100000000 ≤ t < 1790101800000`
(18:00–18:30 na representação UTC do feed, sem ajuste de timestamps).

| Métrica | Resultado |
|---|---:|
| Warm-up fechado | 1000 barras |
| Linhas COPY_TICKS_ALL | 16756 |
| Eventos selecionados por LAST | 15161 |
| Eventos LAST após outro no mesmo timeMsc | 2534 |
| Eventos LAST consecutivos com mesmo timeMsc/preço | 942 |
| Candles M5 reconstruídos | 5 |
| Samples SMA9 / SMA21 | 15161 / 15161 |
| MarketStructure events | 202 |
| MovingAverageInteractions concluídas | 33 |
| Candles comparados / sem contraparte | 5 / 0 |
| Candles oficiais sem reconstrução | 0 |
| Wall-clock Java, leitura do arquivo + processamento | 1,966984 s |

O tempo informado não inclui a exportação Python/MT5 anterior nem animação.
O último bucket da janela, `1790101500000`, continha apenas quotes sem LAST e
não tinha candle M5 oficial. Portanto cinco candles em 30 minutos são corretos
para os dados observados; não se fabricou uma sexta barra.

| Bucket timeMsc | OPEN | HIGH | LOW | CLOSE | ΔO/ΔH/ΔL/ΔC vs MT5 |
|---|---:|---:|---:|---:|---|
| 1790100000000 | 188785 | 188790 | 188705 | 188750 | 0/0/0/0 |
| 1790100300000 | 188750 | 188860 | 188745 | 188840 | 0/0/0/0 |
| 1790100600000 | 188830 | 188840 | 188710 | 188720 | 0/0/0/0 |
| 1790100900000 | 188720 | 188740 | 188635 | 188700 | 0/0/0/0 |
| 1790101200000 | 188700 | 188700 | 188615 | 188645 | 0/0/0/0 |

Um acumulador Python independente sobre as linhas exportadas confirmou os mesmos
OHLC e contagens. Volume não foi comparado. A amostra valida essa janela e esse
símbolo, não todos os dias, sessões, brokers ou instrumentos.

## Testes e execução

23 novos testes Java cobrem OHLC, repetição no bucket, rollover, gaps, mesmo
milissegundo, duplicatas, rejeição sem mutação, eventos inválidos, símbolo/reset,
warm-up, ambas as SMAs, atualização intrabar e fracionária, eviction da série,
baseline do adapter antigo, flags, integridade NDJSON e shadow.

O teste central usa fontes `SyntheticLiveSource` (push) e
`SyntheticReplaySource` (iteração) distintas, com a mesma sequência. Compara
cada estado e cada MarketStructureSample/MarketStructureUpdate, incluindo
MovingAverageInteractions **não vazias**, duplicata, rollover e gap.
Paridade não é inferida apenas de agregados finais.

Execução completa:

| Suíte | Testes | Falhas / erros / ignorados |
|---|---:|---|
| Python (7 novos) | 37 | 0/0/0 |
| Novos Java (subconjunto de Panic) | 23 | 0/0/0 |
| Panic Scanner | 261 | 0/0/0 |
| Crypto Analyzer | 14 | 0/0/0 |
| Maven reactor completo | 275 | 0/0/0 |

```powershell
$env:JAVA_HOME = 'C:/Users/paulo/.jdks/corretto-21.0.4'
python -m unittest discover -s panic-scanner/scripts -p 'test_*.py'
mvn test

# Com dependências em panic-scanner/target/dependency (gerar se necessário):
mvn -pl panic-scanner -am package -DskipTests dependency:copy-dependencies
python panic-scanner/scripts/mt5_price_history.py --symbol WINV26 --start-msc 1790100000000 --end-msc 1790101800000 --output panic-scanner/target/canonical-history.ndjson
& "$env:JAVA_HOME/bin/java.exe" -cp 'panic-scanner/target/classes;panic-scanner/target/dependency/*' br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.CanonicalIntrabarDiagnostics panic-scanner/target/canonical-history.ndjson
& "$env:JAVA_HOME/bin/java.exe" -cp 'panic-scanner/target/classes;panic-scanner/target/dependency/*' br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.CanonicalIntrabarShadow 30
```

## Preparação para Visual Replay e Research Replay

Objetivo futuro: usuário escolhe data → warm-up fechado anterior → sessão
histórica → PLAY → eventos avançam → M5 se forma → SMA9/SMA21 se movem → engines
recebem os estados. Um ReplayController futuro decidirá **quando** entregar o
próximo CanonicalPriceEvent. Domínio e motores usarão sempre `event.timeMsc`.

Research mode entregará eventos em máxima velocidade, sem animação. Visual mode
usará relógio virtual, play/pause e velocidades 0.5x, 1x, 2x, 5x, 10x e MAX.
Ambos compartilharão processor e engines; nenhuma dessas interfaces, controles
ou relógio virtual foi implementada. Não há VWAP replay, sinais ou backtest.

## Decisão de migração e respostas A–H

- **A:** MT5 rates M5 via REST cria os candles LIVE; adapter/frontend só ampliam
  a barra corrente. Novo bucket no caminho antigo exige sincronização externa.
- **B:** LAST.
- **C:** COPY_TICKS_ALL com TICK_FLAG_LAST, preservando multiplicidade. Quotes e
  VOLUME sem LAST não provocam observação de preço histórica.
- **D:** Sim, o novo processor faz a transição sozinho por event time.
- **E:** Sim, mesmo milissegundo e eventos idênticos são preservados.
- **F:** Sim, mesma sequência e mesmo warm-up produzem exatamente OHLC, SMA9,
  SMA21, samples e MovingAverageInteractions iguais em cada passo.
- **G:** Sim nesta janela: 5 candles, zero diferenças O/H/L/C; não é garantia
  universal. A investigação da fronteira de aquisição está registrada acima.
- **H:** **NÃO.** Falta evidência de shadow em mercado aberto e de equivalência
  das fontes reais. O polling ainda observa snapshots, enquanto a fonte histórica
  escolhe eventos LAST. A observação pós-fechamento mostrou uma divergência real
  de bucket. Não houve promoção do canônico a autoridade LIVE.

Mt5TradeStreamClient, mt5_trade_stream.py, TradeFlowEngine e TradeReplayRunner
permanecem sem alterações. Encerramos na infraestrutura determinística testada.
