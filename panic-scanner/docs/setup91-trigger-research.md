# Setup91: pesquisa intrabar dos triggers

O comando local usa uma leitura causal com a validacao de Setup91BatchRunner.
Existe somente uma posicao hipotetica por vez; nenhuma ordem e enviada. Enquanto
posicionado, a deteccao de novos sinais e os eventos de filtros ficam desativados.
Fechamentos continuam alimentando EMA9 e ATR para o gerenciamento e retomada.
Datas UTC inclusivas exigem datasets COMPLETE, manifesto valido e SHA-256 correto.

Argumentos: SYMBOL START_DATE END_DATE MODE OUTPUT_DIR [HISTORY_TEMPLATE].
Os relatorios summary.txt e triggers.csv nunca sao sobrescritos.

Auditoria de frequencia em 180 dias (inclui dias validos sem entradas nas medias):

```powershell
.\panic-scanner\scripts\test-setup91-risk.ps1 -Symbol 'WIN$N' -StartDate 2026-04-04 -EndDate 2026-09-30 -OutputDirectory research-results/setup91-risco-calibrado
```

Essa auditoria salva daily.csv, triggers.csv e summary.txt, e explicita dias
invalidos. A media por pregao usa somente dias com LASTs, incluindo pregoes sem
entradas; a media por dia corrido validado inclui finais de semana sem LASTs.
Datas com dados ausentes ou falha de integridade ficam fora das duas medias,
e o relatorio informa cobertura PARCIAL e retorna codigo 2. Nenhum dado e
adquirido automaticamente nesse comando. ATR dos fechamentos permanece
desligado: a confirmacao de fechamento exige >15 pontos no sentido da reversao.

- FIRST_RETURN: diagnostico bruto da excursao ate primeiro retorno a entrada,
  stop inicial ou fim do dia; permite comparar inclusive sinais de risco baixo.
- PARTIAL_1R_ATR_TRAILING: entrada aceita somente se R >= max(130 pontos,
  1.0 x ATR14) AND R <= min(550 pontos, 4.0 x ATR14). ATR indisponivel ou nao positivo rejeita a entrada. Novas entradas exigem timestamp do trigger <= 17:30:00 UTC (inclusive); posicoes abertas continuam gerenciadas apos o corte.
- BREAKEVEN_1R_TARGET_2R: alias legado para PARTIAL_1R_ATR_TRAILING;
  nao aplica mais breakeven puro nem alvo final de 2R.

Entrada e o LAST do trigger; stop inicial e minima do candle de sinal (BUY)
ou maxima (SELL). Ao MFE atingir R, realiza 50% exatamente em entrada + R
para BUY ou entrada - R para SELL, inclusive quando o LAST salta o alvo.
O saldo de 50% acompanha o pico observado com distancia de 1.5 x ATR14.
O stop nunca recua nem ultrapassa o stop inicial no sentido de aumentar risco.
Cada LAST testa o stop anterior antes de recalcular o trailing, sem look-ahead.

ATRIndicator da ta4j usa BarSeries exclusiva de M5 fechados, com warm-up do
header do dataset e closures do IntrabarM5Processor. O candle em formacao
nao altera o ATR. Respeita getCountOfUnstableBars da versao instalada;
14 candles apenas ainda nao garantem ATR disponivel na ta4j 0.22.6.

Todos os precos de saida gerenciada passam por roundToWinTick (multiplo de 5).
O saldo usa LAST observado arredondado, inclusive gaps alem do stop.
Pontos representam a posicao original: 0.5 x pontos da parcial + 0.5 x pontos
do saldo. Sem parcial, a posicao permanece integral. DAY_END fecha o saldo
no ultimo LAST disponivel, sem fabricar um candle ou tick.

CSV inclui partial_price, partial_sequence e trailing_stop, alem de entrada,
risco, MFE/MAE, tempos e sequencias. Campos de parcial e trailing ficam vazios
quando nao houve parcial. MFE/MAE incluem o LAST de saida e jamais precos
posteriores. MFE nao e lucro realizado. Nao ha custos ou corretagem modelados.

Validacao:

```powershell
mvn -pl panic-scanner -am clean test
```


## Simulacao no replay visual

M5ReplaySession reutiliza Setup91TriggerResearch no modo PARTIAL_1R_ATR_TRAILING,
com os mesmos LASTs, aquecimento M5, filtro dinamico de risco e corte UTC.
Eventos REPLAY_TRADE_EVENT sao causais: ENTRY apenas para entradas aceitas,
PARTIAL para 50% em 1R, BREAKEVEN na primeira vez em que o trailing protege
saldo na entrada ou melhor e EXIT com motivo e resultado total em pontos.
Nao se muda o stop para a entrada artificialmente. Gaps ainda podem gerar perdas.
GAIN / STOP / 0x0 classificam os pontos realizados totais (incluindo parcial),
independentemente de o motivo ser STOP_LOSS, ATR_TRAILING_STOP ou DAY_END.
A parcial informa sua contribuicao ponderada, e cada trade possui identificador.
O frontend preserva eventos durante atualizacoes e remontagens do grafico e
limpa-os quando uma nova sessao e carregada. O cursor mostra precos, risco,
stop, timestamp UTC, motivo e pontos. Marcadores 91 A / 91 T continuam sinais
brutos e nao significam que o filtro aceitou uma entrada. Nenhuma ordem e enviada.
