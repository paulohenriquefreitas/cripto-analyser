# Persistência experimental da liquidez DOM

Analisador offline: `scripts/mt5_dom_persistence.py`. Lê diretamente o NDJSON de
`mt5_dom_probe.py`, reutiliza sua validação de livro e não importa MetaTrader5,
abre conexão, altera a captura ou integra streams, replay, frontend ou estratégias.
Nenhuma alteração em application.properties é necessária.

Da raiz do repositório, para analisar a captura existente e imprimir JSON:

```powershell
python panic-scanner/scripts/mt5_dom_persistence.py panic-scanner/dom-captures/WINZ26-20261015T190503150562Z.ndjson
```

Use `--output caminho/relatorio.json` para salvar (recusa sobrescrita).
`--max-gap-ms 500` define o maior intervalo comparável entre snapshots, inclusive.

A identidade analisada é `(type, price)`, somente ASK=1 e BID=2. A quantidade
observada vem de volume_dbl, sem fallback; volume também é validado pelo analisador
do probe. Não há identificação de ordens individuais. Snapshots repetidos contam.
O script exige identificação WINZ26, sequência contígua começando em 1 e timestamps
UTC/com fuso em ordem não decrescente; erros estruturais interrompem com número
da linha. Metadados e métricas pré-calculadas do snapshot não substituem a leitura
dos níveis originais. Não exige summary final: captura interrompida ainda pode
ser analisada se tiver identificação e snapshots válidos.

Por preço/lado, o relatório traz:

- observations e sample_presence_ratio: contagem e fração de snapshots válidos
  não vazios em que o nível foi observado.
- runs e longest_run_ms: sequências de observações adjacentes com o nível presente;
  duração do primeiro ao último timestamp observado, sem extrapolar ao próximo.
  Uma observação isolada tem duração zero. A quantidade pode variar durante um run.
- matched_presence_ms: soma dos intervalos comparáveis com o nível presente nos
  dois extremos; matched_presence_ratio divide essa soma por comparable_ms global.
  Isso é suporte temporal amostrado, não prova de permanência entre consultas.
- quantity_min/max/mean_sampled: estatística de volume_dbl por observação, incluindo
  repetições; média não ponderada pelo tempo.
- quantity_changes/increases/decreases: mudanças entre observações adjacentes
  comparáveis em que o mesmo preço/lado aparece nos dois extremos.
- observed_exits: transições entre livros válidos em que o nível deixa a janela
  visível. Não significa execução ou cancelamento.

Livro vazio, erro, anomalia de validação ou tipo não limitado tornam o snapshot
não utilizável e interrompem todos os runs. Nenhum intervalo que atravesse essas
leituras entra no denominador temporal. Gaps acima do limite também interrompem
runs, mas suas observações continuam contadas. Anomalias do livro são registradas
por sequência; dados não são corrigidos. O campo quality expõe esses descartes.
Não há limiar de decisão de trading, ranking de sinais ou classificação de spoofing.

Relatório gerado nesta implementação:
`dom-captures/WINZ26-20261015T190503150562Z.persistence.json`.
593 snapshots, 591 válidos não vazios, 2 vazios, sem falhas/anomalias/gaps grandes.
590 intervalos comparáveis totalizam 59.727 ms. Foram observados 18 preços distintos
por lado ao longo da captura, sem que isso implique 18 níveis simultâneos.

Testes sintéticos (sem conexão real):

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_dom*.py" -v
```

Limites: apenas 10 níveis simultâneos por lado na captura, polling de aproximadamente
100 ms e timestamp local de consulta. Persistência refere-se à liquidez agregada
visível por preço, sem evidência de intenção, execução ou permanência da mesma ordem.

## Distribuição das sequências e censura (relatório versão 2)

O relatório inclui `runs`: uma entrada por sequência, com lado, preço, timestamp
de início e última presença, sequência inicial, duração em ms, motivo de término,
horário da detecção do término e indicadores left_censored/right_censored/censored.
`duration_distributions` contém BID e ASK separadamente, cada qual com a lista
completa ordenada `durations_ms`, contagem, mediana, P90 e máxima. O P90 usa o
posto mais próximo: ceil(0,9 * N). Grupo vazio tem estatísticas null.

As distribuições são separadas em all_observed (inclui durações censuradas),
uncensored (entrada e saída observadas) e censored. Há também agrupamento por
motivo de término, independentemente de censura à esquerda:

- observed_exit: preço presente no último livro e ausente no próximo livro válido
  comparável. Término observado da sequência visível, sem inferência de execução.
- query_failure, empty_book, invalid_book, large_gap: interrupção da observação,
  sempre censurada à direita; a duração para na última presença antes da interrupção.
- capture_end: sequência ainda aberta ao terminar o arquivo, censurada à direita.

Uma sequência já presente no primeiro livro utilizável ou retomada após interrupção
é censurada à esquerda: não se conhece seu início real. Isso também se aplica à
primeira leitura válida após um gap. Apenas uma entrada observada após ausência
num livro válido comparável é não censurada à esquerda. Os contadores left/right
podem se sobrepor; censored conta a união.

Duração é sempre última presença menos primeira presença, sem somar o intervalo
até detectar ausência. Mesmo uncensored representa duração amostrada, com limites
reais entre consultas. all_observed descreve trechos observados, não uma estimativa
da distribuição de permanências completas. Os percentis uncensored também são
condicionados a sequências inteiramente observadas nesta janela, sem ajuste de
sobrevivência nem extrapolação da população.

Na captura reprocessada, por lado: 63 sequências; 53 com saída observada e 10 abertas
no fim; nenhuma encerrada por interrupção. Há 10 censuradas à esquerda, 10 à direita,
com sobreposição de 2, resultando em 18 censuradas e 45 sem censura. Os dois livros
vazios ocorreram antes de haver sequências ativas.

Para BID e ASK, respectivamente (resultados iguais nesta captura):

| Grupo | N | Mediana (ms) | P90 (ms) | Máxima (ms) |
| --- | ---: | ---: | ---: | ---: |
| Todas observadas | 63 | 3416 | 27226 | 59727 |
| Sem censura | 45 | 2612 | 12461 | 28934 |
| Censuradas | 18 | 12118 | 59727 | 59727 |
