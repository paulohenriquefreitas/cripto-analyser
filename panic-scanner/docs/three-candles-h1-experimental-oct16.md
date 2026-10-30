# Estudo experimental H1 1+1 — WIN$N, Replay de 16/10/2026

Modo opcional: `EXPERIMENTAL_1_1`; padrão mantido em `STRICT_2_2`. Nenhuma outra estratégia ou detector compartilhado foi alterado.

Comparação com H1 nativo: 9 pivôs 2+2, 13 candidatos 1+1, 4 adicionais; zero divergências de preço, origem ou confirmação. Todos confirmados até a abertura de 16/10. Sem score ou corte por relevância.

## Candidatos adicionais

| Origem MT5 | Tipo | Preço | Confirmação 1+1 |
|---|---|---:|---|
| 14/10 16:00 | BOTTOM | 210480 | 14/10 18:00 |
| 14/10 18:00 | TOP | 211210 | 15/10 10:00 |
| 15/10 14:00 | BOTTOM | 209215 | 15/10 16:00 |
| 15/10 15:00 | TOP | 210095 | 15/10 17:00 |

Nenhum adicional em 13/10. Dois em 14/10 e dois em 15/10. O topo de 14/10 às 18h confirma apenas às 10h de 15/10, após fechar o H1 seguinte. As quatro regiões falham no 2+2 por concorrência com um extremo mais forte a duas barras de distância; não foram rejeitadas pela seleção.

## Referência preservada e antecipação do 1+1

| Origem | Tipo | Preço | Confirmação 2+2 original | Confirmação 1+1 na auditoria |
|---|---|---:|---|---|
| 13/10 10:00 | BOTTOM | 206075 | 13/10 13:00 | 13/10 12:00 |
| 13/10 14:00 | BOTTOM | 206550 | 13/10 17:00 | 13/10 16:00 |
| 13/10 18:00 | BOTTOM | 206230 | 14/10 11:00 | 14/10 10:00 |
| 14/10 09:00 | TOP | 211115 | 14/10 12:00 | 14/10 11:00 |
| 14/10 11:00 | BOTTOM | 207615 | 14/10 14:00 | 14/10 13:00 |
| 14/10 15:00 | TOP | 211750 | 14/10 18:00 | 14/10 17:00 |
| 15/10 10:00 | BOTTOM | 207235 | 15/10 13:00 | 15/10 12:00 |
| 15/10 12:00 | TOP | 210800 | 15/10 15:00 | 15/10 14:00 |
| 15/10 16:00 | BOTTOM | 207440 | 15/10 19:00 | 15/10 18:00 |

As linhas da referência preservam seus IDs, preços, confirmações e estados 2+2. Os nove candidatos 1+1 equivalentes são auditados separadamente, sem desenhar uma segunda linha sobre o mesmo pivô.

## Avaliações não confirmadas pelo 1+1

47 avaliações sem candidato: 45 não satisfazem o extremo estrito dos vizinhos e 2 não dispõem de H1 fechado à direita no corte. Igualdade também falha o critério estrito. Estas últimas são avaliações pendentes, não pivôs prometidos.

70 candidatos 1+1 confirmados no aquecimento foram excluídos por origem fora de 13–15/10. Nenhum dos 13 candidatos confirmados da janela foi descartado por score, distância, rompimento ou limite.

| Origem | Tipo | Preço | Decisão | Motivo |
|---|---|---:|---|---|
| 13/10 09:00 | TOP | 208515 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=209085.0, right=208185.0 |
| 13/10 09:00 | BOTTOM | 206785 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=208725.0, right=206075.0 |
| 13/10 10:00 | TOP | 208185 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=208515.0, right=208035.0 |
| 13/10 11:00 | TOP | 208035 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=208185.0, right=207890.0 |
| 13/10 11:00 | BOTTOM | 206895 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=206075.0, right=207130.0 |
| 13/10 12:00 | TOP | 207890 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=208035.0, right=207820.0 |
| 13/10 12:00 | BOTTOM | 207130 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=206895.0, right=206865.0 |
| 13/10 13:00 | TOP | 207820 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=207890.0, right=207290.0 |
| 13/10 13:00 | BOTTOM | 206865 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=207130.0, right=206550.0 |
| 13/10 14:00 | TOP | 207290 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=207820.0, right=207285.0 |
| 13/10 15:00 | TOP | 207285 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=207290.0, right=207085.0 |
| 13/10 15:00 | BOTTOM | 206620 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=206550.0, right=206640.0 |
| 13/10 16:00 | TOP | 207085 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=207285.0, right=206975.0 |
| 13/10 16:00 | BOTTOM | 206640 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=206620.0, right=206680.0 |
| 13/10 17:00 | TOP | 206975 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=207085.0, right=206835.0 |
| 13/10 17:00 | BOTTOM | 206680 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=206640.0, right=206230.0 |
| 13/10 18:00 | TOP | 206835 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=206975.0, right=211115.0 |
| 14/10 09:00 | BOTTOM | 208890 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=206230.0, right=208160.0 |
| 14/10 10:00 | TOP | 209325 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=211115.0, right=209595.0 |
| 14/10 10:00 | BOTTOM | 208160 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=208890.0, right=207615.0 |
| 14/10 11:00 | TOP | 209595 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=209325.0, right=210325.0 |
| 14/10 12:00 | TOP | 210325 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=209595.0, right=211005.0 |
| 14/10 12:00 | BOTTOM | 208705 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=207615.0, right=209785.0 |
| 14/10 13:00 | TOP | 211005 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=210325.0, right=211635.0 |
| 14/10 13:00 | BOTTOM | 209785 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=208705.0, right=210785.0 |
| 14/10 14:00 | TOP | 211635 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=211005.0, right=211750.0 |
| 14/10 14:00 | BOTTOM | 210785 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=209785.0, right=211025.0 |
| 14/10 15:00 | BOTTOM | 211025 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=210785.0, right=210480.0 |
| 14/10 16:00 | TOP | 211365 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=211750.0, right=211050.0 |
| 14/10 17:00 | TOP | 211050 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=211365.0, right=211210.0 |
| 14/10 17:00 | BOTTOM | 210785 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=210480.0, right=208380.0 |
| 14/10 18:00 | BOTTOM | 208380 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=210785.0, right=207435.0 |
| 15/10 09:00 | TOP | 209285 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=211210.0, right=209560.0 |
| 15/10 09:00 | BOTTOM | 207435 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=208380.0, right=207235.0 |
| 15/10 10:00 | TOP | 209560 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=209285.0, right=210610.0 |
| 15/10 11:00 | TOP | 210610 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=209560.0, right=210800.0 |
| 15/10 11:00 | BOTTOM | 208785 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=207235.0, right=209815.0 |
| 15/10 12:00 | BOTTOM | 209815 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=208785.0, right=209730.0 |
| 15/10 13:00 | TOP | 210710 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=210800.0, right=209930.0 |
| 15/10 13:00 | BOTTOM | 209730 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=209815.0, right=209215.0 |
| 15/10 14:00 | TOP | 209930 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=210710.0, right=210095.0 |
| 15/10 15:00 | BOTTOM | 209365 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=209215.0, right=207440.0 |
| 15/10 16:00 | TOP | 209945 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=210095.0, right=208800.0 |
| 15/10 17:00 | TOP | 208800 | NOT_EXTREME_1_1 | Requires strict high > both neighbors; left=209945.0, right=208495.0 |
| 15/10 17:00 | BOTTOM | 208080 | NOT_EXTREME_1_1 | Requires strict low < both neighbors; left=207440.0, right=208050.0 |
| 15/10 18:00 | TOP | 208495 | UNCONFIRMED_1_1 | Missing closed left/right H1 before cutoff |
| 15/10 18:00 | BOTTOM | 208050 | UNCONFIRMED_1_1 | Missing closed left/right H1 before cutoff |

## Testes e reações por região

Agrupamento descritivo com amplitude máxima de 50 pontos, sem encadeamento de preços distantes e sem esconder linhas. Neste conjunto os 13 preços estão separados por mais de 50 pontos: não há grupos com múltiplos pivôs. Retestes não geram novos pivôs automaticamente.

Testes começam somente após todos os pivôs da faixa estarem confirmados. Horas consecutivas que intersectam a faixa formam um episódio; lacunas e mudança de sessão encerram o episódio. As reações são a excursão máxima para cima/baixo nas próximas duas H1 disponíveis, podendo atravessar sessões, sempre fechadas antes da abertura. Sem direção, qualidade ou score presumidos.

| Região | Pivôs | Episódios após confirmação |
|---|---:|---:|
| 206075–206075 | 1 | 0 |
| 206230–206230 | 1 | 0 |
| 206550–206550 | 1 | 1 |
| 207235–207235 | 1 | 0 |
| 207440–207440 | 1 | 0 |
| 207615–207615 | 1 | 2 |
| 209215–209215 | 1 | 1 |
| 210095–210095 | 1 | 0 |
| 210480–210480 | 1 | 2 |
| 210800–210800 | 1 | 0 |
| 211115–211115 | 1 | 2 |
| 211210–211210 | 1 | 0 |
| 211750–211750 | 1 | 0 |

Os detalhes de cada episódio, excursões e instante final da medição estão no JSON e na auditoria da página. O modo continua experimental: o aumento de sensibilidade adiciona oscilações internas e confirma mais cedo, sem provar relevância operacional.

## Artefatos visuais

[Gráfico H1 nativo interativo](three-candles-h1-experimental-oct16.html). Candles exportados diretamente pelo MT5; linhas sólidas são referência 2+2 e tracejadas são adicionais 1+1, ambas fúcsia. Linhas começam na confirmação, marcadores apontam a origem. O gráfico é um estudo retrospectivo com corte na abertura de 16/10, não uma simulação de divulgação candle a candle.

[Captura H1 nativo](three-candles-h1-experimental-oct16-native.png). [Captura M5](images/three-candles-h1-experimental-oct16.png). O preview M5 usa o frontend real com resposta Java validada e API interceptada; não certifica que o backend em execução foi reiniciado.

## Validação

Testes de igualdade nativa 1+1, preservação 2+2, igualdade entre preparo com histórico completo e corte pré-abertura, fechamento do candle direito, igualdade de extremos, regiões sem futuro, episódios consecutivos, transporte, controles, avanço e reinício do Replay. O critério não foi promovido a padrão.

Nesta execução: 50 testes Java passaram, três testes opcionais foram ignorados; 25 testes de frontend passaram; compilação TypeScript concluída. Os cinco testes de `H1ExperimentalComparisonTest` passaram, incluindo a comparação com o H1 nativo e a configuração HTTP experimental.

Para usar na página: reiniciar o backend com esta versão, selecionar Replay, `WIN$N`, 16/10/2026 e `Detecção H1 → 2+2 + 1+1 experimental`. O modo padrão continua `2+2 (referência)`. Os controles de referência/adicionais mudam somente a visibilidade; a troca do critério requer carregar uma nova sessão.
