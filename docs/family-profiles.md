# CleanYourTV — Edição Família

Edição experimental 2.5 no ramo `feature/family-profiles`, derivada da CleanYourTV Custom Edition 2.4. A edição está a ser preparada para Android TV 9 e não substitui nem altera o ramo estável `custom-2.4`.

## Perfis e limites

| Perfil | Acesso | Horário | Limite diário |
| --- | --- | --- | --- |
| Adulto | PIN parental existente, 4–6 dígitos | Sem restrição | Ilimitado |
| Criança (TV) | Seleção no launcher | 07:00–22:00, hora de Lisboa | 60 minutos |
| Xbox (HDMI) | Entrada HDMI escolhida pelo adulto | 07:00–22:00, hora de Lisboa | 60 minutos nos dias úteis; 120 minutos ao fim de semana |

O controlo começa desativado e só é ativado por um adulto autenticado. Não existe PIN predefinido. Os saldos de Criança e Xbox são independentes e persistem por dia civil. A gestão familiar permite consultar o consumo, acrescentar tempo e escolher a entrada HDMI.

## Monitorização de sessões

Para iniciar aplicações em qualquer perfil familiar, o adulto tem de conceder manualmente **Acesso a dados de utilização** nas Definições Android. A autorização é um acesso especial do sistema e não é concedida pela instalação. O serviço temporário de primeiro plano só funciona durante uma sessão iniciada pelo CleanYourTV e consulta eventos de pacote em primeiro plano; não lê texto, palavras-passe ou conteúdo do ecrã. Não é usado um serviço de acessibilidade para vigiar aplicações. O perfil Adulto não tem quota; esse monitor permite regressar ao launcher e pedir o PIN novamente depois de suspensão.

O serviço consulta a utilização a cada 10 segundos. O contador usa relógios monotónicos, não conta o período de ecrã desligado e divide a utilização à meia-noite em `Europe/Lisbon`. Há avisos aos 10, 5 e 1 minutos. A mudança de aplicação ou o fim do tempo podem ser reconhecidos com até cerca de 10 segundos de atraso.

## Barra de tempo sobre outras aplicações

Em **Gestão familiar**, depois de autenticar o PIN do Adulto, é possível mostrar/ocultar a barra, escolher o canto inferior esquerdo ou direito e escolher se apresenta o tempo utilizado, restante ou ambos. As opções ficam no ficheiro privado da Edição Família. Ocultar a barra só altera a apresentação: a contabilização e os limites continuam ativos.

Para desenhar sobre outras aplicações, o Android exige que o Adulto autorize **Apresentar sobre outras aplicações** nas Definições. A CleanYourTV abre o ecrã oficial de autorização e não contorna uma recusa. Sem autorização, a barra não aparece e os avisos de tempo usam as notificações Toast existentes. A autorização pode ser revogada nas Definições a qualquer momento.

A janela é criada apenas durante uma sessão Criança/Xbox iniciada pelo launcher e removida quando a sessão termina. É sem foco e sem toque, atualizada no mesmo ciclo de 10 segundos do monitor; não captura o comando nem os toques. Mostra consumo/limite e restante, com verde, amarelo (10 minutos), laranja (5 minutos) e vermelho (1 minuto). Os avisos de 10, 5 e 1 minuto são apresentados uma vez por perfil e dia. O Adulto nunca recebe a barra. A mensagem final distingue o tempo de televisão do tempo de jogo.

No Android 8.0/API 26 ou posterior, `TYPE_APPLICATION_OVERLAY` fica acima das janelas de aplicações, abaixo de janelas críticas do sistema, e requer autorização especial do utilizador. O sistema pode alterar a posição/visibilidade da janela. A barra usa uma única janela com `FLAG_NOT_FOCUSABLE` e `FLAG_NOT_TOUCHABLE`, com opacidade limitada para preservar a passagem de toque em versões recentes. A compatibilidade com a composição de vídeo da entrada HDMI — incluindo a Xiaomi Mi TV 4S — **não foi confirmada**; algumas TVs desenham o vídeo HDMI numa camada de hardware que pode ficar acima de overlays Android. A barra Xbox só poderá ser vista sobre HDMI se o firmware aceitar este tipo de janela; continua a não identificar, desligar nem bloquear a entrada física.

Quando o tempo acaba ou começa o período não permitido, o launcher tenta regressar ao Home. Android 9 não permite que uma aplicação comum force o fecho de outra aplicação. Se a firmware não aceitar o regresso automático, o vídeo pode continuar visível enquanto a sessão permanece pendente. Se o Android não confirmar o destino em 30 segundos, se o acesso for revogado ou se ocorrer um erro de armazenamento, a aplicação tenta regressar ao Home e bloqueia novos lançamentos pelo launcher até recuperação adulta.

Após reinício ou suspensão, o perfil Adulto não é restaurado automaticamente: é pedida nova seleção e o Adulto tem de introduzir o PIN. Se uma sessão tiver ficado sem estado fiável, Criança/Xbox ficam suspensos até o adulto autenticar e rever a hora. Os dados já contabilizados não são removidos; um período em que o processo não conseguiu observar eventos pode ficar por contabilizar e não é estimado como tempo usado. A gestão familiar reutiliza o PIN e a limitação progressiva existentes em `LauncherState`; o seu ficheiro não é migrado nem reescrito. Se o ficheiro familiar ficar ilegível, a gestão autenticada pelo PIN pode guardar uma cópia privada e desativar apenas a Edição Família; o PIN e as restantes definições do launcher permanecem intactos, e o adulto pode configurar a Edição Família novamente.

## Limites do controlo

O PIN e os limites aplicam-se apenas a aberturas e sessões iniciadas através do CleanYourTV. Não bloqueiam Definições Android, outro launcher, outras aplicações, assistentes, comandos diretos ou mecanismos do fabricante. Não devem ser tratados como controlo parental do sistema Android inteiro.

O perfil Xbox acompanha apenas uma sessão iniciada pelo atalho do launcher. `TvInputManager` permite enumerar algumas entradas, mas Android TV 9 não fornece a uma aplicação comum uma forma portátil de identificar continuamente a entrada HDMI fisicamente ativa, impedir que o comando mude de entrada, cortar o sinal ou encerrar a Xbox. O tempo Xbox não limita sessões abertas diretamente pelo comando ou pelo sistema. Como complemento, configurar os [limites de tempo de utilização da Xbox](https://support.xbox.com/en-US/help/family-online-safety/online-safety/set-screen-time-limits) na conta familiar Microsoft e validar as opções disponíveis na conta e na consola.

## Validação na televisão

Antes de confiar nos limites, testar na Xiaomi Mi TV 4S com firmware PI.4663:

1. Conceder Usage Access nas Definições Android e ativar a Edição Família com o PIN do adulto.
2. Iniciar uma aplicação pelo launcher em Criança e confirmar que o tempo é contado e que o Home aparece ao chegar ao limite.
3. Testar regresso com Voltar, botão Home, suspensão/retoma, reinício, revogação do Usage Access e mudança de hora.
4. Confirmar separadamente o perfil Xbox e os atalhos HDMI. Não inferir que o sinal HDMI foi bloqueado.
5. Verificar que o adulto consegue sempre autenticar-se e recuperar uma sessão pendente.

Os testes JVM e de interface/emulador verificam política, persistência, foco e fluxos da aplicação; não provam que a firmware da TV aceite iniciar o Home a partir do serviço em segundo plano. Essa validação física continua pendente. O APK experimental não é instalado automaticamente; preservar um caminho de recuperação para voltar à versão estável.
