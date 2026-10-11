# Market Analyzer

Monorepo Maven para aplicações e bibliotecas relacionadas a análise de mercado.

## Estrutura

```text
market-analyzer
├── common
├── bolsai-client
├── ta4j-engine
├── panic-scanner
└── crypto-analyzer
```

## Módulos

- `common`: tipos e utilitários compartilhados.
- `bolsai-client`: cliente para integrações com dados da B3/Bolsa.
- `ta4j-engine`: camada para indicadores e estratégias baseadas em TA4J.
- `panic-scanner`: aplicação Spring Boot para scanner de pânico de mercado.
- `crypto-analyzer`: aplicação Spring Boot existente para análise de cripto.

## Build

Executar todos os módulos:

```bash
mvn test
```

Executar apenas o `crypto-analyzer` com dependências do reactor:

```bash
mvn -pl crypto-analyzer -am spring-boot:run
```

Executar apenas o `panic-scanner` com dependências do reactor:

```bash
mvn -pl panic-scanner -am spring-boot:run
```

Executar o WIN scanner com dados mock:

```bash
mvn -pl panic-scanner -am spring-boot:run -Dspring-boot.run.arguments="--win.market-data.provider=mock"
```

Executar o WIN scanner conectado ao bridge Python/Wine da ProfitDLL:

```bash
mvn -pl panic-scanner -am spring-boot:run -Dspring-boot.run.arguments="--win.market-data.provider=profitdll --profitdll.bridge.url=ws://127.0.0.1:8765 --profitdll.ticker=WINFUT --profitdll.exchange=F --profitdll.mock-warm-up-enabled=true"
```

Antes de usar `provider=profitdll`, inicie o bridge Python Windows x64 no Wine expondo `ws://127.0.0.1:8765`. A autenticação Nelogica permanece no bridge; o backend Java consome somente Market Data via WebSocket. Para ProfitDLL real com histórico próprio, use `--profitdll.mock-warm-up-enabled=false`.

## Histórico D1 MT5 do Panic Scanner

O scanner usa BRAPI por padrão. Para obter somente o histórico diário pelo MT5,
ative `--panic-scanner.historical.provider=mt5`. A descoberta de ativos
(`listStocks()`) continua na BRAPI e requer sua configuração/token existente.
Python com o pacote `MetaTrader5` e o terminal conectado devem estar disponíveis.

O leitor também aceita consultas históricas diretas:

```bash
python panic-scanner/scripts/mt5_reader.py --symbol PETR4 --timeframe D1 --from 2026-07-01 --to 2026-10-06
```

As datas são inclusivas e identificam as sessões D1 em UTC. O volume usa
exclusivamente `real_volume`, sem substituição por volume de ticks. O candle
da sessão atual é conservadoramente marcado como parcial; sessões passadas
são fechadas. Sem argumentos, o leitor mantém WINV26/M5 e 1000 candles.
Esta integração fornece dados históricos; não simula operações ou rentabilidade.

Auditoria histórica do radar, sem simulação de operações:

```powershell
./panic-scanner/scripts/run-panic-replay.ps1 -From 2026-08-01 -To 2026-09-30 -Tickers MGLU3 -RankingMode MOMENTUM -HistoricalProvider mt5
```

`-Tickers` aceita uma lista separada por vírgulas. Quando omitido, a lista atual
da BRAPI é usada (não representa um universo histórico livre de viés de sobrevivência).
O runner usa o fim de cada sessão, `23:59:59.999999999 America/Sao_Paulo`,
incluindo apenas D1 fechados até aquele dia e a mesma janela de 90 dias do scanner.
Os pregões são identificados pelas datas dos candles reais, sem inventar barras
para fins de semana/feriados. A análise sempre passa por um provider limitado à
data simulada, mesmo se a fonte retornar candles posteriores. O limite `To`
deve ser anterior ao dia atual.

A saída em `research-results/panic-replay/<execução>` contém `radar.csv`
(todos os indicadores/checks de momentum e entrada), `radar.ndjson` (resultado
completo), `changes.csv` (mudanças de SignalStatus/entryStatus) e `run.json`.
`selectedRank` reutiliza a seleção existente no modo escolhido, dentro do universo
informado. A auditoria inclui também ativos WATCH/REJECTED fora do ranking.
Dados históricos revisados/ajustados pelo feed não equivalem a um arquivo de
publicações point-in-time; a barreira garante ausência de candles futuros, não
reconstitui versões antigas dos preços. Nenhuma estratégia ou indicador é alterado.

Estudo de oportunidades para execução externa no Windows (Java 21, Maven,
Python/MetaTrader5 e terminal conectado):

```powershell
./panic-scanner/scripts/run-panic-opportunity-study.ps1 -From 2026-08-01 -To 2026-09-30 -HistoricalProvider mt5 -OutputDirectory research-results/panic-opportunity-study/ago-set-2026
```

Sem `-Tickers`, descobre a lista atual pela BRAPI (`BRAPI_TOKEN` quando exigido).
Para estudo restrito, use `-Tickers 'MGLU3,PETR4,VALE3'`. Os thresholds de
avaliação são `-Return5Threshold 10` e `-Return10Threshold 15` (percentuais);
não alteram os thresholds do Panic. `-RankingMode` aceita MOMENTUM, ENTRY,
BREAKOUT ou PULLBACK. O comando não inicia scanners ao vivo nem simula ordens.

Uma única consulta por ticker carrega aquecimento, histórico anterior de sinais
e até `-FutureCalendarDays 40` dias corridos além de To, limitado a ontem.
D+3/D+5/D+10 significam o terceiro/quinto/décimo candle de pregão seguinte,
com retorno close-to-close. Primeiro gera os sinais com a barreira temporal do
replay; só depois calcula labels futuros. Cada ticker/pregão é um caso, inclusive
janelas sobrepostas. Se faltar futuro suficiente, não inventa um falso positivo.

Classificações: DETECTED_EARLY = QUALIFIED em D com oportunidade forte;
LOST_AFTER_DETECTION = não QUALIFIED em D, forte, com QUALIFIED anterior;
MISSED_OPPORTUNITY = forte sem QUALIFIED anterior observado; FALSE_POSITIVE =
QUALIFIED sem atingir os thresholds, com ambos os horizontes disponíveis.
Os casos neutros são NO_OPPORTUNITY; labels inconclusivos são
INSUFFICIENT_FUTURE_DATA. A busca do último QUALIFIED começa 90 dias corridos
antes de From, mantendo a mesma janela de indicadores por sessão.

A saída contém opportunities.csv (todos os casos), missed-opportunities.csv,
detected-early.csv, lost-after-detection.csv, false-positives.csv, summary.md,
settings.json e unavailable-tickers.csv. Os CSVs incluem indicadores, checks,
labels/datas de avaliação e JSON completo da análise de entrada e do sinal.
O ranking final usa o seletor original entre os tickers disponíveis, sem preços
futuros, e deixa rank vazio para quem não passou pelo filtro do modo escolhido.

Os arquivos são gravados progressivamente com rankingFinal=false; ao concluir,
o ranking entre ativos é finalizado com rankingFinal=true. Cada ticker gera um
checkpoint atômico. Tickers indisponíveis são registrados e não interrompem os
demais. Para retomar, repita exatamente os mesmos parâmetros com `-Resume`
e o mesmo `-OutputDirectory`; checkpoints concluídos (inclusive indisponíveis)
não são consultados novamente. Configurações diferentes impedem a retomada.
O lock impede duas execuções simultâneas no mesmo diretório. O console mostra
contagem de tickers, sessões e percentual de progresso. summary.md contém taxas
com denominadores explícitos, causas, RSI>70 exclusivamente e Top 20 perdas.
Dados/universo atuais podem conter revisões e viés de sobrevivência; isso não é
uma simulação de rentabilidade nem um arquivo histórico de publicações.

## Entrada antecipada experimental do Panic Scanner

As consultas de mercado, tradicional e experimental, registram falhas por ativo
e continuam a análise dos demais, inclusive quando o provedor retorna erro de
autenticação, limite de requisições ou indisponibilidade. A lista `failures`
identifica cada tentativa sem resultado; uma falha individual não encerra o scan.

`ANALYZE_ENTRY` é um sinal independente, com hipóteses de reversão, recuperação
de ADX e retomada após pullback. A flag `panic-scanner.experimental.entry-enabled`
permanece desligada por padrão. Os critérios de QUALIFIED e os scores existentes
não são alterados.

Comparação offline usando o estudo e os candles D1 locais:

```powershell
./panic-scanner/scripts/run-panic-experimental-comparison.ps1 -OutputDirectory research-results/panic-experimental/offline-local
```

Para completar os D1 faltantes e comparar o universo do estudo salvo, execute
externamente com `-FetchMissing` (Python/MetaTrader5 e terminal conectado):

```powershell
./panic-scanner/scripts/run-panic-experimental-comparison.ps1 -FetchMissing -OutputDirectory research-results/panic-experimental/universo-local
```

Para corrigir/regenerar somente os relatórios de uma execução existente, usando
seus casos e configurações congelados, sem consultar históricos ou providers:

```powershell
./panic-scanner/scripts/run-panic-experimental-comparison.ps1 -ReportFrom research-results/panic-experimental/universo-local -OutputDirectory research-results/panic-experimental/universo-local-report-v2
```

O relatório schema 2 separa antecipação por período, censura de futuro/fronteira,
cobertura dos ativos com liquidez elegível, retornos negativos e retornos positivos
abaixo do objetivo. Também registra concentração dos acertos por data e padrão.
A saída original é preservada; escolha um diretório novo. `-ReportFrom` não aceita
`-FetchMissing`, mudança de universo ou mudança de split.

Não inicia scanner ao vivo nem ativa a estratégia em produção. Checkpoints antigos
sem OHLCV bruto são inconclusivos para o experimento. Veja os critérios, a auditoria
de MOVI3, as métricas e os limites em
[experimental-early-entry.md](panic-scanner/docs/experimental-early-entry.md).

## Crypto Analyzer

Frontend estático servido pela aplicação:

```text
crypto-analyzer/src/main/resources/static
```

Frontend React + Vite:

```bash
cd crypto-analyzer/frontend
npm install
npm run dev
```

Configuração padrão da tela:

- Base URL: `http://localhost:8080`
- Endpoint: `/analysis/sumarry`
- Symbol: `BTCUSDT`
- Interval: `1m`
- Requested Candles: `259200`

Endpoint:

```text
GET http://localhost:8080/crypto/candles
```

Exemplo:

```text
/crypto/candles?symbol=BTCUSDT&interval=1m&limit=100
```


### P?gina experimental de entradas

Acesse `http://localhost:5173/scanner-experimental` (menu **Scanner experimental**).
Configure `VITE_USE_MOCK_API=false` em `frontend/.env` e reinicie o Vite.
Com o backend e o provedor de hist?ricos configurados, clique em **Executar an?lise experimental agora**.
A consulta chama `POST /api/panic-scanner/experimental/market-scan`: ativa a an?lise somente nessa
requisi??o, seleciona `ANALYZE_ENTRY` independentemente de `QUALIFIED` e retorna at? 50 alertas,
ordenados pelo Momentum Score existente. A p?gina mostra a data do candle fechado, sinal atual,
scores, padr?es, indicadores, checks e falhas de consulta. Nenhuma consulta come?a ao abrir a p?gina.
A flag global experimental continua desativada por padr?o; o scanner tradicional mant?m seus crit?rios.
Os caches hist?ricos do replay n?o substituem uma consulta atual ao provedor.
