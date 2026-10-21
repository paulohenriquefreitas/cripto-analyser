# Etapa 4 — Desequilíbrio descritivo do DOM

Analisador offline: `scripts/mt5_dom_imbalance.py`. Lê exclusivamente o NDJSON
WINZ26, reutiliza timestamp e validação dos componentes experimentais, preserva
a fonte e não abre conexão MT5. Não modifica outros analisadores ou módulos.

```powershell
python panic-scanner/scripts/mt5_dom_imbalance.py panic-scanner/dom-captures/WINZ26-20261015T190503150562Z.ndjson --output panic-scanner/dom-captures/nova-analise-imbalance.json
```

Sem --output imprime JSON. A saída exige arquivo novo. --max-gap-ms tem padrão
500 ms e define continuidade temporal, sem função operacional de trading.
SHA-256 antes/depois verifica integridade da fonte.

Para 1, 3, 5 e 10 níveis, BID é selecionado por preço decrescente e ASK por preço
crescente: sempre a partir do melhor preço. A ordem original é validada segundo
o probe antes da seleção; o arquivo não é reordenado nem corrigido.
Soma volume_dbl sem conversão, normalização de quantidade ou fallback para volume.
Imbalance = (BID - ASK)/(BID + ASK), adimensional. Volumes mantêm unidade do feed.
Não são inferidos negócios, participantes ou intenções.

O relatório armazena para cada snapshot: timestamp original, sequência, situação,
quantidade de níveis por lado e volumes/imbalance por profundidade. Ausência de
profundidade suficiente produz insufficient_levels e valores null, sem somar um
livro parcial. Livro vazio e falha também produzem null com motivo explícito.
Anomalias de preços, quantidades negativas/não finitas/inconsistentes, duplicatas,
ordenação, tipos não limitados e spread inválido produzem invalid_book.
Lado ausente é tratado como profundidade insuficiente. Este analisador aceita
quantidade zero explicitamente, diferentemente do filtro de persistência:
zero não é transformado nem descartado. Denominador zero retorna zero_denominator,
volumes originais zero e imbalance null. Um lado com soma zero e outro positivo
produz -1 ou +1. Nada é dividido por zero.

Por profundidade: distribuições de volumes BID/ASK e imbalance com N, mínimo,
máximo, média, mediana, P10/P90 por posto mais próximo e desvio padrão populacional.
Pesos são por snapshot, incluindo repetições, não por tempo. Série temporal integral
e bins UTC fixos de 5 s (início/fim podem ser parciais) mostram evolução descritiva.
Bins só resumem amostras válidas: não preenchem ausências, não interpolam nem
comprovam continuidade. Série mantém os períodos desconhecidos explicitamente.

Trocas diretas de sinal contam apenas positivo -> negativo e negativo -> positivo
entre snapshots adjacentes válidos da mesma profundidade, sem gaps. Frequência é
contagem dividida pelo tempo desses intervalos comparáveis; fração é contagem
sobre número de intervalos. Transições envolvendo zero aparecem separadamente,
e positivo -> zero -> negativo não conta como troca direta. Gaps, erros, vazios,
profundidade insuficiente e denominador zero interrompem comparações. Significa
apenas sinal matemático do desequilíbrio, sem gatilhos ou indicação de operação.

## Relatório da captura existente

`dom-captures/WINZ26-20261015T190503150562Z.imbalance.json`.
593 snapshots, 591 utilizáveis em todas as profundidades, 2 vazios; nenhuma falha,
anomalia, profundidade insuficiente ou denominador zero. Por profundidade:
590 intervalos comparáveis, 59,727 s. Nenhuma observação de imbalance exatamente zero.

| Níveis | BID médio | ASK médio | Imbalance médio | Mediana | P10 | P90 | Trocas diretas | Trocas/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 53,50 | 56,78 | -0,0228 | -0,0962 | -0,8158 | 0,7476 | 66 | 1,105 |
| 3 | 533,83 | 510,06 | 0,0057 | -0,0096 | -0,2639 | 0,2347 | 39 | 0,653 |
| 5 | 1297,85 | 1063,35 | 0,0771 | 0,0738 | -0,2028 | 0,2929 | 31 | 0,519 |
| 10 | 3979,27 | 2680,01 | 0,1883 | 0,2104 | 0,0434 | 0,3211 | 4 | 0,067 |

Média dos imbalances é média das razões por snapshot, não razão dos volumes médios.
Imbalance mínimo/máximo: N1 -0,9841/0,9860; N3 -0,3725/0,5267;
N5 -0,3750/0,4511; N10 -0,2723/0,4058.
Amostras positivas/negativas: N1 281/310; N3 287/304; N5 400/191; N10 539/52.
Imbalance inicial/final: N1 -0,5652/-0,3421; N3 0,1103/-0,0664;
N5 0,1867/-0,0378; N10 0,3008/0,0979. A série e bins mostram oscilações
intermediárias que os valores inicial/final não representam.

## Testes

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_dom*.py" -v
```

30 testes passaram, incluindo 7 novos: seleção dos melhores preços e profundidades,
fórmula/unidades, distribuição e bins, sinais/zero/repetições, interrupções/gaps,
níveis insuficientes/lado ausente, denominador zero e extremos -1/+1,
contrato/tempo incorretos e preservação/hash da fonte. Nenhuma coleta nova.
