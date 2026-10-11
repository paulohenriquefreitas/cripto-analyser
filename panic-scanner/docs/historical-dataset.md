# Dataset hist?rico MT5 ? etapa 1

Esta etapa somente importa e reutiliza arquivos di?rios. N?o executa Replay, indicadores, setups ou estat?sticas.

## O que foi reutilizado

`ReplayHistoryProvider` j? acionava `scripts/mt5_price_history.py` por dia UTC. O exportador usa COPY_TICKS_ALL em blocos M5 e grava NDJSON schema 2. `Mt5HistoricalPriceSource` j? fazia leitura linha a linha e verificava ordena??o, intervalo e trailer.
O armazenamento padr?o anterior era `panic-scanner/target/replay/{symbol}-{date}.ndjson`, vulner?vel a limpeza do build.

O novo padr?o ? `historical-data/{symbol}/{date}.ndjson`. A propriedade `panic-scanner.replay.history-file` e a vari?vel `PANIC_SCANNER_REPLAY_HISTORY_FILE` continuam dispon?veis. Arquivos v?lidos no caminho legado s?o copiados, preservando o original, sem MT5. Execute com o diret?rio de trabalho na raiz do reposit?rio ou configure caminhos expl?citos.

## Conte?do e completude

* Header: schema, contrato, in?cio/fim UTC, candles de warm-up e candles oficiais; OHLC e volumes permanecem no formato existente.
* Tick: `timeMsc`, `last`, `flags`, `volumeReal`; novas exporta??es acrescentam `sequence`, ordinal no stream bruto.
* Trailer: `end` e n?mero de ticks gravados.
* Manifesto irm?o `.ndjson.metadata.json`: data, contrato, origem MT5, contagens de ticks e LASTs, primeiro/?ltimo timestamp de LAST, instante de importa??o, vers?o, status e SHA-256.

Arquivos antigos sem `sequence` continuam usando o ordinal dos LASTs selecionados, como antes. Seus bytes n?o s?o reescritos. Eventos distintos no mesmo milissegundo permanecem na ordem original; n?o h? deduplica??o.

Antes de importar, verifica-se o cache. Somente manifesto COMPLETE, com contrato/data/formato corretos e hash correspondente, ? reutilizado. Arquivos legados sem manifesto s?o validados integralmente antes da ado??o; o instante de modifica??o do arquivo ? usado como evid?ncia do momento de importa??o.

O importador grava INCOMPLETE e usa um arquivo `.part`. S? publica COMPLETE depois de sucesso do MT5, valida??o do intervalo, ordena??o, sequ?ncia e trailer. H? bloqueio de arquivo durante aquisi??o para impedir duas importa??es simult?neas do mesmo destino. Interrup??o/falha deixa o dia INCOMPLETE. Arquivo COMPLETE corrompido causa erro expl?cito e n?o ? sobrescrito automaticamente.

Um arquivo de zero bytes ? inv?lido. Uma sess?o sem ticks ? v?lida quando h? header/trailer completos e nenhum candle oficial indicando dados ausentes. Falha/retorno `None` do MT5, ou candles oficiais sem nenhum LAST, n?o s?o tratados como dia vazio v?lido.

A importa??o por intervalo aceita somente dias UTC encerrados. Um per?odo terminado no futuro ou no dia UTC atual ? rejeitado antes de consultar o MT5.

## Como informar o per?odo

No IntelliJ, execute a classe principal:

`br.com.bauzin.market.panic.panicscanner.application.win.replay.HistoricalDatasetImportCommand`

Use o classpath do m?dulo `panic-scanner`, JDK 21 e diret?rio de trabalho na raiz do reposit?rio. Argumentos de exemplo:

```text
WINV26 2026-09-14 2026-09-18
```

Argumentos opcionais, ap?s contrato/in?cio/fim:

```text
HISTORY_TEMPLATE PYTHON SCRIPT
```

Exemplo completo:

```text
WINV26 2026-09-14 2026-09-18 historical-data/{symbol}/{date}.ndjson python panic-scanner/scripts/mt5_price_history.py
```

O comando imprime Requested sessions, Cached, Imported e Failed, incluindo as datas e mensagens das falhas e encerra com c?digo diferente de zero em caso de falha. N?o sobe servidor HTTP e n?o executa pesquisa. O contrato ? expl?cito: n?o h? rolagem autom?tica de vencimentos.

Para integra??o Java, use `HistoricalDatasetImporter.importRange(symbol, start, end, onDay)`. O callback recebe apenas o arquivo di?rio e seus metadados, nunca todos os LASTs. O resultado cont?m somente contagens operacionais de importa??o. Replay continua usando `ReplayHistoryProvider.ensureAvailable` e o mesmo dataset.

Em cada dia, dados s?o exportados em blocos M5 e validados linha a linha. Mem?ria n?o cresce com meses de LASTs. Altera??es futuras nas regras Java n?o fazem parte da chave do cache e n?o provocam nova aquisi??o.

## Testes

`HistoricalDatasetImporterTest` cobre dia ausente, cache existente, lacuna dentro do intervalo, arquivo truncado, manifesto INCOMPLETE, timestamps iguais e sequence, volume/flags/warm-up, compatibilidade legada, falha de importa??o, corrup??o e datas inv?lidas.

```powershell
mvn -pl panic-scanner -am test '-Dtest=HistoricalDatasetImporterTest,ReplayHistoryProviderTest,Mt5HistoricalPriceSourceTest,M5ReplaySessionTest,CausalSessionVwapTest,Pullback09*Test' '-Dsurefire.failIfNoSpecifiedTests=false'
python -B -m unittest discover -s panic-scanner/scripts -p test_mt5_price_history.py
```

Nenhuma regra de setup ou f?rmula de outcome foi alterada nesta etapa. As modifica??es locais anteriores nesses componentes foram preservadas.

## Arquivos e valida??o desta etapa

Criados: `HistoricalDatasetImporter.java`, `HistoricalDatasetImportCommand.java`, `HistoricalDatasetImporterTest.java` e este documento.
Alterados: `ReplayHistoryProvider.java`, `Mt5HistoricalPriceSource.java`, `scripts/mt5_price_history.py`, `scripts/test_mt5_price_history.py`, `application.properties` e `.gitignore`.

Valida??o final: 84 testes Java (incluindo regress?es existentes do Replay) e 9 testes Python aprovados, sem falhas.
Foi executado o comando real para WINV26 de 29/08/2026 a 27/09/2026 com Python configurado como `MUST_NOT_CALL_MT5`: 30 dias reutilizados, zero importa??es, sa?da 0. Log em `panic-scanner/target/historical-cache-verification.txt`.

## Preparation orchestration

`HistoricalDatasetOrchestrator.prepare(symbol, start, end)` checks the existing provider's validated cache before calling `HistoricalDatasetImporter` for each missing or INCOMPLETE day. It returns `requestedSessions`, `cached`, `imported`, `failed()` and immutable `failures` (date/message). Ordinary daily failures do not stop later days; thread interruption stops preparation. The existing importer retains its fail-fast API.

The interval includes every calendar day and accepts only closed UTC days, preserving the existing importer semantics. No exchange calendar or automatic contract rollover is introduced. A corrupted COMPLETE file is reported as failed and is never automatically replaced. No manifest or NDJSON format changes are required.

The existing console command now uses this orchestrator and exits with code 1 if any date failed. Example summaries from three-day test fixtures:

```text
Requested sessions: 3; Cached: 3; Imported: 0; Failed: 0
Requested sessions: 3; Cached: 2; Imported: 1; Failed: 0
```

`Pullback09BatchRunner` remains separate and local-only: preparation does not execute research, and research does not acquire datasets.

`HistoricalDatasetOrchestratorTest` uses two-event temporary files and a fake exporter to cover cache reuse without importer calls, missing days, INCOMPLETE retry, failure recovery, immutable COMPLETE files, corrupt COMPLETE rejection, Batch isolation, invalid intervals and interruption. No real MT5/Python process is started.
