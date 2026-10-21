# Absorção experimental — fase 1

**Histórico da versão 1.** A implementação atual usa referência comum de captura,
conforme [revisão de captura](experimental-absorption-capture-clock.md). Os estados,
resultados e restrições sobre offset descritos abaixo documentam o comportamento
anterior e não substituem as regras e resultados dessa revisão.

## Revisão específica de timestamps

A revisão não confirmou erro de conversão de fuso no detector. Ele importa
`timestamp()` de `mt5_dom_persistence.py`, a mesma função usada nas auditorias
temporal e Etapa 5: `datetime.fromisoformat(value).timestamp()*1000`, exigindo
offset explícito. `20:47:15.301+00:00` e `17:47:15.301-03:00` representam o mesmo
instante e produzem o mesmo número. O sufixo `local_ms` significa relógio de
captura convertido em epoch, não horário de parede sem fuso.

O capturador usa `datetime.now(timezone.utc)` nos campos de captura e preserva
`time_msc` bruto. As consultas do stream canônico usam `utc(time_msc)`, definida
em `mt5_price_history.py` com `datetime.fromtimestamp(..., timezone.utc)`, sem
adicionar três horas. A auditoria Etapa 5 mantém o cenário bruto A separado do
cenário B com +3h hipotético; não existe correção comprovada da sessão conjunta
que tenha deixado de ser aplicada pelo detector.

Exemplo real: o primeiro negócio tem `time_msc=1792086434470`, que interpretado
como epoch resulta em `2026-10-15T17:47:14.470+00:00`. A disponibilidade do lote é
`2026-10-15T20:47:15.301+00:00`, equivalente a `17:47:15.301-03:00` em Brasília.
Isso é compatível com a hipótese de que a fonte tenha codificado horário de
parede de Brasília no inteiro, mas o inteiro não possui informação de fuso que
comprove essa convenção. Mudar a representação UTC/Brasília preserva epoch;
reinterpretar aquele horário de parede como Brasília e somar três horas ao inteiro
é uma mudança de instante, que depende de evidência da convenção da fonte.

Sob a hipótese explícita +3h, a diferença entre fim da consulta e negócio,
nos 566 negócios, fica entre 690 e 831 ms, com mediana de 710 ms. Não é uma
medida comprovada de latência: inclui possível offset residual, atraso da fonte,
transporte, polling e processamento. Não foi descontada, calibrada por mediana
nem utilizada para mudar tolerâncias. Com configuração padrão, o detector retorna
`clock_relation_unknown` antes de comparar DOM e negócio nessa relação incerta.

Foram adicionados quatro testes: equivalência UTC/UTC-03 e rejeição de datas sem
fuso; equivalência de todas as emissões com fusos misturados; passagem pela
meia-noite UTC; passagem pela meia-noite de Brasília. Os testes preservam o
`time_msc` original e verificam ausência de erros artificiais de ordem/gap.
Os 17 testes experimentais passaram. Não houve alteração no módulo de detecção,
nos critérios, parâmetros, capturador, EA ou Java operacional.

Replay repetido: `dom-captures/experimental-absorption-timestamp-review.json`,
idêntico ao relatório anterior, inclusive parâmetros, transições e hashes.
Captura conjunta: 11 candidatos, 6 invalidados por deslocamento, 5 inconclusivos
(4 por ambiguidade do agressor e 1 por relação temporal desconhecida), zero
possíveis absorções e zero reações confirmadas. Captura só de DOM: zero candidatos,
sem T&T para avaliação.

Implementação isolada: `scripts/experimental_absorption.py`. Biblioteca padrão Python,
sem MT5, servidor, alteração de configuração Spring ou integração operacional.
O detector recebe registros disponíveis por `Detector.feed(record)` e devolve
transições JSON; `replay(path, config)` aplica exatamente o mesmo processamento.
Não há recomendações de compra/venda, avaliação de lucro ou interface gráfica.

## Inspeção das fontes existentes

- `mt5_dom_trades_probe.py` consulta DOM e ticks serialmente, com horários de
  início/fim locais, monotônicos, sequência e cursor `(time_msc, ordinal)`.
  Isso não estabelece sincronização entre OnTick e OnBookEvent. O arquivo é de
  polling Python; tampouco estabelece equivalência temporal com eventos do EA.
- `mt5_experimental_aggressor.py`: flags 32 e 64, respectivamente BUY e SELL.
  Ambas ou nenhuma tornam o agressor AMBIGUOUS. LAST/VOLUME (8/16) identificam
  negócios; quotes não contam como execução. A direção não vem de variação do
  preço. O detector recalcula a direção das flags, ignorando `side` legado.
- O Java `Mt5AggressorMapper` operacional é mais restritivo: bits desconhecidos
  também levam a AMBIGUOUS. Não foi alterado. Aqui o bit desconhecido é exposto
  em qualidade, seguindo os probes experimentais existentes; essa interpretação
  das flags é uma limitação da análise, não validação do fornecedor.
- `mt5_dom_probe.py` valida preço, quantidades, lados, duplicidade de nível,
  ordenação e spread. ASK=1 e BID=2 são ofertas limitadas, não agressões.
  A quantidade utilizada é `volume_dbl`, sem substituir por volume executado.
- `mt5_dom_persistence.py` mede presenças amostradas por `(lado, preço)`;
  `mt5_dom_quantity_changes.py` observa aumentos/reduções. Não há identificação
  de ordem ou instituição, nem separação confiável entre cancelamento, execução,
  substituição ou reposição causal. Ausência do nível pode ser saída da
  profundidade visível. Nenhuma dessas observações demonstra intenção.
- `mt5_dom_temporal_audit.py` e seus testes já documentam a relação desconhecida
  dos relógios: cerca de 3 horas de diferença e atraso residual na captura conjunta.
  Não é legítimo corrigir isso por mediana de recepção e assumir simultaneidade.
  O detector não estima offset a partir dos dados, nem mistura os dois arquivos
  como se fossem a mesma sessão.

## Regras e parâmetros

Os valores de `Config` são exemplos de pesquisa, registrados em cada saída,
não calibração para WIN. Podem ser substituídos por JSON via `--config`.

| Parâmetro | Padrão | Uso |
| --- | ---: | --- |
| window_ms | 1000 | Janela móvel de negócios |
| min_aggressor_volume | 100 | Piso de volume do agressor dominante |
| min_dominance | 0.75 | Fração do volume total, incluindo ambíguos |
| max_adverse_points | 5 | Máxima excursão adversa desde o primeiro negócio da janela |
| dom_distance_points | 5 | Distância máxima do nível ao preço de referência |
| min_dom_samples | 2 | Mínimo de observações explícitas do mesmo nível |
| min_dom_span_ms | 100 | Intervalo mínimo entre fim da primeira e início da última leitura |
| max_dom_age_ms | 500 | Idade máxima conservadora de todas as leituras utilizadas |
| max_poll_gap_ms | 500 | Intervalo máximo entre consultas e duração máxima de consulta |
| reaction_points | 10 | Deslocamento posterior favorável à hipótese |
| invalidation_points | 10 | Deslocamento posterior contrário à hipótese |
| reaction_timeout_ms | 2000 | Horizonte máximo em tempo de mercado e disponibilidade |
| market_to_local_offset_ms | null | Offset externo explícito, nunca inferido |
| clock_uncertainty_ms | null | Limite externo da incerteza da relação temporal |

O volume mínimo e a dominância são configuráveis; não há afirmação de que 100
contratos seja relevante em qualquer regime. Não há adaptação automática de
limiar nem ajuste aos resultados. A cadência de avaliação é de pelo menos uma
janela desde o primeiro negócio e entre candidatos; depois dela avalia a janela
móvel disponível. Não é segmentação exaustiva de todos os possíveis episódios.
As contagens dependem desses parâmetros e da cadência.

Possível absorção COMPRADORA exige SELL dominante, excursão para baixo limitada
e suporte BID observado. VENDEDORA exige BUY dominante, excursão para cima
limitada e suporte ASK observado. O preço de referência é o último negócio da
janela, não uma estimativa de preço médio institucional. Esta fase conservadora
exige DOM utilizável para `POSSÍVEL_ABSORÇÃO`; sem DOM o candidato é inconclusivo.

Com offset/limite explicitamente fornecidos, somente livros inteiramente anteriores
ao menor instante local possível do negócio são elegíveis. Todos precisam estar
dentro da idade máxima até o maior instante possível. Acrescenta-se 1 ms de
margem textual; isso não certifica precisão de 1 ms. A faixa do negócio também
deve terminar até a disponibilidade da consulta. Exige-se o mesmo nível presente
em todas as leituras elegíveis, com amostras e duração mínimas. Aumentos de
quantidade são informados separadamente, sem afirmar que repuseram negócios.
Livros vazios/inválidos interrompem a evidência; silêncio não cria observações.

Sem calibração externa, `clock_relation_unknown` impede suspeita conjunta mesmo
se os relógios parecerem próximos. Fornecer parâmetros é uma hipótese externa
registrada como `explicit_external_assumption`, não uma certificação pelo módulo.

## Estados e integridade

1. `OBSERVANDO`: volume/dominância atingidos; registra candidato e evidências.
2. `POSSÍVEL_ABSORÇÃO`: preço contido, agressor sem ambiguidade e DOM suficiente.
3. `REAÇÃO_CONFIRMADA`: negócio com tempo de mercado estritamente posterior à
   suspeita, recebido em lote posterior, alcança o deslocamento configurado.
   Confirma apenas reação do preço; não prova que absorção foi sua causa.
4. `INVALIDADA`: excursão inicial incompatível ou rompimento adverso posterior.
5. `DADOS_INSUFICIENTES`: ambiguidade, DOM inadequado, relógio desconhecido,
   falha de integridade, ausência de reação no horizonte ou fim de captura.

As transições OBSERVANDO/resultado inicial podem compartilhar timestamp. Cada
transição é uma cópia imutável, com `event_id`, referência, janela, volumes/delta,
evidências, qualidade, motivo e `state_history`. Os timestamps são epoch em ms;
`*_market_msc` mantém o relógio bruto da fonte e `*_local_ms` usa UTC local.
`reaction` tem seu próprio preço, tempo de mercado e tempo de conhecimento.

O lote é atômico: a suspeita só é avaliada após todos os ticks do lote estarem
disponíveis. Não se simula emissão anterior ao fim da consulta. Para candidatos
já emitidos, cada negócio posterior é avaliado na ordem original: um rompimento
intermediário não pode ser escondido pelo último preço do lote. Quotes não
confirmam reação e negócios com o mesmo `time_msc` não confirmam a suspeita.

Falha de cursor, desconexão, erro de consulta, lote não drenado, sequência ausente,
contrato divergente, tempo fora de ordem, consulta/gap acima do limite e negócio
inválido bloqueiam a instância até uma nova sessão independente. Não há reset
automático, ordenação retrospectiva nem deduplicação por conteúdo. Um gap entre
negócios, isoladamente, não é considerado perda: pode haver silêncio do mercado.
Falhas descobertas posteriormente geram transição posterior; nunca reescrevem
o conhecimento passado. Um resumo final não pode validar retroativamente o feed.

`finish()` é uma operação de encerramento offline. Se uma hipótese ainda estiver
aberta, registra censura com o timestamp `ended_utc` do resumo quando disponível.
Sem esse timestamp, `known_at_local_ms` é null e a origem é explicitamente
`offline_eof_without_capture_timestamp`; não inventa instante de captura para EOF.
A última observação permanece em `capture_boundary`. Não compare eventos
produzidos por EOF artificial de um prefixo com emissões durante a captura.

## Replay e resultado das capturas

Da raiz do repositório, usando um caminho de saída ainda inexistente:

```powershell
python -B panic-scanner/scripts/experimental_absorption.py panic-scanner/dom-captures/WINZ26-20261015T190503150562Z.ndjson panic-scanner/dom-captures/WINZ26-joint-probe-30s.ndjson --output panic-scanner/dom-captures/novo-relatorio-absorcao.json
python -B -m unittest discover -s panic-scanner/scripts -p test_experimental_absorption.py -v
python -B -m unittest discover -s panic-scanner/scripts -p "test_mt5_dom*.py" -q
python -B -m unittest discover -s panic-scanner/scripts -p test_mt5_experimental_aggressor.py -q
```

Para variar parâmetros, por exemplo: `--config parametros.json`, contendo
`{"min_aggressor_volume": 250, "min_dominance": 0.8, "window_ms": 1500}`.
Não fornecer offset/limite sem evidência independente de calibração.

Saída versionada desta execução: `dom-captures/experimental-absorption-phase1.json`.
Inclui SHA-256 conferido antes/depois da leitura, parâmetros e todas as transições.

| Captura | Observações | Candidatos | Possíveis | Invalidados | Inconclusivos |
| --- | --- | ---: | ---: | ---: | ---: |
| WINZ26-20261015T190503150562Z | 593 DOM, 2 inutilizáveis, nenhum T&T | 0 | 0 | 0 | 0 candidatos; captura sem T&T |
| WINZ26-joint-probe-30s | 297 DOM, 3 inutilizáveis; 566 negócios e 50 quotes | 11 | 0 | 6 | 5 |

Na conjunta, 340 SELL, 206 BUY e 20 AMBIGUOUS. Os 616 ticks possuem bits
desconhecidos, expostos na qualidade. Dos cinco inconclusivos, quatro têm volume
ambíguo na janela; um não tem relação temporal dos relógios comprovada. Os seis
invalidados têm excursão inicial incompatível. Zero reações confirmadas. As
contagens de motivos são primárias, não exclusividade das limitações: a relação
temporal desconhecida também impediria os demais candidatos de virarem suspeitas.
Não foram observadas falhas estruturais de sequência/cursor nessas duas fontes;
isso não prova completude de mercado.

## Validação e limites

13 testes novos cobrem os dois lados, reação posterior, falso positivo por
deslocamento, DOM ausente/vazio/obsoleto, ausência de novas observações, relógios
desconhecidos/incertos, agressor ambíguo, duplicatas, desconexão, falha de cursor,
lote incompleto, gap/ordem de sequência, negócio inválido, timeout e censura.
Também passaram os 54 testes existentes `test_mt5_dom*.py` e os 4 de agressor.

O teste de causalidade compara cada prefixo da sequência sintética com o
processamento completo: todas as emissões são idênticas, inclusive evidências e
timestamps. Anexar observações futuras não modifica transições anteriores. Outro
teste impede que um lote confirme sua própria suspeita; outro conserva rompimento
adverso intermediário. O hash serve apenas para integridade do arquivo, nunca
entra nas regras de decisão.

Amostras de aproximadamente 30/60 segundos, DOM com profundidade limitada,
polling, ausência de timestamp de origem DOM e de identificação individual de
ofertas não permitem estabelecer causalidade ou permanência entre amostras.
Também não permitem certificar atraso máximo do feed, perdas anteriores ao
terminal, correções silenciosas de conteúdo do cursor ou completude dos negócios.
A checagem de cursor só examina o que foi gravado; não reconstrói o prefixo bruto
que não foi capturado. Os relógios e o atraso precisariam de validação externa
antes de interpretação conjunta mais forte. Não há taxa de acerto, lucratividade
ou recomendação operacional derivada desta amostra.
