# Captura reproduzível do observador Python

Na raiz do repositório, com o terminal MT5 conectado:

```powershell
python -B panic-scanner/scripts/observe_absorption.py --observe-live --seconds 1500 --warmup-timeout 30 --record-inputs --output panic-scanner/dom-captures/observacao-reproduzivel.ndjson
```

O arquivo de saída deve ser novo: o comando recusa sobrescrita. A gravação de
entradas é opcional e fica desativada sem `--record-inputs`. Não executa ordens.
O aquecimento permanece separado; as entradas são gravadas a partir da criação
do detector, inclusive seu cabeçalho de sessão, erros e resumo final.

## NDJSON versão 1

O mesmo arquivo contém:

- `detector_input`: `schema_version`, `session`, `order`, `received_utc`,
  configuração vigente, `record` completo recebido após o adaptador e `guard`.
  `record` preserva timestamps, sequências ajustadas e de captura, cursores,
  níveis DOM e ticks em ordem, incluindo duplicatas. `guard` preserva os motivos
  de falha aplicados antes de `feed`, os eixos temporais, conexão, intervalo e
  atraso de entrega observados.
- `transition`: estado e evidência completos originais, com identificadores,
  ordem e motivos.
- `observation`: bloqueio e conjunto de falhas após cada entrada.
- Registros existentes de aquecimento, ativação e resumo.

As respostas rejeitadas pelo validador do cursor não geram `detector_input`.
Os diagnósticos de retentativa continuam como metadados do lote aceito ou do
erro terminal. Um DOM inválido entregue ao observador é gravado: é necessário
para reproduzir seu bloqueio. Não há filtros nem deduplicação nas entradas.

## Replay com comparação automática

```powershell
python -B panic-scanner/scripts/replay_observation.py --input panic-scanner/dom-captures/observacao-reproduzivel.ndjson
```

O replay não importa MT5. Somente `detector_input` alimenta um detector novo,
na ordem gravada. As falhas externas do observador são reaplicadas antes de
cada `feed`; conexão e atraso históricos não são recalculados usando o ambiente
atual. Os registros originais de saída servem apenas como referências.

A comparação exige igualdade de toda a evidência das transições, incluindo
IDs, estados, ordem e motivos, além de bloqueio e falhas após cada entrada.
Portanto também verifica o primeiro ponto de bloqueio e sua persistência.
Não chama `finish()` nem trata EOF como nova observação.

A saída JSON informa `equivalent`, contagens, primeiro bloqueio, falhas,
diferenças e duração. Código de saída: 0 para equivalência exata (inclusive
quando ambos bloquearam), 1 para diferenças e 2 para entrada inválida.
Recusa ausência de entradas, esquema/configuração incompatível, descontinuidade
de sessão/ordem e gravação sem entrada final `summary`.

Este replay verifica as decisões sobre as entradas aceitas. Não valida perda
na fonte, continuidade entre amostras DOM nem o algoritmo de recuperação do
cursor. O arquivo antigo `observacao-25min.ndjson` não contém as entradas e não
pode fornecer essa comparação.

## Testes simulados

```powershell
python -B -m unittest discover -s panic-scanner/scripts -p test_replay_observation.py
```

Cobrem equivalência com transições e bloqueios, duplicatas, adulteração de
transições/bloqueios, ausência de entradas, ordem inválida, truncamento,
gravação desativada, conexão/atraso históricos e exclusão das respostas
rejeitadas durante retentativas.
