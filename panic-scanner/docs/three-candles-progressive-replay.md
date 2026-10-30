# Replay progressivo M1 e origem visual do DOW 50%

Na branch `feat/three-candles-foundation`, `/three-candles` oferece **Replay por candle fechado** (padrao) e **Replay progressivo M1 (experimental)**. Selecione M5, escolha o modo e carregue a sessao. Mudar de modo recria a sessao; Play, Pause, Reiniciar, Proximo M1 e velocidades 1/2/5/10x permanecem disponiveis.

## Conhecimento por instante

Um passo progressivo consome um **M1 nativo fechado**, disponivel no fim daquele minuto. A abertura M5 vem do primeiro M1; maxima/minima acumulam somente minutos consumidos; fechamento parcial e o ultimo fechamento M1 conhecido. O quinto minuto fecha o M5. A 1x, cada observacao leva um segundo de reproducao. Nao se inventa uma trajetoria dentro do minuto: essa modalidade tem resolucao M1, nao ticks.

`ProgressiveM1History` prepara as observacoes; `ThreeCandleReplay` usa o mesmo pipeline e controles nos dois modos. O pipeline ja aceitava atualizacoes parciais e os detectores continuam trabalhando com fechamentos. Dow e o monitor 50% continuam usando M5 fechado, com seus tempos de confirmacao originais. O preco parcial pode atravessar visualmente a linha antes de existir um evento experimental confirmado por fechamento M5.

Os M5 oficiais sao usados para conferir o agregado, nunca para preencher precos parciais. Cada fechamento deve coincidir exatamente em OHLC. Aquecimento anterior a abertura continua fechado e conhecido. O M1 fica em cache separado `three-candles-m1`, com simbolo exato, periodo, data e SHA-256; o cache M5 e o historico de ticks nao sao substituidos. A ponte MT5 passa a aceitar M1, mantendo M5/D1 e o modo tick anteriores.

**Lacunas reais:** WINZ26 nao fornece M1 de 15:01 em 28/10/2026, inclusive em uma segunda consulta limitada a 15:00-15:05. O Replay conserva a lacuna e informa `missingM1Times` na resposta e um aviso na pagina. Esse bucket possui quatro atualizacoes reais, em vez de cinco. Nao e criado candle nem se presume ausencia de negocios. Somente lacunas internas sao aceitas, e apenas se o OHLC agregado ainda coincidir exatamente com M5 oficial. Falta do primeiro/ultimo minuto ou divergencia bloqueia a modalidade com erro explicito. A lista de lacunas e metadado de cobertura da fonte, nao um evento de mercado futuro usado pelos detectores.

Ticks sao uma evolucao futura: outra sequencia de observacoes causais poderia alimentar o mesmo pipeline. Nao ha interpolacao de ticks nem mudanca da estrategia nesta entrega.

## Origem visual e disponibilidade sao distintas

A linha branca de 2,5 px com etiqueta `DOW 50% — preco` nasce no centro das coordenadas horizontais dos dois extremos da onda e estende-se para a direita. Usar as duas coordenadas nativas evita deslocamentos quando o centro cai entre candles ou atravessa lacunas do eixo temporal. O nivel continua `(maxima+minima)/2`.

Ela so aparece quando `recognizedAtMsc <= availableAtMsc`, conservando o criterio de conclusao documentado em [three-candles-dow-fifty.md](three-candles-dow-fifty.md). Desenhar a origem na area historica da onda e uma associacao geometrica apresentada depois da confirmacao, nao uma alteracao retrospectiva da disponibilidade do nivel. Antes da confirmacao nao existe linha. Continua havendo uma unica referencia vigente e nenhuma contribuicao para autoscale.

O clique continua selecionando a onda original: extremos, precos, formacoes, confirmacoes, maxima/minima, ponto medio e **linha vigente desde** o reconhecimento. Consultar historico nao reativa referencias encerradas. Detectores, selecao de ondas, Fibonacci manual e Fibonacci automatica antiga foram preservados.

## Validacao nativa

As duas series sao separadas, com timestamps civis originais do MT5. Os M1 foram consultados novamente; os M5 oficiais e cortes anteriores foram mantidos. Arquivos, SHA-256 e consulta estao em [sources.json](../../frontend/src/features/threecandles/validation/progressive/sources.json).

| Serie/data | M5 oficiais | Passos M1 reais | Atualizacoes parciais | Corte preservado | Igualdade no fechamento |
|---|---:|---:|---:|---|---|
| WINZ26 28/10/2026 | 103 | 514 | 411 | 17:35 | Todos os 103 OHLC iguais |
| WIN$N 16/10/2026 | 113 | 565 | 452 | 18:25 | Todos os 113 OHLC iguais |

Os testes comparam todos os fechamentos com o Replay antigo, incluindo estrutura, ondas Dow, eventos e monitor 50%. Cada estado parcial e reconstruido por instante com a fonte completa disponivel ao reconstrutor, sem disponibilizar minutos futuros ao pipeline. Tambem verificam reset, play/pause, velocidade, cache M1 separado, erros por divergencia e lacunas nas bordas. Resultado exportado em [summary.json](../../frontend/src/features/threecandles/validation/progressive/summary.json).

Verificacao final: 63 testes frontend, TypeScript, 28 testes Java distintos e seis testes Python aprovados; tres testes Java condicionais ignorados. As 11 capturas novas nao registraram erros de navegador. Os hashes dos motores Dow principal e micro coincidem com a auditoria anterior. Detalhes em [verification.json](../../frontend/src/features/threecandles/validation/progressive/verification.json).

Exemplo do candle M5 aberto 09:55 em WINZ26, aproximando-se do nivel 177.360, conhecido desde 09:50:

| Disponivel em | Abertura | Maxima acumulada | Minima acumulada | Fechamento conhecido | Estado |
|---|---:|---:|---:|---:|---|
| 09:56 | 177.210 | 177.275 | 177.165 | 177.240 | Parcial |
| 09:57 | 177.210 | 177.340 | 177.145 | 177.310 | Parcial |
| 09:58 | 177.210 | 177.350 | 177.145 | 177.350 | Parcial |
| 09:59 | 177.210 | 177.470 | 177.145 | 177.425 | Parcial |
| 10:00 | 177.210 | 177.485 | 177.145 | 177.410 | Fechado, igual ao oficial |

No modo antigo, esse candle somente aparece completo as 10:00. No progressivo, as cinco observacoes atualizam o mesmo candle, sem recalcular a onda ou antecipar a confirmacao M5.

## Capturas

Capturas da interface React e grafico nativo, com respostas Java reais exportadas pelos testes e HTTP interceptado; nao sao uma sessao conectada de ordens. As imagens anteriores foram preservadas, e as novas foram recapturadas. A auditoria de navegador verifica estilo branco 2,5 px, etiqueta no centro projetado da onda, clique de selecao, autoscale e independencia de Fibonacci.

| Comparacao | Capturas |
|---|---|
| Origem WINZ26 09:50 | [Antes](../../frontend/src/features/threecandles/validation/progressive/WINZ26-confirmed-before.png), [depois](../../frontend/src/features/threecandles/validation/progressive/WINZ26-confirmed-after.png) |
| Origem WIN$N 10:00 | [Antes](../../frontend/src/features/threecandles/validation/progressive/WIN_N-confirmed-before.png), [depois](../../frontend/src/features/threecandles/validation/progressive/WIN_N-confirmed-after.png) |
| WINZ26 candle fechado | [Modo antigo 10:00](../../frontend/src/features/threecandles/validation/progressive/WINZ26-closed-1000.png) |
| WINZ26 formacao progressiva | [09:56](../../frontend/src/features/threecandles/validation/progressive/WINZ26-0956.png), [09:57](../../frontend/src/features/threecandles/validation/progressive/WINZ26-0957.png), [09:58](../../frontend/src/features/threecandles/validation/progressive/WINZ26-0958.png), [09:59](../../frontend/src/features/threecandles/validation/progressive/WINZ26-0959.png), [fechamento 10:00](../../frontend/src/features/threecandles/validation/progressive/WINZ26-1000.png) |
| WIN$N | [Modo antigo](../../frontend/src/features/threecandles/validation/progressive/WIN_N-closed-1000.png), [parcial 09:59](../../frontend/src/features/threecandles/validation/progressive/WIN_N-0959.png), [fechamento 10:00](../../frontend/src/features/threecandles/validation/progressive/WIN_N-1000.png) |

Reproducao: JDK 21, `mvn -pl panic-scanner -am test -Dtest=ProgressiveM1ReplayTest,ThreeCandleHistoryLoaderTest,DowFiftyEngineTest,DowStructureEngineTest,MicroDowEngineTest,MicroDowOpeningTest,MicroDowContinuityTest,MicroDowOctober28ValidationTest -Dsurefire.failIfNoSpecifiedTests=false`. Frontend: `node --experimental-strip-types --test tests/threeCandles*.test.mjs` e `npx tsc -b`. Ponte: `python -m unittest test_mt5_reader.py` no diretorio scripts.

Para as capturas, iniciar Vite em 127.0.0.1:5174 e definir `DOW_FIFTY_CAPTURE_ROOT=progressive/` ao executar `node frontend/src/features/threecandles/validation/captureDowFifty.mjs`. O caminho padrao continua sendo `dow-fifty/`.
