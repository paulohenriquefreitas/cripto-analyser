# Dataset causal M5 para pesquisa ML

Esta linha de pesquisa nao depende de Setup91/Pullback09. Nao treina modelos, nao faz previsoes nem toma decisoes de trading. Somente le arquivos locais e escreve artefatos novos.

## Infraestrutura reutilizada

- `Mt5HistoricalPriceSource.streamHistorical`: parse estrito, trailer, ordem de timestamp/sequence, selecao LAST e validacao de volumeReal.
- `ReplayHistoryProvider.Metadata`: somente o record de manifesto. Nenhuma instancia do provider, aquisicao ou MT5.
- `Mt5CanonicalPriceMapper`, `CanonicalPriceEvent`, `IntrabarCandleSnapshot`, `M5Bucket`: contratos e validacao de preco/OHLC/intervalos.
- TA4J `BaseBarSeries`, `BaseBar`, `EMAIndicator(9)`, `ATRIndicator(14)`, `ClosePriceIndicator`: recebem exclusivamente candles fechados.

O agregador ML nao usa o processor intrabar de replay: mantem OHLC do candle corrente separado dos indicadores fechados. `CausalSessionVwap` e `Mt5SessionVwap` foram examinados; volume/VWAP foram omitidos por falta de garantia de volume negociado sem duplicacao. Nao substituir volume negociado por quantidade de LASTs silenciosamente.

## Novos arquivos

- `application/win/ml/LocalMlHistoryReader.java`: validacao COMPLETE, data, contrato, schema, SHA256, trailer, contagens, limites temporais e revalidacao do manifesto.
- `application/win/ml/MlCandleDataset.java`: features imutaveis e labels futuros em estruturas separadas.
- `application/win/ml/MlDatasetExportCommand.java`: exportacao diaria; dia invalido nao publica nenhuma linha.
- `scripts/export-ml-dataset.ps1`: build e execucao local.
- Dois testes em `src/test/java/.../application/win/ml/`.
- Esta documentacao e `ml-dataset-example.csv`.

Nenhum arquivo existente de Setup91, Pullback09, replay ou frontend foi alterado nesta entrega.

## Arquivos de saida

- `features.csv`: todos os candles observados fechados, inclusive warm-up/contexto insuficiente. Sem labels. Contexto incompleto usa campos vazios.
- `dataset.csv`: exemplos com indicadores/contexto e horizonte validos; 170 features/identificadores mais 9 labels = 179 colunas.
- `schema.txt`: cabecalhos exatos dos dois CSVs.
- `daily.csv`: cobertura, contagens e erros por dia.
- `report.txt`: totais, descartes, distribuicoes, parametros fixos e limites conhecidos.

`ml-dataset-example.csv` contem o cabecalho exato e uma linha sintetica completa, para inspecao do formato. Nao e um exemplo de setembro nem uma amostra de mercado.

Contexto: `ctx_lag20_*` ate `ctx_lag1_*`, do mais antigo ao mais recente, excluindo o candle ancora. Cada lag tem `return,range_atr,body_atr,upper_wick_atr,lower_wick_atr,ema_delta_atr,close_ema_atr`, calculados com o ATR disponivel naquele candle.

Corpo absoluto e assinado sao separados. Retorno e `close/previous_close - 1`. Excursao para baixo e magnitude positiva. Campos numericos nao finitos sao vazios, nunca NaN/Infinity. OHLC nominal fica para auditoria; os modelos posteriores devem selecionar conscientemente as features normalizadas.

## Labels fixados antes da exportacao

Limite inicial: 1.0 x ATR14 do candle ancora. Parametro explicito, nao ajustado por resultados de setembro.

Os tres candles futuros precisam ser consecutivos e pertencer ao mesmo arquivo/data UTC. Horizonte: `[fechamento da ancora, fechamento do terceiro candle futuro)`. O fechamento do terceiro candle e seu ultimo LAST, nao um LAST do bucket seguinte.

- `label_up_excursion`, `label_down_excursion`: extremos dos LASTs futuros versus fechamento da ancora, limitados inferiormente em zero.
- `label_return_15m`: fechamento futuro / fechamento ancora - 1.
- Versoes normalizadas pelo ATR da ancora.
- `label_direction`: UP se deslocamento final >= limite; DOWN se <= -limite; FLAT nos demais casos.
- `label_first_touch`: UP_FIRST/DOWN_FIRST pela ordem original de LASTs, inclusive sequence em timestamps iguais; NONE se nenhum limite foi tocado.

Um caminho pode tocar UP primeiro e terminar DOWN; sao labels diferentes. Nao existe regra de entrada, stop ou take profit.

## Causalidade e cobertura

Warm-up do header alimenta indicadores somente. Barras `official` do header, que podem conter informacao futura, sao completamente ignoradas. Contexto e linhas usam somente candles reconstruidos dos LASTs da propria sessao. Lacunas invalidam horizontes pendentes e reiniciam o contexto; nao ha preenchimento artificial de barras. O estado dos indicadores e inicializado por sessao a partir do warm-up.

O arquivo COMPLETE e seu EOF validado provam que o ultimo bucket observado ja fechou. Seu OHLC pode ser publicado sem criar um LAST sintetico. Os tres ultimos exemplos elegiveis ficam sem horizonte, a menos que existam efetivamente tres candles futuros.

Data/sessao usa a convencao UTC dos arquivos existentes. Hora/minuto sao America/Sao_Paulo. Isso nao implementa calendario de bolsa. Abril-julho = DISCOVERY; agosto = VALIDATION; setembro = TEST. Nenhuma normalizacao global, treinamento ou escolha de parametros usa essas particoes.

Testes deterministas verificam invariancia das features sob mudancas no futuro, indicadores fechados, ordem de contexto, limites inclusivos, first-touch ordenado, exclusao de ticks no fim do horizonte, lacunas/fim de sessao, oficial futuro ignorado e integridade dos arquivos.

## Execucao

Na raiz do repositorio:

```powershell
.\panic-scanner\scripts\export-ml-dataset.ps1 -Symbol 'WIN$N' -StartDate 2026-04-01 -EndDate 2026-09-30
```

O script recompila por padrao. Cada execucao cria uma pasta nova. Codigo de saida 2 indica cobertura parcial, com dias ausentes/invalidos explicitos; os demais dias validos permanecem disponiveis. Nunca busca dados nem altera o historico local.
