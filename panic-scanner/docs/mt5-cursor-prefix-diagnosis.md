# Diagnóstico: CURSOR_PREFIX_DISAPPEARED com resposta vazia

Escopo: inspeção somente leitura do EA, NDJSON real e testes sintéticos. Nenhuma
mudança no EA, auditor operacional, DOM, estratégias ou lógica de aquisição.
Relatório separado: `dom-captures/WINZ26-cursor-prefix-diagnosis.json`.

## Evidência real confirmada

Arquivo localizado na pasta MQL5/Files do terminal:
`WINZ26-event-joint-152888781-75245385033055.ndjson`.
Resumo: 7.943 ticks aceitos, 7.137 eventos de negócio por flags, 1.888 notificações
DOM, zero drops, pico de fila 10, zero lotes cheios. Duração até limpeza aproximada
619,55 s. Último erro de API no lote: 0. A falha da captura veio da validação local.

| Sequência do lote | returned_rows | Novas linhas | Cursor após consulta | Duração consulta |
| --- | ---: | ---: | --- | ---: |
| 46337 | 4 | 1 | 1792174240036 / 1 | 11 us |
| 46344 | 1 | 0 | 1792174240036 / 1 | 7 us |
| 46354 | 0 | 0 | 1792174240036 / 1 (inalterado) | 202.678 us |

No lote 46344, a única linha retornada foi a âncora já consumida. Portanto ticks=[]
nesse lote NÃO significa resposta bruta vazia. No 46354, returned_rows=0 confirma
que a API não devolveu sequer a âncora, e não apenas que não houve negócio novo.
O campo error=0 registra GetLastError imediatamente após a consulta.

## Caminho exato no código

RecoverTicks faz, nesta ordem:

```mql5
ResetLastError();
int count=CopyTicks(SYMBOL,rows,COPY_TICKS_ALL,(ulong)cursor_ms,TICK_BATCH);
o.error=GetLastError();
o.returned=count;
o.ok=count>=0 && o.error==0;
ulong ordinal=0,seen_prefix=0;
// ...
if(count>=0)
{
   // O loop de count linhas não roda quando count=0.
   // seen_prefix fica zero.
   if(cursor_ordinal>0 && seen_prefix<cursor_ordinal)
   { fault="CURSOR_PREFIX_DISAPPEARED";o.ok=false; }
}
```

O serializador usa o.returned em returned_rows. Não existe atribuição posterior
que transforme retorno positivo em zero. ArrayResize(o.ticks,accepted) só muda
a lista de linhas consideradas novas, não o.returned.

Com count=0, error=0, cursor_ordinal=1, seen_prefix=0, a condição dispara sempre.
O EA não consegue distinguir falta de evidência numa resposta vazia de prefixo
menor/ausente numa resposta não vazia. Como o.ok passa a false, não avança cursor,
incrementa query_errors e OnTimer encerra pela existência de fault. Não é falha
de fila ou de capacidade de lote demonstrada pelos dados disponíveis.

## O que está e não está demonstrado

Demonstrado: a mensagem foi gerada pela condição local sobre uma resposta bruta
vazia com erro zero. A atribuição categórica 'prefixo desapareceu' é mais forte
que a evidência. Pode ser falso positivo diagnóstico; não se provou perda real.

Não demonstrado: resposta temporária por cache/sincronização, alteração efetiva
do histórico, problema do terminal/feed ou a ausência real de novos ticks.
202,678 ms contra 7 us na consulta anterior mostra uma consulta mais demorada,
mas não identifica sua causa. OnTick é uma notificação, não prova de negócio.
Não há relação demonstrada com a diferença de 3 horas investigada anteriormente.

CopyTicks documenta sincronização do histórico e retorno de quantidade copiada;
chamadas em EA podem esperar sincronização. Isso torna sincronização uma hipótese,
não explicação comprovada para zero/error=0 nessa execução.
Referência: https://www.mql5.com/en/docs/series/copyticks

## Menor correção segura proposta — NÃO aplicada

Manter a política conservadora de interrupção, diferenciando o motivo. Na validação
do prefixo, após validar ordem e erros, distinguir:

```mql5
// Proposta para revisão; não aplicada ao EA.
if(o.ok && cursor_ordinal>0)
{
   if(count==0)
   { fault="CURSOR_CONTINUITY_UNVERIFIED_EMPTY";o.ok=false; }
   else if(seen_prefix<cursor_ordinal)
   { fault="CURSOR_PREFIX_DISAPPEARED";o.ok=false; }
}
```

Isso não chama vazio de sucesso, não modifica cursor, não descarta ticks e não
reconecta. A captura termina com diagnóstico explícito de continuidade
INDETERMINADA; o arquivo anterior continua intacto e a captura segue interrompida.
O status global pode continuar incompleto porque o prazo não foi concluído;
continuidade indeterminada não equivale a quantidade conhecida de dados perdidos.

Apenas colocar count>0 na condição atual e continuar normalmente seria insuficiente:
ocultaria o intervalo sem revalidação. Pular timestamp, resetar ordinal ou deduplicar
conteúdo também não é seguro.

Se futuramente for desejada recuperação, seria outra mudança, com retentativa
limitada no mesmo cursor, registro do estado indeterminado, revalidação do prefixo
e sem publicar candidatos como continuidade comprovada antes disso. Resposta
não vazia sem prefixo, prefixo menor, erro ou retentativas esgotadas devem manter
interrupção explícita. Prefixo de mesma contagem mas conteúdo substituído ainda
não é detectável pelo cursor posicional atual; não se deve prometer integridade
completa mesmo após recuperação.

## Delimitação do trecho

Última confirmação do prefixo: query_end_us=619337305. Consulta que falhou:
query_start_us=619353142 até query_end_us=619555820. Todos são microssegundos
relativos à instância EA. O instante exato de eventual descontinuidade não é
observável. Continuidade após a última confirmação fica INDETERMINADA; número
de eventos perdidos=null. Os 7.943 ticks anteriores permanecem preservados,
sem certificação retroativa de cobertura total do feed.

## Testes executados

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_cursor_prefix_diagnostic.py" -v
```

9 testes passaram. Usam a função query experimental Python, que contém a mesma
condição de prefixo, com API falsa, e um decisor conservador apenas dentro dos testes.
Não alegam execução nativa do EA. Casos: resposta vazia com ordinal consumido,
vazio com seed ordinal=0, prefixo sem ticks novos, duplicatas legítimas no mesmo ms,
resposta não vazia sem âncora, prefixo menor, erro de API, sequência hipotética de
recuperação e substituição de payload com mesma contagem (limite da validação).

Nenhuma captura real ou consulta MT5 foi executada. O EA e o NDJSON real tiveram
hashes conferidos e permanecem inalterados. Não houve compilação por não haver
alteração MQL5 proposta para execução nesta etapa.
