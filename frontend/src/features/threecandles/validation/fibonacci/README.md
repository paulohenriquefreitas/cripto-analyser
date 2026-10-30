> Registro anterior ? corre??o de renova??o. A pol?tica e os n?meros vigentes est?o em [renewal/README.md](renewal/README.md).

# Fibonacci dinâmica — WIN$N, 16/10/2026

## Hipótese explicitamente experimental

Cada amplitude usa somente pivôs originais confirmados do próprio timeframe, nunca os representantes agrupados. Micro: M5 da sessão selecionada. Macro: H1 2+2 dos três pregões de contexto e do dia atual. Os candidatos adicionais 1+1 não entram no motor; suas linhas e critérios permanecem intactos.

A primeira dupla oposta com preços e origens válidos inicia a hipótese. A ordem de origem define alta/baixa. Alta amplia apenas por topo mais alto confirmado; baixa apenas por fundo mais baixo confirmado. O extremo oposto fica fixo. Isso é uma política de estudo para gerar hipóteses auditáveis, não uma regra confirmada das aulas. Novos extremos contrários fora da amplitude registram ambiguidade sem inverter automaticamente a direção. Não há regra validada para encerrar/reiniciar um movimento: a seleção manual permite analisar outra amplitude sem alterar o histórico automático.

Cada versão conserva instante da decisão, preços, origens, confirmações, direção, motivo e IDs anteriores/novos. O preço de 50% é a média exata, inclusive meios pontos. Contatos com 50% não alteram âncoras. Aproximação usa distância do intervalo do candle à linha (parâmetro inicial 5 pontos para observação); teste usa máxima/mínima; possíveis rejeições/rompimentos usam fechamentos consecutivos e não presumem ordem intrabar. São observações geométricas sem validade operacional. Uma versão só é testada em candles cuja abertura é posterior ou igual à sua decisão.

## Evolução Micro M5

| Decisão MT5 | Máxima | Mínima | 50% | Motivo |
|---|---:|---:|---:|---|
| 2026-10-16 09:45 | 209375 | 208675 | 209025 | INITIAL_OPPOSITE_PAIR_HYPOTHESIS |
| 2026-10-16 10:25 | 209375 | 208640 | 209007.5 | CONFIRMED_LOWER_BOTTOM_HYPOTHESIS |
| 2026-10-16 12:00 | 209375 | 208340 | 208857.5 | CONFIRMED_LOWER_BOTTOM_HYPOTHESIS |
| 2026-10-16 13:20 | 209375 | 208280 | 208827.5 | CONFIRMED_LOWER_BOTTOM_HYPOTHESIS |
| 2026-10-16 14:20 | 209375 | 208215 | 208795 | CONFIRMED_LOWER_BOTTOM_HYPOTHESIS |

Foram registradas 12 ambiguidades Micro. Exemplos: o topo confirmado às 10:00 excede a máxima da hipótese de baixa; sua adoção como nova amplitude não é automática. As 2 versões Macro H1 ficam em trilha independente.

## Seleção manual e desenho

Exemplo manual às 11:00: máxima 210210 (origem 2026-10-16 10:40, confirmação 2026-10-16 10:55), mínima 208675 (origem 2026-10-16 09:30, confirmação 2026-10-16 09:45), 50% 209442.5. A escolha representa outra leitura de amplitude e permanece independente das ampliações automáticas posteriores.

Botões de máxima/mínima destacam círculos azuis nos pivôs confirmados; um clique próximo seleciona a âncora original. Seletores permitem escolher origens fora do viewport. Aplicar registra uma versão manual no instante do cursor, sem retroagir à origem. Ao reiniciar, decisões manuais desta sessão reaparecem somente quando o Replay chega ao instante em que foram feitas. Trocar a sessão limpa essa memória manual. Comparar mostra a outra leitura em azul tracejado.

A linha principal é contínua, branca no tema escuro, escura no tema claro e tem 2,5 px no canvas. Máxima/mínima têm 1 px e opacidade 0,3. As linhas começam no instante da decisão, não nas origens dos pivôs; não se reescreve a linha antiga para justificar o passado. As versões antigas permanecem na auditoria. O primitive não fornece autoscaleInfo: níveis distantes não ampliam o eixo. O gráfico dedicado ao módulo preserva as linhas M5/H1, recebe o agrupamento existente do controller e mantém os mesmos recursos nativos de zoom e Ajustar gráfico. Nenhuma tela de outra estratégia foi alterada.

## Validação

113 prefixos de candles fechados: reconstrução igual ao avanço incremental, preços corretos, confirmação de ambas as âncoras não posterior à decisão, histórico prefixal estável e memória estrutural intacta. O adaptador rejeita dados futuros por cutoff tanto em pivôs quanto em candles. O backend acrescenta confirmedPivots ao snapshot do próprio módulo, sem modificar detector nem seleção visual. Respostas antigas funcionam com os pivôs disponíveis, com aviso de ausência do H1 intradiário.

Testes de alta/baixa, ampliação, ambiguidades, duplicação, cálculo fracionário, validação manual, interações sem redesenho, seleção gráfica, comparação independente, reset sem retroagir decisões, cor/espessura e ausência de contribuição ao autoscale. [Auditoria completa](report.json).

Nesta execução: 134 testes de frontend passaram, TypeScript passou; 47 testes Java do módulo passaram e cinco opcionais sem fontes adicionais foram ignorados. ESLint: zero erros, um aviso preexistente no cleanup do polling. A validação de viewport também foi repetida no gráfico novo: zoom manual preservado no NEXT; Ajustar gráfico e Reiniciar restauraram o eixo inicial.

## Capturas

[Amplitude inicial às 10:00](initial.png) · [Amplitude ampliada às 11:00](expanded.png) · [Comparação manual](manual.png) · [Manual preservada durante nova ampliação](manual-preserved.png) · [Macro H1](macro.png).

As capturas usam a página e a biblioteca reais em Edge headless, com API interceptada entregando prefixos dos snapshots Java sobre o histórico local exportado do MT5. Não certificam pregão ao vivo nem atualização do backend que já estava em execução. Para disponibilizar confirmedPivots/H1 intradiário na API local, reiniciar o backend com a versão atual. [Verificação do navegador](browser.json).

Para reproduzir na raiz, com frontend em localhost:5173 e Node 24:

```powershell
node --experimental-strip-types frontend/src/features/threecandles/validation/auditFibonacci.mjs
node frontend/src/features/threecandles/validation/captureFibonacci.mjs
```
