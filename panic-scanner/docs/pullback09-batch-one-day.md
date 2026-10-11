# Etapa 2 ? PULLB09 Batch de um dia local

Execute a classe principal `br.com.bauzin.market.panic.panicscanner.application.win.replay.Pullback09BatchCommand` com o classpath do m?dulo `panic-scanner`, JDK 21 e diret?rio de trabalho na raiz do reposit?rio.

Argumentos:

```text
WINV26 2026-09-14
```

Opcionalmente, o terceiro argumento ? o template do dataset:

```text
WINV26 2026-09-14 historical-data/{symbol}/{date}.ndjson
```

O comando s? aceita s?mbolo e uma data (mais o caminho opcional). N?o inicia Spring, servidor, feed, Python ou MT5. N?o importa arquivos automaticamente. Falhas retornam c?digo 1 e mensagem `BATCH ERROR`; par?metros incorretos retornam c?digo 2.

## Processamento compartilhado

`Pullback09CausalProcessor` cont?m somente a orquestra??o extra?da de `M5ReplaySession`: warm-up, LAST -> `IntrabarM5Processor`, candle fechado no research engine, VWAP causal, m?quinas UP/DOWN, registro de PULLB09 e atualiza??o do outcome pelo LAST.

Replay e Batch usam esse mesmo componente. As classes `Pullback09Setup`, `Pullback09DownSetup`, `Pullback09ResearchEngine`, `Pullback09SetupOutcomeTracker`, os records e a VWAP n?o foram modificados nesta etapa. As m?quinas, condi??es, janelas e f?rmulas s?o as existentes.

`MarketStructureEngine`, mensagens visuais, PLAY/PAUSE e coalesc?ncia permanecem no Replay. O Batch n?o instancia a sess?o visual nem publica MARKET_STATE.

## Dataset estritamente local

`Pullback09BatchRunner` l? diretamente `historical-data/{symbol}/{date}.ndjson` e seu manifesto. Reutiliza apenas o record de metadados da etapa 1; n?o instancia nem chama o servi?o de importa??o.

Exige manifesto COMPLETE e contrato/data/origem/vers?o v?lidos. O header deve representar exatamente o dia UTC solicitado. A leitura usa `Mt5HistoricalPriceSource`, preservando timestamps iguais e sequence, sem ordenar nem deduplicar LASTs. Pre?o, volume e flags chegam ao mesmo n?cleo causal usado pelo Replay.

SHA-256 ? calculado junto da leitura streaming. Contagens, primeiro/?ltimo LAST, trailer e manifesto s?o conferidos antes de devolver o resultado. Arquivo ausente, INCOMPLETE, manifesto ausente ou corrup??o geram erro; nada ? criado, migrado ou reimportado.

A mem?ria cont?m indicadores e eventos/records de setups de um ?nico dia, sem lista de LASTs. `finish()` mant?m os outcomes n?o encerrados como incompletos segundo o tracker existente. Nenhum candle ou pre?o futuro ? sintetizado.

## Console

Mostra s?mbolo/data UTC, LASTs processados, CRZ/RJ/PULLB separados por dire??o, outcomes completos/incompletos, tempo total e LASTs/s. COMPLETE na contagem geral significa todas as tr?s janelas completas. A listagem individual informa o status de cada janela, inclusive m?tricas nulas, exatamente como retornadas pelo tracker.

O tempo do Batch inclui abertura/valida??o do manifesto, leitura, verifica??o do hash, processamento causal e consolida??o. N?o inclui a execu??o separada do Replay usada pelo teste de paridade.

## Valida??o

`Pullback09BatchRunnerTest`: UP, DOWN, ambos; janelas completas/incompletas; igualdade de todos os eventos CRZ/RJ/PULLB e records (dire??o, timestamps, refer?ncia, indicadores, MFE/MAE e status); valores esperados de excurs?o; timestamps iguais/sequence; arquivo/manifesto ausente; INCOMPLETE; corrup??o; contagens/contrato; sequ?ncia inv?lida; header de m?ltiplos dias e console.

```powershell
mvn -pl panic-scanner -am test '-Dtest=Pullback09BatchRunnerTest,M5ReplaySessionTest,Pullback09SetupTest,Pullback09DownSetupTest,Pullback09ResearchProfileTest,CausalSessionVwapTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Teste real opt-in, exclusivamente no dataset local de 14/09/2026:

```powershell
mvn -pl panic-scanner -am test '-Dtest=Pullback09BatchLocalDayTest' '-Dbatch.real.day=true' '-DargLine=-Xmx512m' '-Dsurefire.failIfNoSpecifiedTests=false'
```

A compara??o executa `M5ReplaySession` sobre o mesmo arquivo, aguarda sua conclus?o e compara listas completas de eventos e records com igualdade exata, sem toler?ncia nos MFE/MAE. N?o basta comparar contagens.
O log do teste real fica em `panic-scanner/target/pullback09-batch-2026-09-14.txt`.

Arquivos criados nesta etapa: `Pullback09CausalProcessor.java`, `Pullback09BatchRunner.java`, `Pullback09BatchCommand.java`, `Pullback09BatchRunnerTest.java`, `Pullback09BatchLocalDayTest.java` e este documento. ?nico arquivo de c?digo existente alterado: `M5ReplaySession.java`, para chamar o n?cleo compartilhado.

Esta etapa n?o adiciona m?ltiplos dias, intervalo, frontend, job, relat?rio agregado, thresholds ou CSV.

## Resultado real validado ? 14/09/2026

WINV26: 1.872.569 LASTs; 57,696 s incluindo valida??o do dataset; 32.455,53 LASTs/s.
CRZ09 UP/DOWN: 11/9. RJ09 UP/DOWN: 2/3. PULLB09 UP/DOWN: 1/1.
Dois outcomes completos em todas as janelas e nenhum incompleto.

| Dire??o | Refer?ncia | MFE/MAE 5m | MFE/MAE 15m | MFE/MAE 30m |
|---|---:|---:|---:|---:|
| UP | 187005 | 105 / 715 | 105 / 1385 | 105 / 1445 |
| DOWN | 187795 | 220 / 25 | 365 / 25 | 525 / 25 |

Todos os movimentos est?o em pontos. UP: C3 ?s 10:15 UTC, dispon?vel ?s 10:20:00.006 UTC. DOWN: C3 ?s 13:55 UTC, dispon?vel ?s 14:00:00.005 UTC.

84 testes determin?sticos/regress?es e 1 teste real aprovados; zero falhas.
Batch ? Replay id?nticos em toda a lista ordenada de eventos e todos os campos dos records.
ZERO chamadas a MT5/Python pelo Batch e pela compara??o real. Hashes dos sete arquivos existentes de regras, engine, tracker, records e VWAP permaneceram iguais aos anteriores ? etapa 2.
