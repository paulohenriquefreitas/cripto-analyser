# Experimento ML etapa 2 — somente DISCOVERY e VALIDATION

Nenhum setup, replay, frontend ou gerenciamento de trading e modificado. Nao se calcula lucro, nao se integra inferencia no Java e nao se abre setembro.

## Entradas e protecao temporal

O script `scripts/ml-baselines.py` interrompe a leitura do CSV original ao encontrar a identidade de uma linha de setembro, antes de interpretar suas features ou labels. A auditoria escreve recortes fisicos `discovery.csv` e `validation.csv`. O treinamento le exclusivamente esses dois recortes. So metadados de cobertura diarios de setembro podem participar da reconciliacao das contagens solicitada; nao se consultam seus resultados/labels.

Auditoria: ordem estrita, duplicatas, contexto de 20 candles, warm-up, sessoes, EMA/ATR fechados por recorrencia independente, igualdade das features antes/depois da inclusao de labels e horizonte exato de tres barras. A lista de preditores e uma allowlist causal fixa, nao uma selecao automatica a partir de resultados.

`MlFirstAuditCommand` verifica FIRST independentemente no NDJSON original de abril-agosto: limites fixos close +/- ATR da ancora; LASTs em [fim da ancora, fim do terceiro futuro); empate de timestamp resolvido por sequencia original. Nenhuma inferencia OHLC. O treinamento exige seu relatorio PASS.

## Contagens historicas

O ML publica o ultimo bucket observado depois da validacao COMPLETE/EOF; a pesquisa anterior aguardava um LAST do proximo bucket e nao publicava esse ultimo candle. Ha 122 dias nao vazios: 13.826 - 122 = 13.704. O teste de EOF demonstra a diferenca de uma barra por sessao.

A janela de 180 dias (04/04-30/09) tinha 2 ausencias. A janela de seis meses (01/04-30/09) acrescenta 01/04 e 02/04 ausentes e 03/04 valido vazio. Assim, 178 dias validos tornam-se 179, e 2 ausencias tornam-se 4. Isso e diferenca de escopo e fechamento, nao prova de corrupcao do dataset.

## Modelos e medidas

Baseline `DummyClassifier(strategy=prior)`: sempre preve a classe majoritaria, com probabilidades iguais as frequencias de DISCOVERY. Logistic Regression balanceada C=1 em Pipeline com StandardScaler. Random Forest balanceada por bootstrap, 300 arvores, profundidade 6, folha minima 30 e seed 42. Parametros fixos, sem busca ou selecao automatica de threshold.

O [Pipeline do scikit-learn](https://scikit-learn.org/stable/common_pitfalls.html) permite manter o ajuste do preprocessing na amostra de treino. Nesta implementacao, o scaler e conferido contra quantidade de exemplos e medias de DISCOVERY. Nenhum preprocessing e ajustado em agosto.

Precision/recall/F1 por classe, balanced accuracy, matrizes de confusao, distribuicoes reais/previstas, log loss, Brier e probabilidades por exemplo sao registrados. Thresholds 55/60/65/70/75/80% sao avaliados somente em VALIDATION e incluem previsoes FLAT. Precision micro e a fracao de acertos entre selecionados. A media diaria usa todos os dias validos com candles, mesmo se nao houver exemplos selecionados.

Probabilidades brutas nao sao garantidamente calibradas; pesos de classe alteram sua interpretacao. Metricas de DISCOVERY sao otimistas por avaliar o treino. Labels de 15 minutos se sobrepoem, portanto nao ha independencia estatistica entre linhas. Nao inferir significancia ou rentabilidade a partir de uma diferenca pequena em agosto.

Importancia da Random Forest usa reducao de impureza; correlacao e cardinalidade podem enviesar o ranking. Revisao manual de causalidade acompanha as 20 maiores features. Nenhum modelo nem threshold e congelado automaticamente; setembro depende de autorizacao posterior.

## Arquivos novos desta etapa

- `application/win/ml/MlFirstAuditCommand.java`: auditoria independente de FIRST.
- Testes adicionais em `MlCandleDatasetTest.java`: cinco caminhos FIRST e diferenca EOF.
- `scripts/ml-baselines.py`: auditoria, treinamento e relatorio Markdown.
- `scripts/test_ml_baselines.py`: lacre de setembro, allowlist e preprocessing.
- `scripts/ml-requirements.txt`: dependencia direta fixada; lock completo acompanha cada experimento.
- Esta documentacao. O dataset e o gerador existentes nao foram corrigidos.

## Reproducao

Criar um ambiente Python local, instalar `scripts/ml-requirements.txt`, escolher uma pasta nova e rodar primeiro `ml-baselines.py audit DATASET_DIR OUTPUT_DIR`. Gerar `first-audit.txt` nessa pasta pelo comando Java `MlFirstAuditCommand DATASET_CSV historical-data OUTPUT_DIR/first-audit.txt` usando as classes compiladas e classpath do exportador. Depois de revisar a auditoria, executar `ml-baselines.py train DATASET_DIR OUTPUT_DIR`.

Treinamento nao sobrescreve um diretorio de modelos existente. A auditoria tambem recusa sobrescrever experimentos ja treinados. Os artefatos incluem parametros, seed, versoes, hashes dos recortes, modelos Python, predicoes, metricas, confianca e importancia. Somente abrir TEST em uma etapa posterior explicitamente autorizada.
