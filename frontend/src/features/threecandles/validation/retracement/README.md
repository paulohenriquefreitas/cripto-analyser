# Fibonacci de retração ancorada — WIN$N, 16/10/2026

A imagem original não está disponível neste ambiente. A comparação foi feita com a descrição fornecida: 0% no topo, 100% no fundo, 50% branco em destaque, diagonal branca pontilhada, faixa azul semitransparente e etiquetas diretamente nas linhas. Não se afirma igualdade de fonte, dimensões ou outros detalhes não fornecidos da ferramenta do Profit.

## Bibliotecas verificadas antes da implementação

- **TA4J 0.22.6**, explicitamente em `ta4j-engine/pom.xml`, com o mesmo artefato e seu sources JAR no cache Maven local. A inspeção do JAR encontrou `FibonacciReversalIndicator`, seus enums e validadores de relações Fibonacci/Elliott; nenhuma ferramenta gráfica de retração por duas âncoras. O source de `FibonacciReversalIndicator` recebe `PivotPointIndicator` e calcula suporte/resistência somando ou subtraindo um fator da amplitude do período ao pivot point. Não representa dois extremos arbitrários selecionados no gráfico. Não foi utilizado nem alterado.
- **Lightweight Charts 5.2.1**, confirmado no pacote instalado e no lockfile. A API instalada não contém uma ferramenta pronta de Fibonacci. Seu mecanismo oficial de [series primitives](https://tradingview.github.io/lightweight-charts/docs/plugins/series-primitives) suporta desenhos, anotações, camadas e etiquetas de eixo. A solução estende o `FibonacciPrimitive` já existente, implementando `ISeriesPrimitive`/`IPrimitivePaneRenderer`, com `timeToCoordinate` e `priceToCoordinate`.

Nenhuma dependência foi instalada e nenhuma versão foi atualizada. A média de 50% continua vindo do motor existente.

## Desenho

- **A** é o primeiro extremo no tempo; **B**, o segundo. Usam o horário de origem do candle e seu preço estrutural, não o horário posterior de confirmação/decisão.
- A diagonal pontilhada liga exatamente os dois pares horário/preço convertidos pela API. Uma alta liga o fundo anterior ao topo posterior; uma baixa liga o topo anterior ao fundo posterior.
- A orientação inicial solicitada é baseada em preço: **0% = máxima**, **50% = valor existente do motor**, **100% = mínima**, em ambos os sentidos do movimento.
- Os níveis começam na âncora mais à esquerda e se projetam para a direita. A faixa `#2563EB`, opacidade 0,10, preenche a amplitude entre 0% e 100%, também projetada à direita.
- O 50% principal mantém linha contínua branca de 2,5 px no tema escuro e `#111827` no claro. Os limites têm 1 px e opacidade 0,65. A diagonal principal tem 1,2 px, padrão pontilhado `[2,4]`.
- As etiquetas de percentual/preço e os marcadores A/B ficam em camada superior, com contorno da cor de fundo para leitura sobre os candles. A faixa e as linhas ficam abaixo dos candles. A comparação permanece azul tracejada, com etiquetas à direita; a ferramenta principal é desenhada por último para conservar seu destaque quando houver coincidência.
- Preços e horários nunca são substituídos por coordenadas fixas. Apenas larguras, fontes e espaçamentos tipográficos usam pixels. Cada repaint converte novamente as âncoras pela API, respondendo a zoom, deslocamento e redimensionamento. Se o timestamp de origem não puder ser convertido, a ferramenta não é artificialmente encaixada em outro candle.
- Não há `autoscaleInfo` no primitive. A área, os pontos e as etiquetas não ampliam o eixo para incluir extremos distantes.

Na descrição de referência, a média exata de 179040 e 177919 é 178479,5; o exemplo mostra 178480 sem fração. O cálculo e a precisão existentes foram preservados, sem alterar o motor para imitar esse arredondamento.

## Evolução e âncoras verificadas

| Captura / decisão | Direção | A: origem e preço | B: origem e preço | 0% | 50% | 100% |
|---|---|---|---|---:|---:|---:|
| Automática inicial às 10:00 / decisão 09:45 | Baixa | 09:25 · 209375 | 09:30 · 208675 | 209375 | 209025 | 208675 |
| Automática ampliada às 11:00 / decisão 10:25 | Baixa | 09:25 · 209375 | 10:10 · 208640 | 209375 | 209007,5 | 208640 |
| Manual às 11:00 | Alta | 09:30 · 208675 | 10:40 · 210210 | 210210 | 209442,5 | 208675 |

O aparecimento continua dependendo da versão já disponibilizada pelo Replay. Depois da confirmação, a ferramenta pode ligar suas duas origens históricas, como uma retração ancorada; isso não cria a ferramenta nos prefixos anteriores à decisão. As versões anteriores não são reescritas. Não houve mudança em `FibonacciMovementEngine`, seleção automática/manual, histórico, detectores, agrupamento ou processamento do Replay.

## Capturas

- [Antes: três linhas independentes](../fibonacci/initial.png)
- [Captura do gráfico com Fibonacci completa](complete.png)
- [Ferramenta completa — automática inicial](initial.png)
- [Amplitude ampliada — diagonal e área atualizadas](expanded.png)
- [Manual ascendente e comparação automática descendente](manual.png)
- [Manual preservada durante nova ampliação automática](manual-preserved.png)
- [Zoom horizontal](zoom-horizontal.png)
- [Zoom vertical](zoom-vertical.png)
- [Deslocamento](pan.png)
- [Redimensionamento](resize.png)
- [Ajustar gráfico](adjusted.png)

Capturas da página e biblioteca reais em Edge headless com API interceptada, entregando prefixos dos snapshots Java sobre o histórico MT5 local. O teste avança entre os snapshots conhecidos de 10:00, 11:00 e 12:00; não representa um pregão ao vivo nem execução pelo backend em operação. A seleção manual foi realizada por cliques reais nos círculos dos pivôs confirmados. [Registro do navegador](browser.json).

## Verificação

**138 testes frontend passaram; TypeScript passou.** Os testes novos verificam extremos exatos nos dois sentidos, 0/50/100, preenchimento azul, diagonal, texto, 2,5 px, conversões após pan/zoom/resize, renovação integral da estrutura, comparação manual preservada, origem ausente sem snap e ausência de contribuição ao autoscale. A suíte existente conserva a reconstrução causal dos 113 candles fechados e o histórico prefixal de versões.

No navegador: seleção manual, atualização automática, comparação, reset sem antecipar decisões, zoom horizontal/vertical, pan, resize e Ajustar gráfico executados sem exceções. Âncoras/valores manuais permaneceram iguais após essas interações. O teste de geometria confere os endpoints diretamente contra as conversões de horário/preço da API; os testes de memória confirmam que desenhar não altera os objetos das versões.

Reprodução na raiz do repositório, com Node 24 e frontend em `localhost:5173`:

```powershell
node frontend/src/features/threecandles/validation/captureRetracement.mjs
```
