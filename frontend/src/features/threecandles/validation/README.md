# M5 do pregao corrente ? WIN$N, 16/10/2026

Detector existente 2+2 M5, sem filtros de proximidade, agrupamento ou quota. Cada pivo conserva seu ID (simbolo, timeframe, origem e tipo), preco, regiao do candle, confirmacao e primeiro rompimento por fechamento. Pivos distintos no mesmo preco permanecem na memoria; repeticoes do mesmo ID sao rejeitadas. A janela M5 reinicia por data civil do feed MT5. A memoria anterior permanece disponivel internamente e nao e desenhada como M5 do dia.

O H1 2+2 e o experimental 1+1 nao foram modificados. Os nove candidatos H1 estritos do dia coincidiram integralmente, incluindo IDs, precos, horarios e estados, com a fixture experimental anterior a esta mudanca. As nove linhas preparadas foram preservadas em todos os prefixos do Replay.

| Cursor MT5 | Pivos M5 | M5 rompidos ainda presentes | H1 |
|---|---:|---:|---:|
| 10:00 | 3 | 0 | 9 |
| 12:00 | 10 | 8 | 9 |
| 16:00 | 22 | 15 | 9 |
| 18:00 | 29 | 21 | 9 |

113 candles fechados do dia, 31 pivos confirmados no fim da sessao. Cada prefixo foi comparado com uma verificacao independente de extremos estritos de cinco candles do dia e com o pipeline incremental. Nenhum ID duplicado, confirmacao futura, pivo anterior removido ou referencia M5 de outro pregao. Reinicio reconstruiu o mesmo resultado; reconexao com memoria e bootstrap intradiario produziram o mesmo snapshot. O candle em formacao nao confirma pivos. O parametro legado maxLevels continua aceito pela API, mas nao limita M5.

## Capturas

- [10:00 ? 3 M5](oct16-1000.png)
- [12:00 ? 10 M5, incluindo 8 rompidos](oct16-1200.png)
- [16:00 ? 22 M5, incluindo 15 rompidos](oct16-1600.png)
- [12:00 ? H1 oculto, M5 preservado](oct16-1200-h1-preparation-none.png)

Capturas do frontend real em Edge headless, com API interceptada entregando snapshots causais produzidos pelo Replay Java sobre o cache local exportado do MT5. Os tres pregoes anteriores estao no contexto visual. Nao houve conexao ao stream MT5 durante as capturas nem reinicio do backend ja em execucao. O fluxo Tempo Real foi verificado pelo pipeline compartilhado e pelos testes do adaptador, incluindo reconexao; nao foi acompanhado um pregao ao vivo.

## Verificacao

- 47 testes Java do modulo passaram; 5 testes opcionais sem fontes adicionais foram ignorados.
- 7 testes de StructureReactionScanner passaram.
- 116 testes de frontend passaram; TypeScript `tsc -b` passou.
- Nenhuma alteracao desta tarefa no detector compartilhado, M5ReplaySession ou outras estrategias. As alteracoes preexistentes da workspace foram preservadas.

Para repetir o Replay de auditoria na raiz do repositorio (JDK 21):

```powershell
mvn -pl panic-scanner -am test '-Dtest=*ThreeCandle*,M5SessionReplayTest,H1SessionPreparationTest,H1ExperimentalComparisonTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dthreecandles.m5Cache=C:/Users/paulo/project/cripto-analyser/historical-data/WIN$N/three-candles-m5/2026-10-16.json'
```

Snapshots e relatorio regeneraveis em `panic-scanner/target/threecandles-m5-validation`. [Relatorio desta execucao](report.json).
