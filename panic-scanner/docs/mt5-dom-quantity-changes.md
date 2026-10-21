# Etapa 3 — Variações de quantidade no DOM

Análise exclusivamente offline do NDJSON original WINZ26. O script
`mt5_dom_quantity_changes.py` reutiliza a leitura, validação, identidade preço/lado
e regras de comparabilidade de `mt5_dom_persistence.analyze_file`, por meio de um
callback opcional de observações válidas. O callback informa se há continuidade;
o resultado anterior de persistência e sua CLI permanecem compatíveis.
Nenhum stream, replay, estratégia, frontend ou application.properties foi alterado.

Execução da raiz do repositório:

```powershell
python panic-scanner/scripts/mt5_dom_quantity_changes.py panic-scanner/dom-captures/WINZ26-20261015T190503150562Z.ndjson --output panic-scanner/dom-captures/analise-quantidades.json
```

A saída deve ser um arquivo novo; não há sobrescrita. Sem --output imprime JSON.
--max-gap-ms (padrão 500) define o limite entre snapshots comparáveis.
Hash SHA-256 antes/depois da leitura verifica que a fonte permaneceu igual.
O relatório desta execução está em
`dom-captures/WINZ26-20261015T190503150562Z.quantity-changes.json`.

## Semântica dos eventos

- increase/decrease: diferença líquida positiva/negativa de volume_dbl para o
  mesmo preço/lado presente nos dois snapshots consecutivos válidos. Magnitude
  é o valor absoluto da diferença; repetições não geram evento.
- disappearance: preço/lado sai da janela observada. Magnitude é sua última
  quantidade visível, não uma redução comprovada dessa quantidade a zero.
- reappearance: preço/lado antes visto e depois ausente volta à janela dentro
  do mesmo trecho de observação contínua. Magnitude é a nova quantidade visível;
  não indica a mesma ordem, participante ou instituição.
- first_appearance: entrada de preço/lado ainda não visto no trecho contínuo.
  Separada de reaparecimento para não inventar presença anterior à captura.

O primeiro snapshot utilizável de cada trecho é uma base e não gera eventos.
Vazios, falhas, livros inválidos e gaps acima do limite interrompem o conhecimento:
não há comparação, tempo de ausência ou intervalo entre eventos atravessando essas
interrupções. Retomada estabelece nova base. Erro estrutural, sequência faltante,
contrato errado e tempo fora de ordem interrompem explicitamente o processamento,
conforme o leitor existente; não são tratados como continuidade.

## Métricas

O relatório guarda eventos individuais com preço/lado, sequência, timestamps de
ambos os snapshots, quantidades anterior/posterior (null quando fora da janela),
magnitude e identificador do trecho contínuo. Por lado e tipo de evento: contagem,
eventos por segundo comparável, distribuição completa da magnitude e intervalos.
Há contagens também por preço/lado.

`side_interevent_ms` mede intervalo entre detecções do mesmo tipo de evento no
mesmo lado, agregando preços; múltiplos preços mudando no mesmo snapshot geram
intervalos zero. `same_price_kind_interevent_ms` compara eventos sucessivos do
mesmo tipo, preço e lado, sem atravessar períodos desconhecidos. Pode incluir
saída/reentrada na janela entre os eventos, sempre dentro do mesmo trecho conhecido.
`observed_absence_ms` mede primeira ausência detectada até reaparecimento do mesmo
preço/lado. Não estima ausências ainda abertas ou atravessadas por interrupções.

Distribuições contêm valores, contagem, soma, mediana, P90 por posto mais próximo
e máximo. Estatísticas sem amostras são null; frequência sem tempo comparável é
null. Quantidades permanecem na unidade volume_dbl do feed. Totais de diferenças
são somas de mudanças líquidas amostradas, não volume negociado ou fluxo bruto.

## Resultado da captura existente

593 snapshots: 591 válidos não vazios, 2 vazios, nenhuma falha, livro inválido ou
lacuna acima de 500 ms. 590 intervalos comparáveis totalizam 59,727 s.
SHA-256 original: c848f3fd5e51f1b87af3dd50ec37718fde3fb773ed8426e7b3b2037319e8e8b7.

| Evento | BID N / eventos por s | ASK N / eventos por s | BID magnitude mediana / P90 / máxima | ASK magnitude mediana / P90 / máxima |
| --- | ---: | ---: | ---: | ---: |
| Aumento | 510 / 8,539 | 462 / 7,735 | 4 / 42 / 176 | 4 / 37 / 250 |
| Redução | 345 / 5,776 | 272 / 4,554 | 7 / 70 / 441 | 9 / 75 / 251 |
| Saída da janela | 53 / 0,887 | 53 / 0,887 | 435 / 617 / 905 | 249 / 421 / 498 |
| Reaparecimento | 45 / 0,753 | 45 / 0,753 | 206 / 673 / 910 | 241 / 427 / 504 |
| Primeira aparição | 8 / 0,134 | 8 / 0,134 | 273,5 / 599 / 599 | 166 / 444 / 444 |

5.847 comparações entre níveis presentes nos dois extremos por lado: 14,62% tiveram
alteração no BID e 12,55% no ASK. Somas das magnitudes dos aumentos/reduções:
BID 7.459/8.364; ASK 6.458/6.357. Saídas não entram nessas somas.

| Intervalo entre eventos do mesmo preço/lado/tipo | BID mediana / P90 / máximo (ms) | ASK mediana / P90 / máximo (ms) |
| --- | ---: | ---: |
| Aumentos | 704 / 2811 / 27929 | 805 / 3517 / 31849 |
| Reduções | 1104 / 4545 / 29740 | 1404 / 6327 / 28331 |
| Saídas | 7343 / 28733 / 32551 | 7343 / 28733 / 32551 |
| Reaparecimentos | 7338 / 20232 / 32551 | 7338 / 20232 / 32551 |

Nos 45 reaparecimentos de cada lado, ausência observada mediana de 2.713 ms,
P90 de 12.562 ms e máximo de 29.035 ms. Essas métricas excluem ausências sem
reaparecimento observado; não descrevem uma distribuição completa de ausências.

## Validação

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_dom*.py" -v
```

23 testes passaram, sendo 7 testes novos cobrindo magnitudes, frequência,
intervalos, lados, saídas/reaparecimentos, primeiras aparições, repetições,
reinício após vazios/falhas/anomalias/gaps, ausência de dados, percentis,
rejeição de entradas inconsistentes e preservação da fonte. Nenhuma coleta real.
