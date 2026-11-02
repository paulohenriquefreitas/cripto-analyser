# Exibição individual e auditoria de duplicidades — WINZ26 30/10/2026

Alteração restrita à apresentação de `/three-candles`. Detector e motores preservados; nenhum evento apagado, combinado ou selecionado por resultado financeiro.

## Comportamento

* **Padrão selecionado**, inicial: somente 1/2/3 e eventual E de um candidato. Sem escolha explícita, seleciona o primeiro candidato conhecido. A seleção explícita permanece pelo ID durante o Replay; etapas só aparecem quando chegam no snapshot causal. Ao voltar para antes de sua existência, as marcas desaparecem e a seleção continua reservada, sem antecipação.
* **Gatilhos alcançados**: nenhum contorno ou número de etapa; somente E pequeno, por candidato com `ALCANCADO_HIPOTETICO` disponível. Referências coincidentes continuam sendo candidatos independentes; os E usam empilhamento discreto. O controle de números 1/2/3 não oculta E neste modo; o controle geral de marcações oculta tudo.
* **Todos os candidatos**: preserva a apresentação diagnóstica anterior, inclusive coalescência gráfica de marcas idênticas. A auditoria contém cada registro original.
* A tabela **Candidatos do pregão**, inicialmente recolhida, inclui horário dos três candles conhecidos, direção, preço/origem/ID da referência, variantes, estado, nível/estado E e motivos de rejeição, invalidação ou indeterminação. Clicar numa linha ou no botão de horário seleciona aquele candidato, muda para modo individual e enquadra horizontalmente os candles conhecidos com margem. Não muda a escala de preço ou dados dos motores.
* **Acompanhar candidato mais recente no Replay** é opcional: escolhe o último candidato publicado no snapshot corrente, sem consulta ao futuro. Desligá-lo fixa o ID corrente. Selecionar uma linha também desliga o acompanhamento. A seleção é delimitada por símbolo e pregão.
* A auditoria detalhada anterior continua integral, independentemente do modo ou seleção. E significa alcance hipotético após disponibilidade, sem inferir execução, lucro ou validação do padrão.

## Diagnóstico quantitativo

Fonte: snapshot nativo congelado até **16:10**, 86 M5 fechados / 430 M1, mesma base da implementação experimental. [Contagens reproduzíveis](../../frontend/src/features/threecandles/validation/pattern-detector/presentation-duplicates.json), [candidatos integrais](../../frontend/src/features/threecandles/validation/pattern-detector/candidates.csv).

| Medida | Quantidade |
|---|---:|
| Total por referência | 477 |
| Sequências distintas de IDs C1/C2/C3 | 125 |
| Sequência + direção | 150 |
| C1 compartilhado | 73 grupos / 473 candidatos / 400 excedentes ao primeiro |
| C2 compartilhado | 72 grupos / 464 candidatos / 392 excedentes |
| C3 compartilhado | 52 grupos / 258 candidatos / 206 excedentes |
| Referências diferentes na mesma faixa exata, mesmo C1 e direção | 114 grupos / 288 candidatos |
| Chegaram a REGIAO_TESTADA | 249 |
| Alcançaram E hipotético | 169 |
| Testes invalidados | 54 |
| Rejeitados na NEGA | 188 |

“Sequência” usa a tupla dos IDs já conhecidos; papéis ainda ausentes recebem `?`. Uma sequência parcial não é artificialmente completada. A mesma sequência pode existir nas duas direções ou com diferentes graus de reconhecimento. Grupos compartilhados não somam entre C1/C2/C3: o mesmo candidato pode participar dos três. “Mesma região” significa **igualdade exata de low/high**, sem tolerância inventada; apenas referências com IDs diferentes no mesmo episódio/lado contam nessa medida. Não é uma fusão.

## As hipóteses A/B estão permissivas?

Há evidência de permissividade e de multiplicação por referências, sem demonstração de taxa de falsos positivos:

| Variante | Candidatos | Com teste | Testes invalidados |
|---|---:|---:|---:|
| A — falha de renovação | 224 | 200 | 41 |
| B — falso rompimento | 133 | 121 | 22 |

77 aparecem nas duas linhas; A exclusiva =147, B exclusiva =56, união =280. As contagens são por referência, não operações independentes. Os outros 197 são 188 rejeitados e 9 apenas ALVO no corte.

A aceita igualdade de extremos: **9 registros** passaram por A com igualdade exata. Ela não exige dimensão mínima da falha ou reação no fechamento de C2; candles estreitos podem satisfazê-la. B exige rompimento e fechamento de retorno à faixa, mas não exige a rejeição posterior de C3. Ambas podem classificar a mesma negação. A faixa completa de C2 usada pelo teste e múltiplas referências sobrepostas também ampliam o número de candidatos reconhecidos, independentemente de A/B.

**31 registros com E alcançado não possuem evento de REJEICAO_OBSERVADA** no histórico. Isso reforça a separação entre teste, nível alcançado e padrão validado. Esses registros não foram suprimidos. Não há rotulagem humana suficiente para declarar quais são falsos positivos ou alterar regras nesta entrega.

Para avaliação futura: rotular por sequência/direção os casos das aulas e do pregão; comparar A exclusiva, B exclusiva e A+B; examinar igualdade, amplitude da falha e reação M1. Eventual revisão de critérios deve ser decidida a partir dessa auditoria, sem usar lucro posterior para combinar ou escolher candidatos.

## Validação visual e automatizada

As três capturas usam o **mesmo snapshot causal às 11:23** de 30/10/2026, com **127 candidatos conhecidos nesse instante**, não os 477 do corte final. O candidato escolhido é a compra 11:10/11:15/11:20, referência 170.115, nível hipotético 170.260 conhecido às 11:21 e alcançado às 11:23.

* [Padrão selecionado e centralizado](../../frontend/src/features/threecandles/validation/pattern-detector/mode-selected.png)
* [Somente gatilhos E](../../frontend/src/features/threecandles/validation/pattern-detector/mode-triggers.png)
* [Todos para diagnóstico](../../frontend/src/features/threecandles/validation/pattern-detector/mode-all.png)

São capturas do React/canvas real com respostas congeladas do teste Java, sem simular negócio novo ou reexecutar o detector no navegador. Cada `-browser.json` registra o modo, cursor, total de linhas, seleção e erros de console. Reproduzir com Vite local e `node scripts/capturePatternModes.mjs` em `frontend`.

Testes cobrem modo inicial individual, alternância entre os três modos, seleção por linha, seleção fixa durante avanço M1 11:20→11:23 e retorno anterior à existência do candidato, acompanhamento do candidato recente, permanência de todos os dados, contagens de duplicidade e enquadramento horizontal sem alterar preços ou recriar gráfico. TypeScript compilado e **183 testes frontend passaram**. Nenhum arquivo Java ou dado M1 foi alterado nesta etapa.
