# Auditoria H1: Replay de 16/10/2026

## Diagnóstico anterior a qualquer alteração de seleção

Confirmado na API em execução: WIN$N; contexto de 13, 14 e 15/10; nove candidatos e nove linhas H1. Nenhum pivô confirmado foi descartado. O limite configurado de seis aplica-se ao M5, não ao H1 preparado. Não existe score atual. Os níveis rompidos são mantidos. Cada grupo tem exatamente um membro; nenhum agrupamento esconde linhas neste caso.

Horários civis MT5, preservados sem conversão. Apenas H1 fechado e conhecido antes da abertura de 16/10 entra nesta auditoria. Candles do próprio Replay foram excluídos da confirmação.

| Origem | Tipo | Preço | Confirmação | Estado na abertura | Score atual | Decisão |
|---|---|---:|---|---|---|---|
| 13/10 10:00 | BOTTOM | 206075 | 13/10 13:00 | CONFIRMED | N/A: não existe | Exibido |
| 13/10 14:00 | BOTTOM | 206550 | 13/10 17:00 | BROKEN | N/A: não existe | Exibido |
| 13/10 18:00 | BOTTOM | 206230 | 14/10 11:00 | CONFIRMED | N/A: não existe | Exibido |
| 14/10 09:00 | TOP | 211115 | 14/10 12:00 | BROKEN | N/A: não existe | Exibido |
| 14/10 11:00 | BOTTOM | 207615 | 14/10 14:00 | CONFIRMED | N/A: não existe | Exibido |
| 14/10 15:00 | TOP | 211750 | 14/10 18:00 | CONFIRMED | N/A: não existe | Exibido |
| 15/10 10:00 | BOTTOM | 207235 | 15/10 13:00 | CONFIRMED | N/A: não existe | Exibido |
| 15/10 12:00 | TOP | 210800 | 15/10 15:00 | CONFIRMED | N/A: não existe | Exibido |
| 15/10 16:00 | BOTTOM | 207440 | 15/10 19:00 | CONFIRMED | N/A: não existe | Exibido |

## Descartes

- Não confirmado entre os nove detectados: zero.
- Agrupado: zero.
- Limite: zero.
- Score insuficiente: zero; não há score.
- Rompido: zero; dois rompidos continuam exibidos.
- Fora da janela: zero entre os nove; pivôs do aquecimento não são candidatos da janela.
- Outros: as avaliações H1 abaixo falham o critério estrito de dois candles de cada lado; não são pivôs já detectados removidos pela seleção.

## Todas as avaliações H1 dos três pregões

| Origem | Lado | Preço | Resultado e impedimentos conhecidos antes da abertura |
|---|---|---:|---|
| 13/10 09:00 | TOP | 208515 | OUTRO: não é extremo estrito 2+2; impedimentos: 09/10 17:00 (209405.0), 09/10 18:00 (209085.0) |
| 13/10 09:00 | BOTTOM | 206785 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 10:00 (206075.0) |
| 13/10 10:00 | TOP | 208185 | OUTRO: não é extremo estrito 2+2; impedimentos: 09/10 18:00 (209085.0), 13/10 09:00 (208515.0) |
| 13/10 10:00 | BOTTOM | 206075 | DETECTADO E EXIBIDO |
| 13/10 11:00 | TOP | 208035 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 09:00 (208515.0), 13/10 10:00 (208185.0) |
| 13/10 11:00 | BOTTOM | 206895 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 09:00 (206785.0), 13/10 10:00 (206075.0), 13/10 13:00 (206865.0) |
| 13/10 12:00 | TOP | 207890 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 10:00 (208185.0), 13/10 11:00 (208035.0) |
| 13/10 12:00 | BOTTOM | 207130 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 10:00 (206075.0), 13/10 11:00 (206895.0), 13/10 13:00 (206865.0), 13/10 14:00 (206550.0) |
| 13/10 13:00 | TOP | 207820 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 11:00 (208035.0), 13/10 12:00 (207890.0) |
| 13/10 13:00 | BOTTOM | 206865 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 14:00 (206550.0), 13/10 15:00 (206620.0) |
| 13/10 14:00 | TOP | 207290 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 12:00 (207890.0), 13/10 13:00 (207820.0) |
| 13/10 14:00 | BOTTOM | 206550 | DETECTADO E EXIBIDO |
| 13/10 15:00 | TOP | 207285 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 13:00 (207820.0), 13/10 14:00 (207290.0) |
| 13/10 15:00 | BOTTOM | 206620 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 14:00 (206550.0) |
| 13/10 16:00 | TOP | 207085 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 14:00 (207290.0), 13/10 15:00 (207285.0) |
| 13/10 16:00 | BOTTOM | 206640 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 14:00 (206550.0), 13/10 15:00 (206620.0), 13/10 18:00 (206230.0) |
| 13/10 17:00 | TOP | 206975 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 15:00 (207285.0), 13/10 16:00 (207085.0), 14/10 09:00 (211115.0) |
| 13/10 17:00 | BOTTOM | 206680 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 15:00 (206620.0), 13/10 16:00 (206640.0), 13/10 18:00 (206230.0) |
| 13/10 18:00 | TOP | 206835 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 16:00 (207085.0), 13/10 17:00 (206975.0), 14/10 09:00 (211115.0), 14/10 10:00 (209325.0) |
| 13/10 18:00 | BOTTOM | 206230 | DETECTADO E EXIBIDO |
| 14/10 09:00 | TOP | 211115 | DETECTADO E EXIBIDO |
| 14/10 09:00 | BOTTOM | 208890 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 17:00 (206680.0), 13/10 18:00 (206230.0), 14/10 10:00 (208160.0), 14/10 11:00 (207615.0) |
| 14/10 10:00 | TOP | 209325 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 09:00 (211115.0), 14/10 11:00 (209595.0), 14/10 12:00 (210325.0) |
| 14/10 10:00 | BOTTOM | 208160 | OUTRO: não é extremo estrito 2+2; impedimentos: 13/10 18:00 (206230.0), 14/10 11:00 (207615.0) |
| 14/10 11:00 | TOP | 209595 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 09:00 (211115.0), 14/10 12:00 (210325.0), 14/10 13:00 (211005.0) |
| 14/10 11:00 | BOTTOM | 207615 | DETECTADO E EXIBIDO |
| 14/10 12:00 | TOP | 210325 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 13:00 (211005.0), 14/10 14:00 (211635.0) |
| 14/10 12:00 | BOTTOM | 208705 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 10:00 (208160.0), 14/10 11:00 (207615.0) |
| 14/10 13:00 | TOP | 211005 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 14:00 (211635.0), 14/10 15:00 (211750.0) |
| 14/10 13:00 | BOTTOM | 209785 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 11:00 (207615.0), 14/10 12:00 (208705.0) |
| 14/10 14:00 | TOP | 211635 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 15:00 (211750.0) |
| 14/10 14:00 | BOTTOM | 210785 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 12:00 (208705.0), 14/10 13:00 (209785.0), 14/10 16:00 (210480.0) |
| 14/10 15:00 | TOP | 211750 | DETECTADO E EXIBIDO |
| 14/10 15:00 | BOTTOM | 211025 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 13:00 (209785.0), 14/10 14:00 (210785.0), 14/10 16:00 (210480.0), 14/10 17:00 (210785.0) |
| 14/10 16:00 | TOP | 211365 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 14:00 (211635.0), 14/10 15:00 (211750.0) |
| 14/10 16:00 | BOTTOM | 210480 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 18:00 (208380.0) |
| 14/10 17:00 | TOP | 211050 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 15:00 (211750.0), 14/10 16:00 (211365.0), 14/10 18:00 (211210.0) |
| 14/10 17:00 | BOTTOM | 210785 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 16:00 (210480.0), 14/10 18:00 (208380.0), 15/10 09:00 (207435.0) |
| 14/10 18:00 | TOP | 211210 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 16:00 (211365.0) |
| 14/10 18:00 | BOTTOM | 208380 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 09:00 (207435.0), 15/10 10:00 (207235.0) |
| 15/10 09:00 | TOP | 209285 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 17:00 (211050.0), 14/10 18:00 (211210.0), 15/10 10:00 (209560.0), 15/10 11:00 (210610.0) |
| 15/10 09:00 | BOTTOM | 207435 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 10:00 (207235.0) |
| 15/10 10:00 | TOP | 209560 | OUTRO: não é extremo estrito 2+2; impedimentos: 14/10 18:00 (211210.0), 15/10 11:00 (210610.0), 15/10 12:00 (210800.0) |
| 15/10 10:00 | BOTTOM | 207235 | DETECTADO E EXIBIDO |
| 15/10 11:00 | TOP | 210610 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 12:00 (210800.0), 15/10 13:00 (210710.0) |
| 15/10 11:00 | BOTTOM | 208785 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 09:00 (207435.0), 15/10 10:00 (207235.0) |
| 15/10 12:00 | TOP | 210800 | DETECTADO E EXIBIDO |
| 15/10 12:00 | BOTTOM | 209815 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 10:00 (207235.0), 15/10 11:00 (208785.0), 15/10 13:00 (209730.0), 15/10 14:00 (209215.0) |
| 15/10 13:00 | TOP | 210710 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 12:00 (210800.0) |
| 15/10 13:00 | BOTTOM | 209730 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 11:00 (208785.0), 15/10 14:00 (209215.0), 15/10 15:00 (209365.0) |
| 15/10 14:00 | TOP | 209930 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 12:00 (210800.0), 15/10 13:00 (210710.0), 15/10 15:00 (210095.0), 15/10 16:00 (209945.0) |
| 15/10 14:00 | BOTTOM | 209215 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 16:00 (207440.0) |
| 15/10 15:00 | TOP | 210095 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 13:00 (210710.0) |
| 15/10 15:00 | BOTTOM | 209365 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 14:00 (209215.0), 15/10 16:00 (207440.0), 15/10 17:00 (208080.0) |
| 15/10 16:00 | TOP | 209945 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 15:00 (210095.0) |
| 15/10 16:00 | BOTTOM | 207440 | DETECTADO E EXIBIDO |
| 15/10 17:00 | TOP | 208800 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 15:00 (210095.0), 15/10 16:00 (209945.0) |
| 15/10 17:00 | BOTTOM | 208080 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 16:00 (207440.0), 15/10 18:00 (208050.0) |
| 15/10 18:00 | TOP | 208495 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 16:00 (209945.0), 15/10 17:00 (208800.0) |
| 15/10 18:00 | BOTTOM | 208050 | OUTRO: não é extremo estrito 2+2; impedimentos: 15/10 16:00 (207440.0) |

## Comparação com os prints

Os três arquivos anexados estão recortados, sem escala de preço, data, identificação do ativo ou período. Há muito mais candles do que os dez H1 de um pregão; portanto não é seguro tratar cada candle desenhado como H1. As setas apontam retornos/rejeições visuais, mas não identificam inequivocamente um candle H1 ou preço. Não foi atribuído horário/preço inventado a nenhuma seta.

Como exemplo verificável, o topo de 14/10 às 18h em 211210 não é um pivô H1 estrito: o H1 de 16h tem máxima 211365. O detector existente não o entrega é seleção. Isto pode explicar uma rejeição visual intermediária, mas não constitui correspondência comprovada com uma seta.

O símbolo também importa: no terminal, WINZ26 tem sete pivôs estritos na janela. Os fundos de 13/10 às 10h e 14h são 210400 e 210900, enquanto WIN$N fornece 206075 e 206550. O fundo de 13/10 às 18h e o topo de 14/10 às 09h de WIN$N não são pivôs estritos de WINZ26. Os demais cinco pivôs coincidem. Não substituir séries silenciosamente.

## Consequência para o ajuste solicitado

Não há níveis confirmados disponíveis para recuperar aumentando sensibilidade apenas da seleção. Adicionar score ou remover limite não adicionaria nenhuma linha neste Replay. Nenhum novo nível foi apresentado como se tivesse sido detectado. Nenhuma regra da estratégia foi alterada nesta auditoria.

Para acrescentar os movimentos apontados, é necessário identificar ativo/preço/horário das setas e verificar se são pivôs do detector atual ou regiões adicionais. Se não forem pivôs estritos, incluí-los exige autorizar um critério de detecção/região adicional; isso ultrapassa a restrição de melhorar apenas a seleção. O detector compartilhado não foi modificado.

## Validacao executada

API real: nove candidatos e nove H1 exibidos. Testes Java de H1SessionPreparationTest e ThreeCandleHistoryLoaderTest: 11 passaram, um teste opcional ignorado. Comparacao nativa H1: zero divergencias. Preview controlado com a resposta da API real, requests interceptados: zero erros JavaScript, H1 visivel, M5 oculto; toggles H1, zoom e rolagem exercitados. A captura corresponde ao comportamento atual, sem novos niveis.

[Captura M5](images/three-candles-h1-selection-oct16.png)
