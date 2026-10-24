# Volume Profile incremental e delta por preço

## Fluxo e escopo

`mt5_trade_stream.py` já fornece `price=last`, `volume=volume_real`, `timeMsc=time_msc`,
`side` e `cursorOrdinal`. `MarketTrade` valida números positivos finitos;
`Mt5TradeStreamClient` valida a proveniência `MT5_VOLUME_REAL`, classifica flags
com o `Mt5AggressorMapper` existente e filtra entregas repetidas pelo cursor.

Não havia Volume Profile no projeto. O novo `VolumeProfile` é um motor Java puro,
independente de Spring, DOM, candles, detecção e estratégias. A única extensão
do cliente é `startWithCursor`: entrega também a identidade que já foi lida.
Os métodos anteriores delegam a essa extensão. Consultas Python, classificação,
algoritmo de cursor, ordenação, warm-up e política de reconexão não mudam.

O mesmo `Mt5TradeMonitoringLifecycle` entrega cada negócio a `Mt5MonitoringFlowService`
e `Mt5VolumeProfileService`. Não há outra captura MT5. O perfil em memória é
compartilhado por todos os clientes; ativar/desativar o painel não reseta o motor.
REST apenas lê snapshots. A coleta compartilhada permanece controlada por
`mt5.trade-monitoring.enabled`, que já existia.

O contrato operacional vem de **mt5.symbol / MT5_SYMBOL**, padrão **WINZ26**.
O perfil não implementa rolagem. Exibe o símbolo e a data dos negócios realmente recebidos,
mesmo se o fluxo estiver entregando dados de uma sessão anterior. O teste com a
captura local usa **WINZ26** sem modificar o fluxo ao vivo.

## Preço, volume e agressão

- Tick size WIN: **5 pontos de índice**. Chaves são índices inteiros da grade,
  obtidos por divisão decimal exata. Um preço fora da grade é rejeitado e marca
  integridade comprometida; nunca é arredondado para outro preço.
- A B3 especifica tick de 5 pontos e R$ 0,20 por ponto:
  https://www.b3.com.br/pt_br/produtos-e-servicos/negociacao/renda-variavel/futuro-mini-de-ibovespa.htm
- A unidade de todos os cálculos é **MT5_VOLUME_REAL_UNITS**, preservando exatamente
  o volume executado fornecido pelo feed, sem fator monetário, volume DOM, tick
  count ou soma de `volume` com `volume_real`. Na captura local auditada,
  `trade_contract_size=1`, `volume_step=1` e todos os 566 negócios têm
  `volume_real=volume`, compatíveis com quantidade de contratos. Isso não prova
  a unidade de qualquer outro símbolo/corretora; o DTO mantém a proveniência bruta.
- `time_msc` é preservado na identidade de origem; conversão de horário de sessão
  é separada e configurável. A documentação MT5 declara timestamps UTC, mas o
  projeto já tem evidências de divergência entre horário do feed e captura. Não
  se aplica automaticamente um deslocamento de três horas.
  https://www.mql5.com/en/docs/python_metatrader5/mt5copyticksfrom_py
- BUY/SELL/AMBIGUOUS vêm do classificador operacional. As máscaras extras verificadas
  1024 e 1280 não anulam direção exclusiva. Outras máscaras, ausência de agressor
  ou inconsistência permanecem ambíguas. Sem inferência por
  variação do preço, bid/ask ou DOM.

Para um preço p:

```
V(p) = BUY(p) + SELL(p) + AMBIGUOUS(p)
delta(p) = BUY(p) - SELL(p)
participação_lado(p) = 100 × volume_lado(p) / V(p)
volume_sessão = soma V(p)
delta_sessão = soma delta(p)
```

Volumes e preços usam `BigDecimal`; somente percentuais e largura das barras
usam números de ponto flutuante. Cada negócio incrementa a contagem, inclusive
negócios de conteúdo idêntico com ordinais distintos. Uma identidade já entregue
não é acumulada outra vez. A identidade é `(time_msc, ordinal)` do fluxo ordenado;
não se deduplica por preço, volume, lado ou timestamp isoladamente.
Ordinal faltante ou conteúdo alterado na identidade corrente marca falha.
Identidades antigas são entregas repetidas, conforme o contrato monotônico do
adaptador; o motor não é um importador de negócios fora de ordem arbitrária.

## POC, área de valor e nós

POC é o nível de maior volume total; empate escolhe o **menor preço**, independentemente
da ordem de inserção. POC e totais são atualizados a cada negócio.

Área de valor determinística:

1. Começa no POC, incluindo seu volume inteiro.
2. Compara os próximos preços **negociados** imediatamente abaixo e acima do
   intervalo já incluído. Acrescenta o lado com maior volume; empate escolhe abaixo.
3. Repete até atingir pelo menos `0,70 × volume total observado`.
4. VAL/VAH são o menor/maior preço incluído. Não se fraciona um nível: o percentual
   pode exceder 70%, inclusive 100% em distribuições esparsas ou dominadas pelo POC.

Distribuições descontínuas usam vizinhos negociados, sem criar milhares de preços
intermediários. O intervalo VAL–VAH pode atravessar lacunas. Um preço ausente significa
**sem volume observado nesse preço**, não prova de ausência de negócios no pregão.
Não é inserido como volume zero nem classificado automaticamente como LVN.

HVN: níveis com volume `>= 70% do volume do POC`.
LVN: níveis negociados com volume `<= 30% do volume do POC`.
Níveis selecionados consecutivos na grade de 5 pontos formam regiões. Lacunas ou
níveis não selecionados separam as regiões. Limites inclusivos; são descritores de
concentração, não suportes/resistências ou sinais. Não se afirma que sejam máximos
ou mínimos locais de um histograma suavizado.

Acumulação custa `O(log P)` por negócio, com P preços negociados; não guarda nem
reprocessa todos os negócios. Snapshot percorre níveis para VA/nós (`O(P log P)` no
pior caso), é imutável e fica em cache até nova atualização/falha. Memória `O(P)`;
o cursor corrente evita um conjunto de milhões de IDs.

## Sessão, cobertura e integridade

Data da sessão é calculada em **America/Sao_Paulo**, após aplicar o deslocamento
de feed explicitamente configurado, sem alterar a identidade original.
As regras abaixo pertencem somente ao perfil:

| Propriedade | Padrão |
|---|---|
| `mt5.volume-profile.session-open` | `09:00` |
| `mt5.volume-profile.session-close` | `18:00` |
| `mt5.volume-profile.holidays` | vazio; datas ISO separadas por vírgula |
| `mt5.volume-profile.calendar-confirmed` | `false` |
| `mt5.volume-profile.feed-time-shift-minutes` | `0` |
| `mt5.volume-profile.time-basis-confirmed` | `false` |

As variáveis `MT5_PROFILE_*` correspondentes estão em `application.properties`.
Horários são limites configuráveis, **não sincronização automática com o calendário
B3 vigente**. Finais de semana e feriados configurados são excluídos. Ajustar
horários especiais antes da captura; não há fornecedor automático de feriados ou
agenda por data. Sem confirmação de calendário/relógio isso fica explícito na
cobertura. Negócios fora da sessão configurada não entram e geram falha.

O perfil ao vivo é **PARTIAL**, mesmo quando o primeiro negócio coincide com a
abertura. O warm-up existente de 35 segundos não comprova o pregão inteiro.
Não existe recuperação histórica automática para completar a sessão.

`INCOMPLETE` tem prioridade se houver desconexão, erro de fonte, volume inválido,
falha de identidade, preço fora da grade ou sessão incompatível. O volume já
conhecido fica preservado e pode receber novos negócios, mas a falha permanece
visível. Reconexão não limpa falhas nem presume que uma lacuna teve volume zero.
Falhas de integridade também são preservadas na próxima sessão do mesmo processo.

Troca de data posterior cria outro perfil, sem somar volumes anteriores; o último
perfil encerrado permanece em `previousSession`. Dados atrasados de outra data
são rejeitados, sem voltar a sessão atual. Não há limpeza por atualização da tela.
O estado é em memória: após reinício do backend, nova captura volta a ser parcial.

`COMPLETE` só é possível pela API interna `confirmCoverage(from, through)`, reservada
a um futuro importador de histórico **auditado**, com intervalo cobrindo abertura
até fechamento, calendário e relógio confirmados e nenhuma falha. Não há endpoint
nem chamada ao vivo para promover o estado. Novo negócio aceito invalida a
afirmação anterior de cobertura completa. O método é uma afirmação explícita do
importador, não prova automática de disponibilidade de histórico.

## Leitura e visualização

`GET /api/mt5/volume-profile` devolve conectividade, último recebimento UTC, perfil
atual, sessão anterior e falhas. Perfil `null` indica falta de negócios aceitos,
nunca sessão completa com volume zero.

`GET /api/mt5/volume-profile/level?price=208350` e `VolumeProfile.read(price)` retornam
sessão, status, cobertura e falhas junto com volume/delta/participações/contagem
no preço exato e distâncias absolutas em pontos
a POC, VAH, VAL, HVN e LVN. Dentro de uma região a distância é zero; ausência de
nível/região retorna null. Fora da grade não se consulta outro preço por aproximação.
O futuro motor de confluências deve consultar também cobertura e integridade.

Na página **WIN M5 → Tempo real**, ativar **Volume Profile + Delta por preço**.
O painel separado é desativado por padrão. Só enquanto ativado consulta REST a
cada segundo, sem sobrepor pedidos; desativação desmonta o polling e cancela a
requisição. Histograma horizontal usa verde/vermelho/cinza, destaca POC e marca
VAH/VAL. Selecionar um preço mostra delta e percentuais. Falha de rede mantém o
último snapshot com aviso; não inventa volume nem apaga integridade. O gráfico
principal, candles e indicadores não mudam. Não há sinais nem execução de ordens.

## Validação

Testes do motor cobrem acumulação, três lados, deltas, duplicatas legítimas,
redelivery, POC/VA, empates, lacunas, HVN/LVN, tick exato, unidade fracionária,
cobertura parcial/completa explícita, feriados/fuso, troca de pregão, falhas
persistentes e replay determinístico a cada prefixo. Serviço/REST verificam
separação de sessões, consultas de distância, cobertura e integridade. O teste
do cliente usa processo Java simulado para verificar que ordinais são expostos
sem mudar a filtragem existente. Nenhuma conexão real MT5 é iniciada pelos testes.

Teste opcional somente leitura da captura local
`dom-captures/WINZ26-joint-probe-30s.ndjson`: **566 negócios, volume 4.685, 12 níveis,
POC 208.350**. Após a correção restrita das flags: BUY **1.444**, SELL **3.188**,
AMBIGUOUS **53**, delta **−1.744**. Flags e arquivos originais continuam intactos.
Detalhes em `mt5-live-contract-and-aggression.md`. A captura não comprova sessão completa
nem continuidade de todos os negócios. Se não estiver disponível, somente esse
teste é pulado; os testes sintéticos não dependem dela.

```powershell
mvn -pl panic-scanner -am "-Dtest=VolumeProfileTest,VolumeProfileRealCaptureTest,Mt5VolumeProfileServiceTest,VolumeProfileControllerTest,Mt5TradeStreamClientTest,Mt5MonitoringFlowServiceTest,Mt5TickWebSocketHandlerTest,MonitoringFlowEngineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

No frontend: `npm test` e `npx tsc -b --pretty false`.
