# Auditoria da interface /three-candles

Branch inspecionada: `feat/three-candles-foundation`. Escopo: investigação estática do frontend e de suas integrações Java. Esta entrega cria somente este documento; não reorganiza a tela, não altera padrões, cálculos, detectores, Replay ou estratégias.

Cobertura: **106 itens**, cada um com uma linha de decisão e ficha individual. A organização proposta aparece depois das fichas; ela não descreve uma interface nova já implementada.

A classificação é uma recomendação para decisão individual. **Ocultar um controle não equivale a desligar seu valor atual, remover seu cálculo ou descontinuar a funcionalidade.** Em especial, nenhum checkbox de desenho interrompe o cálculo de Dow no servidor. A finalidade descrita abaixo é a finalidade funcional demonstrada pelo código; quando não há registro histórico específico, não se atribui uma motivação histórica além dessa finalidade.

## Leitura rápida dos achados

1. Há três conjuntos independentes: Fibonacci antiga no navegador; Dow principal/micro no servidor; monitor DOW 50% no servidor. Não são três maneiras de ligar o mesmo cálculo.
2. **Detecção H1** muda a preparação do backend e recria a sessão. **Agrupamento M5** recalcula grupos de apresentação no navegador. **Aproximação 50%** recalcula observações da Fibonacci antiga no navegador. Os demais checkboxes de camadas/etiquetas são filtros de apresentação, com as dependências descritas individualmente.
3. “Micro M5” da Fibonacci antiga usa os pivôs M5 principais 2+2. Não é o “Dow micro 1+1”. “Macro H1” escolhe a origem das âncoras, não transforma o gráfico em H1.
4. “Mostrar 50% Dow principal” não depende de ligar “Mostrar Dow principal” e não depende de “Fibonacci”. Desligá-lo oculta a linha e seus detalhes condicionais, mas mantém o resumo de preço/estado e o cálculo no backend.
5. O título “Padrão de 3 Candles” identifica uma infraestrutura de estudo. Não há nesta página uma regra operacional completa do padrão, ordens ou Alvo → Nega → Testa. `ThreeCandlePatternDetector` é uma porta para futuras regras; a implementação atual de estrutura não é um detector de entrada do padrão.
6. Várias legendas continuam visíveis com as camadas desligadas; “Candidatos H1 1+1” é renderizado aberto. Isso explica parte da poluição sem que haja controles duplicados de fato.

## Como usar o inventário

Os IDs ligam cada linha da tabela à ficha individual posterior. Cada ficha informa: **nome; o que faz; finalidade; efeito de usar/ativar/desativar; impacto em cálculos; dependências; necessidade atual; perda ao ocultar**. Mensagens e desenhos não têm um botão de ligar próprio: sua condição de exibição é indicada em “Uso”.

Classificações: **ESSENCIAL AGORA** = base para Replay/Dow/50%; **ÚTIL EM TESTES** = comparação/checagem opcional; **EXPERIMENTAL ANTIGO** = hipótese automática anterior, mantida para comparação; **AUDITORIA TÉCNICA** = explicação e rastreabilidade; **POSSIVELMENTE REDUNDANTE** = conteúdo sobreposto que pode ser consolidado, sujeito à escolha do usuário.

“Não — visual” não significa que o frontend inteiro deixa de trabalhar. Significa que aquele elemento não muda a regra nem os dados calculados. “Local” identifica cálculos no navegador, sem mudança do backend. As recomendações de área são propostas, não mudanças efetuadas.

## Tabela de decisão

### A. Configuração e disponibilidade do Replay

| Grupo | Item | O que faz | Afeta cálculos? | Utilidade atual | Recomendação |
|---|---|---|---|---|---|
| A | A01 — Ativo | Escolhe a série exata. | Sim: dados/sessão. | ESSENCIAL AGORA | Área principal. |
| A | A02 — Período | Escolhe M5 ou M15. | Sim: agregação e recursos disponíveis. | ESSENCIAL AGORA | Principal; explicitar que Dow/50% é M5. |
| A | A03 — Referência do horário | Define interpretação do horário e do início. | Sim: corte do Replay; exibição local. | ESSENCIAL AGORA | Principal ou avançada com base sempre identificável. |
| A | A04 — Tempo Real | Seleciona feed atual. | Sim: origem de dados. | ESSENCIAL AGORA | Principal. |
| A | A05 — Replay | Seleciona sessão histórica. | Sim: origem de dados. | ESSENCIAL AGORA | Principal. |
| A | A06 — Modo de Replay | Fechado ou progressivo M1. | Sim: sequência de observações. | ESSENCIAL AGORA | Principal no Replay M5. |
| A | A07 — Detecção H1 | Prepara 2+2 ou comparação 1+1. | Sim: backend; recria sessão. | ÚTIL EM TESTES | Avançada; não confundir com visibilidade. |
| A | A08 — Data | Define o pregão. | Sim: histórico/preparação. | ESSENCIAL AGORA | Principal. |
| A | A09 — Hora inicial | Define o instante de partida. | Sim: prefixo conhecido. | ESSENCIAL AGORA | Principal. |
| A | A10 — Carregar | Valida/obtém histórico e cria sessão. | Sim: inicializa backend. | ESSENCIAL AGORA | Principal. |
| A | A11 — Reiniciar | Reconstrói o início configurado. | Sim: estado causal. | ESSENCIAL AGORA | Principal. |
| A | A12 — Play | Avança por tempo de reprodução. | Sim: disponibiliza novas observações. | ESSENCIAL AGORA | Principal. |
| A | A13 — Pause | Para o avanço automático. | Sim: estabiliza o instante observado. | ESSENCIAL AGORA | Principal. |
| A | A14 — Próximo candle / Próximo M1 | Consome um passo. | Sim: um novo prefixo. | ESSENCIAL AGORA | Principal. |
| A | A15 — Velocidade | Define 1x/2x/5x/10x. | Sim: ritmo, não critérios. | ESSENCIAL AGORA | Principal. |
| A | A16 — Sessões locais / histórico MT5 | Explica catálogo e origem da série. | Não — informativo. | ÚTIL EM TESTES | Resumo na principal, detalhes sob demanda. |
| A | A17 — Obtenção/erro de configuração | Mostra inicialização e nova tentativa. | Não — informa consulta. | ESSENCIAL AGORA | Condicional na principal. |
| A | A18 — Progresso do histórico | Explica cache, consulta e preparação. | Não — informa operações reais. | ESSENCIAL AGORA | Somente durante carregamento. |
| A | A19 — Fonte M1 com minutos ausentes | Identifica lacunas nativas. | Não — informa cobertura já validada. | ESSENCIAL AGORA | Alerta condicional; detalhes em auditoria. |
| A | A20 — Erro geral | Expõe falha de API/validação/carregamento. | Não — informativo. | ESSENCIAL AGORA | Condicional na principal. |

### B. Suportes e resistências

| Grupo | Item | O que faz | Afeta cálculos? | Utilidade atual | Recomendação |
|---|---|---|---|---|---|
| B | B01 — M5 - amarelo | Mostra linhas de pivôs/regiões M5. | Não — filtro visual. | ÚTIL EM TESTES | Camada opcional. |
| B | B02 — H1 - fucsia | Mostra referências H1. | Não — filtro visual. | ÚTIL EM TESTES | Camada opcional. |
| B | B03 — Agrupamento M5 (pontos) | Junta preços próximos na apresentação. | Local: grupos/representantes; backend intacto. | ÚTIL EM TESTES | Avançada. |
| B | B04 — Separar movimentos apos rompimento | Evita juntar episódios separados por rompimento. | Local: associação dos grupos. | ÚTIL EM TESTES | Avançada, junto ao agrupamento. |
| B | B05 — Referência H1 2+2 | Filtra representantes 2+2 já preparados. | Não — visual. | ÚTIL EM TESTES | Avançada, apenas na comparação H1. |
| B | B06 — Adicionais H1 1+1 | Filtra representantes adicionais. | Não — visual. | ÚTIL EM TESTES | Avançada, apenas na comparação H1. |
| B | B07 — Texto H1 fixo / memória M5 | Explica de onde vêm as linhas. | Não. | AUDITORIA TÉCNICA | Ajuda contextual. |
| B | B08 — Pivôs M5 → linhas visíveis/ocultas | Conta originais e grupos. | Não — mostra resultado local. | POSSIVELMENTE REDUNDANTE | Consolidar com B13/F03 após decisão. |
| B | B09 — Regiões retomadas | Conta grupos com mais de um episódio. | Não — mostra resultado local. | AUDITORIA TÉCNICA | Auditoria M5. |
| B | B10 — Estudo H1 sem score | Resume 2+2, 1+1 e adicionais. | Não — dados do backend. | AUDITORIA TÉCNICA | Avançada/auditoria. |
| B | B11 — Referências H1 para a sessão | Mostra fontes, cobertura, pivôs e linhas. | Não — dados da preparação. | ÚTIL EM TESTES | Resumo de cobertura; detalhes em auditoria. |
| B | B12 — Contexto M5 dos dias anteriores | Explica aquecimento visível e ausência de dias. | Não. | ÚTIL EM TESTES | Condicional; auditoria para lista completa. |
| B | B13 — Níveis exibidos H1 / linhas M5 | Resume a entrega gráfica ou incompatibilidade. | Não. | POSSIVELMENTE REDUNDANTE | Consolidar contagens sem perder alertas. |

### C. Fibonacci antiga e manual

| Grupo | Item | O que faz | Afeta cálculos? | Utilidade atual | Recomendação |
|---|---|---|---|---|---|
| C | C01 — Fibonacci | Liga desenho da ferramenta antiga/manual. | Não — análise continua local. | ÚTIL EM TESTES | Camada opcional avançada. |
| C | C02 — Modo Fibonacci | Escolhe versão automática ou manual. | Local: versão apresentada. | EXPERIMENTAL ANTIGO | Automático em avançada; preservar manual. |
| C | C03 — Amplitude Fibonacci | Escolhe âncoras M5 ou H1. | Local: faixa de análise/seleção. | ÚTIL EM TESTES | Avançada da Fibonacci. |
| C | C04 — Comparar manual e automático | Sobrepõe a segunda modalidade. | Não — composição visual. | ÚTIL EM TESTES | Avançada. |
| C | C05 — Aproximação 50% (pontos) | Muda tolerância de observações antigas. | Sim, local: observações; não Dow. | EXPERIMENTAL ANTIGO | Avançada com identificação explícita. |
| C | C06 — Selecionar máxima no gráfico | Arma seleção de topo confirmado. | Local: escolha, sem aplicar ainda. | ÚTIL EM TESTES | Ferramenta manual sob demanda. |
| C | C07 — Selecionar mínima no gráfico | Arma seleção de fundo confirmado. | Local: escolha, sem aplicar ainda. | ÚTIL EM TESTES | Ferramenta manual sob demanda. |
| C | C08 — Cancelar seleção | Sai do modo de captura por clique. | Não — interação local. | ÚTIL EM TESTES | Junto à seleção ativa. |
| C | C09 — Máxima manual confirmada | Escolhe topo pela lista. | Local: escolha pendente. | ÚTIL EM TESTES | Editor manual. |
| C | C10 — Mínima manual confirmada | Escolhe fundo pela lista. | Local: escolha pendente. | ÚTIL EM TESTES | Editor manual. |
| C | C11 — Aplicar Fibonacci manual | Valida âncoras e cria versão manual. | Sim, local: nível/observações manuais. | ÚTIL EM TESTES | Preservar, editor sob demanda. |
| C | C12 — Instrução do círculo azul | Explica captura e lista alternativa. | Não. | ÚTIL EM TESTES | Só durante seleção. |
| C | C13 — Erro de âncoras manuais | Explica por que a versão não foi aplicada. | Não — informa validação. | ÚTIL EM TESTES | Junto ao editor. |
| C | C14 — FIBO 50% / aguardando âncoras | Resume versão principal antiga/manual. | Não — mostra cálculo local. | EXPERIMENTAL ANTIGO | Dentro da área Fibonacci. |
| C | C15 — Explicação de renovação automática | Descreve ampliação/reversão antiga. | Não. | EXPERIMENTAL ANTIGO | Ajuda da modalidade automática. |
| C | C16 — Memória completa indisponível | Avisa uso de fonte limitada de âncoras. | Não — informa fallback local. | AUDITORIA TÉCNICA | Alerta contextual da Fibonacci. |
| C | C17 — Legenda de linhas, A/B e autoscale | Explica desenho antigo/manual. | Não. | POSSIVELMENTE REDUNDANTE | Ajuda contextual, longe do DOW 50%. |
| C | C18 — Desenho Fibonacci 0/50/100%, faixa e A/B | Mostra amplitude e comparação. | Não — visual. | ÚTIL EM TESTES | Camada opcional, independente do novo 50%. |

### D. Teoria de Dow

| Grupo | Item | O que faz | Afeta cálculos? | Utilidade atual | Recomendação |
|---|---|---|---|---|---|
| D | D01 — Mostrar ondas Dow micro (1+1 experimental) | Mostra cadeia micro e auxiliares. | Não — backend continua. | ÚTIL EM TESTES | Avançada; não necessária ao 50% principal. |
| D | D02 — Mostrar todos os fractais micro confirmados | Amplia marcadores, não a cadeia. | Não — visual. | AUDITORIA TÉCNICA | Avançada micro. |
| D | D03 — Mostrar etiquetas HH/HL/LH/LL micro | Adiciona rótulos micro. | Não — visual. | ÚTIL EM TESTES | Avançada micro. |
| D | D04 — Fractais / Utilizados / Segmentos / Versões | Resume seleção micro. | Não. | AUDITORIA TÉCNICA | Auditoria micro. |
| D | D05 — Abertura micro: estado | Distingue referência de abertura de pivô. | Não. | AUDITORIA TÉCNICA | Contextual micro. |
| D | D06 — Ondas micro indisponíveis | Informa snapshot ausente. | Não. | AUDITORIA TÉCNICA | Condicional à camada pedida. |
| D | D07 — Mostrar Dow principal | Mostra movimentos e pivôs principais. | Não — backend continua. | ESSENCIAL AGORA | Principal. |
| D | D08 — Mostrar etiquetas HH/HL/LH/LL | Mostra classes principais. | Não — visual. | ÚTIL EM TESTES | Controle da camada principal. |
| D | D09 — Dow experimental · M5 · ALTA/BAIXA/... | Resume estado estrutural. | Não — estado Java. | ESSENCIAL AGORA | Resumo na principal. |
| D | D10 — R↑ Resistência rompida / R↓ Suporte rompido | Mostra o último rompimento conhecido. | Não — evento Java. | ESSENCIAL AGORA | Resumo contextual. |
| D | D11 — Legenda de movimentos/pontos/classes | Explica leitura e limitações. | Não. | AUDITORIA TÉCNICA | Ajuda contextual. |
| D | D12 — Estrutura Dow M5 indisponível | Informa falta de dados, não falta de tendência. | Não. | ESSENCIAL AGORA | Alerta condicional. |
| D | D13 — Ficha de extremo selecionado | Explica pivô, comparação e participação. | Não — inspeção. | AUDITORIA TÉCNICA | Ao clicar no pivô. |
| D | D14 — Desenho principal e anotações R↑/R↓ | Vincula pivôs e eventos aos candles. | Não — visual. | ESSENCIAL AGORA | Gráfico principal. |
| D | D15 — Desenho micro e referência âmbar | Compara cadeia menor e abertura. | Não — visual. | ÚTIL EM TESTES | Camada avançada independente. |

### E. Linha DOW 50%

| Grupo | Item | O que faz | Afeta cálculos? | Utilidade atual | Recomendação |
|---|---|---|---|---|---|
| E | E01 — Mostrar 50% Dow principal | Liga linha e detalhes condicionais. | Não — monitor Java continua. | ESSENCIAL AGORA | Principal. |
| E | E02 — DOW 50% — preço / estado / conclusão | Resume referência vigente. | Não. | ESSENCIAL AGORA | Principal, inclusive quando fora do viewport. |
| E | E03 — Fonte exclusiva / segmento terminal | Explica vínculo com onda concluída. | Não. | ÚTIL EM TESTES | Ajuda curta da camada. |
| E | E04 — Hipóteses e tolerância própria | Explica políticas experimentais do monitor. | Não — não é seletor. | ÚTIL EM TESTES | Avançada/ajuda; preservar aviso de hipótese. |
| E | E05 — Lado / Rompimento / Retorno / referências | Resume reação e possíveis extremos. | Não — leitura Java. | ESSENCIAL AGORA | Contextual à referência vigente. |
| E | E06 — Auditar onda 50% Dow | Inspeciona atual ou histórica. | Não — não reativa alvo. | AUDITORIA TÉCNICA | Área de auditoria. |
| E | E07 — Ficha da onda e linha vigente desde | Prova origem e disponibilidade temporal. | Não — inspeção. | ESSENCIAL AGORA | Ao clicar na linha. |
| E | E08 — 50% Dow indisponível | Informa falta do contrato de dados. | Não. | ESSENCIAL AGORA | Condicional. |
| E | E09 — Linha branca DOW 50% | Projeta um único nível a partir da onda. | Não — desenho do cálculo Java. | ESSENCIAL AGORA | Gráfico principal. |
| E | E10 — Decisões de substituição/encerramento | Explicam mudança de referência nos eventos. | Não ao ler; regra é Java. | AUDITORIA TÉCNICA | Histórico de decisões; não há seletor de política. |

### F. Painéis expansíveis, status e diagnósticos

| Grupo | Item | O que faz | Afeta cálculos? | Utilidade atual | Recomendação |
|---|---|---|---|---|---|
| F | F01 — Auditoria Fibonacci | Lista versões, âncoras e ambiguidades. | Não ao expandir. | AUDITORIA TÉCNICA | Auditoria da Fibonacci. |
| F | F02 — Observações geométricas dos 50% | Lista reações da Fibonacci antiga/manual. | Não ao expandir. | EXPERIMENTAL ANTIGO | Auditoria antiga identificada. |
| F | F03 — Regioes M5 | Relaciona grupos aos pivôs originais. | Não ao expandir. | AUDITORIA TÉCNICA | Auditoria de níveis. |
| F | F04 — Referencias H1 preparadas | Explica origem/seleção 2+2. | Não ao expandir. | AUDITORIA TÉCNICA | Auditoria H1. |
| F | F05 — Candidatos H1 1+1 | Compara candidatos com o 2+2. | Não ao expandir. | ÚTIL EM TESTES | Auditoria H1, preferencialmente recolhida no futuro. |
| F | F06 — Avaliações 1+1 | Explica confirmação/rejeição do detector H1. | Não ao expandir. | AUDITORIA TÉCNICA | Auditoria H1. |
| F | F07 — Regiões e episódios de teste H1 | Mede testes históricos de faixas. | Não ao expandir. | AUDITORIA TÉCNICA | Auditoria H1. |
| F | F08 — Auditoria micro | Mostra JSON completo do micro. | Não ao expandir. | AUDITORIA TÉCNICA | Auditoria micro. |
| F | F09 — Auditoria Dow | Lista pivôs, movimentos e eventos principais. | Não ao expandir. | AUDITORIA TÉCNICA | Auditoria Dow. |
| F | F10 — Botão Topo/Fundo na auditoria Dow | Abre ficha do pivô listado. | Não — seleção local. | AUDITORIA TÉCNICA | Preservar como alternativa ao clique gráfico. |
| F | F11 — Auditoria 50% Dow | Lista ondas, transições, eventos e observações. | Não ao expandir. | AUDITORIA TÉCNICA | Auditoria principal dos 50%. |
| F | F12 — Status inferior | Mostra estado da fonte/reprodução. | Não. | ESSENCIAL AGORA | Principal, resumo curto. |
| F | F13 — Último candle | Mostra horário de abertura do último bucket. | Não. | ESSENCIAL AGORA | Principal, distinguindo de instante do Replay. |
| F | F14 — Fechado / Em formação | Explica se o último candle é parcial. | Não. | ESSENCIAL AGORA | Principal. |
| F | F15 — Lacunas entre candles | Conta diferenças entre buckets adjacentes. | Não — contagem local. | AUDITORIA TÉCNICA | Diagnóstico; inclui intervalos entre sessões. |
| F | F16 — Stream de ticks indisponível | Informa indisponibilidade live e reconexão. | Não — informa comportamento do feed. | ESSENCIAL AGORA | Condicional. |
| F | F17 — Fim dos candles disponíveis | Informa término da cobertura. | Não. | ESSENCIAL AGORA | Condicional. |
| F | F18 — Legenda do horário / valores preservados | Explica convenção temporal. | Não — informativa. | ÚTIL EM TESTES | Contextual ao horário, sem repetir texto longo. |
| F | F19 — Legenda do ritmo / nenhum sinal operacional | Explica resolução e finalidade de estudo. | Não. | ÚTIL EM TESTES | Ajuda contextual e aviso curto. |
| F | F20 — Backend sem níveis estruturais | Expõe resposta de versão incompatível. | Não. | ESSENCIAL AGORA | Condicional; não ocultar falha silenciosamente. |

### G. Gráfico, navegação e interações adicionais

| Grupo | Item | O que faz | Afeta cálculos? | Utilidade atual | Recomendação |
|---|---|---|---|---|---|
| G | G01 — Título Padrão de 3 Candles | Identifica o módulo. | Não. | ESSENCIAL AGORA | Manter orientação, sem prometer sinais existentes. |
| G | G02 — Link lateral Padrão de 3 Candles | Abre a rota. | Não; navegação pode desmontar estado local. | ESSENCIAL AGORA | Navegação compartilhada. |
| G | G03 — Candles do gráfico | Mostra OHLC conhecido da série. | Não — apresentação dos dados. | ESSENCIAL AGORA | Área principal. |
| G | G04 — Eixos e cursor do gráfico | Permitem ler preço/data no viewport. | Não. | ESSENCIAL AGORA | Área principal. |
| G | G05 — Rolagem horizontal | Examina candles já recebidos. | Não — viewport. | ESSENCIAL AGORA | Gráfico. |
| G | G06 — Zoom horizontal | Altera a quantidade de candles visíveis. | Não — viewport. | ESSENCIAL AGORA | Gráfico. |
| G | G07 — Escala vertical/autoscale | Ajusta leitura do preço. | Não — viewport. | ESSENCIAL AGORA | Gráfico. |
| G | G08 — Ajustar gráfico | Foca a parte recente e restaura autoscale. | Não — viewport. | ESSENCIAL AGORA | Próximo ao gráfico. |
| G | G09 — Clique contextual no gráfico | Escolhe âncora, linha 50% ou pivô Dow. | Não até aplicar âncoras; inspeção local. | ESSENCIAL AGORA | Entrada para detalhes sob demanda. |
| G | G10 — Panic Scanner (cabeçalho) | Identifica o aplicativo compartilhado. | Não. | ÚTIL EM TESTES | Fora da reorganização específica do módulo. |

## Fichas individuais

As fichas abaixo complementam a tabela. Para mensagens, “ocultar” significa deixar de apresentá-las, não interromper a operação que as originou. Para campos e botões, “uso” descreve o efeito real; não existe um estado “desligado” quando o elemento não é um checkbox.

### A. Configuração do Replay

#### A01 — Ativo

**Faz:** escolhe contrato/série pelo símbolo, sem converter WIN$N em WINZ26. **Criado para:** estudar a fonte correta. **Uso:** mudar limpa a visão e encerra a sessão anterior; no Replay exige carregar novamente. Em live só o contrato configurado é aceito. A lista combina o contrato live com símbolos do catálogo, não é um campo livre. **Cálculos:** muda os dados enviados ao backend. **Depende:** configuração e histórico do símbolo. **Necessário agora:** sim. **Ao ocultar:** perde-se a escolha/comprovação da série; o símbolo atual continuaria sendo usado.

#### A02 — Período

**Faz:** seleciona M5 ou M15. **Criado para:** comparar agregações do preço. **Uso:** mudar recria a fonte; M15 agrega trincas M5 consecutivas, não oferece Dow principal/micro/50% nem Replay progressivo M1. Os controles de camadas podem continuar na tela apesar disso. **Cálculos:** altera agregação e suporte de estrutura no backend. **Depende:** dados M5; estudo estrutural atual exige M5. **Necessário agora:** M5 sim; alternativa M15 é complementar. **Ao ocultar:** perde-se alternância; não se deve mudar silenciosamente o período existente.

#### A03 — Referência do horário

**Faz:** oferece “Hora do terminal MT5” ou “Timestamp UTC → Brasília”. **Criado para:** evitar interpretar o relógio da fonte com fuso errado. **Uso:** muda rótulos/eixos e reinicia a fonte; no Replay a hora inicial passa a ser mapeada por outra convenção. **Cálculos:** o backend interpreta o início como campo civil UTC no primeiro caso e como America/Sao_Paulo no segundo; não reescreve timestamps dos candles. **Depende:** convenção real da fonte. **Necessário agora:** sim, como definição correta. **Ao ocultar:** perde-se a possibilidade de conferir/corrigir o corte temporal.

#### A04 — Tempo Real

**Faz:** seleciona acompanhamento do feed atual. **Criado para:** observar candles em formação fora de uma sessão histórica. **Uso:** volta ao símbolo live configurado e encerra Replay anterior; consultas de snapshot são repetidas aproximadamente a cada segundo após cada resposta. **Cálculos:** backend usa o feed compartilhado e reconcilia candles; não executa ordens por este botão. **Depende:** terminal, contrato configurado e stream existente. **Necessário agora:** útil para estudo ao vivo; dispensável numa sessão exclusivamente histórica. **Ao ocultar:** perde-se essa troca de origem.

#### A05 — Replay

**Faz:** seleciona estudo histórico. **Criado para:** repetir uma sequência causal. **Uso:** ao entrar, pode escolher símbolo/data da primeira sessão catalogada; mostra controles de carregamento. Sair encerra a sessão e limpa a visão. **Cálculos:** passa a usar `ThreeCandleReplay`, não `M5ReplaySession`. **Depende:** configuração, fonte histórica e Carregar. **Necessário agora:** sim. **Ao ocultar:** perde-se a entrada explícita no estudo histórico; não é apenas um filtro de candles existentes.

#### A06 — Modo de Replay

**Faz:** escolhe “Replay por candle fechado” ou “Replay progressivo M1 (experimental)”. **Criado para:** oferecer montagem rápida ou formação parcial mais próxima do acompanhamento ao vivo. **Uso:** mudar encerra/limpa a sessão; Carregar busca M1 quando necessário. Em M15 o modo efetivo é fechado. **Cálculos:** muda observações no backend; o progressivo agrega apenas M1 conhecidos e confere o M5 final. **Depende:** M5 oficial, M1 válido, backend compatível. **Necessário agora:** sim para escolher o tipo de estudo. **Ao ocultar:** perde-se essa escolha; a modalidade atual permanece.

#### A07 — Detecção H1

**Faz:** escolhe “2+2 (referência)” ou “2+2 + 1+1 experimental”. **Criado para:** comparar candidatos H1 sem substituir o detector 2+2. **Uso:** disponível no bloco Replay M5; mudar encerra a sessão e exige nova carga. O servidor prepara candidatos adicionais e avaliações quando solicitado. **Cálculos:** sim, preparação H1 do backend. Não altera a regra do Dow M5 nem fornece micro ao DOW 50%. **Depende:** histórico anterior à abertura e M5. **Necessário agora:** não para Dow/50% principal. **Ao ocultar:** perde-se comparação de detectores e acesso visual à configuração que gerou os dados.

#### A08 — Data

**Faz:** escolhe o pregão histórico. **Criado para:** repetir casos auditáveis. **Uso:** mudar limpa a sessão imediatamente; não carrega automaticamente. O loader exige data anterior ao dia atual em UTC. **Cálculos:** define histórico e três pregões usados na preparação H1. **Depende:** símbolo, calendário e disponibilidade MT5/local. **Necessário agora:** sim. **Ao ocultar:** perde-se seleção de sessão e conferência de identidade temporal.

#### A09 — Hora inicial (MT5) / Hora inicial (Brasília)

**Faz:** define o início do Replay segundo A03. **Criado para:** iniciar em um corte específico. **Uso:** mudar limpa a sessão; Carregar reconstrói tudo já conhecido até esse instante. Não pode começar antes da abertura de 09:00 na convenção selecionada. **Cálculos:** muda prefixo conhecido; não apaga aquecimento anterior. **Depende:** data, referência de horário e fonte. **Necessário agora:** sim. **Ao ocultar:** perde-se o controle do corte e a reprodução direta de momentos auditados.

#### A10 — Carregar / Carregando replay…

**Faz:** verifica cache, prepara histórico e cria uma nova sessão. **Criado para:** tornar a escolha de dados executável. **Uso:** substitui sessão anterior; fica desabilitado durante carga/comando ou sem configuração/data/hora. Pode consultar MT5 e gravar caches M5/M1. **Cálculos:** inicializa preparação e reconstrução no servidor. **Depende:** A01–A09 e conectividade quando falta histórico. **Necessário agora:** sim. **Ao ocultar:** perde-se a ação de efetivar a configuração; não equivale a uma consulta somente visual.

#### A11 — ◀ Reiniciar

**Faz:** volta ao início configurado. **Criado para:** repetir o mesmo estudo. **Uso:** envia RESET, reconstrói pipeline/preparação e limpa o estado gráfico de entrega; mantém velocidade e opções locais. Versões manuais da mesma montagem permanecem na memória local, mas só reaparecem quando seu instante é alcançado. **Cálculos:** reconstrói o estado causal Java. **Depende:** sessão carregada. **Necessário agora:** sim. **Ao ocultar:** perde-se repetição rápida; carregar outra sessão não preserva necessariamente a memória manual local.

#### A12 — ▶ Play

**Faz:** inicia avanço automático. **Criado para:** assistir à evolução. **Uso:** envia PLAY; desabilitado se já reproduzindo, terminado ou sem sessão. O avanço é consumido no servidor e entregue por consultas aproximadamente a cada 250 ms após respostas. **Cálculos:** disponibiliza novos dados na ordem; não altera critérios. **Depende:** sessão, velocidade e modo. **Necessário agora:** sim. **Ao ocultar:** perde-se reprodução contínua, restando passos se disponíveis.

#### A13 — ⏸ Pause

**Faz:** interrompe avanço automático. **Criado para:** inspecionar um instante. **Uso:** PAUSE primeiro contabiliza o tempo já decorrido e então pausa; a resposta pode avançar até o momento do comando. Não cancela consultas de atualização da interface. **Cálculos:** estabiliza o prefixo após esse avanço; regras intactas. **Depende:** sessão. **Necessário agora:** sim. **Ao ocultar:** perde-se parada controlada para auditoria.

#### A14 — ▶| Próximo candle / Próximo M1

**Faz:** consome uma observação. **Criado para:** conferir mudanças passo a passo. **Uso:** NEXT deixa a sessão pausada e avança um candle fechado ou um M1 fechado; nesse último caso atualiza o mesmo M5 parcial. Uma lacuna M1 interna não cria um passo fictício. **Cálculos:** processa o próximo prefixo real. **Depende:** modo e dados restantes. **Necessário agora:** sim. **Ao ocultar:** perde-se o avanço determinístico manual; o botão não significa sempre “próximo M5”.

#### A15 — Velocidade

**Faz:** escolhe 1x, 2x, 5x ou 10x. **Criado para:** ajustar o ritmo do estudo. **Uso:** SPEED contabiliza avanço já decorrido e altera a taxa; não inicia Play por si só. A unidade é uma observação por segundo em 1x, não tempo de mercado em escala real. **Cálculos:** ritmo no servidor, sem alterar sequência ou regras. **Depende:** sessão e modo. **Necessário agora:** sim. **Ao ocultar:** perde-se ajuste do ritmo, não a causalidade.

#### A16 — Sessões locais deste ativo / Datas fora desta lista… / WIN$N

**Faz:** mostra até 12 datas catalogadas do símbolo e explica consulta MT5 e série contínua. **Criado para:** orientar a escolha da fonte. **Uso:** aparece no Replay com configuração; não é botão. Catálogo vazio não prova inexistência de cache M5 separado ou de histórico no terminal. **Cálculos:** nenhum; consultas só ocorrem ao carregar. **Depende:** `/config`, símbolo e catálogo. **Necessário agora:** resumo é útil, texto longo não precisa ser permanente. **Ao ocultar:** perde-se orientação sobre disponibilidade e diferença entre contrato e contínuo.

#### A17 — Obtendo configuração… / Não foi possível obter a configuração…

**Faz:** distingue inicialização e falha, com nova tentativa automática em três segundos. **Criado para:** evitar tela aparentemente travada. **Uso:** desaparece quando a configuração chega; não exige apertar um checkbox. **Cálculos:** mensagem não muda cálculos; o fluxo de consulta continua. **Depende:** `/config`. **Necessário agora:** sim quando ocorre. **Ao ocultar:** perde-se explicação de por que carregar/selecionar está indisponível.

#### A18 — Verificando histórico local… / Consultando MetaTrader 5… / Preparando Replay…

**Faz:** informa as etapas da carga e uso de cache. **Criado para:** explicar operações demoradas. **Uso:** aparece somente enquanto `replayLoading`; M1 pode ser obtido na criação progressiva, mesmo após mensagem de cache M5 validado. **Cálculos:** mensagem não; as operações informadas consultam/validam dados reais. **Depende:** A10 e API. **Necessário agora:** sim durante carga. **Ao ocultar:** perde-se progresso e distinção entre espera e falha.

#### A19 — Fonte M1 com … minuto(s) interno(s) ausente(s)

**Faz:** lista lacunas nativas do progressivo e informa que não houve preenchimento artificial. **Criado para:** não confundir uma sequência incompleta com cinco atualizações garantidas. **Uso:** condicionado a `missingM1Times`; essa lista é metadado de cobertura, não evento de mercado. **Cálculos:** não; a validação M1/M5 já foi feita no backend. **Depende:** modo progressivo e fonte com lacuna. **Necessário agora:** sim nesses casos. **Ao ocultar:** perde-se transparência sobre passos ausentes, como WINZ26 às 15:01.

#### A20 — Erro geral da página

**Faz:** apresenta erro de consulta, comando, carga ou contrato de dados. **Criado para:** expor falhas sem fabricar resultados. **Uso:** renderizado quando há `error`; mensagens podem tratar sessão expirada, histórico insuficiente, fonte M1 divergente, modo não suportado ou falha MT5. **Cálculos:** não, informa operação que falhou. **Depende:** API, validação Zod e tratamento local. **Necessário agora:** sim. **Ao ocultar:** a ausência de dados pode parecer ausência de oportunidade/onda, em vez de erro.

### B. Suportes e resistências

#### B01 — M5 - amarelo

**Faz:** mostra/oculta linhas horizontais amarelas dos grupos M5. **Criado para:** localizar regiões de pivôs com menos repetição. **Uso:** só chama a visibilidade do controlador local; não encerra Replay. Desligar não oculta a cadeia Dow nem retira pivôs da Fibonacci. **Cálculos:** nenhum no backend; muda entrega de níveis ao gráfico. **Depende:** níveis M5 recebidos e B03/B04. **Necessário agora:** opcional para contexto. **Ao ocultar:** perde-se o comando de limpar/recuperar essas linhas, não seus dados de origem.

#### B02 — H1 - fucsia

**Faz:** mostra/oculta referências horizontais H1. **Criado para:** dar contexto dos pregões anteriores. **Uso:** filtro local; ao desligar, B05/B06 não conseguem exibir H1 por conta própria. Auditorias H1 e âncoras H1 continuam disponíveis. **Cálculos:** nenhum no backend. **Depende:** preparação H1 ou seleção fallback recebida. **Necessário agora:** opcional, não alimenta Dow principal. **Ao ocultar:** perde-se alternância de contexto H1; a preparação continua sendo calculada.

#### B03 — Agrupamento M5 (pontos)

**Faz:** oferece sem agrupamento, 15, 30 ou 50 pontos. **Criado para:** reduzir linhas próximas sem apagar extremos. **Uso:** recalcula grupos por símbolo, sessão e tipo; a largura total deve caber na tolerância. O primeiro pivô confirmado mantém o preço representante. Zero preserva linhas individuais até para preços iguais. **Cálculos:** sim no navegador, só apresentação; não muda pivôs/Dow/Fibonacci. **Depende:** M5 e símbolo WIN. **Necessário agora:** não ao 50% principal. **Ao ocultar:** perde-se escolha entre detalhe e simplificação das regiões.

#### B04 — Separar movimentos apos rompimento

**Faz:** evita juntar um novo extremo a grupo já rompido antes da origem dele. **Criado para:** diferenciar retomadas de região. **Uso:** altera associação local; desligar permite agrupar episódios distintos pela mesma faixa. Desabilitado com agrupamento zero ou fora de M5. **Cálculos:** local, usa `brokenAtMsc` já fornecido; não altera regra de rompimento nem seleção de ondas. **Depende:** B03 e estados dos pivôs. **Necessário agora:** não. **Ao ocultar:** perde-se essa comparação de episódios.

#### B05 — Referência H1 2+2

**Faz:** filtra linhas representantes da referência 2+2. **Criado para:** isolar o baseline na comparação H1. **Uso:** só aparece quando existe comparação experimental; atua junto a B02. Desligar não muda detector nem candidatos/tabelas. **Cálculos:** somente filtro visual. **Depende:** A07 experimental e H1 visível. Linhas de preços compartilhados seguem o ID representante recebido, não desenham cópias independentes para cada origem. **Necessário agora:** não ao estudo principal. **Ao ocultar:** perde-se isolamento visual do baseline.

#### B06 — Adicionais H1 1+1

**Faz:** filtra representantes adicionais confirmados por 1+1. **Criado para:** comparar o ganho de candidatos. **Uso:** não ativa o detector; ele já foi executado por A07. Desligar mantém avaliações, candidatos e preparação. **Cálculos:** apenas filtro visual por IDs adicionais. **Depende:** comparação existente e B02; sobreposição exata pode ter representante 2+2. **Necessário agora:** não. **Ao ocultar:** perde-se a sobreposição seletiva, mas não os resultados do experimento.

#### B07 — H1: referencias fixas dos 3 pregoes anteriores… / M5: regioes…

**Faz:** explica que H1 é preparado na abertura e M5 conserva pivôs do dia. **Criado para:** distinguir referências históricas de novas confirmações. **Uso:** texto sempre apresentado junto aos controles, mesmo com camadas desligadas. **Cálculos:** nenhum. **Depende:** significado das séries; a afirmação descreve o fluxo preparado atual, não garante preparação suficiente em qualquer resposta. **Necessário agora:** conhecimento útil, presença permanente dispensável. **Ao ocultar:** perde-se a explicação imediata da origem das linhas.

#### B08 — … pivos M5 → … linhas (visiveis/ocultas)

**Faz:** compara quantidade de pivôs recebidos com grupos locais e estado da camada. **Criado para:** tornar o agrupamento verificável. **Uso:** atualiza com respostas e B03/B04; fica visível mesmo quando B01 está desligado. “Linhas” não significa que todas estão dentro do viewport. **Cálculos:** apenas leitura de contagens locais. **Depende:** pivôs e grupos. **Necessário agora:** não permanentemente. **Ao ocultar:** perde-se feedback imediato da compactação, também consultável em F03/B13.

#### B09 — … regioes retomadas em movimentos apos rompimento

**Faz:** conta grupos com mais de um episódio e orienta separar movimentos. **Criado para:** explicar por que uma faixa pode representar origens distantes. **Uso:** contador permanece mesmo com camada oculta; não é um detector de nova onda. **Cálculos:** apenas apresenta `movementCount` local. **Depende:** B03/B04 e rompimentos conhecidos. **Necessário agora:** não ao Dow/50%. **Ao ocultar:** perde-se o indício resumido de reutilização; detalhes permanecem em F03.

#### B10 — Estudo experimental, sem score ou corte de relevância…

**Faz:** mostra contagens 2+2/1+1/adicionais e largura das regiões de teste. **Criado para:** delimitar o experimento H1. **Uso:** aparece quando há comparação, independentemente de B02/B05/B06. **Cálculos:** não; largura de 50 pontos é parâmetro do backend para auditoria, não B03. **Depende:** preparação experimental. **Necessário agora:** não permanentemente; o aviso importa quando o experimento é usado. **Ao ocultar:** perde-se identificação da origem e ausência de score.

#### B11 — Referencias H1 para [data]…

**Faz:** lista pregões fonte, quantidade de candidatos e linhas, e explica agrupamento de até cinco pontos ou adicionais de preço idêntico. **Criado para:** comprovar preparação antes da abertura. **Uso:** informativo quando existe preparação; muda de severidade se insuficiente. **Cálculos:** não; reporta backend. **Depende:** `structure.preparation`. **Necessário agora:** aviso de insuficiência sim; lista completa pode ir à auditoria. **Ao ocultar:** perde-se verificação rápida da cobertura e da sessão das referências.

#### B12 — Contexto M5… / Replay de… / Use o zoom e a rolagem…

**Faz:** lista dias anteriores presentes nos candles entregues e avisa se há menos de três. **Criado para:** explicar aquecimento e navegação histórica. **Uso:** só no Replay com preparação; não consulta dias extras ao expandir nada. **Cálculos:** contagem local das datas recebidas, não novo detector. **Depende:** sessão e candles visíveis ao cliente. **Necessário agora:** útil em conferência; não precisa dominar a tela. **Ao ocultar:** perde-se diagnóstico de contexto ausente e orientação de navegação.

#### B13 — … niveis exibidos H1; … linhas M5… / Preparacao H1 indisponivel…

**Faz:** resume quantidades conforme filtros, ou explica falta de preparação/suporte. **Criado para:** conferir a resposta gráfica. **Uso:** continua abaixo do gráfico com `structure`; “exibidos” conta entregues à camada, não necessariamente dentro da janela atual. Em M15 informa referências disponíveis em M5. **Cálculos:** apenas leitura/filtro local. **Depende:** preparação, comparação e visibilidades. **Necessário agora:** alertas úteis; contagens se sobrepõem a B08. **Ao ocultar:** perde-se resumo e eventual diagnóstico, que merecem destinos diferentes numa reorganização.

### C. Fibonacci antiga e manual

#### C01 — Fibonacci

**Faz:** habilita desenho da versão principal e comparação da ferramenta antiga/manual. **Criado para:** estudar amplitudes por âncoras. **Uso:** desmarcar retira essas versões do overlay, mas mantém análise automática, versões manuais, resumo e auditorias. A seleção de âncoras por clique pode continuar armada, pois `pick` é independente. **Cálculos:** backend não; cálculo local continua mesmo desmarcado. **Depende:** versões/âncoras disponíveis. **Necessário agora:** não para DOW 50%. **Ao ocultar:** perde-se controle visual da ferramenta, não se elimina seu processamento.

#### C02 — Modo Fibonacci

**Faz:** escolhe “Automático experimental” ou “Manual” como versão principal. **Criado para:** comparar hipótese de seleção automática com escolha do usuário. **Uso:** trocar não muda sessão nem âncoras já salvas; sem versão manual, fica aguardando. Se comparar estiver ativo, a outra modalidade torna-se secundária. **Cálculos:** seleção local, sem mudar backend ou DOW 50%. **Depende:** versões da amplitude selecionada. **Necessário agora:** automático é experimento anterior; manual continua útil como referência. **Ao ocultar:** perde-se alternância/comparação controlada, não a necessidade de preservar o manual.

#### C03 — Amplitude Fibonacci

**Faz:** escolhe “Micro M5” ou “Macro H1 2+2”. **Criado para:** estudar âncoras de escalas diferentes. **Uso:** altera versões/listas mostradas, limpa topo/fundo pendentes e cancela seleção. Não altera período dos candles. “Micro M5” usa pivôs principais 2+2, não Dow micro 1+1. **Cálculos:** análise local por faixa; sem pedido ao backend. Observações da faixa H1 usam os candles recebidos do gráfico, normalmente M5. **Depende:** memória de pivôs. **Necessário agora:** opcional. **Ao ocultar:** perde-se a comparação de amplitudes e identificação da fonte.

#### C04 — Comparar manual e automático

**Faz:** exibe a outra modalidade como comparação azul pontilhada. **Criado para:** conferir diferenças de âncoras e nível. **Uso:** marcar não cria versões nem escolhe âncoras automaticamente para o manual; sem segunda versão não há sobreposição. Desmarcar conserva ambas. **Cálculos:** composição gráfica local, sem mudança de regra. **Depende:** C01 ligado e versões compatíveis com C03. **Necessário agora:** somente em testes. **Ao ocultar:** perde-se comparação simultânea; ainda seria possível alternar C02 se mantido.

#### C05 — Aproximação 50% (pontos)

**Faz:** escolhe 0, 5, 15 ou 30 pontos para proximidade da Fibonacci antiga/manual. **Criado para:** comparar sensibilidade das observações geométricas. **Uso:** mudar refaz observações locais, mesmo com desenho desligado; não muda o preço do 50% ou as âncoras. **Cálculos:** sim, `observeFibonacci`; não é tolerância do `DowFiftyEngine`, que tem sua própria configuração. **Depende:** versões e candles fechados conhecidos. **Necessário agora:** não ao novo monitor. **Ao ocultar:** perde-se comparação de tolerâncias antigas, não controle do DOW 50%.

#### C06 — Selecionar máxima no gráfico

**Faz:** arma captura de um topo confirmado. **Criado para:** escolher manualmente vendo a origem. **Uso:** desenha círculos azuis dos topos disponíveis; clique escolhe o ID e desarma captura, mas só C11 cria versão. Desabilitado sem topo. **Cálculos:** estado local de seleção, sem backend. **Depende:** âncoras de C03 e gráfico; enquanto armado, cliques deixam de inspecionar Dow/50%. **Necessário agora:** não ao cálculo automático principal. **Ao ocultar:** perde-se escolha visual de máxima; C09 é alternativa.

#### C07 — Selecionar mínima no gráfico

**Faz:** arma captura de um fundo confirmado. **Criado para:** selecionar a origem inferior visualmente. **Uso:** círculos azuis de fundos; clique preenche o ID, desarma captura e aguarda aplicação. Desabilitado sem fundo. **Cálculos:** escolha local, sem modificar níveis ainda. **Depende:** C03 e âncoras conhecidas; ocupa temporariamente a interação do gráfico. **Necessário agora:** apenas ao estudo manual. **Ao ocultar:** perde-se seleção visual de mínima; C10 continua alternativa.

#### C08 — Cancelar seleção

**Faz:** desarma a captura por clique. **Criado para:** devolver a interação normal ao gráfico sem escolher um pivô. **Uso:** só habilitado com seleção armada; não limpa IDs já escolhidos nem versões aplicadas. **Cálculos:** nenhum. **Depende:** C06/C07. **Necessário agora:** necessário quando se oferece captura, não ao cálculo Dow. **Ao ocultar:** perde-se uma saída explícita; trocar amplitude ou concluir clique também desarma, mas tem outro efeito.

#### C09 — Máxima manual confirmada

**Faz:** lista topos da amplitude, com preço e horário de formação. **Criado para:** alcançar âncoras inclusive fora do viewport. **Uso:** escolher muda apenas o topo pendente; não cria Fibonacci até aplicar. **Cálculos:** seleção local. **Depende:** C03, âncoras confirmadas até o corte; seleção visual C06 preenche o mesmo campo. **Necessário agora:** opcional ao estudo manual. **Ao ocultar:** perde-se escolha por lista e acesso fácil a origem fora da janela; o clique seria a alternativa restante.

#### C10 — Mínima manual confirmada

**Faz:** lista fundos com preço e formação. **Criado para:** selecionar sem depender da posição atual do gráfico. **Uso:** define o fundo pendente; não altera uma versão existente. **Cálculos:** seleção local. **Depende:** C03, confirmações conhecidas; C07 pode preencher o mesmo ID. **Necessário agora:** somente para manual. **Ao ocultar:** perde-se a alternativa de lista para o fundo, especialmente útil fora do viewport.

#### C11 — Aplicar Fibonacci manual

**Faz:** valida extremos e registra uma nova versão. **Criado para:** preservar uma escolha independente do automático. **Uso:** exige dois IDs; valida mesmo símbolo/período, preços ordenados, origens distintas e confirmações conhecidas; ao funcionar muda o modo para Manual. **Cálculos:** cria nível médio e histórico local, depois observações locais; sem backend. **Depende:** C09/C10 válidos. **Necessário agora:** útil como referência manual, não ao Dow principal. **Ao ocultar:** perde-se a capacidade de efetivar novas escolhas, embora as já aplicadas possam continuar em memória.

#### C12 — Clique no círculo azul… / Use a navegação…

**Faz:** orienta topo/fundo esperado e alternativa por lista. **Criado para:** evitar clicar em um candle arbitrário como se fosse pivô confirmado. **Uso:** aparece apenas durante captura. **Cálculos:** nenhum. **Depende:** C06/C07. **Necessário agora:** útil enquanto captura está ativa. **Ao ocultar:** perde-se indicação do modo de interação e pode parecer que o clique normal em Dow/50% parou de funcionar.

#### C13 — Alerta de erro manual

**Faz:** explica seleção ausente ou âncoras incompatíveis. **Criado para:** impedir uma Fibonacci manual sem origem válida. **Uso:** aparece após falha de aplicação; selecionar graficamente ou aplicar corretamente pode limpar o erro. Não há correção automática das âncoras. **Cálculos:** mensagem não; a validação local rejeitou a versão. **Depende:** C11. **Necessário agora:** sim se manual estiver acessível. **Ao ocultar:** perde-se motivo da falha, sem fazer a operação passar.

#### C14 — FIBO 50% — … / Aguardando dupla… / Fibonacci manual aguardando…

**Faz:** mostra preço, modalidade, amplitude, direção, extremos e decisão da versão principal. **Criado para:** identificar o desenho antigo/manual. **Uso:** resumo permanece com C01 desmarcado; aguardar versão manual não significa que falta onda Dow. **Cálculos:** leitura de resultado local, sem backend. **Depende:** C02/C03 e memória conhecida. **Necessário agora:** não para o novo 50%. **Ao ocultar:** perde-se identificação textual dessa ferramenta; manter junto à área Fibonacci reduziria a confusão com E02.

#### C15 — Estudo experimental… / Extremos confirmados ampliam…

**Faz:** descreve regras antigas de renovação e ressalta que toque não muda âncora. **Criado para:** explicar a hipótese automática anterior. **Uso:** texto sempre visível no workspace, inclusive em modo manual ou Fibonacci oculta. **Cálculos:** nenhum; não configura regras. **Depende:** entendimento do `FibonacciMovementEngine`. **Necessário agora:** não na área principal de Dow/50%. **Ao ocultar:** perde-se explicação dessa hipótese, que pode ficar como ajuda da ferramenta antiga.

#### C16 — Memória completa indisponível nesta resposta…

**Faz:** avisa ausência de `confirmedPivots` e uso de níveis/candidatos disponíveis como fallback. **Criado para:** não apresentar análise limitada como memória completa. **Uso:** aparece com estrutura mas sem memória completa, independentemente de C01. **Cálculos:** aviso não muda regra; o fallback já muda a fonte disponível à análise local. **Depende:** contrato do backend. **Necessário agora:** importante se a Fibonacci é usada nesse estado. **Ao ocultar:** perde-se ressalva sobre âncoras e H1 intradiário ausentes.

#### C17 — Linha principal: branca… / 0% na máxima… / Nenhum nível amplia o autoscale…

**Faz:** explica a legenda da Fibonacci, comparação e persistência local após Reiniciar. **Criado para:** interpretar 0/50/100%, A/B e faixa. **Uso:** aparece sempre abaixo do gráfico; não é descrição específica do DOW 50%, apesar de repetir “linha branca”. **Cálculos:** nenhum. **Depende:** C18. **Necessário agora:** ajuda opcional, não texto permanente do estudo principal. **Ao ocultar:** perde-se legenda antiga; uma futura ajuda contextual evita ambiguidade com E09.

#### C18 — Linhas 0%/50%/100%, diagonal A→B, faixa azul e comparação

**Faz:** desenha amplitude completa da versão, etiquetas de preço no gráfico/eixo e origens A/B em ordem temporal. **Criado para:** visualizar âncoras e comparar manual/automático. **Uso:** C01 governa versões; C04 acrescenta comparação azul. 0% fica na máxima e 100% na mínima em ambas as direções. **Cálculos:** só geometria; não amplia autoscale. **Depende:** origens disponíveis no eixo e versões. **Necessário agora:** opcional; E09 desenha somente um nível. **Ao ocultar:** perde-se a amplitude completa, não a linha Dow independente.

### D. Teoria de Dow

#### D01 — Mostrar ondas Dow micro (1+1 experimental)

**Faz:** exibe cadeia menor ciano tracejada, marcadores e informações micro condicionais. **Criado para:** comparar a seleção 1+1 com a estrutura principal. **Uso:** desmarcar oculta também contagens/abertura/auditoria micro condicionais, mas preserva preferências de etiquetas/fractais. **Cálculos:** nenhum no backend; micro continua retornado em snapshots M5. **Depende:** `microDow`, não A07 de H1. **Necessário agora:** não alimenta nem substitui 50% principal. **Ao ocultar:** perde-se comparação de ondas menores, sem perder dados principais.

#### D02 — Mostrar todos os fractais micro confirmados

**Faz:** troca marcadores selecionados por todos os fractais confirmados. **Criado para:** conferir o que foi incluído/descartado pelo zigzag micro. **Uso:** não cria segmentos para todos os fractais; com D01 desligado fica sem efeito visual. **Cálculos:** somente filtro local de marcadores, não seleção de ondas. **Depende:** D01 e `selectedPivotIds`. **Necessário agora:** auditoria opcional. **Ao ocultar:** perde-se comparação entre universo detectado e cadeia escolhida.

#### D03 — Mostrar etiquetas HH/HL/LH/LL micro

**Faz:** põe classes com prefixo “micro” nos marcadores exibidos. **Criado para:** distinguir classes locais da estrutura principal. **Uso:** depende de D01; D02 define quais marcadores recebem etiqueta. Desmarcar não retira marcadores/segmentos nem a legenda própria de abertura. **Cálculos:** só visual; classes vêm do Java. **Depende:** snapshot micro. **Necessário agora:** somente comparação. **Ao ocultar:** perde-se leitura rápida das classes micro, ainda disponíveis no JSON.

#### D04 — Fractais / Utilizados / Segmentos / Versoes historicas

**Faz:** mostra detectados, IDs selecionados, arestas vigentes e versões históricas micro. **Criado para:** distinguir quantidade detectada de quantidade desenhada. **Uso:** aparece com D01; cada número deriva de um campo distinto, não de um novo cálculo de tendência. **Cálculos:** leitura local do snapshot, com fallback de contagem para contratos antigos. **Depende:** `microDow`. **Necessário agora:** não permanentemente. **Ao ocultar:** perde-se diagnóstico resumido; F08 conserva os detalhes.

#### D05 — Abertura micro: [status] / referencia ambar…

**Faz:** informa estado da tentativa de conexão com referência de abertura. **Criado para:** não tratar abertura provisória como pivô confirmado. **Uso:** aparece com D01 e objeto `opening`; texto pode indicar estado sem haver uma linha conectada desenhável. **Cálculos:** nenhum; estado vem do backend. **Depende:** observador de abertura micro. **Necessário agora:** somente para examinar esse experimento. **Ao ocultar:** perde-se distinção imediata entre referência de abertura e onda por pivôs.

#### D06 — Ondas micro indisponiveis nesta resposta do backend

**Faz:** avisa snapshot ausente. **Criado para:** diferenciar indisponibilidade de uma cadeia legitimamente vazia. **Uso:** só se D01 ligado e `microDow` ausente; não tenta detectar no navegador. **Cálculos:** nenhum. **Depende:** versão do backend/período. **Necessário agora:** quando a camada micro é solicitada. **Ao ocultar:** pode parecer que o algoritmo não encontrou ondas quando, na verdade, os dados não foram enviados.

#### D07 — Mostrar Dow principal

**Faz:** mostra movimentos, pivôs e marcações de rompimento principais. **Criado para:** estudar a estrutura maior M5 2+2. **Uso:** desligar retira desenho e clique gráfico em pivôs, mas resumo, último rompimento e auditoria continuam. Não desliga E09. **Cálculos:** nenhum no backend. **Depende:** snapshot `dow`; independente das linhas amarelas e de Fibonacci. **Necessário agora:** sim. **Ao ocultar:** perde-se o comando e, se a camada for retirada, o vínculo visual entre ondas e 50%.

#### D08 — Mostrar etiquetas HH/HL/LH/LL

**Faz:** mostra classes dos pivôs principais; primeiro extremo aparece “?” e igual aparece “=”. **Criado para:** ler comparações de extremos sem abrir tabelas. **Uso:** só tem desenho com D07; não oculta linhas/pontos nem anotações R↑/R↓ ao desmarcar. **Cálculos:** somente visual; classificação vem do backend. **Depende:** pivôs principais. **Necessário agora:** útil, mas a onda pode ser estudada sem texto sobre cada ponto. **Ao ocultar:** perde-se leitura rápida de classes; ficha/auditoria são alternativas.

#### D09 — Dow experimental · M5 · ALTA / BAIXA / INDEFINIDO / TRANSIÇÃO

**Faz:** mostra estado estrutural calculado pelo Java. **Criado para:** resumir pares HH/HL ou LH/LL e quebra de referência protegida. **Uso:** permanece com desenho desligado; INDEFINIDO não é uma classificação comprovada de lateralização; TRANSIÇÃO não confirma a tendência contrária. **Cálculos:** nenhum na mensagem. **Depende:** `dow.state`; snapshot ausente gera outro texto. **Necessário agora:** sim como contexto, não sinal. **Ao ocultar:** perde-se leitura sintetizada do estado do motor.

#### D10 — R↑ Resistência rompida / R↓ Suporte rompido

**Faz:** mostra o último evento principal de rompimento com horário de conhecimento e motivo/preço. **Criado para:** explicar mudança estrutural por fechamento M5. **Uso:** aparece quando há evento, mesmo com D07 desligado. Pode ser um evento antigo; não é uma nova notificação a cada renderização. **Cálculos:** nenhum; backend exige fechamento além de referência já conhecida. **Depende:** eventos principais. **Necessário agora:** útil ao estudo de Dow. **Ao ocultar:** perde-se o contexto resumido; F09 mantém cronologia.

#### D11 — Linhas finas… / Pontos cheios… / Sem critério de lateralização…

**Faz:** explica estrutura, pivô local, terminal substituído, classes e rompimentos. **Criado para:** evitar interpretar qualquer segmento como onda finalizada ou correção como reversão. **Uso:** texto sempre presente no painel principal; não é controle de regras. **Cálculos:** nenhum. **Depende:** convenções de D14. **Necessário agora:** conhecimento necessário, apresentação longa permanente opcional. **Ao ocultar:** perde-se a legenda e suas limitações; pode ser ajuda associada ao gráfico.

#### D12 — Estrutura Dow M5 indisponível…

**Faz:** informa ausência do contrato `dow`. **Criado para:** evitar inferência improvisada no navegador. **Uso:** só com D07 ligado e snapshot ausente; em M15 o recurso não é fornecido. **Cálculos:** nenhum. **Depende:** período/versão do backend. **Necessário agora:** sim quando falta estrutura solicitada. **Ao ocultar:** ausência de dados pode ser confundida com INDEFINIDO ou falta de ondas.

#### D13 — Topo/Fundo selecionado: preço, origem, confirmação, classe…

**Faz:** explica um extremo individual, sua comparação e participação: estrutura, local ou terminal substituído. **Criado para:** rastrear por que entra ou sai da cadeia. **Uso:** aparece após clique no pivô principal ou botão F10; selecionar não muda o motor. Pode continuar visível ao ocultar a camada. **Cálculos:** só inspeção local. **Depende:** ID ainda presente no snapshot. **Necessário agora:** sob demanda. **Ao ocultar:** perde-se explicação contextual sem ler toda a tabela.

#### D14 — Segmentos principais, pontos cheios/vazios e R↑/R↓ no gráfico

**Faz:** desenha arestas selecionadas e extremos na posição de formação; anota rompimentos no candle do evento. **Criado para:** vincular estrutura e preço. **Uso:** D07 liga o conjunto; D08 só altera classes. Um terminal pode ser substituído: segmento desenhado não implica conclusão para o 50%. **Cálculos:** geometria local; decisões Java; não amplia autoscale. **Depende:** dados conhecidos até o cursor e coordenadas no eixo. **Necessário agora:** sim. **Ao ocultar:** perde-se contexto visual da origem de E09.

#### D15 — Cadeia micro tracejada / Abertura (referencia, nao pivo)

**Faz:** desenha segmentos ciano e, quando conectada, ligação âmbar de abertura com círculo/legenda próprios. **Criado para:** comparar micro sem misturar abertura com pivôs. **Uso:** D01 governa; D02 muda marcadores e D03 classes. A legenda âmbar não depende de D03. **Cálculos:** visual, sem alimentar principal/50%. **Depende:** micro e abertura `CONNECTED` conhecida. **Necessário agora:** não ao objetivo principal. **Ao ocultar:** perde-se leitura do experimento menor, sem perda das ondas principais.

### E. Nova linha de 50% baseada em Dow

#### E01 — Mostrar 50% Dow principal

**Faz:** mostra a linha vigente e o bloco condicional de hipóteses/inspeção/histórico. **Criado para:** estudar o ponto médio da onda principal sem ligar Fibonacci completa. **Uso:** desligar não apaga resumo E02, explicação E03 nem o cálculo Java; também não altera D07. **Cálculos:** só apresentação; nenhum parâmetro de visibilidade é enviado ao backend. **Depende:** `dowFifty`, não da Fibonacci antiga ou micro. **Necessário agora:** sim. **Ao ocultar:** perde-se acesso explícito à camada principal de estudo.

#### E02 — DOW 50% — preço / estado / concluida em… / Aguardando onda

**Faz:** resume referência vigente e instante de reconhecimento. **Criado para:** distinguir nível atual de referências encerradas. **Uso:** permanece mesmo com E01 desligado. Sem referência ativa mostra “Aguardando onda”; isso também ocorre sem snapshot, enquanto E08 é o aviso específico de indisponibilidade quando a camada é pedida. **Cálculos:** nenhum; leitura Java. **Depende:** `currentWaveId` e referências. **Necessário agora:** sim. **Ao ocultar:** perde-se leitura da referência quando a linha está fora da escala ou a camada está oculta.

#### E03 — Fonte exclusiva: ondas do Dow principal 2+2…

**Faz:** explica que o próximo pivô oposto sela a onda e que o terminal não é automaticamente concluído. **Criado para:** separar novo nível de Fibonacci antiga/manual e micro. **Uso:** aparece sempre, sem configurar o critério. **Cálculos:** nenhum na mensagem. **Depende:** critério experimental `SEALED_BY_NEXT_STRUCTURAL_PIVOT_V1`. **Necessário agora:** importante entendimento; pode ser ajuda curta. **Ao ocultar:** perde-se a ressalva de que a conclusão é uma inferência documentada sobre eventos do Dow, não um novo detector explícito.

#### E04 — Hipotese de rompimento… / Hipotese de retorno… / Tolerancia propria…

**Faz:** explica confirmação experimental por fechamento M5 e retorno candidato por toque/fechamento no mesmo lado. **Criado para:** não apresentar hipóteses como entradas ou destinos garantidos. **Uso:** condicionado a E01; não há neste texto seletor de política ou de tolerância. Valor vem do snapshot, com fallback textual de cinco pontos. **Cálculos:** nenhum ao ler; backend monitora independentemente. **Depende:** política Java. **Necessário agora:** aviso experimental sim; descrição extensa sob demanda. **Ao ocultar:** perde-se distinção entre confirmação experimental e validação definitiva.

#### E05 — Lado observado / Rompimento / Retorno / Referencias possiveis

**Faz:** mostra lado do preço, estado das hipóteses e topo/fundo da onda. **Criado para:** acompanhar a reação ao 50%. **Uso:** aparece com E01 e referência ativa; lados TOP_SIDE/BOTTOM_SIDE referem-se ao lado do fechamento, não provam que o preço visitou o extremo. **Cálculos:** mensagem não; backend avalia M5 fechado. **Depende:** referência vigente e eventos conhecidos. **Necessário agora:** sim como leitura experimental. **Ao ocultar:** perde-se a reação resumida; F11 ainda contém detalhes e distâncias.

#### E06 — Auditar onda 50% Dow

**Faz:** escolhe referência atual ou histórica para inspeção. **Criado para:** consultar ondas anteriores sem torná-las vigentes. **Uso:** seleciona só `selectedFifty`; não troca `currentWaveId`, não redesenha linha antiga nem envia comando. A opção “Referencia vigente” volta a acompanhar a atual. **Cálculos:** nenhum. **Depende:** E01 e lista de referências Java. **Necessário agora:** consulta útil, não controle diário do monitor. **Ao ocultar:** perde-se acesso direto à ficha de ondas anteriores; F11 é alternativa mais extensa.

#### E07 — Ficha da onda: inicial, final, máxima/mínima, 50%, vigente desde, última reação

**Faz:** comprova ID/direção/estado da onda; extremos com preço, formação e confirmação; cálculo médio; reconhecimento, evento fonte e última reação. **Criado para:** justificar cada linha branca. **Uso:** aparece com E01 e referência inspecionada, inclusive histórica; clique E09 a seleciona. **Cálculos:** nenhum ao consultar. **Depende:** referências e eventos Java. **Necessário agora:** sim, sob demanda. **Ao ocultar:** perde-se a prova contextual de origem/tempo, fundamental para diferenciar projeção visual retrospectiva de disponibilidade causal.

#### E08 — 50% Dow indisponivel nesta resposta…

**Faz:** informa que faltou `dowFifty` e que o navegador não vai inventar onda. **Criado para:** distinguir contrato ausente de nenhum nível confirmado. **Uso:** somente com E01 ligado e snapshot ausente. **Cálculos:** nenhum. **Depende:** backend M5 compatível. **Necessário agora:** sim nesses casos. **Ao ocultar:** “Aguardando onda” pode parecer uma decisão do algoritmo quando faltam os dados.

#### E09 — Linha branca contínua DOW 50% — preço

**Faz:** desenha um único `(máxima+mínima)/2`, começando no centro horizontal projetado dos extremos e estendendo à direita. **Criado para:** vincular visualmente nível e onda. **Uso:** só aparece após reconhecimento, com E01 e referência não encerrada; clique abre E07. O início na área antiga da onda não antecipa a disponibilidade no Replay. **Cálculos:** geometria local, valor Java; 2,5 px branca fixa, sem ampliar autoscale. **Depende:** onda selada e eixo. **Necessário agora:** sim. **Ao ocultar:** perde-se a referência visual principal.

#### E10 — Substituição/encerramento da referência, refletidos em E02 e F11

**Faz:** registra por que uma referência deixa de ser atual. **Criado para:** conservar vínculo onda→nível e impedir troca por micro. **Uso:** não existe botão de política; nova conclusão principal única encerra a antiga e ativa a nova. Ambiguidade/invalidez encerra a anterior e deixa sem alvo. **Cálculos:** regra Java; consultar eventos não a muda. **Depende:** evento principal, movimento afetado e terceiro pivô confirmados. **Necessário agora:** histórico é necessário para validação, não precisa ficar aberto. **Ao ocultar:** perde-se explicação da troca, não a troca em si.

### F. Auditorias e informações técnicas

#### F01 — Auditoria Fibonacci: … versões · … ambiguidades (M5/H1)

**Faz:** lista decisão, modo/direção, máxima e mínima com preço/origem/confirmação, 50%, motivo e âncoras anteriores→novas; abaixo mostra ambiguidades e eventos automáticos. **Criado para:** rastrear renovação de âncoras antigas e versões manuais. **Uso:** abrir/fechar só expande conteúdo; segue C03, não C01. Linhas não reativam versões. **Cálculos:** nenhum ao expandir; análise local já existe. **Depende:** versões até o corte e memória local manual. **Necessário agora:** opcional. **Ao ocultar:** perde-se rastreabilidade da comparação antiga/manual.

#### F02 — Observações geométricas dos 50%: … · não confirmam sinais

**Faz:** lista aproximação, teste, possível rompimento/rejeição da Fibonacci antiga e manual, com horário e versão. **Criado para:** experimentar leitura geométrica sem ordem intrabar presumida. **Uso:** abrir não roda monitor Java; C05 muda observações. Exige versão conhecida antes da abertura do candle e usa candles fechados. **Cálculos:** locais já calculados, não afetados pela expansão. **Depende:** C03/C05 e versões. **Necessário agora:** não substitui F11. **Ao ocultar:** perde-se comparação antiga; convém manter identificada como outro monitor.

#### F03 — Regioes M5: … pivos originais em … linhas

**Faz:** mostra tipo, preço representante, faixa original, quantidade de movimentos e membros com preço/origem/confirmação/rompimento. O ID do membro também aparece como título ao passar o ponteiro. **Criado para:** comprovar que agrupamento não remove pivôs. **Uso:** existe se há grupos, mesmo com B01 desligado; abrir não muda grupos. **Cálculos:** somente resultado local de B03/B04. **Depende:** níveis M5. **Necessário agora:** testes, não leitura diária. **Ao ocultar:** perde-se prova detalhada das linhas amarelas.

#### F04 — Referencias H1 preparadas: preco, origem e confirmacao

**Faz:** lista critério 2+2, tipo, preço, formação, confirmação e se foi referência ou agrupado com preço próximo. **Criado para:** auditar a preparação anterior à abertura. **Uso:** aparece com preparação, independentemente de B02; em comparação usa os candidatos estritos, não todos os adicionais. **Cálculos:** nenhum ao abrir. **Depende:** `preparation` e baseline. **Necessário agora:** opcional para contexto H1. **Ao ocultar:** perde-se rastreio das referências preparadas; a lista não controla seleção no backend.

#### F05 — Candidatos H1 1+1: comparação com 2+2

**Faz:** lista critério, tipo, preço, origem MT5, confirmação e correspondência/adicional, incluindo indicação de linha própria. **Criado para:** avaliar o detector alternativo sem alterar 2+2. **Uso:** renderizado aberto quando há comparação; B06 não o fecha nem retira candidatos. **Cálculos:** nenhum ao expandir/recolher. **Depende:** A07 experimental e snapshot comparativo. **Necessário agora:** só na investigação H1. **Ao ocultar:** perde-se comparação por candidato, não o cálculo; recolher por padrão seria uma decisão futura.

#### F06 — Avaliações 1+1: confirmações e rejeições

**Faz:** lista origem, tipo, preço, decisão e motivo do detector H1: confirmado, não extremo estrito ou sem vizinho fechado antes do corte. **Criado para:** explicar por que cada candidato entra ou é recusado. **Uso:** abrir não altera regra nem reprocessa seleção. “Rejeição” aqui é do candidato a pivô, não rejeição de preço no 50%. **Cálculos:** nenhum ao consultar. **Depende:** preparação H1 experimental. **Necessário agora:** diagnóstico. **Ao ocultar:** perde-se explicação das ausências/descartes H1.

#### F07 — Regiões e episódios de teste H1 (sem gerar novos pivôs por toque)

**Faz:** mostra faixas de até 50 pontos, quantidade de pivôs e episódios; tabela traz início/fim, horas, reação de alta/baixa e “Medido até”. Essas faixas podem reunir pivôs de tipos/pregões distintos, ao contrário do agrupamento M5. **Criado para:** descrever testes históricos, sem score. **Uso:** H1 consecutivas na mesma sessão/faixa formam episódio; reações usam até duas H1 seguintes disponíveis antes da abertura, podendo cruzar sessões. **Cálculos:** medição Java na preparação; expandir não altera. **Depende:** comparação e faixa já conhecida. **Necessário agora:** não. **Ao ocultar:** perde-se estudo histórico de reação H1, não ondas Dow.

#### F08 — Auditoria micro: … pivos confirmados

**Faz:** exibe JSON de pivôs/classes, cadeia selecionada, versões de movimentos, decisões de inclusão/recusa, eventos, abertura e seu histórico. **Criado para:** investigar causalidade e continuidade micro. **Uso:** só renderizada com D01; expandir não ativa detector. Pode conservar termos técnicos sem tradução. **Cálculos:** nenhum na interação. **Depende:** `microDow`. **Necessário agora:** não ao 50% principal. **Ao ocultar:** perde-se detalhe técnico do experimento menor, inclusive razões não desenhadas.

#### F09 — Auditoria Dow: … pivôs · … movimentos · … eventos

**Faz:** tabela de extremo/preço, origem, confirmação, classe, participação, comparação, motivo e movimentos que usam o pivô; lista eventos com estado anterior→novo, pivô e movimento afetado. **Criado para:** justificar a cadeia principal. **Uso:** disponível mesmo com D07 desligado; abrir não muda algoritmo. **Cálculos:** nenhum ao consultar. **Depende:** snapshot principal. **Necessário agora:** sim como recurso de conferência, recolhido no uso normal. **Ao ocultar:** perde-se explicação detalhada de seleção, substituição, transição e rompimento.

#### F10 — Botão Topo [preço] / Fundo [preço] dentro da auditoria Dow

**Faz:** seleciona o pivô da linha e mostra D13. **Criado para:** inspecionar extremos fora do viewport ou com desenho oculto. **Uso:** clique muda apenas ID local, sem centrar automaticamente o gráfico e sem alterar onda. **Cálculos:** nenhum. **Depende:** F09 e ID no snapshot. **Necessário agora:** útil como alternativa acessível ao clique gráfico. **Ao ocultar:** perde-se essa seleção por lista; navegar no gráfico pode ser mais difícil para origens antigas.

#### F11 — Auditoria 50% Dow: … ondas / … eventos

**Faz:** tabela de reconhecimento, onda, direção, 50%, estado e encerramento; lista eventos/justificativas e JSON de observações por candle. Inclui fechamento, distâncias até 50%/topo/fundo, lado e hipóteses. **Criado para:** provar origem, substituições e causalidade do novo monitor. **Uso:** aparece com E01; abrir não reativa referências nem altera políticas. **Cálculos:** nenhum ao consultar; Java já calcula mesmo com E01 desligado. **Depende:** `dowFifty`. **Necessário agora:** para auditoria, não aberto permanentemente. **Ao ocultar:** perde-se histórico completo acessível na tela.

#### F12 — Status: Carregando / Aguardando / PAUSED / PLAYING / COMPLETED / LIVE / UNAVAILABLE

**Faz:** identifica estado de consulta/fonte/sessão. **Criado para:** explicar se há reprodução ou apenas dados preservados. **Uso:** apresenta `Carregando` durante consulta e status da resposta fora dela. Pode piscar durante polling; não equivale ao estado da onda E02. **Cálculos:** nenhum; leitura de estados. **Depende:** modo, carregamento e resposta. **Necessário agora:** sim. **Ao ocultar:** perde-se distinção essencial entre pausado, sem dados, ao vivo e terminado.

#### F13 — Último candle: [horário]

**Faz:** mostra `bucketStartTimeMsc` do último candle recebido. **Criado para:** situar a série exibida. **Uso:** atualização passiva; no progressivo pode permanecer 09:55 enquanto os passos já chegaram a 09:56/57/58/59. Não é um relógio explícito de `availableAtMsc`. **Cálculos:** nenhum. **Depende:** último candle e A03. **Necessário agora:** sim, corretamente interpretado. **Ao ocultar:** perde-se posição temporal legível; não se resolve a ausência de um relógio de Replay distinto.

#### F14 — Fechado / Em formação

**Faz:** informa `closed` do último candle. **Criado para:** distinguir preço parcial de fechamento utilizável pelos detectores. **Uso:** passivo, serve ao live e ao progressivo; não muda política de confirmação. **Cálculos:** nenhum. **Depende:** candle recebido. **Necessário agora:** sim. **Ao ocultar:** perde-se uma distinção importante para entender por que preço já cruzou o nível e o evento M5 ainda não ocorreu.

#### F15 — Lacunas entre candles: [quantidade]

**Faz:** conta cada diferença entre inícios consecutivos que não seja exatamente um período. **Criado para:** sinalizar descontinuidade da série. **Uso:** inclui intervalo noturno, fim de semana e dias de aquecimento; não mede duração da ausência nem lacunas M1 internas de A19. **Cálculos:** contagem local, sem preencher dados. **Depende:** lista inteira recebida e período. **Necessário agora:** diagnóstico, não indicador de defeito por si só. **Ao ocultar:** perde-se alerta bruto; manter sem explicação pode induzir conclusão errada sobre qualidade.

#### F16 — Stream de ticks indisponível. Candles preservados; reconexão automática.

**Faz:** explica `UNAVAILABLE` no live. **Criado para:** distinguir desconexão de ausência de negociação/estrutura. **Uso:** aparece pelo status; frontend segue consultando e backend reconcilia ao recuperar feed. Não é botão “reconectar”. **Cálculos:** mensagem não controla reconstrução. **Depende:** stream compartilhado e implementação live. **Necessário agora:** sim quando ocorre. **Ao ocultar:** dados preservados podem parecer novos e atuais.

#### F17 — Fim dos candles disponíveis

**Faz:** informa que não há próximo passo. **Criado para:** distinguir fim da cobertura de pausa voluntária. **Uso:** aparece em COMPLETED; não afirma que todo o pregão oficial foi obtido se a fonte tem corte mais cedo. **Cálculos:** nenhum. **Depende:** limite do histórico carregado. **Necessário agora:** sim. **Ao ocultar:** pode parecer que Play falhou, quando a sequência terminou legitimamente.

#### F18 — Horários civis do terminal… / Timestamps interpretados como UTC… / Valores originais preservados…

**Faz:** explica a convenção selecionada e necessidade de validar a fonte. **Criado para:** evitar deslocar uma série civil como se fosse UTC real. **Uso:** texto acompanha A03; não realiza nova conversão por si. **Cálculos:** nenhum na legenda. **Depende:** referência temporal. **Necessário agora:** informação importante, texto longo não precisa se repetir permanentemente. **Ao ocultar:** perde-se a ressalva de interpretação; a configuração continua produzindo seu efeito.

#### F19 — Replay de candles fechados… / Replay progressivo… / Nenhum sinal operacional nesta etapa

**Faz:** explica unidade do passo, taxa 1x e ausência de ticks simulados/sinais. **Criado para:** delimitar o que o usuário está assistindo. **Uso:** escolhe texto pelo `view.replayMode`; sem modo progressivo usa texto fechado, inclusive em live, pois não há condição adicional para ocultá-lo. **Cálculos:** nenhum. **Depende:** modo informado. **Necessário agora:** aviso curto útil. **Ao ocultar:** perde-se limite experimental; no futuro convém contextualizar por origem em vez de repetir texto de Replay no live.

#### F20 — O backend ainda não enviou os níveis estruturais…

**Faz:** avisa resposta sem `structure`, orientando backend compatível. **Criado para:** não tratar falha de integração como conjunto vazio de referências. **Uso:** aparece quando há view sem estrutura; não dispara atualização/reinício sozinho. **Cálculos:** nenhum. **Depende:** versão do serviço/contrato. **Necessário agora:** sim em falha. **Ao ocultar:** pode-se estudar uma tela incompleta sem perceber a ausência do motor estrutural.

### G. Gráfico e elementos adicionais

#### G01 — Padrão de 3 Candles (título)

**Faz:** identifica o módulo. **Criado para:** separar infraestrutura de estudo dos outros scanners. **Uso:** sempre visível, sem ação. O nome não significa que já exista uma regra completa de entrada do padrão. **Cálculos:** nenhum. **Depende:** rota. **Necessário agora:** orientação sim. **Ao ocultar:** perde-se identificação da ferramenta, especialmente se o menu lateral não aparece em telas pequenas.

#### G02 — Padrão de 3 Candles (link lateral)

**Faz:** navega para `/three-candles`. **Criado para:** dar acesso ao módulo no aplicativo. **Uso:** menu compartilhado, oculto em telas menores que o breakpoint do Drawer; sair da rota desmonta componentes e perde estados locais não persistidos. **Cálculos:** não configura algoritmo; sair limpa sessão pela desmontagem. **Depende:** AppLayout/Router. **Necessário agora:** acesso sim. **Ao ocultar:** perde-se descoberta pelo menu; rota direta ainda existe. Os outros destinos laterais pertencem a outros módulos, não são controles de Dow.

#### G03 — Gráfico de candles

**Faz:** mostra OHLC M5/M15 conhecido, com cores de alta/baixa do tema e último preço nativos da série. **Criado para:** base visual do estudo. **Uso:** recebe atualizações do mesmo candle ou novos buckets; no progressivo não usa o M5 completo antes do quinto minuto. **Cálculos:** apresenta dados; não gera detector por desenho. **Depende:** controller, API e gráfico nativo. **Necessário agora:** sim. **Ao ocultar:** perde-se o principal contexto de preço e formação, restando tabelas insuficientes ao estudo visual.

#### G04 — Eixo de preço, eixo de horário e cursor/cruzamento nativo

**Faz:** permite ler posição temporal/preço e preço atual no gráfico. **Criado para:** situar candles e referências. **Uso:** são recursos nativos sem controles próprios desta página; horários usam A03 e cores usam tema. **Cálculos:** coordenadas locais, não indicadores nem OHLC novos. **Depende:** gráfico e viewport. **Necessário agora:** sim. **Ao ocultar:** perde-se precisão de leitura. A página não adiciona painel próprio de volume, SMA/VWAP ou sinais da estratégia antiga.

#### G05 — Rolagem horizontal do gráfico

**Faz:** navega entre candles já recebidos, incluindo aquecimento. **Criado para:** examinar origens antigas sem trocar dados. **Uso:** arrastar/rolar conforme interação nativa; não avança cursor do Replay nem revela candles futuros privados do servidor. **Cálculos:** viewport somente. **Depende:** histórico recebido; não é consulta de histórico adicional por paginação. **Necessário agora:** sim. **Ao ocultar/desabilitar futuramente:** perde-se acesso visual a origens fora da janela.

#### G06 — Zoom horizontal

**Faz:** altera espaçamento e quantidade de candles visíveis. **Criado para:** alternar contexto e detalhe. **Uso:** interação nativa, sem reset da sessão; não muda M5 para M1/H1. **Cálculos:** geometria somente; overlays acompanham coordenadas. **Depende:** gráfico e candles disponíveis. **Necessário agora:** sim. **Ao ocultar/desabilitar:** perde-se capacidade de enxergar onda completa ou detalhe da aproximação.

#### G07 — Escala vertical / autoscale

**Faz:** ajusta faixa de preços visível. **Criado para:** ler candles sem comprimi-los por níveis distantes. **Uso:** escala nativa pode ser manipulada; Ajustar gráfico restaura autoscale. Overlays não fornecem expansão de escala, portanto linha fora do intervalo pode não aparecer. **Cálculos:** apenas apresentação dos candles visíveis, não preço dos níveis. **Depende:** viewport e série. **Necessário agora:** sim. **Ao ocultar/desabilitar:** perde-se ajuste de leitura; ausência visual de nível não prova ausência no snapshot.

#### G08 — Ajustar gráfico

**Faz:** ajusta faixa lógica para a parte recente e restaura autoscale/margens. **Criado para:** recuperar enquadramento após pan/zoom. **Uso:** desabilitado sem candles; implementação foca aproximadamente os últimos 40 candles mais margem, não dá `fitContent` de todo o histórico. Não reinicia Replay. **Cálculos:** viewport local. **Depende:** controller e gráfico. **Necessário agora:** sim. **Ao ocultar:** perde-se recuperação rápida; é diferente de Reiniciar, que reconstrói dados.

#### G09 — Clique em âncora, linha 50% ou pivô Dow

**Faz:** direciona seleção contextual. **Criado para:** inspecionar objetos sem abrir todos os painéis. **Uso:** seleção manual armada tem prioridade exclusiva; fora dela, testa primeiro linha DOW 50%, depois pivô principal. Sobreposição pode selecionar a linha. Não existe callback de clique para pivôs micro nesta página. **Cálculos:** inspeção/seleção local; só Aplicar manual cria versão. **Depende:** camada visível e hit-test. **Necessário agora:** sim. **Ao ocultar/remover interação:** perde-se auditoria contextual, embora F10/E06 ofereçam alternativas de lista.

#### G10 — Panic Scanner (cabeçalho do aplicativo)

**Faz:** identifica o aplicativo na barra superior compartilhada. **Criado para:** orientação global entre rotas. **Uso:** texto sem ação nesta página. **Cálculos:** nenhum. **Depende:** AppLayout, não do Replay. **Necessário agora:** não ao cálculo, útil à orientação. **Ao ocultar:** perde-se identidade global, sem reduzir diretamente as opções experimentais do módulo. Outros links do menu levam a Dashboard, Scanner, Scanner experimental, WIN M5, WIN Scanner, detalhe de ativo e Configurações; são navegação compartilhada, não parâmetros desta análise.

## Integrações e dependências verificadas

### Fluxo real da página

```text
/three-candles → ThreeCandlesPage → ThreeCandlesWorkspace → ThreeCandlesChart
                        ↓ API /api/three-candles
                ThreeCandlesController
                    ├─ live → ThreeCandleLive → CandlePipeline
                    └─ Replay → ThreeCandleReplay → CandlePipeline
                                           ↓
                              ThreeCandleStructure (pivôs)
                                           ↓
                       DowStructureEngine / MicroDowEngine / DowFiftyEngine

Snapshot recebido → FibonacciMovementEngine (análise antiga/manual local)
                  → m5DisplayGroups (agrupamento visual local)
                  → camadas gráficas e auditorias
```

O diagrama resume dependências, não uma única cadeia obrigatória: `MicroDowEngine` usa candles M5 e é independente do principal; o novo `DowFiftyEngine` usa o principal, não micro ou Fibonacci. O controller reconstrói esses observadores para o instante da resposta. Nenhum checkbox de camada é argumento da API.

| Ação/configuração | Efeito de integração comprovado |
|---|---|
| Inicializar página | GET `/config`; falha provoca nova tentativa. |
| Tempo Real | GET `/live` com símbolo/período/`maxLevels`; preparação H1 atual usa modo padrão 2+2. |
| Carregar | GET `/history/status`; POST `/history/prepare` se faltar cache válido; POST `/replays`. O progressivo obtém/valida M1 durante a criação. |
| Símbolo/data/hora/período/base de horário/modo H1/modo Replay | São dependências do efeito que encerra sessão e limpa visão. No Replay, é necessário carregar novamente. A seleção H1 não é transmitida na chamada live. |
| Play/Pause/Próximo/Reiniciar/Velocidade | POST `/replays/{id}/commands`; GET da sessão continua atualizando a interface. |
| Visibilidade M5/H1, filtros H1, agrupamento/separação | Apenas métodos do controlador local; não reiniciam o Replay. |
| Fibonacci: modo/amplitude/tolerância/manual | Apenas estado/análise do workspace no navegador; sem endpoint correspondente. |
| Mostrar Dow/micro/50% e etiquetas/fractais | Apenas overlays e conteúdo condicional; sem economia correspondente do cálculo Java. |
| Abrir um `<details>` | Expansão HTML local; os dados já estão no snapshot ou já foram derivados. A consulta periódica continua. |
| Sair da página/trocar fonte | Limpeza do efeito cancela consultas e tenta DELETE da sessão. Não é armazenamento permanente de estudo. |

### Dependências que precisam continuar claras numa eventual reorganização

| Elemento | Precisa de | Não depende de |
|---|---|---|
| Linhas amarelas | Pivôs M5, visibilidade B01, agrupamento local. | Fibonacci, micro e linha branca. |
| Referências H1 | Dados H1 preparados e B02; filtros B05/B06 se houver comparação. | Ondas Dow M5. |
| Fibonacci antiga M5 | Pivôs principais M5 confirmados e seu motor local. | Grupos amarelos ou detector micro 1+1. |
| Fibonacci manual | Dois extremos válidos da amplitude e aplicação local. | Seleção automática de ondas Dow. |
| Dow principal | Pivôs M5 2+2, candles fechados conhecidos. | Linhas amarelas, H1 adicional e Fibonacci. |
| Dow micro | Fractais M5 1+1, cadeia micro e observador de abertura. | Modo H1 1+1 e seleção principal. |
| DOW 50% | Conclusão principal validada pelo observador, candles M5 fechados para monitorar reação. | Micro, Fibonacci antiga/manual, agrupamento amarelo e checkboxes de desenho. |
| Inspeção por clique | Camada visível e nenhum seletor manual armando prioridade alternativa. | Alterar algoritmo ou enviar ordem. |

### Padrões atuais observados — somente registro

Nenhum padrão foi alterado nesta auditoria.

| Configuração | Valor inicial no código |
|---|---|
| Origem | Tempo Real. |
| Símbolo | WINZ26 inicial, substituído pelo símbolo live de `/config`. |
| Período / horário | M5 / Hora do terminal MT5. |
| Data / início | Primeira sessão de `/config`, ou data ISO atual como fallback / 09:00. |
| Modo Replay / velocidade | Candle fechado / 1x. |
| Detecção H1 | 2+2 de referência. |
| M5 amarelo / H1 fúcsia | Ambos ligados na página. |
| Agrupamento / separação | 30 pontos / separação desligada. |
| Filtros H1 2+2 e adicionais | Ambos ligados, mas só aparecem quando há comparação. |
| Fibonacci / modo / amplitude | Ligada / Automático experimental / Micro M5. |
| Comparar / aproximação antiga | Comparar desligado / cinco pontos. |
| Seleção manual / versões manuais | Nenhuma seleção armada, campos vazios, lista local vazia. |
| Dow principal / suas etiquetas | Desligado / etiquetas ligadas. |
| Dow micro / seus fractais completos / suas etiquetas | Todos desligados. |
| 50% Dow principal | Desligado. |
| Painéis expansíveis | Recolhidos, exceto Candidatos H1 1+1, renderizado aberto quando disponível. |

Existe uma inversão prática: a Fibonacci automática anterior começa visível e as camadas do objetivo atual Dow/50% começam desligadas. Isso é **um achado para decisão do usuário**, não autorização para mudar os padrões.

### Memória local, reconstrução e contexto histórico

- Reiniciar a mesma sessão conserva estado local do workspace, incluindo versões manuais; o filtro pelo instante esconde versões decididas depois do novo cursor e as revela quando esse instante volta a ser alcançado.
- Carregar cria outro ID de Replay; a chave do workspace muda e reinicia seus estados locais. Trocar rota, símbolo, data ou período e recarregar o navegador também pode desmontar essa memória. Não foi encontrado salvamento das escolhas manuais em banco/localStorage neste fluxo.
- As preferências do topo da página e as do workspace têm ciclos de vida diferentes. Por exemplo, mostrar M5/H1 é estado da página; Fibonacci/Dow/50% são estados internos do workspace.
- Os snapshots representam o prefixo conhecido. O gráfico pode projetar uma referência até a direita e associá-la a uma origem antiga, mas a existência da linha continua limitada pelo momento de confirmação/reconhecimento.
- O progressivo muda a formação visual do candle; os monitores de reação documentados continuam usando M5 fechado. “Em formação” e “rompimento experimental confirmado por M5” não são a mesma informação.
- A lista de lacunas M1 é metadado de qualidade da fonte. O contador inferior de lacunas entre buckets é outro diagnóstico, que inclui saltos entre dias. Não devem ser tratados como dois números equivalentes.

### Estados e critérios do novo 50%, sem criar controles inexistentes

| Estado | Significado no código |
|---|---|
| AGUARDANDO_ONDA | Não há referência vigente selecionada; pode incluir espera inicial ou conclusão ambígua. Falta de snapshot merece o alerta E08 separado. |
| ONDA_CONFIRMADA | Conclusão registrada; estado transitório de evento antes de iniciar monitoramento no mesmo instante. |
| MONITORANDO_50 | Referência vigente sob observação. |
| ROMPIMENTO_EM_AVALIACAO | Hipótese de atravessamento, com status próprio: candidato, confirmado experimentalmente pelo fechamento M5, invalidado ou destino observado. |
| REJEICAO_EM_AVALIACAO | Hipótese de retorno candidata; não existe critério definitivo validado de rejeição. |
| ONDA_ENCERRADA | Referência histórica que não é mais enviada ao monitor como atual. |

O backend registra aproximação, primeiro toque, tentativa, candidato, confirmação experimental, invalidação/falso rompimento e alcance de extremos. A direção de aproximação vem do lado do fechamento conhecido, não da suposição de ter vindo efetivamente do topo/fundo. A confirmação experimental de rompimento é registrada no próprio fechamento que originou a candidata; não há uma contagem adicional de candles escondida na UI. Observar retorno/extremo não valida uma regra de rejeição nem cria destino garantido.

O último evento mostrado na ficha filtra nomes contendo TOUCH/BREAK/REJECTION/DESTINATION. Logo, “Última reação” não é necessariamente o último evento geral: aproximações, início e encerramento têm sua trilha em F11. Uma ficha histórica selecionada pode permanecer na tela enquanto E02 e a linha seguem uma nova referência; são objetos distintos por ID, não duas linhas vigentes.

### Elementos técnicos presentes sem controle próprio na tela

| Elemento interno | Constatação e implicação para a decisão de interface |
|---|---|
| `maxLevels = 6` | Constante legada enviada à API, sem campo editável atual. M5 do pregão e H1 preparado não são limitados a seis. O fallback H1 sem preparação ainda utiliza o limite. Não inventar um controle de “máximo de linhas” no inventário visível. |
| `candlesOnly`, `structuralLevels`, `visibleCandleViewport` | Props passadas ao workspace e declaradas no tipo, mas não consumidas na implementação atual do workspace. O gráfico dedicado determina o comportamento atual. Não representam três opções de usuário. |
| `lastTickMsc`, `availableAtMsc`, `criterion`, memória completa | Campos do contrato; não existem campos independentes editáveis nem um relógio explícito de `availableAtMsc` no rodapé. Há usos internos/auditorias desses dados. |
| Tolerância do DOW 50% / política de rompimento / política de rejeição | São parâmetros/identificadores do snapshot Java. A UI descreve-os, não oferece seletor próprio para alterá-los. |
| Validação Zod | Pode impedir aplicação de resposta com pivô futuro, onda fabricada, nível inconsistente ou referência reativada. As falhas aparecem em A20; não é um detector adicional. |
| Checagem de modo progressivo em `api.start` | Há duas verificações consecutivas equivalentes de que o backend ativou o modo. É redundância interna de código, não dois controles da tela. Registrada sem correção nesta tarefa. |
| `outsideWindowCandidates` e campos de episódios | Alguns dados estão no contrato de comparação H1 sem coluna específica visível. A tabela de episódios mostra “Medido até”, mas não exibe todo campo de conhecimento interno. |
| Tema e acessibilidade | O gráfico usa cores do tema; a Fibonacci antiga adapta a cor principal, o novo DOW 50% usa branco fixo. O rótulo acessível do gráfico ainda menciona candles/estrutura/Fibonacci experimental. Não há seletor local de tema nem de cor nessa página. |
| Containers, espaçamentos e ícones | Elementos de composição, sem configuração ou função de estudo separada. Não foram tratados como controles de algoritmo. |
| Menu e cabeçalho compartilhados | Pertencem ao aplicativo. Esta proposta não inclui reorganizar scanners, detalhe de ativo ou Configurações de outras rotas. |
| Ordens, Alvo → Nega → Testa, médias, VWAP e volume | Não são funcionalidades operacionais exibidas pelo fluxo atual desta página. O nome do módulo e o arquivo legado anexado não autorizam atribuí-las a esta UI. |

## Proposta de organização futura — ainda não implementada

### Área principal

- Configuração da fonte: Ativo, Período, Tempo Real/Replay, Data/Hora, base temporal identificável e Modo de Replay quando aplicável (A01–A06, A08–A09).
- Comandos de reprodução: Carregar, Reiniciar, Play, Pause, Próximo passo e Velocidade (A10–A15).
- Gráfico e Ajustar gráfico, com navegação nativa preservada (G03–G09).
- Mostrar Dow principal e Mostrar 50% Dow principal (D07/E01). Resumo de tendência, referência atual, estado da reação e último rompimento relevante (D09/D10/E02/E05).
- Estado da fonte, candle parcial/fechado e avisos de falha/cobertura somente quando pertinentes (A17–A20, F12–F14, F16–F17, F20).
- M5 amarelo e H1 fúcsia poderiam permanecer como camadas opcionais de contexto, conforme a preferência do usuário (B01/B02). Não são requisitos do cálculo do 50%.

### Área avançada

- Detecção H1 1+1 e filtros de comparação, com indicação explícita de que mudar o detector exige recarregar (A07, B05/B06).
- Dow micro, todos os fractais e etiquetas micro, mantendo independência do principal (D01–D03).
- Agrupamento M5 e separação por rompimento, juntos e claramente identificados como apresentação local (B03/B04).
- Ferramenta Fibonacci antiga e editor manual preservado: modo, amplitude, comparação, tolerância e seleção/aplicação (C01–C13, C18). A área manual não deve ser eliminada por classificar a seleção automática como experimento antigo.
- Etiquetas principais e legendas detalhadas como preferências/ajuda, sem retirar o acesso às classes do estudo (D08/D11/C17).
- Convenção temporal pode ser uma configuração avançada se o resumo principal continuar mostrando a base usada; essa localização precisa de escolha do usuário (A03/F18).

### Área de auditoria

- Ondas/pivôs/eventos principais e a cronologia de 50%, incluindo justificativa de atualização e referências encerradas (F09–F11, E06/E10).
- Regiões M5 e suas origens, preparação/candidatos/avaliações/episódios H1 (F03–F07).
- Versões e observações da Fibonacci antiga/manual, identificadas como independentes do monitor Dow (F01/F02).
- JSON micro e abertura, memórias e reconstrução por instante, com diagnóstico de cobertura de fonte separado de intervalos entre sessões (F08, A19/F15 e dados técnicos disponíveis).
- Contagens extensas de grupos, candidatos e versões; listas de dias fonte; explicações técnicas que hoje ocupam vários blocos da área principal (B07–B13, D04/D05).

“Reconstrução causal” é uma finalidade da auditoria; não existe hoje um botão dedicado com esse nome. Uma reorganização pode acomodar os dados disponíveis sem afirmar que já há exportação, persistência de estudo ou comparação automatizada por uma ação da tela.

### Informações candidatas a aparecer só por clique

| Objeto clicado | Informações a apresentar sob demanda | Acesso alternativo a preservar |
|---|---|---|
| Linha DOW 50% | ID/direção/estado da onda, dois extremos com formação/confirmação, máxima/mínima, média, vigente desde, evento fonte, última reação e possíveis extremos. | E06 e F11, inclusive para histórico. |
| Pivô Dow principal | Preço, tipo, formação/confirmação, classe, pivô comparado, participação, motivo e movimentos associados. | F10/F09. |
| Onda principal | Extremos que formam o segmento e diferença entre aresta selecionada, terminal provisório e onda selada para o 50%. | Auditorias D13/F09/F11. Hoje não há hit-test específico no segmento da onda: seria nova interação a aprovar. |
| Linha/região M5 amarela | Representante, faixa original, membros, confirmações e rompimentos/episódios. | F03. Hoje a linha amarela não tem callback próprio de inspeção nesta página. |
| Linha H1 | Origem, confirmação, pregão, detector, agrupamento e eventual correspondência 2+2/1+1. | F04–F07. Hoje não há callback próprio H1. |
| Fibonacci antiga/manual | Modalidade, amplitude, âncoras A/B, valor, decisão e motivo/versão. | F01 e editor manual. Hoje não há seleção geral da versão por clique na linha; a interação implementada é seleção de âncoras quando armada. |
| Pivô/abertura micro | Motivo de inclusão/recusa, IDs selecionados, versões e distinção entre abertura e pivô confirmado. | F08. Hoje não há callback micro, portanto seria uma ampliação futura, não apenas mover painel. |

Ao propor contexto por clique, não se deve tornar a inspeção impossível quando o objeto está fora do viewport. Listas históricas e botões alternativos continuam necessários. O modo de selecionar âncoras também precisa continuar explicitamente distinguível da inspeção de ondas/níveis.

### Redundâncias prováveis e distinções que não devem ser apagadas

- B08/B13/F03 repetem contagens M5 em níveis diferentes de detalhe. É candidato a consolidação, não a apagar membros originais.
- A03/F18 e A06/F19 repetem explicações de relógio e ritmo; podem ter resumo e ajuda contextual.
- C17 e E03/E04 falam de linhas brancas com origens distintas. Consolidar a ajuda exige identificar qual ferramenta está sendo descrita, não unir seus cálculos.
- C06/C09 e C07/C10 são dois meios de escolher a mesma âncora. São alternativas complementares, especialmente fora do viewport, não redundância automática a eliminar.
- D09 é tendência; F12 é estado da fonte; E02 é estado da referência. Não são três rótulos do mesmo estado.
- F02 e F11 observam “50%”, mas usam referências e motores diferentes. Podem ser separados espacialmente; não devem ser apresentados como um único monitor sem decisão de produto/algoritmo.
- E02 mostra a vigente e E07 pode mostrar uma histórica selecionada. A diferença deve ficar legível; não tornar o histórico operacional para fazer os textos coincidirem.

## Evidências e limites da auditoria

O código atual é a fonte principal deste inventário. Documentos de etapas anteriores ajudam no contexto, mas podem descrever versões anteriores do gráfico e não substituem a leitura da implementação atual. Não foram executadas consultas MT5, comandos de Replay ou alterações de configuração para esta auditoria, nem testes que regeneram artefatos. Não se afirma validação dinâmica de todas as combinações de viewport/tema nesta tarefa.

Conferência documental: os 106 IDs da tabela têm ficha individual com todos os campos solicitados; os links locais de fontes foram validados. A impressão digital conjunta de 808 arquivos sob `frontend/src`, `panic-scanner/src` e `panic-scanner/scripts` permaneceu idêntica antes/depois da documentação, excluindo apenas caches Python dessa comparação. Nenhum arquivo de código foi editado.

| Fonte | Trechos/funções que sustentam o levantamento |
|---|---|
| [ThreeCandlesPage.tsx](../../frontend/src/features/threecandles/pages/ThreeCandlesPage.tsx) | Defaults; efeito de identidade e encerramento; `loadReplay`, `command`; controles A/B; painéis H1/M5; rodapé e alertas. |
| [ThreeCandlesWorkspace.tsx](../../frontend/src/features/threecandles/components/ThreeCandlesWorkspace.tsx) | Defaults locais; `analyzeFibonacci`; seleção/aplicação manual; overlays; micro; DOW 50%; auditorias antigas. |
| [DowDebugPanel.tsx](../../frontend/src/features/threecandles/components/DowDebugPanel.tsx) | Controles principais, tendência/rompimento, ficha selecionada, tabela e eventos. |
| [ThreeCandlesChart.tsx](../../frontend/src/features/threecandles/components/ThreeCandlesChart.tsx) | Camadas independentes; prioridade dos cliques; gráfico nativo; `focusTime` e autoscale. |
| [candleChartController.ts](../../frontend/src/features/threecandles/models/candleChartController.ts) | Filtros locais e agrupamento para entrega; `fitChart`; identidade e atualizações incrementais. |
| [m5DisplayGroups.ts](../../frontend/src/features/threecandles/models/m5DisplayGroups.ts) | Largura total, representante estável, associação por rompimento e contagem de episódios. |
| [FibonacciMovementEngine.ts](../../frontend/src/features/threecandles/models/FibonacciMovementEngine.ts) | `fibVersion`, `observeFibonacci`, `analyzeFibonacci`; âncoras originais e exclusão dos adicionais H1. |
| [FibonacciPrimitive.ts](../../frontend/src/features/threecandles/components/FibonacciPrimitive.ts) | Níveis completos, faixa, A/B, cores, rótulos no eixo e seleção manual. |
| [DowStructurePrimitive.ts](../../frontend/src/features/threecandles/components/DowStructurePrimitive.ts) | Marcadores selecionados/todos; etiquetas; abertura âmbar; anotações de rompimento; hit-test principal. |
| [DowFiftyPrimitive.ts](../../frontend/src/features/threecandles/components/DowFiftyPrimitive.ts) | Referência única, confirmação antes de desenhar, origem geométrica e seleção. |
| [SwingPivotPrimitive.ts](../../frontend/src/features/win/components/SwingPivotPrimitive.ts) | Linhas M5/H1 horizontais, estilo e clipping, sem ação específica de clique. |
| [threeCandlesApi.ts](../../frontend/src/features/threecandles/api/threeCandlesApi.ts) | Contratos/validações; chamadas de configuração, cache, live, Replay e comandos. |
| [AppRouter.tsx](../../frontend/src/app/router/AppRouter.tsx) e [AppLayout.tsx](../../frontend/src/common/layout/AppLayout.tsx) | Rota, link lateral, título compartilhado e limites do layout global. |
| [ThreeCandlesController.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/api/ThreeCandlesController.java) | Criação da sessão, parâmetros enviados, preparação H1, modo progressivo e montagem dos observadores em cada view. |
| [ThreeCandleReplay.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/replay/ThreeCandleReplay.java) e [ProgressiveM1History.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/replay/ProgressiveM1History.java) | RESET, comandos, velocidade, observações e lacunas M1 sem preenchimento artificial. |
| [ThreeCandleLive.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/infrastructure/ThreeCandleLive.java) | Símbolo live único, reconstrução/reconexão, preparação H1 padrão e snapshots M5. |
| [ThreeCandleHistory.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/infrastructure/ThreeCandleHistory.java) e [ThreeCandleHistoryLoader.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/infrastructure/ThreeCandleHistoryLoader.java) | Catálogo, caches, validação, consultas MT5 e cobertura anterior à abertura. |
| [CandleTimeBasis.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/domain/CandleTimeBasis.java) e [candleTime.ts](../../frontend/src/features/threecandles/models/candleTime.ts) | Conversão do início do Replay e formatação de horário. |
| [CandlePipeline.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/application/CandlePipeline.java) e [ThreeCandleStructure.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/application/ThreeCandleStructure.java) | Aceitação de parciais/fechados, memória de pivôs, seleção M5/H1 e ausência de regra operacional completa do padrão. |
| [H1SessionPreparation.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/application/H1SessionPreparation.java) e [H1ExperimentalComparison.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/application/H1ExperimentalComparison.java) | Baseline/addicionais, critérios 1+1, agrupamentos e episódios. |
| [DowStructureEngine.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/application/DowStructureEngine.java), [MicroDowEngine.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/application/MicroDowEngine.java) e [DowFiftyEngine.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/strategy/threecandles/application/DowFiftyEngine.java) | Separação dos motores, estados, eventos, conclusão e independência de controles visuais. |
| [M5ReplaySession.java](../src/main/java/br/com/bauzin/market/panic/panicscanner/application/win/replay/M5ReplaySession.java) | Fluxo legado anexado, com indicadores/estratégia próprios; não é o motor chamado por esta página. |

## Próxima decisão do usuário

Usar os IDs da tabela para escolher individualmente: **manter visível**, **mover para avançada**, **manter só na auditoria**, **ocultar por padrão** ou **avaliar descontinuação**. As categorias de utilidade não executam nenhuma dessas ações. Em particular, ocultar por padrão exige decidir se o valor inicial permanece igual e se o acesso continua disponível; descontinuar exige uma decisão separada sobre dados/cálculos preservados.

Nenhuma reorganização deve começar antes dessa escolha. Este documento é a entrega da auditoria, não uma implementação de nova interface.
