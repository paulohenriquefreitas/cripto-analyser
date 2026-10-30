# Preparacao H1 de 26/10/2026 - WINZ26

Branch: feat/three-candles-foundation. Pagina: /three-candles.

## Criterio apresentado antes da implementacao

O detector existente ConfirmedPivotDetector nao classifica importancia estrutural:
confirma apenas extremos estritos com dois vizinhos a esquerda e dois a direita.
Nesta etapa conservadora, todos os pivos confirmados desse detector no H1 sao
referencias estruturais elegiveis. Nao se introduziu score, ATR, prominencia,
volume, numero de toques ou selecao por proximidade sem validacao.

Origens elegiveis: tres pregoes anteriores com candles do mesmo simbolo.
Confirmacao: fechamento do segundo H1 da direita ate a abertura do pregao alvo.
Warmup: pelo menos dois H1 antes do primeiro pregao elegivel; origem do warmup
nao e exibida. Os vizinhos direitos podem atravessar sessoes, mas nunca passar
pelo corte de abertura para disponibilizar uma referencia.

Todos os candidatos ficam registrados. Somente precos a ate 5 pontos de um
representante sao agrupados visualmente; o representante e o primeiro extremo
em ordem cronologica. Os membros de cada grupo continuam no relatorio/API.
Nao ha encadeamento de agrupamento alem da tolerancia do representante.
Nao ha limite H1, filtro por distancia ou descarte por rompimento.

## Resultado da comparacao nativa

Pregao analisado: 26/10/2026, abertura 09:00 no eixo civil original do MT5.
Pregoes de origem: 21/10, 22/10 e 23/10/2026. Fim de semana nao conta como pregao.
O recorte MT5 inclui candles desde 01/10 para aquecimento; somente origens dos
tres pregoes acima sao selecionadas. A comparacao usa diretamente a serie H1
nativa retornada pelo terminal, sem extrair H1 do proprio agregador como oracle.

Oito candidatos e oito linhas selecionadas; nenhum par ficou a ate 5 pontos.
Todos foram mantidos pelo mesmo criterio: extremo estrito H1, origem elegivel e
confirmacao conhecida antes da abertura. Zero divergencias de tipo, preco,
origem e horario nominal de confirmacao em relacao ao H1 nativo.
O estado na tabela e o conhecido na preparacao, nao um filtro de selecao.

| Tipo | Preco | Origem (MT5) | Confirmacao (MT5) | Estado na abertura | Decisao |
| --- | ---: | --- | --- | --- | --- |
| Topo | 213385 | 21/10/2026 10:00 | 21/10/2026 13:00 | CONFIRMED | Selecionado |
| Topo | 210670 | 21/10/2026 17:00 | 22/10/2026 10:00 | CONFIRMED | Selecionado |
| Fundo | 206755 | 22/10/2026 12:00 | 22/10/2026 15:00 | BROKEN | Selecionado |
| Topo | 208235 | 22/10/2026 15:00 | 22/10/2026 18:00 | BROKEN | Selecionado |
| Fundo | 205865 | 22/10/2026 18:00 | 23/10/2026 11:00 | CONFIRMED | Selecionado |
| Topo | 208630 | 23/10/2026 10:00 | 23/10/2026 13:00 | BROKEN | Selecionado |
| Fundo | 206995 | 23/10/2026 14:00 | 23/10/2026 17:00 | CONFIRMED | Selecionado |
| Topo | 209485 | 23/10/2026 15:00 | 23/10/2026 18:00 | CONFIRMED | Selecionado |

## Comportamento da implementacao

H1SessionPreparation produz um plano imutavel, compartilhado com o registro
ThreeCandleStructure. Replay o recebe antes de avancar e o reaplica no reset.
O plano nao muda com candles intradiarios, rompimento, proximidade ou limite M5.
Reabrir a sessao produz o mesmo plano a partir do historico anterior ao corte.
A API informa availableAtMsc para validar a disponibilidade na abertura mesmo
sem um candle M5 atual. O grafico recebe somente candles da sessao alvo; todo o
warmup permanece no processamento. A escala inclui as referencias H1 visiveis.

Live prepara a data corrente mesmo antes do primeiro candle do dia, usando o
historico ja disponibilizado pelo Mt5ProcessClient; caches sao preservados na
reconexao. O ultimo M5 de uma sessao passada e tratado como fechado. Nenhum novo
stream ou estrategia foi criado. Se faltam tres sessoes ou warmup, sufficient=false
informa cobertura parcial e nao fabrica candles ou referencias.

M5: detector, registros e codigo preservados; visualizacao desativada por padrao.
O controle permite reativacao sem limpar a memoria. O limite existente passa a
se aplicar somente ao conjunto M5 quando H1 tem plano preparado.

## Evidencias e testes

- H1SessionPreparationTest: selecao dos tres pregoes, warmup fora da janela,
  sessao corrente e dados futuros excluidos, preservacao M5, agrupamento sem
  apagar candidatos, preparacao Live antes do primeiro candle e reconexao.
- Caso explicito: extremo da sexta-feira as 18h que so confirma com os dois H1
  da segunda-feira nao e disponibilizado no plano de abertura de segunda.
- Comparacao MT5: oito referencias, origem/tipo/preco/confirmacao identicos.
- Replay real: plano estavel em todos os prefixos da sessao; reset identico;
  nova preparacao sem qualquer candle futuro tambem identica.
- Frontend: M5 oculto por padrao, controle preservado, oito referencias H1
  aceitas mesmo com maxLevels=1, confirmacao depois da abertura rejeitada mesmo
  quando o cursor e posterior. Oito raios fucsia desenhados no teste do chart.
- Captura: [M5 com somente H1](images/h1-preparation-2026-10-26.png).
  Frontend real, snapshot processado pelo Java com candles nativos MT5; a API
  foi interceptada para a captura, sem reiniciar o backend em execucao.
- Canvas: nenhum pixel amarelo no grafico por padrao; fucsia presente; ambos
  desaparecem ao ocultar H1; M5 pode ser reativado; restauracao identica.
  Precos proximos podem ocupar pixels adjacentes por causa da escala.
- Dados detalhados: h1-preparation-2026-10-26.json.
- Cores e restauracao: h1-preparation-visual-validation.json.

## Observacao sobre os dados retornados pelo terminal

O ultimo fechamento M5 de 23/10 retornado foi 208240; a abertura
do primeiro M5 de 26/10 foi 177215. Essa descontinuidade esta na
serie nativa consultada. Nenhum offset, ajuste de contrato ou normalizacao
foi aplicado. Por isso a captura tem escala ampla, com referencias acima dos
candles do dia. A validacao comprova correspondencia com o terminal, nao a
autenticidade economica do historico do broker. O simbolo e WINZ26 em ambos.

SHA-256 da referencia nativa M5/H1: `4cbeb6ba005e052f4b4de43ec61a6d8fbc41746b97ea0f8d6b0454a9025112a7`.

## Reproducao

```powershell
python panic-scanner/scripts/three_candles_h1_reference.py --symbol WINZ26 --from 2026-10-01 --to 2026-10-26 --output panic-scanner/target/h1-preparation-win26-native.json
mvn -o -pl panic-scanner -am test '-Dtest=ThreeCandle*Test,H1SessionPreparationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dthreecandles.preparationNative=C:/Users/paulo/project/cripto-analyser/panic-scanner/target/h1-preparation-win26-native.json' '-Dthreecandles.preparationReport=C:/Users/paulo/project/cripto-analyser/panic-scanner/docs/h1-preparation-2026-10-26.json' '-Dthreecandles.preparationPreview=C:/Users/paulo/project/cripto-analyser/panic-scanner/target/h1-preparation-preview.json'
node frontend/scripts/captureThreeCandlesStructure.mjs panic-scanner/target/h1-preparation-preview.json panic-scanner/docs/images/h1-preparation-2026-10-26.png
```

Entradas, stops, sinais e regras do Padrao de 3 Candles nao foram implementados.
As regras das outras estrategias e M5ReplaySession permanecem inalteradas.

Resultado final: 40 testes Java aprovados, sem skips (incluindo historico local e
comparacoes nativas); 53 testes frontend aprovados; TypeScript aprovado.
