# CleanYourTV — Edição Família (ramo feature/family-profiles)

> Estado: **em desenvolvimento**. Não instalar na TV nem substituir a Custom Edition 2.4 estável.
> Base: `custom-2.4` em `92e83f6d551d707cc11d74f9e91334b1b93182eb`.
> O motor puro `FamilyTimePolicy` já define as regras de horário e quota; **a contabilização real, a interface e a proteção HDMI ainda não foram implementadas**.

## Produto e regras

Ativação expressa por adulto nas definições, com PIN válido já configurado. **Nunca ativar restrições automaticamente após atualizar uma instalação 2.4**.

| Perfil | Identificação | Horário local | Saldo por dia civil |
| --- | --- | --- | --- |
| Adulto | PIN já existente (4–6 dígitos) | Sem restrição | Ilimitado |
| Criança (TV) | Seleção sem PIN | 07:00 inclusive a 22:00 exclusive | 60 minutos |
| Xbox (HDMI) | Seleção sem PIN | 07:00 inclusive a 22:00 exclusive | 60 min. segunda–sexta; 120 min. sábado/domingo |

Saldos independentes de Criança e Xbox. Usar `Europe/Lisbon` como fuso horário explícito ou a zona local da TV confirmada; testar mudança entre horário de verão/inverno. Os tempos consumidos no mesmo dia somam-se mesmo entre várias sessões. Limite e janela de horário prevalecem sobre os atalhos existentes. Às 22:00 não iniciar ou continuar uma sessão infantil autorizada **dentro das possibilidades efetivas das APIs disponíveis**.

## Integração no código existente

- Launcher Java/Views, módulo `launcher/app`, minSdk 26, targetSdk 34.
- `LauncherState`: PIN com PBKDF2, salt e bloqueio progressivo; respetivo ficheiro `launcher-state.properties` deve continuar íntegro, sem migrações destrutivas.
- `MainActivity`: ações que abrem aplicações passam por `requirePin(...)`; integrar política do perfil antes dessas ações **e também** para cartões, biblioteca, atalhos e ecrã de entradas. Não criar um bypass noutro fluxo.
- `InputKeyService` e `HomeKeyService`: serviços de acessibilidade existentes, opcionais, com acessos específicos a teclas. Não alargar captura de teclas sem necessidade e consentimento explícito.
- Entradas HDMI já descobertas por `TvInputManager`; isto permite apresentação/abertura em certos firmwares, **não equivale a bloquear HDMI**.

## Sessões e contabilização

Definir uma máquina de estados testável: `UNCONFIGURED`, `SELECT_PROFILE`, `ADULT_AUTHENTICATING`, `ADULT_ACTIVE`, `CHILD_ACTIVE`, `XBOX_ACTIVE`, `LOCKED`. Aplicar transições em entradas de sistema, regresso ao HOME, retoma de atividade, fim de tempo, suspensão e reinício. Nunca restaurar silenciosamente `ADULT_ACTIVE` após um arranque novo. Permitir regressar ao seletor e autenticar Adulto mesmo com saldo infantil esgotado.

**Não contabilizar tempo de relógio enquanto o ecrã está desligado ou a TV está em suspensão**; usar `SystemClock.elapsedRealtime()` para duração dentro de uma sessão e fechar fragmentos ao suspender/trocar de perfil. Persistir consumos frequentemente e em cada transição. Reconciliar consumo após morte súbita do processo, sem reduzir o tempo já consumido. Usar data civil local para saldos diários e resistir a alterações manuais de relógio: não permitir recarregamentos gratuitos por recuos de data/hora; em estado temporal incerto, restringir sessões infantis sem impedir desbloqueio adulto.

Se `UsageStatsManager` for necessário para tempo de aplicações fora do launcher, documentar que requer autorização do utilizador e testar o comportamento real no Android TV 9. Não assumir disponibilidade de acessibilidade, overlays ou execução permanente em segundo plano. Sem acesso suficiente, apresentar explicitamente modo de proteção parcial. Não criar uma aplicação que pareça impor limites universais se apenas impede novos lançamentos pelo Home.

## Xbox / HDMI: etapa experimental separada

O `TvInputManager` permite enumerar entradas passthrough e a aplicação atual abre o seletor. O sinal HDMI e os botões físicos do controlo remoto são controlados, em parte, por componentes do fabricante. Uma aplicação Android comum não pode presumir capacidade para detetar continuamente HDMI ativo, cortá-lo ao fim da quota, nem impedir que o comando volte diretamente a HDMI.

Implementar primeiro a **restrição dos caminhos controlados pelo launcher**, contabilizando apenas o tempo que seja possível medir com fiabilidade. Investigar *read-only* os IDs `TvInputInfo`, ações de mudança de input, visibilidade/foco e possíveis eventos no Xiaomi Android TV 9. Testar fisicamente com Xbox e comando original. Se não houver imposição fiável, informar **Limitação HDMI** dentro da app e recomendar controlo complementar da consola (Xbox Family Settings ou equivalente), em vez de fingir um bloqueio completo. Nunca modificar firmware, SELinux, MCU, HDMI-CEC ou privilegiar a app.

## Interface em PT-PT

- Ao abrir Home após arranque/retorno da suspensão: seleção Adulto / Criança / Xbox.
- Adulto → ecrã de PIN no D-pad, reutilizando validação e cooldown existentes.
- Criança / Xbox → cartões com saldo restante, horário e aviso aos 10/5/1 minutos; mensagens claras de esgotado/fora de horário; botões sempre navegáveis via D-pad/OK/Voltar.
- Painel Gestão Familiar protegido por PIN: ativar/desativar, consultar consumo, limites e extensões de tempo, escolher HDMI da Xbox; não enfraquecer biblioteca/reordenação/proteção PIN existentes.
- PIN guardado só derivado, sem código inicial/predefinido e sem registo em logs.
- Não mostrar modo de bloqueio como mecanismo intransponível se outro launcher, comando direto HDMI, definições, assistente ou aplicação externa puder contornar o controlo.

## Critérios de aceitação antes de instalar na TV

1. `FamilyTimePolicyTest` e todos os testes antigos passam; compilar e executar lint.
2. Separação correta dos dois saldos e dos limites de 1h/2h; testar 06:59, 07:00, 21:59, 22:00, sexta, sábado, domingo, segunda e mudança de hora.
3. Persistência entre reinícios, falhas e atualização, sem perda de opções e PIN anteriores.
4. Adulto não fica autenticado após suspensão/reboot quando houver seleção obrigatória.
5. Tentativas de contorno: HOME, INPUT, botões do comando, seleção HDMI manual, apps externas, definições, alterações de data/hora e morte do processo.
6. Diferenciar nas notas de teste: **verificado em JVM/emulador** vs **verificado na Mi TV 4S real**.
7. Não publicar/instalar automaticamente. Preservar `custom-2.4` e disponibilizar APK separado para teste deliberado.

## Trabalho a realizar pelo Codex

Continuar diretamente neste ramo com autonomia para decidir, programar, testar e corrigir sem pedidos de autorização intermédios. Concluir a máquina de estados, armazenamento robusto, interfaces e testes; auditar todas as rotas de abertura; investigar e testar HDMI em segurança; entregar APK e evidências, identificando claramente o que permanece por confirmar no hardware. Não é aceitável entregar apenas uma simulação ou afirmar controlo HDMI sem testes físicos.
