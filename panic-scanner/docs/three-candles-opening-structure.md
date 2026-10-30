# Contexto estrutural experimental da abertura WIN

Branch: `feat/three-candles-foundation`. Implementação isolada em `OpeningStructureContext`; integração aditiva nas respostas de Live e Replay. Não fornece entradas para motores, detector, estratégias ou Fibonacci.

## Auditoria de D-1 e dos horários

Consulta explícita de WINZ26 pelo `mt5_reader.py` existente, usando `MetaTrader5.copy_rates_range`. Nenhum WIN$N, contrato de outro vencimento ou ajuste de preços foi utilizado. Dados brutos, hashes e recibo da consulta em `src/test/resources/threecandles/opening-structure/source-audit.json`.

Todos os preços/amplitudes são pontos do índice WIN, não reais. O eixo preserva o relógio civil MT5 (`MT5_WALL_CLOCK`); os valores epoch do feed não recebem conversão automática para Brasília. Disponibilidade causal de M5 fechado = início + 5 minutos. A fonte histórica não registra o instante real de ingestão de cada barra; esses horários indicam disponibilidade pelo fechamento, não latência histórica de rede. A abertura negociada existe desde o primeiro negócio, mas esta projeção só a expõe quando fecha o primeiro M5, às 09:05.

| Dado | Valor observado | Origem | Disponibilidade usada |
|---|---:|---|---|
| Fechamento de 29/10 | 171.050 | Último M5 nativo, origem 18:20; corroborado pelo `close` D1 nativo | 29/10 18:25 |
| Ajuste oficial de 29/10 | Ausente | Nenhuma fonte de ajuste oficial encontrada no fluxo | Ausente (`null`) |
| Máxima de 29/10 | 173.805 | M5 10:40; corroborada pelo D1 | Extremo conhecido às 10:45; resumo observado de D-1 disponível às 18:25 |
| Mínima de 29/10 | 170.775 | M5 18:15; corroborada pelo D1 | Extremo conhecido às 18:20; resumo observado de D-1 disponível às 18:25 |
| Abertura de 30/10 | 170.910 | `open` do M5 09:00 | Exposta conservadoramente às 09:05 |
| Máximas/mínimas intradiárias | OHLC nativo | M5 fechados desde 09:00, inclusive | Cada fechamento M5, sem esperar fractais |

O adaptador D1 existente é `Mt5MarketDataClient`; ele não fornece ajuste oficial nem metadados que certifiquem o último negócio da sessão. O contexto reutiliza os M5 já presentes no aquecimento, sem consultas novas por snapshot. Como a memória pode estar incompleta, informa **último fechamento M5 observado**, máxima/mínima observadas, data exata e cobertura não certificada. A coincidência D1/M5 acima é evidência desta reprodução, não uma certificação geral. Não transforma o `close` D1 em ajuste.

Se faltar D-1, suas referências e o gap ficam ausentes; a perna intradiária funciona normalmente. Se o aquecimento contiver somente uma sessão mais antiga, a data exibida continua sendo a dessa sessão, sem rebatizá-la como o pregão imediatamente anterior. Contrato/vencimento é filtrado por igualdade exata de símbolo.

## Origem e evolução da perna

A direção inicial é uma hipótese fixada no primeiro fechamento diferente da abertura negociada: `close > open` indica alta; `close < open`, baixa. Doji aguarda outro fechamento. A origem é a mínima do primeiro M5 para alta ou sua máxima para baixa. O destino é a máxima/mínima acumulada de todos os M5 fechados desde 09:00. Empates mantêm a primeira ocorrência.

O estado é sempre `EM_FORMACAO`; não há promoção para pivô ou tendência. A origem fica fixa, inclusive durante correções. O contexto não encerra automaticamente quando surge o 2+2: ele continua sendo um resumo experimental da abertura, com controle visual independente. Cada M5 fechado acrescenta uma versão a `legHistory`; reconstrução e execução incremental preservam as versões anteriores. Nenhuma micro-onda é removida.

No primeiro M5, a ordem máxima/mínima é desconhecida. O contexto registra a amplitude com `UNKNOWN_SAME_M5`, mas o renderer não conecta esses extremos. A primeira linha possível liga candles distintos; em 30/10 surge às **09:10**, do topo observado 09:00 (171.600) à mínima 09:05 (170.420), amplitude 1.180 pontos. É hipótese sobre extremos observados, não uma sequência de negócios reconstruída.

O gap é `170.910 - 171.050 = -140 pontos`, conhecido pela projeção às 09:05. É identificado como `GAP` e desenhado verticalmente na abertura; não existe uma onda diagonal negociada entre o fechamento de D-1 e a abertura.

## Reprodução causal de 30/10

| Ponto | Preço | Extremo do M5 disponível | Confirmação micro | Confirmação principal |
|---|---:|---|---|---|
| Máxima de abertura 09:00 | 171.600 | 09:05 | Não é pivô de borda | Não é pivô de borda |
| Fundo 09:15 | 170.115 | 09:20 | 09:25 | 09:30 |
| Topo 09:25 | 170.510 | 09:30 | 09:35 | Não passa no 2+2 |
| Fundo 09:45 | 169.070 | 09:50 | 09:55 | 10:00 |
| Topo 10:00 | 169.785 | 10:05 | 10:10 | 10:15 |

A linha de abertura alcança o fundo 09:15 às 09:20 e o fundo 09:45 às 09:50, sem antecipar confirmações. O topo micro 09:25 permanece na cadeia micro. A correção até 10:00 permanece representada pelos motores existentes; a perna inicial de baixa continua apontando para a mínima acumulada de 169.070 (amplitude 2.530 pontos). O principal continua formando sua primeira onda somente às 10:15, com origem 09:45.

As janelas são intervalos `[09:00, fim)`: 09:00–09:15 inclui barras 09:00, 09:05 e 09:10. Portanto o candle de origem 09:45 não entra na janela que termina às 09:45.

| Janela | Amplitude final | Disponível |
|---|---:|---|
| 09:00–09:15 | 1.480 pontos | 09:15 |
| 09:00–09:30 | 1.485 pontos | 09:30 |
| 09:00–09:45 | 2.070 pontos | 09:45 |

Janelas ainda abertas mostram amplitude parcial; se faltar um M5, não são marcadas completas. M5 09:00 ausente torna a perna indisponível: nenhum início arbitrário de janela vira abertura.

0, 250 e 300 pontos são comparações experimentais da amplitude da perna. O backend informa quando cada limiar foi alcançado; o seletor filtra somente a nova linha. Não há bloqueio de pivôs até 09:30/09:45, sinal operacional ou regra de entrada nova. O gap é mostrado independentemente desse filtro.

## Fibonacci futura

`DowFiftyEngine` e a Fibonacci atual permanecem intactos. Nenhum 50% novo é calculado ou desenhado nesta implementação. Opção futura proposta: controle desligado por padrão **“50% da perna de abertura (experimental)”**, calculado por `(origem intradiária + destino intradiário) / 2`, versionado pelo fechamento conhecido. Deve ter identidade, cor e auditoria próprias e excluir integralmente o gap. Em 09:50, por exemplo, seria 170.335, distinto dos 50% do Dow principal. Essa opção exige avaliação separada antes de implementação.

## Validação e capturas

- 18 prefixos nativos M5 de 30/10 até 10:30: contexto incremental, repetição idempotente, reconstrução com memória futura cortada e `ThreeCandleReplay` concordam.
- 90 passos M1 nativos (72 observações parciais e 18 fechamentos): `ProgressiveM1History` valida OHLC M1 contra o M5. O contexto ignora todos os M5 parciais; reconstruções em cada minuto coincidem com o replay.
- Golden snapshots anteriores à implementação preservam os pivôs, movimentos e eventos micro/principal integralmente. Snapshots antes/depois e testes frontend também comparam DOW 50% e a Fibonacci legada.
- Pregões nativos adicionais: 22/10, gap -1.560; 23/10, gap +1.090; 30/10, gap -140 (pequeno na comparação experimental de 250 pontos). Casos sintéticos cobrem gap zero, pequeno, alto/baixo, doji, falta de abertura/D-1/M5 e outro vencimento.
- `source-audit.json` registra hashes dos motores, detector e scanner preservados. As únicas integrações Java são campos aditivos de resposta em Live/Replay; não há chamadas do contexto a estratégias nem alterações em suas regras.

Resultados: 4 testes novos de contexto aprovados; 7 testes de `StructureReactionScannerTest` aprovados; regressões selecionadas de Dow/3 Candles/Replay aprovadas (casos dependentes de propriedades externas permanecem condicionais). Os 80 testes frontend de 3 Candles passaram, além da verificação TypeScript. Os pares antes/depois comparam também a saída da Fibonacci legada, sem inserir a nova perna nas suas âncoras.

Snapshots e capturas em `frontend/src/features/threecandles/validation/opening-structure/`: pares `0910`, `0935`, `0955`, `1015` com sufixos `before`/`after`. São capturas da interface React real com API interceptada por snapshots Java sobre a fonte nativa, mesmo cursor, viewport e camadas. O par anterior omite somente o campo novo `openingStructure`; não é uma captura da sessão original do usuário. Relatórios `*-browser.json` registram erros do navegador e resumos exibidos.

Reproduzir: testes `OpeningStructureContextTest` e regressões `*Dow*Test`, `ThreeCandle*Test`, `ProgressiveM1ReplayTest`; frontend `node --experimental-strip-types --test tests/threeCandles*.test.mjs` e `npx tsc -b`. Capturas: `node frontend/scripts/captureOpeningStructure.mjs` com Vite local ativo. O backend em execução precisa ser reiniciado/recarregado com esta versão para enviar o campo aditivo; a aplicação já em execução não foi reiniciada por esta investigação.
