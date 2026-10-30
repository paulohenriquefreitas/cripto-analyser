# Dow 50%: acompanhar a onda em formacao

Caso confirmado: WINZ26, 29/10/2026. A imagem mostra 29/10/2026 15:50 no cursor e o desktop em 29/10/2026. A data anterior 09/10 nao correspondia a esse recorte. Os OHLC e os dois niveis foram reproduzidos a partir de uma captura da API live compartilhada do MT5 em 29/10/2026 as 16:18:48, preservada na fixture `src/test/resources/threecandles/dow-fifty-winZ26-2026-10-29.json`.

## Causa comprovada

Nao foi necessario aplicar deslocamento de timestamp. O fundo de 15:25 em 170985 foi confirmado as 15:40. A onda anterior (topo 14:45 em 171790 ate esse fundo) manteve 50% em 171387,5. A alta rompe esse topo no candle 15:50, cujo fechamento fica disponivel as 15:55. O novo topo ocorre em 15:55 a 172845, mas o fractal estrito 2+2 so e confirmado as 16:10, apos os fechamentos das duas velas seguintes. Ate essa confirmacao, o codigo exibia somente a onda confirmada anterior.

Reproducao no debugger IntelliJ: `DowFiftyOctober29Test.confirmedReferenceWaitsUntil1610OnOctober29`. Logpoint nao suspensivo em `DowFiftyEngine.activate` registrou a onda UP 170985 -> 172845, level50=171915, terminal.extremeTimeMsc=1793289300000 (15:55), recognizedAtMsc=1793290200000 (16:10). Estado pausado na assercao da linha 30 do teste mostrou o snapshot anterior com cutoff=1793289300000 (15:55) e a onda DOWN anterior, e o posterior com cutoff=1793290200000. Breakpoints criados para o diagnostico foram removidos. A aplicacao em uso nao foi reiniciada nem pausada.

## Ajuste

`DowFiftyEngine` agora entrega `formingWave`, uma projecao visual separada das ondas e eventos confirmados. A ancora e o ultimo terminal confirmado do Dow principal. O rompimento observado do extremo oposto inicia a projecao; o extremo movel acompanha maxima/minima efetivamente disponivel. Funciona com M5 fechado, parcial do tempo real e observacoes M1 progressivas. Rompimento pelo range e uma hipotese visual, sem transformar pavio em confirmacao de tendencia. Um range que ultrapassa a propria ancora invalida a hipotese, sem inventar ordem intrabar.

O grafico prioriza a linha de 50% dessa projecao e identifica `em formacao`; a diagonal provisoria e tracejada. Quando o pivo 2+2 e confirmado, a projecao entrega o lugar a onda confirmada. A linha nao e retroativamente declarada confirmada. O monitoramento confirmado, Dow principal, Dow micro, detectores e Fibonacci manual/antiga nao recebem pivos antecipados.

| Instante disponivel | Ultimo candle fechado | Referencia visual nova | Referencia confirmada |
| --- | --- | --- | --- |
| 15:50 | 15:45 | nenhuma, topo anterior ainda nao rompido | 171387,5 |
| 15:55 | 15:50 | 171767,5 em formacao (maxima 172550) | 171387,5 |
| 16:00 | 15:55 | 171915 em formacao (maxima 172845) | 171387,5 |
| 16:05 | 16:00 | 171915 em formacao | 171387,5 |
| 16:10 | 16:05 | entrega para a onda confirmada | 171915 |

O rotulo do eixo e a origem do candle; o fechamento desse M5 fica disponivel cinco minutos depois. Em tempo real/parciais, a projecao pode surgir dentro do candle assim que o rompimento for observado. Nao se utiliza o OHLC final do M5 para antecipar seus minutos internos.

## Validacao

- 15 testes Java passaram: caso real, alta/baixa, invalidacao, replay incremental versus reconstruido, parcial sintetica identificada como tal, tempo e replay progressivo.
- 77 testes frontend passaram, incluindo transporte causal, renderer, Dow, Fibonacci, agrupamento M5, pagina e horario.
- TypeScript passou.
- Snapshots causais em `frontend/src/features/threecandles/validation/dow-fifty-oct29/`.
- Capturas `before.png` e `after.png`: pagina React/grafico reais, mesma janela de dados as 16:05; a API foi interceptada com o snapshot congelado. Antes omite somente a nova projecao, reproduzindo a politica anterior. As referencias H1 nao estao preparadas nessa fixture de diagnostico. Nao sao capturas de mercado ao vivo.

Reiniciar o backend para disponibilizar o novo campo e recarregar a pagina. Backends anteriores continuam compativeis, mas so fornecem a onda confirmada antiga.
