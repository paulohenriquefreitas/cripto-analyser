# Panic Scanner: hipóteses de entrada antecipada v1

Implementação experimental de `EARLY_REVERSAL`, `ADX_RECOVERY` e `PULLBACK_REENTRY`.
O sinal independente é `experimentalEntry.signal = ANALYZE_ENTRY`.
`NONE` significa avaliação conclusiva sem hipótese confirmada; `INCONCLUSIVE`
significa histórico/evidência insuficiente. O campo é omitido com a flag desligada.

`panic-scanner.experimental.entry-enabled` tem default `false`. Nenhuma configuração
de produção foi ativada. `SignalStatus`, QUALIFIED, Momentum Score, Entry Score,
ranking e análises de entrada existentes não receberam critérios novos.

## Indicadores e barreira temporal

`TechnicalAnalysis` acrescenta quatro campos, sem modificar os indicadores anteriores:

| Campo | Definição |
|---|---|
| `adxSma5` | SMA de cinco valores do ADX14, no mesmo BarSeries de candles fechados |
| `adxRising` | ADX14(D) > ADX14(D−1), comparação sem arredondamento prévio |
| `adxSma5Rising` | SMA5(ADX14)(D) > SMA5(ADX14)(D−1) |
| `plusDiAboveMinusDi` | +DI14(D) > −DI14(D) |

O replay mantém a janela de 90 dias corridos configurada no scanner. Os valores de
D−1/D−2 usados nos checks são recalculados no prefixo **da mesma janela**, mantendo
o início e a inicialização dos indicadores; não se calcula a SMA5 pela média de
snapshots de replays com inícios diferentes. Essa diferença é relevante para ADX.

Candles parciais não participam das hipóteses. O use case experimental também limita
o provider pela data do clock. O detector reforça esse limite pela `analysisDate`.
O replay gera todos os sinais do ticker antes de calcular labels futuros. Somente
sessões reais daquele ticker entram em D+3, D+5 e D+10. Os históricos do feed podem
ter revisões: a barreira temporal não reconstitui versões antigas publicadas.

## Critérios congelados antes da auditoria

São hipóteses genéricas, sem condições por ticker e sem ajuste para obter alertas
nas datas de MOVI3. A versão é `early-entry-v1`.

Gates comuns: pelo menos 35 candles fechados, liquidez mínima já configurada,
RSI9 entre 45 e 72, distância acima de EMA21 até 6% e até 3 ATR14. Os 35 candles
são exigidos apenas pelo experimento; não alteram os 21 exigidos pela estratégia atual.

| Padrão | Checks exigidos além dos gates |
|---|---|
| EARLY_REVERSAL | Close acima de EMA9 e da máxima anterior; RSI crescente em duas transições; EMA9 e SMA9 crescentes; SMA21 crescente ou inclinação melhorando; recuperação de EMA9 observada nos dois candles anteriores |
| ADX_RECOVERY | ADX < 20 e > SMA5(ADX); duas altas consecutivas do ADX; SMA5(ADX) crescente; +DI > −DI; EMA9 > EMA21; close > EMA21 |
| PULLBACK_REENTRY | Tendência anterior com EMA9 > EMA21, SMA9 > SMA21 e inclinação positiva da SMA21; D−1 corrige a mínima anterior e toca EMA9/EMA21/SMA21 na faixa ±1%, fecha acima do suporte e da abertura; D confirma fechando acima da máxima de D−1 e da EMA9, preservando a mínima de D−1 |

A rejeição do pullback e sua confirmação ocorrem em candles diferentes. Não há
pivô cuja confirmação dependa de candles posteriores à data analisada.

## Separação de períodos

O estudo salvo cobre agosto/setembro de 2026. Desenvolvimento: datas anteriores a
01/09/2026; validação: 01/09 a 30/09. MOVI3 é excluído das métricas de desenvolvimento,
mas permanece na auditoria. Labels de desenvolvimento cujo D+h alcance a validação
são descartados, separadamente por horizonte. Preços anteriores podem aquecer os
indicadores de validação. Nenhum label é argumento do detector.

Não houve calibração empírica: faltam os candles brutos dos outros tickers no período
de desenvolvimento. O split está implementado para a comparação externa, mas não
há uma validação ampla concluída nem evidência de superioridade desta versão.

## Execução observada em 07/10/2026

Foi executado o replay **offline** sobre todo o universo do estudo salvo: 374 tickers,
14.003 casos. Os checkpoints contêm indicadores/checks e labels futuros, mas não
OHLCV bruto, +DI/−DI ou dados suficientes para verificar a rejeição de suporte.
Não foram reconstruídos candles fictícios nem interpolados indicadores ausentes.

Apenas MOVI3 recebeu uma consulta D1 restrita, de 01/02 a 06/10/2026, para a auditoria
solicitada. O cache resultante está em `historical-data/panic-d1/MOVI3.mt5.json`.
Nenhuma varredura completa da B3 foi executada. Os outros 373 tickers ficaram
`INCONCLUSIVE` no experimento; seus checkpoints permitiram métricas da estratégia
atual. Eles não entram no denominador da comparação pareada.

Artefatos: `research-results/panic-experimental/20261007-offline-v1/`.
`summary.md`, `metrics.json`, `comparison.csv`, `cases.ndjson`, `MOVI3-audit.csv`,
`alerts-per-day.csv`, `unavailable.json`, `settings.json` e `raw-provenance.json`.
O CSV da auditoria registra indicadores, checks atuais/experimentais, scores,
entryStatus, sinais, razões, retornos e datas dos labels. A proveniência inclui
SHA-256 dos arquivos D1 utilizados.

## Decisões em MOVI3

Todas as sessões abaixo resultaram em `NONE` no sinal independente. Isso não
remove QUALIFIED quando a estratégia atual o produz. Em 19–20/09 não há sessão D1.

| Data | Atual | Close | RSI9 | ADX14 | SMA5(ADX) | ADX sobe | SMA5 sobe | +DI > −DI | Motivo relevante para não alertar |
|---|---|---:|---:|---:|---:|---|---|---|---|
| 25/08 | WATCH | 7,42 | 55,8915 | 46,4159 | 50,1206 | não | não | não | Momentum não cresce em duas transições; recuperação já ocorreu antes da janela curta; não há direção ADX nem rejeição de suporte confirmada |
| 02/09 | WATCH | 7,87 | 65,3517 | 18,7292 | 19,9322 | sim | não | sim | ADX abaixo da própria SMA5; EMA9 ainda abaixo de EMA21; extensão de 7,5034% |
| 03/09 | WATCH | 7,76 | 61,8548 | 19,5174 | 19,4246 | sim | não | sim | ADX supera a SMA5, mas ela cai; close não confirma máxima anterior; RSI recua; pullback sem confirmação posterior |
| 04/09 | QUALIFIED | 8,11 | 67,9957 | 28,5671 | 30,3531 | não | não | sim | Extensão de 9,2256%; ausência de fortalecimento consistente e de rejeição anterior confirmada |
| 18/09 | QUALIFIED | 7,89 | 54,4713 | 20,5786 | 22,0149 | não | não | sim | Sem recuperação/momentum crescente, ADX sem recuperação e sem rejeição + confirmação de pullback |
| 21/09 | QUALIFIED | 8,45 | 66,8820 | 20,2565 | 21,9640 | sim | não | sim | Extensão de 8,63%; média do ADX cai; rejeição anterior de suporte não confirmada |
| 22/09 | WATCH | 8,44 | 66,5639 | 18,8292 | 18,9172 | sim | não | sim | Extensão de 7,719%; ADX abaixo da SMA5 e ela cai; sem confirmação de máxima anterior |

O replay do sinal atual de MOVI3 teve **zero divergências de status** em relação ao
checkpoint salvo. Nenhum alerta experimental apareceu em agosto/setembro para MOVI3.

## Diferenças mensuradas e limites

Na amostra pareada de validação, exclusivamente MOVI3, CURRENT e COMBINED
(QUALIFIED ou ANALYZE_ENTRY) foram iguais:

| Horizonte | Casos com futuro | Movimentos fortes por data inicial | Alertas | Cobertos | Cobertura | Falsos alertas | Taxa de falsos |
|---|---:|---:|---:|---:|---:|---:|---:|
| D+3 | 21 | 10 | 9 | 3 | 30% | 6 | 66,67% |
| D+5 | 20 | 8 | 9 | 2 | 25% | 7 | 77,78% |
| D+10 | 15 | 6 | 8 | 4 | 66,67% | 4 | 50% |

Thresholds de avaliação: 5%, 10% e 15%, respectivamente. Falso alerta significa
não alcançar o threshold daquele horizonte; inclui pequenos retornos positivos.
Futuro insuficiente não é falso alerta. São retornos close-to-close, sem ordens,
custos, stop, execução ou rentabilidade simulada.

As datas iniciais podem sobrepor movimentos. O relatório também agrupa janelas
sem sobreposição por ticker/horizonte: D+3 tem 4 movimentos, 2 cobertos; D+5 tem
2 movimentos, 1 coberto; D+10 tem 1 movimento, coberto. A cobertura agrupada exige
alerta durante a janela, antes da sessão final do label; é uma medida descritiva.

Alertas adicionais: zero. Antecipação: sem pares adicionais para medir. Na validação
pareada houve média de 0,4286 alertas/dia, máximo 1 e P95 1, em 21 sessões.
O CSV diário conserva separadamente os números do universo atual e da amostra pareada.
A amostra de desenvolvimento pareada está vazia após excluir MOVI3. Não extrapolar
essas diferenças zero para os demais 373 tickers.

## Comando externo

Offline, reutilizando os checkpoints e candles que já existirem:

```powershell
./panic-scanner/scripts/run-panic-experimental-comparison.ps1 -OutputDirectory research-results/panic-experimental/offline-local
```

Para completar a comparação **fora do Codex**, buscando somente D1 faltantes dos
tickers do estudo salvo e preservando o cache existente:

```powershell
./panic-scanner/scripts/run-panic-experimental-comparison.ps1 -FetchMissing -OutputDirectory research-results/panic-experimental/universo-local
```

Requer Java 21/Maven; com `-FetchMissing`, também Python/MetaTrader5 e terminal
conectado. Não faz descoberta na BRAPI nem inicia servidor/scanner ao vivo. O
universo é o `settings.json` do estudo, sujeito ao viés de sobrevivência da coleta
original. Há `-StudyDirectory`, `-CacheDirectory`, `-ValidationFrom`, `-Tickers`
(lista separada por vírgulas) e `-SkipBuild`. Escolha uma saída nova em cada execução;
o cache é reutilizado inclusive após execução interrompida. Arquivos corrompidos
são registrados como indisponíveis e não são sobrescritos automaticamente.

Este comando habilita a hipótese somente em instâncias isoladas do replay. Não
altera a flag ou o comportamento da aplicação em produção.

## Revisão do relatório: schema 2

A versão dos critérios permanece `early-entry-v1`. O schema do relatório é 2;
nenhum threshold de detecção, score, ranking ou flag de produção foi alterado.
As seções históricas acima descrevem a primeira execução offline e seus artefatos
preservados; as execuções novas usam as métricas revisadas.

Correções na avaliação:

- A busca do próximo QUALIFIED fica restrita à partição do sinal e às dez próximas
  sessões observadas daquele ticker. Não mistura desenvolvimento e validação.
- `anticipation.csv` distingue MATCHED, ALREADY_QUALIFIED, PARTITION_BOUNDARY,
  INSUFFICIENT_SESSIONS e NO_MATCH_WITHIN_10. Antecipação ausente continua null.
- `anticipation-metrics.json` apresenta médias/medianas por período, episódios
  adicionais e contexto de QUALIFIED anterior. A busca de contexto anterior pode
  usar agosto em uma decisão de setembro; os pares futuros não atravessam o split.
- `metrics.json` acrescenta a amostra PAIRED_LIQUID, que exige o check de liquidez
  atual. CURRENT e COMBINED são comparados dentro da mesma amostra.
- `belowThresholdAlerts` substitui a nomenclatura ambígua `falseAlerts` do schema 1.
  `negativeReturnAlerts`, `flatReturnAlerts` e `positiveBelowThresholdAlerts`
  separam as causas. Não atingir +5/+10/+15% não equivale a ter prejuízo.
- Retorno ou data futura ausente é INSUFFICIENT_FUTURE_DATA; o label que cruza o
  split de desenvolvimento é PURGED_AT_SPLIT. Nenhum deles entra como fracasso,
  zero ou observação nas médias. `evaluation-labels.csv` identifica cada caso.
- `additional-metrics.json`, `pattern-metrics.json` e `concentration.json` mostram
  resultados incrementais, padrões sobrepostos, datas e ausência de futuro.
- O CSV diário e o resumo adotam a mesma exclusão de MOVI3 no desenvolvimento.
  Os alertas diários são contados antes do limite de exibição da tela.

Regeneração a partir dos casos salvos, sem MT5, BRAPI ou recálculo de indicadores:

```powershell
./panic-scanner/scripts/run-panic-experimental-comparison.ps1 -ReportFrom research-results/panic-experimental/universo-local -OutputDirectory research-results/panic-experimental/universo-local-report-v2
```

`-ReportFrom` é incompatível com `-FetchMissing`, `-Tickers`, `-StudyDirectory`,
`-CacheDirectory` ou outro `-ValidationFrom`. A saída precisa ser nova. Ela mantém
a proveniência e adiciona o SHA-256 do `cases.ndjson` original. Os campos de sinais,
indicadores, scores e retornos são preservados; somente a antecipação e os relatórios
são regenerados. Labels futuros faltantes continuam faltantes.

## Testes

101 testes Java aprovados após a revisão do relatório, incluindo scanner, API, score,
análise de entrada e replay. A implementação inicial tinha 86 testes aprovados.
Os testes novos verificam padrões positivos/negativos, ADX/SMA5, exclusão de candles
parciais, perturbação de candles futuros, flag desligada por padrão, invariância
dos scores/checks/entrada, independência do ticker, purge de labels, futuro
insuficiente, históricos corrompidos/duplicados, antecipação por sessões observadas,
janelas sobrepostas e tratamento inconclusivo de checkpoints sem OHLCV. A revisão
acrescenta proteção da fronteira na antecipação, isolamento por ticker, distinção
de censura/ausência de pares, contagem de episódios, denominador de liquidez,
separação de retornos negativos/abaixo do objetivo e preservação dos casos no modo
ReportFrom, inclusive recusa de consultas a providers e sobrescrita da origem.

```powershell
mvn -B -pl panic-scanner -am test '-Dtest=Experimental*,TechnicalAnalysisEngineTest,AnalyzeStockUseCaseTest,Panic*Test,*Entry*Test,ScoreCalculatorTest,Momentum*Test,ScanStocksUseCaseTest,ScanB3MarketUseCaseTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Logs locais: `experimental-entry-tests.log` (inicial) e
`experimental-report-v2-tests.log` (schema 2). Não foi executada a suíte inteira de WIN/ML.
