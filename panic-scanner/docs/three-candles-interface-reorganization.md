# Reorganização da interface /three-candles

Branch: `feat/three-candles-foundation`. Referência de migração: [auditoria dos 106 itens](three-candles-interface-audit.md). Escopo implementado: apresentação, composição dos controles e defaults visuais aprovados. Nenhuma funcionalidade foi removida.

## Resultado

A página apresenta primeiro identidade da sessão, configuração de mercado/Replay, reprodução e camadas. O gráfico fica imediatamente abaixo das quatro camadas principais. Depois vêm o resumo de estrutura/reação/sessão, fichas selecionadas por clique e dois painéis nativos inicialmente recolhidos: **Configurações avançadas** e **Auditoria e diagnósticos**.

Os painéis usam `details/summary` e mantêm o conteúdo montado. Expandir/recolher não envia comandos nem reconstrói a sessão. O gráfico está fora deles e conserva a instância, viewport e escala. Tabelas de auditoria têm rolagem própria; seu estilo é restrito a tabelas dentro de `details`, sem interferir na tabela interna da biblioteca gráfica.

## Componentes frontend modificados

| Arquivo | Alteração |
|---|---|
| [ThreeCandlesPage.tsx](../../frontend/src/features/threecandles/pages/ThreeCandlesPage.tsx) | Cabeçalho/configuração/reprodução compactos; H1/M5 inicialmente ocultos; slots de suportes/diagnósticos; alertas condicionais; resumo temporal usando `availableAtMsc`; preferências de camadas em memória da visita. |
| [ThreeCandlesWorkspace.tsx](../../frontend/src/features/threecandles/components/ThreeCandlesWorkspace.tsx) | Gráfico antes das ferramentas antigas; camadas independentes; áreas avançada/auditoria; defaults aprovados; detalhes por seleção; suporte visual explícito de M15. |
| [DowDebugPanel.tsx](../../frontend/src/features/threecandles/components/DowDebugPanel.tsx) | Seções de apresentação reutilizáveis para camada, etiquetas, resumo, ficha, ajuda e auditoria. Último rompimento identificado como histórico conhecido. Movimentos/eventos existentes acessíveis também como JSON, sem exportador novo. |
| [ThreeCandlesChart.tsx](../../frontend/src/features/threecandles/components/ThreeCandlesChart.tsx) | Altura útil responsiva e rótulo acessível atualizado. Biblioteca, camadas, hit-tests, atualização incremental e dependências de montagem preservados. |

Não foram alterados `AppLayout`/menu, API, controlador de candles, agrupamento, primitivas de desenho, `FibonacciMovementEngine`, Java, caches, fontes MT5, detectores ou algoritmos do Replay. A formatação dos quatro componentes foi normalizada com o Prettier já instalado.

## Defaults visuais

| Camada | Antes | Agora |
|---|---|---|
| Dow principal | Desligado | **Ligado** |
| DOW 50% principal | Desligado | **Ligado** |
| H1 fúcsia | Ligado | **Desligado** |
| M5 amarelo | Ligado | **Desligado** |
| Etiquetas principais | Ligadas | **Desligadas** |
| Dow micro | Desligado | Desligado |
| Todos os fractais micro | Desligado | Desligado |
| Etiquetas micro | Desligadas | Desligadas |
| Fibonacci antiga | Ligada | **Desligada** |

Fonte inicial Tempo Real, símbolo configurado, M5, hora MT5, data inicial, 09:00, modo de Replay fechado, velocidade 1x, detecção H1 2+2, agrupamento 30 pontos, separação desligada, filtros comparativos e parâmetros da Fibonacci permanecem como antes.

As preferências de camadas são conservadas em memória da página. Um ref de apresentação fornece os valores escolhidos quando o workspace precisa remontar ao carregar uma sessão. Não há efeito que reaplique defaults em snapshots, Play/Pause/Próximo/Reiniciar. Não foi introduzido banco, localStorage ou persistência após sair/recarregar a página. A memória manual mantém o ciclo anterior: Reiniciar a mesma sessão conserva versões e as filtra pelo instante; nova identidade de sessão pode remontar o editor e limpar versões locais.

## Distribuição das áreas

### Principal

- Ativo, período, Tempo Real/Replay e referência temporal continuam explícitos. No Replay aparecem data, hora, modo e Carregar.
- Reprodução contém os comandos originais e seus estados desabilitados, além de Ajustar gráfico. Em live os comandos de Replay não são apresentados.
- Ordem das camadas: Dow principal, DOW 50%, H1 fúcsia, M5 amarelo. Desligar Dow não desliga 50%, nem modifica o snapshot Java.
- Gráfico com largura disponível e altura responsiva: 440 px em telas menores; em desktop, altura entre 500 e 760 px conforme a janela. Navegação nativa permanece disponível.
- Resumo distingue **estado Dow**, **referência/reação DOW 50%** e **estado da fonte/Replay**. `COMPLETED` é apresentado como `FINISHED` no resumo, sem mudar o valor transportado. O instante vem de `availableAtMsc`; a origem do último candle é identificada separadamente.
- Último rompimento aparece como **Último rompimento conhecido**, com horário; seu motivo completo permanece nos eventos da auditoria.

### Configurações avançadas

Todos os subgrupos começam recolhidos:

- **Estrutura de Dow:** etiquetas principais, ondas micro, todos os fractais, etiquetas micro, contagens/abertura e legendas.
- **Suportes e resistências:** agrupamento M5, separação por rompimento, detecção H1 e filtros comparativos. A ajuda distingue agrupamento local, preparação Java que exige recarga e os dois experimentos 1+1 diferentes.
- **Fibonacci antiga / Comparação manual:** toda a ferramenta e mensagens originais, seleção por clique/listas, cancelar, aplicar, âncoras e resumo de versão. Sua tolerância está identificada como independente do monitor Dow.
- **Ajuda e informações técnicas:** origem da linha e critérios experimentais de reação/conclusão.

Durante seleção manual armada, um aviso curto e Cancelar captura também ficam próximos do gráfico. A prioridade de clique existente continua restrita a esse estado explícito. Não foram criados hit-tests para H1/M5 ou para segmentos de onda.

### Auditoria e diagnósticos

Todos os assuntos e painéis internos começam recolhidos, inclusive **Candidatos H1 1+1**:

- **Dow principal:** tabela de pivôs/classificações/participação, movimentos e eventos com motivos/horários, incluindo substituições.
- **Dow micro:** JSON original com fractais, selecionados, segmentos, abertura, ambiguidades e históricos, acessível mesmo com desenho micro oculto.
- **DOW 50%:** seletor histórico, tabela de referências, justificativas de encerramento/substituição, eventos e observações por candle. Selecionar histórico não troca a linha vigente.
- **Fibonacci antiga:** versões, âncoras, ambiguidades, eventos e observações geométricas.
- **Suportes e resistências:** regiões M5, originais, preparação H1, candidatos, avaliações e episódios.
- **Dados e Replay:** catálogo de sessões, cobertura/aquecimento, lista completa de lacunas M1, status técnico, contador bruto entre buckets e ajuda temporal/de resolução.

O contador de diferenças entre buckets continua identificado como diagnóstico: seus intervalos entre pregões não são confundidos com a lista de minutos M1 ausentes. Nenhum preenchimento, exportação ou mecanismo novo de reconstrução foi criado.

## Detalhes e alertas

O clique da linha DOW 50% abre uma ficha com ID/direção, extremos e horários de formação/confirmação, máxima/mínima, média, vigente desde, estado, última reação e possíveis extremos. O clique no pivô abre a ficha original. Há ações para fechar as fichas; listas históricas e botões na auditoria continuam alternativas ao clique.

Falhas de conexão/API, backend sem estrutura, falta de preparação/cobertura, M1 ausente/divergente e fim da sequência têm avisos condicionais na principal. Mensagens longas de experimentos visualmente ocultos ficam nos respectivos painéis. Se a Fibonacci ativa recebe memória limitada, também há aviso curto fora dos painéis.

Em M15 os controles Dow/micro/50% ficam desabilitados, os overlays estruturais não são apresentados como disponíveis e a página explica a limitação. As preferências escolhidas em M5 não são apagadas. O modo progressivo permanece limitado a M5 como já era no contrato.

## Preservação analítica verificada

O levantamento de integridade foi criado **antes** da edição, pois a branch já tinha alterações e arquivos não rastreados de etapas anteriores. A comparação desta tarefa não depende de considerar essas alterações anteriores como parte da reorganização.

- **397 arquivos protegidos** conservaram SHA-256: fontes/testes Java, modelos frontend, API e primitivas.
- Comparação por AST confirmou que as expressões de análise/observação/versões da Fibonacci, seleção/aplicação manual, agrupamento, carga/comandos e todos os efeitos/dependências da fonte permaneceram semanticamente iguais. Trivia do formatador não conta como alteração analítica.
- Os testes de navegador verificam que snapshots de entrada não são mutados por camadas, painéis, cliques ou editor manual. A mudança da versão manual altera somente o estudo local já existente.

Evidência: [source-integrity.json](../../frontend/src/features/threecandles/validation/interface-reorg/source-integrity.json). Reprodução: `node frontend/scripts/checkThreeCandlesPresentation.mjs`.

## Testes e validação visual

Foram adicionados testes para defaults, gráfico fora de seções recolhidas, preservação de preferências em snapshots/remontagens, M15, distinção de instante/origem do candle, alertas condicionais e candidatos H1 inicialmente recolhidos. Os testes existentes de controles, seleção/aplicação manual, independência de camadas, viewport, causalidade e contratos continuam fazendo parte da suíte.

O teste de navegador usa React e o gráfico nativo reais em Edge com HTTP interceptado. Exercita comandos, todas as camadas, expansão por teclado, labels, viewport/escala, cursor do Replay, cliques de Dow/50%, aplicação manual, live M5 e M15. Em PROGRESSIVE, Próximo M1 troca o snapshot parcial das 09:59 pelo fechamento nativo das 10:00. Não se trata de uma sessão operacional conectada ao MT5.

Fontes separadas:

- **WINZ26 — 28/10/2026:** prefixo auditado das 10:00, micro do mesmo corte e M1 parcial 09:59→10:00. Os prefixos originais pertencem ao histórico já limitado a 17:35; o corte não foi ampliado. A fixture de prefixo não traz preparação H1; por isso seu aviso aparece corretamente.
- **WIN$N — 16/10/2026:** snapshot final de 18:25, combinando preparação já auditada e observadores Dow/50% do mesmo símbolo/corte. Não houve conversão para WINZ26.
- **Erro de dados:** resposta HTTP 503 explicitamente rotulada como fixture, acionada no comando de passo. Não é uma falha real do terminal.
- **M15:** fixture de apresentação derivada somente de trincas completas dos M5 registrados, sem dados futuros. Serve para verificar disponibilidade/controles, não é uma nova coleta nativa de M15.
- **Live:** mesma fotografia histórica em contrato de apresentação LIVE, para testar layout/controles; não demonstra a conexão do terminal em tempo real.

Desktop foi conferido em 1600×1200 e largura menor em 390×844. Os inputs têm labels associados; `summary` recebe foco/Space e foco visível. Tabelas/JSON não expandem a largura da página. Não foi adicionada dependência de UI.

Resultado final: **161 testes frontend aprovados, zero falhas**, executados com `npm test`; **TypeScript aprovado** com `npx tsc -b`. Os três cenários de navegador concluíram sem exceções da aplicação. A criação de uma segunda sessão registrada nas evidências é a carga explícita de M15, não uma reconstrução causada por painéis.

| Requisito de teste | Evidência executada |
|---|---|
| 1. Defaults aprovados | Teste de workspace e leitura dos nove checkboxes no navegador. |
| 2. Gráfico sem expandir painéis | Gráfico fora de todos os `details`; captura principal e todos os painéis inicialmente fechados. |
| 3. Reprodução | Testes existentes de Page; navegador registra PLAY, PAUSE, NEXT, SPEED e RESET. |
| 4. Todas as camadas | Alternância de todos os checkboxes e independência dos overlays; preferências não padrão conservadas após comandos. |
| 5. Expandir/recolher sem alterar sessão/resultados | Contador de criação de sessão constante durante painéis; snapshots imutáveis e integridade analítica. |
| 6. Zoom | Faixa lógica e escala manual preservadas no gráfico nativo; testes de viewport existentes. |
| 7. Cursor/navegação horizontal | Mesmo instante textual do Replay e mesma faixa lógica após painéis; mesma instância do gráfico. |
| 8. Clique no DOW 50% | Clique por coordenada nativa abre `dow-fifty-selected`, incluindo origem e possíveis extremos. |
| 9. Clique em pivô Dow | Clique real no extremo abre `dow-selected`; alternativa por botão continua presente. |
| 10. Fibonacci manual | Armar/cancelar prioridade e aplicar âncoras no navegador; testes de escolha, comparação e Replay/reset existentes. |
| 11. M5/M15 | Inputs preservados, avisos corretos e overlays estruturais indisponíveis em M15; retorno a M5. |
| 12. Alertas condicionais | Testes de ausência M1/fonte/estrutura/preparação; captura de resposta 503 rotulada como fixture. |
| 13. Avançada que exige recarregar | Teste existente de Detecção H1 e recriação da fonte com o modo solicitado; efeitos de API semanticamente iguais. |
| 14. Responsividade/acessibilidade básica | 390×844 sem overflow horizontal; labels dos inputs e abertura de painel por Space/foco. |

Evidências consolidadas em [verification.json](../../frontend/src/features/threecandles/validation/interface-reorg/verification.json). Testes acrescentados/adaptados: [threeCandlesPage.test.mjs](../../frontend/tests/threeCandlesPage.test.mjs) e [threeCandlesFibonacciManual.test.mjs](../../frontend/tests/threeCandlesFibonacciManual.test.mjs).

### Capturas

As capturas anteriores foram feitas antes da edição com o capturador existente, ligando Dow/50% para permitir comparação de origem. As novas usam os defaults aprovados; detalhes por clique e painéis têm capturas próprias.

| Situação | WINZ26 | WIN$N |
|---|---|---|
| Interface anterior | [Antes](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-before.png) | [Antes](../../frontend/src/features/threecandles/validation/interface-reorg/WIN_N-before.png) |
| Principal, defaults aprovados | [Nova](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-main.png) | [Nova](../../frontend/src/features/threecandles/validation/interface-reorg/WIN_N-main.png) |
| Avançadas abertas | [Avançadas](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-advanced.png) | [Avançadas](../../frontend/src/features/threecandles/validation/interface-reorg/WIN_N-advanced.png) |
| Auditoria aberta | [Auditoria](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-audit.png) | [Auditoria](../../frontend/src/features/threecandles/validation/interface-reorg/WIN_N-audit.png) |
| Clique no DOW 50% | [Ficha](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-fifty-details.png) | [Ficha](../../frontend/src/features/threecandles/validation/interface-reorg/WIN_N-fifty-details.png) |
| Tela menor | [390 px](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-mobile.png) | [390 px](../../frontend/src/features/threecandles/validation/interface-reorg/WIN_N-mobile.png) |
| Apresentação live M5 | [Live](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-live.png) | [Live](../../frontend/src/features/threecandles/validation/interface-reorg/WIN_N-live.png) |
| M15, recursos limitados | [M15](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-m15.png) | [M15](../../frontend/src/features/threecandles/validation/interface-reorg/WIN_N-m15.png) |

Progressivo WINZ26: [parcial 09:59](../../frontend/src/features/threecandles/validation/interface-reorg/PROGRESSIVE-progressive.png) e [fechado 10:00](../../frontend/src/features/threecandles/validation/interface-reorg/PROGRESSIVE-progressive-closed.png). Alerta: [erro de dados rotulado](../../frontend/src/features/threecandles/validation/interface-reorg/WINZ26-data-error.png).

Reprodução visual, com Vite em 127.0.0.1:5174: `node frontend/src/features/threecandles/validation/captureInterfaceReorg.mjs WINZ26`, depois `WIN_N` e `PROGRESSIVE`. As respostas gravadas e os arquivos `*-browser.json` identificam o método de captura e as verificações.

## Limitações e decisões preservadas

- Não há novos cliques em ondas, H1/M5 ou micro; as interações existentes foram mantidas.
- O resumo de reação continua experimental. Não foi criado critério definitivo de rejeição, ordem ou detector Alvo → Nega → Testa.
- Tabelas técnicas conservam termos/códigos originais, sob demanda; nem todos foram traduzidos.
- Recolher painéis não desliga motores nem evita os cálculos locais que já eram feitos. Esta é uma redução de poluição visual, não uma otimização do processamento analítico.
- A nova altura do gráfico altera o enquadramento físico inicial, sem alterar valores ou cronologia. Reset, troca de identidade/fuso e Ajustar gráfico mantêm suas ações anteriores de enquadramento/reconstrução; expandir painéis não as executa.
- Não foram executados testes Java que regeneram históricos, já que nenhum fonte Java foi modificado. A preservação foi verificada por hashes e a validação exigida de frontend permanece registrada abaixo.

## Matriz de rastreabilidade A01–G10

Os 106 IDs originais aparecem uma vez na matriz. “Resumido” significa que a informação curta fica na principal e os detalhes continuam acessíveis; não significa exclusão. G02/G10 são elementos compartilhados e permaneceram sem edição.

| ID | Item original | Localizacao final | Situacao |
|---|---|---|---|
| A01 | Ativo | Principal > Configuracao da sessao | Preservado |
| A02 | Período | Principal > Configuracao da sessao | Preservado |
| A03 | Referência do horário | Principal > Configuracao da sessao | Preservado |
| A04 | Tempo Real | Principal > Configuracao da sessao | Preservado |
| A05 | Replay | Principal > Configuracao da sessao | Preservado |
| A06 | Modo de Replay | Principal > Configuracao da sessao | Preservado |
| A07 | Detecção H1 | Avancadas > Suportes e resistencias > Deteccao H1 | Preservado |
| A08 | Data | Principal > Configuracao da sessao | Preservado |
| A09 | Hora inicial | Principal > Configuracao da sessao | Preservado |
| A10 | Carregar | Principal > Configuracao da sessao | Preservado |
| A11 | Reiniciar | Principal > Reproducao | Preservado |
| A12 | Play | Principal > Reproducao | Preservado |
| A13 | Pause | Principal > Reproducao | Preservado |
| A14 | Próximo candle / Próximo M1 | Principal > Reproducao | Preservado |
| A15 | Velocidade | Principal > Reproducao | Preservado |
| A16 | Sessões locais / histórico MT5 | Auditoria > Dados e Replay > Sessoes locais / fonte MT5 | Preservado |
| A17 | Obtenção/erro de configuração | Principal > Alertas condicionais | Preservado |
| A18 | Progresso do histórico | Principal > Alertas condicionais | Preservado |
| A19 | Fonte M1 com minutos ausentes | Principal > Alerta M1; lista completa em Auditoria > Dados e Replay | Preservado |
| A20 | Erro geral | Principal > Alertas condicionais | Preservado |
| B01 | M5 - amarelo | Principal > Camadas, imediatamente acima do grafico | Preservado |
| B02 | H1 - fucsia | Principal > Camadas, imediatamente acima do grafico | Preservado |
| B03 | Agrupamento M5 (pontos) | Avancadas > Suportes e resistencias; originais em auditoria | Preservado |
| B04 | Separar movimentos apos rompimento | Avancadas > Suportes e resistencias; originais em auditoria | Preservado |
| B05 | Referência H1 2+2 | Avancadas > Suportes e resistencias; originais em auditoria | Preservado |
| B06 | Adicionais H1 1+1 | Avancadas > Suportes e resistencias; originais em auditoria | Preservado |
| B07 | Texto H1 fixo / memória M5 | Avancadas > Suportes e resistencias; originais em auditoria | Preservado |
| B08 | Pivôs M5 → linhas visíveis/ocultas | Avancadas > Suportes e resistencias; originais em auditoria | Preservado |
| B09 | Regiões retomadas | Avancadas > Suportes e resistencias; originais em auditoria | Preservado |
| B10 | Estudo H1 sem score | Avancadas > Suportes e resistencias; originais em auditoria | Preservado |
| B11 | Referências H1 para a sessão | Auditoria > Dados e Replay; insuficiencia resumida nos alertas da principal | Preservado |
| B12 | Contexto M5 dos dias anteriores | Auditoria > Dados e Replay; insuficiencia resumida nos alertas da principal | Preservado |
| B13 | Níveis exibidos H1 / linhas M5 | Auditoria > Suportes e resistencias > Resumo dos niveis entregues | Preservado |
| C01 | Fibonacci | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C02 | Modo Fibonacci | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C03 | Amplitude Fibonacci | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C04 | Comparar manual e automático | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C05 | Aproximação 50% (pontos) | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C06 | Selecionar máxima no gráfico | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C07 | Selecionar mínima no gráfico | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C08 | Cancelar seleção | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C09 | Máxima manual confirmada | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C10 | Mínima manual confirmada | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C11 | Aplicar Fibonacci manual | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C12 | Instrução do círculo azul | Editor manual nas avancadas; aviso de selecao armada tambem junto ao grafico | Preservado |
| C13 | Erro de âncoras manuais | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C14 | FIBO 50% / aguardando âncoras | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C15 | Explicação de renovação automática | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C16 | Memória completa indisponível | Ajuda do editor manual; aviso principal se Fibonacci ativa e memoria limitada | Preservado |
| C17 | Legenda de linhas, A/B e autoscale | Avancadas > Fibonacci antiga / Comparacao manual | Preservado |
| C18 | Desenho Fibonacci 0/50/100%, faixa e A/B | Grafico, quando habilitada a camada Fibonacci | Preservado |
| D01 | Mostrar ondas Dow micro (1+1 experimental) | Avancadas > Estrutura de Dow; dados completos nas auditorias | Preservado |
| D02 | Mostrar todos os fractais micro confirmados | Avancadas > Estrutura de Dow; dados completos nas auditorias | Preservado |
| D03 | Mostrar etiquetas HH/HL/LH/LL micro | Avancadas > Estrutura de Dow; dados completos nas auditorias | Preservado |
| D04 | Fractais / Utilizados / Segmentos / Versões | Avancadas > Estrutura de Dow; dados completos nas auditorias | Preservado |
| D05 | Abertura micro: estado | Avancadas > Estrutura de Dow; dados completos nas auditorias | Preservado |
| D06 | Ondas micro indisponíveis | Principal > Alerta condicional da camada solicitada | Preservado |
| D07 | Mostrar Dow principal | Principal > Camadas, imediatamente acima do grafico | Preservado |
| D08 | Mostrar etiquetas HH/HL/LH/LL | Avancadas > Estrutura de Dow; dados completos nas auditorias | Preservado |
| D09 | Dow experimental · M5 · ALTA/BAIXA/... | Principal > Resumo da estrutura, separado do estado do Replay | Preservado |
| D10 | R↑ Resistência rompida / R↓ Suporte rompido | Principal > Ultimo rompimento conhecido; motivo completo em Auditoria > Dow principal | Preservado |
| D11 | Legenda de movimentos/pontos/classes | Avancadas > Estrutura de Dow; dados completos nas auditorias | Preservado |
| D12 | Estrutura Dow M5 indisponível | Principal > Alerta condicional da camada solicitada | Preservado |
| D13 | Ficha de extremo selecionado | Principal > Ficha de pivo por clique ou selecao na auditoria | Preservado |
| D14 | Desenho principal e anotações R↑/R↓ | Grafico > Overlays existentes, independentes | Preservado |
| D15 | Desenho micro e referência âmbar | Grafico > Overlays existentes, independentes | Preservado |
| E01 | Mostrar 50% Dow principal | Principal > Camadas, imediatamente acima do grafico | Preservado |
| E02 | DOW 50% — preço / estado / conclusão | Principal > Resumo DOW 50% e reacao experimental | Preservado |
| E03 | Fonte exclusiva / segmento terminal | Avancadas > Ajuda e informacoes tecnicas | Preservado |
| E04 | Hipóteses e tolerância própria | Avancadas > Ajuda e informacoes tecnicas | Preservado |
| E05 | Lado / Rompimento / Retorno / referências | Principal > Resumo DOW 50% e reacao experimental | Preservado |
| E06 | Auditar onda 50% Dow | Auditoria > DOW 50% > Auditar onda 50% Dow | Preservado |
| E07 | Ficha da onda e linha vigente desde | Principal > Ficha DOW 50% por clique ou escolha historica | Preservado |
| E08 | 50% Dow indisponível | Principal > Alerta condicional da camada solicitada | Preservado |
| E09 | Linha branca DOW 50% | Grafico > Overlays existentes, independentes | Preservado |
| E10 | Decisões de substituição/encerramento | Auditoria > DOW 50% > Historico, eventos e observacoes | Preservado |
| F01 | Auditoria Fibonacci | Auditoria > Fibonacci antiga > Versoes / Observacoes geometricas | Preservado |
| F02 | Observações geométricas dos 50% | Auditoria > Fibonacci antiga > Versoes / Observacoes geometricas | Preservado |
| F03 | Regioes M5 | Auditoria > Suportes e resistencias > Paineis originais recolhidos | Preservado |
| F04 | Referencias H1 preparadas | Auditoria > Suportes e resistencias > Paineis originais recolhidos | Preservado |
| F05 | Candidatos H1 1+1 | Auditoria > Suportes e resistencias > Paineis originais recolhidos | Preservado |
| F06 | Avaliações 1+1 | Auditoria > Suportes e resistencias > Paineis originais recolhidos | Preservado |
| F07 | Regiões e episódios de teste H1 | Auditoria > Suportes e resistencias > Paineis originais recolhidos | Preservado |
| F08 | Auditoria micro | Auditoria > Dow micro > JSON completo, independente da visibilidade | Preservado |
| F09 | Auditoria Dow | Auditoria > Dow principal > Tabela, botoes de extremos e eventos | Preservado |
| F10 | Botão Topo/Fundo na auditoria Dow | Auditoria > Dow principal > Tabela, botoes de extremos e eventos | Preservado |
| F11 | Auditoria 50% Dow | Auditoria > DOW 50% > Historico, eventos e observacoes | Preservado |
| F12 | Status inferior | Principal > Resumo da sessao; status tecnico original em Dados e Replay | Preservado |
| F13 | Último candle | Principal > Resumo da sessao; status tecnico original em Dados e Replay | Preservado |
| F14 | Fechado / Em formação | Principal > Resumo da sessao; status tecnico original em Dados e Replay | Preservado |
| F15 | Lacunas entre candles | Auditoria > Dados e Replay > Diagnosticos de intervalo, horario e resolucao | Preservado |
| F16 | Stream de ticks indisponível | Principal > Alertas condicionais | Preservado |
| F17 | Fim dos candles disponíveis | Principal > Alertas condicionais | Preservado |
| F18 | Legenda do horário / valores preservados | Auditoria > Dados e Replay > Diagnosticos de intervalo, horario e resolucao | Preservado |
| F19 | Legenda do ritmo / nenhum sinal operacional | Auditoria > Dados e Replay > Diagnosticos de intervalo, horario e resolucao | Preservado |
| F20 | Backend sem níveis estruturais | Principal > Alertas condicionais | Preservado |
| G01 | Título Padrão de 3 Candles | Principal > Cabecalho compacto, com identidade de ativo/periodo/modo | Preservado |
| G02 | Link lateral Padrão de 3 Candles | Layout compartilhado: menu e cabecalho do aplicativo, sem alteracao | Preservado |
| G03 | Candles do gráfico | Principal > Grafico nativo e interacoes contextuais preservadas | Preservado |
| G04 | Eixos e cursor do gráfico | Principal > Grafico nativo e interacoes contextuais preservadas | Preservado |
| G05 | Rolagem horizontal | Principal > Grafico nativo e interacoes contextuais preservadas | Preservado |
| G06 | Zoom horizontal | Principal > Grafico nativo e interacoes contextuais preservadas | Preservado |
| G07 | Escala vertical/autoscale | Principal > Grafico nativo e interacoes contextuais preservadas | Preservado |
| G08 | Ajustar gráfico | Principal > Barra de reproducao > Ajustar grafico | Preservado |
| G09 | Clique contextual no gráfico | Principal > Grafico nativo e interacoes contextuais preservadas | Preservado |
| G10 | Panic Scanner (cabeçalho) | Layout compartilhado: menu e cabecalho do aplicativo, sem alteracao | Preservado |
