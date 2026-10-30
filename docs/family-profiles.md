# CleanYourTV — Edição Família

Edição experimental 2.9 no ramo `feature/family-profiles`, derivada da CleanYourTV Custom Edition 2.4. Esta versão usa `versionCode` 24 para permitir atualização das versões experimentais anteriores. Está a ser preparada para Android TV 9 e não substitui nem altera o ramo estável `custom-2.4`.

## Perfis e limites

| Perfil | Acesso | Horário | Limite diário |
| --- | --- | --- | --- |
| Adulto | Sequência secreta de 4–10 movimentos do comando | Sem restrição | Ilimitado |
| Criança (TV) | Seleção no launcher | 07:00–22:00, hora de Lisboa | 60 minutos |
| Xbox (HDMI) | Entrada HDMI escolhida pelo adulto | 07:00–22:00, hora de Lisboa | 60 minutos nos dias úteis; 120 minutos ao fim de semana |

O controlo começa desativado e só é ativado por um adulto autenticado. Não existe sequência predefinida. Para introduzir ou confirmar a sequência, usar Cima, Baixo, Esquerda e Direita; OK confirma; Voltar apaga o último movimento ou cancela quando não há movimentos. O ecrã mostra apenas indicadores de progresso, nunca a sequência introduzida. Os saldos de Criança e Xbox são independentes e persistem por dia civil. A gestão familiar permite consultar o consumo, acrescentar 15, 30 ou 60 minutos, rever os últimos sete dias, ajustar horários/limites por dia da semana e escolher a entrada HDMI.

Os horários avançados são opcionais e independentes para Criança e Xbox. Sem alterações, mantêm-se 07:00–22:00 e os limites existentes (60 minutos para Criança; 60 minutos úteis e 120 minutos ao fim de semana para Xbox). As janelas são do mesmo dia; não são aceites horários que atravessem a meia-noite. Cada dia pode ser reposto individualmente à predefinição.

As estatísticas são locais, mantêm Criança e Xbox em colunas/valores separados e mostram os últimos sete dias, incluindo o dia atual. Apenas contam intervalos registados como ecrã ligado e aplicação em primeiro plano. A suspensão normal é excluída pelo estado de ecrã e pelos relógios monotónicos. Se o processo for terminado enquanto o dispositivo continua ligado, o estado persistido pode levar a contabilizar conservadoramente o intervalo desde a última amostra; eventos entretanto ocorridos podem causar uma pequena sobrecontagem. Se o dispositivo reiniciar, o relógio monotónico deixa de ser contínuo: a sessão desconhecida fica pendente sem ser cobrada, o acesso infantil fica suspenso e o Adulto tem de rever o relógio antes de retomar. Os perfis não partilham nem duplicam saldos.

## Aplicações do perfil Criança

Em **Gestão familiar → Aplicações para Criança**, o adulto escolhe por D-pad quais as aplicações de TV autorizadas. Na primeira utilização, a lista inicial copia as aplicações atualmente visíveis no launcher; as preferências globais de ocultação, proteção e organização não são modificadas. A lista infantil é guardada separadamente no ficheiro privado familiar. Depois da inicialização, aplicações novas ficam ocultas até serem aprovadas. Uma atualização da mesma aplicação mantém a aprovação; uma desinstalação final revoga-a, e uma reinstalação exige nova aprovação.

O launcher filtra a grelha, o dock, a biblioteca e os cartões com esta política. O mesmo controlo é aplicado no ponto comum de lançamento de tiles, biblioteca e cartões; tentar abrir diretamente uma aplicação não aprovada através de uma ação interna também é bloqueado. As opções administrativas não aparecem no menu do perfil Criança e os caminhos administrativos verificam o perfil Adulto e a sequência. Estas regras só abrangem ações iniciadas pela CleanYourTV; não controlam o Android ou outros launchers.

As aprovações são associadas ao nome do pacote e à hora de primeira instalação fornecida pelo Android. Esta identidade ajuda a detetar uma remoção e reinstalação mesmo que o launcher não estivesse em primeiro plano para receber o evento de desinstalação. A lista inclui aplicações com entrada de TV (`LEANBACK_LAUNCHER`), que são as que a CleanYourTV pode apresentar e abrir com a interface de televisão.

Na primeira utilização desta versão, a aplicação pede o PIN parental anterior uma vez e, após o validar, solicita a nova sequência duas vezes. A nova credencial só fica ativa depois de guardada com sucesso. O PIN anterior permanece guardado como hash para permitir a recuperação com o APK 2.4; a Edição Família não o aceita como método alternativo depois de a sequência estar ativa. Se os dados da sequência ativa ficarem incompletos, a aplicação falha fechada e não volta a aceitar o PIN antigo. A sequência é derivada com PBKDF2-HMAC-SHA-256 e salt aleatório; não é guardada em texto simples. As tentativas falhadas têm limitação progressiva persistida, partilhada com a migração inicial. Em **Gestão familiar** e **Aplicações protegidas** pode alterar a sequência após nova autenticação.

## Monitorização de sessões

Para iniciar aplicações em qualquer perfil familiar, o adulto tem de conceder manualmente **Acesso a dados de utilização** nas Definições Android. A autorização é um acesso especial do sistema e não é concedida pela instalação. O serviço temporário de primeiro plano só funciona durante uma sessão iniciada pelo CleanYourTV e consulta eventos de pacote em primeiro plano; não lê texto, palavras-passe ou conteúdo do ecrã. Não é usado um serviço de acessibilidade para vigiar aplicações. O perfil Adulto não tem quota; esse monitor permite regressar ao launcher e pedir a sequência novamente depois de suspensão.

O serviço existe apenas enquanto uma sessão de perfil está pendente; não fica ativo em repouso. Uma thread de trabalho consulta Usage Events e grava o estado a cada 10 segundos. A atualização do cursor e do contador é guardada num único `fsync` atómico por ciclo; a interface, os Toasts, a sobreposição e a tentativa de abrir o Home são encaminhados para a thread principal Android. O contador usa relógios monotónicos, não conta o período de ecrã desligado e divide a utilização à meia-noite em `Europe/Lisbon`. Há avisos aos 10, 5 e 1 minutos. A mudança de aplicação ou o fim do tempo podem ser reconhecidos com até cerca de 10 segundos de atraso.

## Diagnóstico rápido

Depois de autenticar a gestão familiar como Adulto, é possível iniciar uma sessão experimental de 2 ou 5 minutos para uma aplicação aprovada de Criança ou para a entrada Xbox configurada. O teste usa um contador separado, apresenta progresso na barra, emite o aviso de 1 minuto, regressa ao CleanYourTV ao terminar e não altera o saldo, extensões ou histórico diários. O teste de Criança só pode abrir aplicações já aprovadas; o de Xbox usa apenas a entrada HDMI configurada. Voltar cancela a preparação antes de se iniciar uma aplicação.

A duração mede apenas o tempo que o monitor observa a aplicação em primeiro plano com o ecrã ligado. Ao suspender ou reiniciar a TV, a sessão experimental é terminada/recuperada sem cobrar saldo diário. Estes testes confirmam os fluxos da aplicação, não demonstram que o firmware Xiaomi aceite efetivamente a abertura do Home sobre qualquer aplicação ou vídeo HDMI.

O estado de gestão distingue funcionalidade desligada, falta de Usage Access, dados ilegíveis, necessidade de rever o relógio e estado pronto. “Pronta” significa que a monitorização está preparada para lançamentos originados neste launcher; não significa bloqueio do Android inteiro nem controlo da entrada HDMI.

## Atualização após suspensão (v24)

O regresso ao launcher, o despertar e o botão **Atualizar** usam o mesmo caminho de contabilização. Atualizar não pede a sequência do Adulto. Quando os relógios de parede e monotónico concordam, o estado pendente é fechado automaticamente; a utilização é limitada pelo avanço de `elapsedRealtime()` e `uptimeMillis()`, pelo estado persistido de ecrã ligado e pela aplicação em primeiro plano. Assim, numa suspensão das 23:00 às 14:00, o período de suspensão não é cobrado e o saldo do novo dia é consultado sem alterar o histórico do dia anterior. Se não chegaram eventos de ecrã, o tempo acordado recuperável é atribuído ao fim do intervalo e ao dia atual, porque o Android não fornece informação suficiente para reconstruir o instante exato de cada utilização nesse período.

Uma alteração real ou ambígua do relógio, ou a reinicialização do dispositivo durante uma sessão, mantém a sessão pendente e Criança/Xbox pausados. **Atualizar** mostra um aviso curto, não autentica nem apaga dados; o Adulto tem de entrar no perfil e rever a hora. Os estados persistidos pela v22 não guardam a causa da incerteza, por isso uma instalação que já tenha esse indicador ativo não pode distingui-lo com segurança de uma alteração real do relógio e pode exigir esta revisão uma vez. Esta salvaguarda evita repor saldos ou horários com uma hora que não possa ser confirmada.

## Abertura da entrada Xbox

Ao escolher o perfil Xbox, a CleanYourTV valida a configuração e envia imediatamente uma intent `ACTION_VIEW` com a URI de passthrough criada por `TvContract.buildChannelUriForPassthroughInput()`. Não abre novamente o seletor de entradas. Se a intent padrão não tiver destino, pode tentar a ação `input_action` configurada para o fabricante, mantendo a mesma URI e a mesma entrada autorizada. A sessão e o monitor são iniciados antes do envio; uma falha síncrona de lançamento limpa a sessão pendente. Uma sessão já pendente impede outro início.

Os registos sem dados de destino podem ser consultados com `adb logcat -s FamilyXboxTiming`. `launcher_path_ms` mede o caminho no launcher até à aceitação do pedido; `launcher_dispatch_ms` mede a chamada síncrona a `startActivity`; `launcher_to_activity_pause_ms` mede o handoff da janela do launcher ao Android. Nenhum destes valores mede quando o primeiro fotograma HDMI aparece. `TvInputManager.getInputState()` expõe apenas os estados ligado, ligado em espera e desligado, não qual entrada está ativa. Por isso, a comutação do firmware tem de ser medida separadamente na TV (por exemplo, gravação de vídeo do instante em que se seleciona Xbox até aparecer a imagem); não pode ser inferida dos tempos do launcher.

## Barra de tempo sobre outras aplicações

Em **Gestão familiar**, depois de autenticar a sequência do Adulto, é possível mostrar/ocultar a barra, escolher o canto inferior esquerdo ou direito e escolher se apresenta o tempo utilizado, restante ou ambos. As opções ficam no ficheiro privado da Edição Família. Ocultar a barra só altera a apresentação: a contabilização e os limites continuam ativos.

Para desenhar sobre outras aplicações, o Android exige que o Adulto autorize **Apresentar sobre outras aplicações** nas Definições. A CleanYourTV abre o ecrã oficial de autorização e não contorna uma recusa. Sem autorização, a barra não aparece e os avisos de tempo usam as notificações Toast existentes. A autorização pode ser revogada nas Definições a qualquer momento.

A janela é criada apenas durante uma sessão Criança/Xbox iniciada pelo launcher e removida quando a sessão termina. É sem foco e sem toque, atualizada no mesmo ciclo de 10 segundos do monitor; não captura o comando nem os toques. Mostra consumo/limite e restante, com verde, amarelo (10 minutos), laranja (5 minutos) e vermelho (1 minuto). Os avisos de 10, 5 e 1 minuto são apresentados uma vez por perfil e dia. O Adulto nunca recebe a barra. A mensagem final distingue o tempo de televisão do tempo de jogo.

No Android 8.0/API 26 ou posterior, `TYPE_APPLICATION_OVERLAY` fica acima das janelas de aplicações, abaixo de janelas críticas do sistema, e requer autorização especial do utilizador. O sistema pode alterar a posição/visibilidade da janela. A barra usa uma única janela com `FLAG_NOT_FOCUSABLE` e `FLAG_NOT_TOUCHABLE`, com opacidade limitada para preservar a passagem de toque em versões recentes. A compatibilidade com a composição de vídeo da entrada HDMI — incluindo a Xiaomi Mi TV 4S — **não foi confirmada**; algumas TVs desenham o vídeo HDMI numa camada de hardware que pode ficar acima de overlays Android. A barra Xbox só poderá ser vista sobre HDMI se o firmware aceitar este tipo de janela; continua a não identificar, desligar nem bloquear a entrada física.

Quando o tempo acaba ou começa o período não permitido, o launcher tenta regressar ao Home. Android 9 não permite que uma aplicação comum force o fecho de outra aplicação. Se a firmware não aceitar o regresso automático, o vídeo pode continuar visível enquanto a sessão permanece pendente. Se o Android não confirmar o destino em 30 segundos, se o acesso for revogado ou se ocorrer um erro de armazenamento, a aplicação tenta regressar ao Home e bloqueia novos lançamentos pelo launcher até recuperação adulta.

Após reinício ou suspensão, o perfil Adulto não é restaurado automaticamente: é pedida nova seleção e o Adulto tem de introduzir a sequência. Se uma sessão tiver ficado sem estado fiável, Criança/Xbox ficam suspensos até o Adulto autenticar e rever a hora. Os dados já contabilizados não são removidos; um período em que o processo não conseguiu observar eventos pode ficar por contabilizar e não é estimado como tempo usado. A gestão familiar guarda estado no ficheiro privado da aplicação. Se o ficheiro familiar ficar ilegível, a gestão autenticada pela sequência pode guardar uma cópia privada e desativar apenas a Edição Família; as credenciais e as restantes definições do launcher permanecem intactas, e o Adulto pode configurar a Edição Família novamente.

## Limites do controlo

A sequência e os limites aplicam-se apenas a aberturas e sessões iniciadas através do CleanYourTV. Não bloqueiam Definições Android, outro launcher, outras aplicações, assistentes, comandos diretos ou mecanismos do fabricante. Não devem ser tratados como controlo parental do sistema Android inteiro.

O perfil Xbox acompanha apenas uma sessão iniciada pelo atalho do launcher. `TvInputManager` permite enumerar algumas entradas, mas Android TV 9 não fornece a uma aplicação comum uma forma portátil de identificar continuamente a entrada HDMI fisicamente ativa, impedir que o comando mude de entrada, cortar o sinal ou encerrar a Xbox. O tempo Xbox não limita sessões abertas diretamente pelo comando ou pelo sistema. Como complemento, configurar os [limites de tempo de utilização da Xbox](https://support.xbox.com/en-US/help/family-online-safety/online-safety/set-screen-time-limits) na conta familiar Microsoft e validar as opções disponíveis na conta e na consola.

## Validação na televisão

Antes de confiar nos limites, testar na Xiaomi Mi TV 4S com firmware PI.4663:

1. Se a instalação já tiver um PIN 2.4, introduzi-lo uma vez na migração; depois definir e confirmar uma sequência de 4–10 movimentos.
2. Conceder Usage Access nas Definições Android e ativar a Edição Família com a sequência do adulto.
3. Iniciar uma aplicação pelo launcher em Criança e confirmar que o tempo é contado e que o Home aparece ao chegar ao limite.
4. Testar regresso com Voltar, botão Home, suspensão/retoma, reinício, revogação do Usage Access e mudança de hora.
5. Confirmar separadamente o perfil Xbox e os atalhos HDMI. Não inferir que o sinal HDMI foi bloqueado.
6. Verificar que o adulto consegue sempre autenticar-se e recuperar uma sessão pendente.
7. Em Gestão familiar, executar um diagnóstico de 2 minutos numa aplicação aprovada e confirmar barra, aviso, retorno ao CleanYourTV e saldo diário inalterado; depois repetir com 5 minutos ou Xbox.
8. Alterar um dia no horário avançado, confirmar o bloqueio/abertura no horário escolhido, repor a predefinição e rever os sete dias de estatísticas.

Os testes JVM e de interface/emulador verificam política, persistência, foco e fluxos da aplicação; não provam que a firmware da TV aceite iniciar o Home a partir do serviço em segundo plano. Essa validação física continua pendente. O APK experimental não é instalado automaticamente; preservar um caminho de recuperação para voltar à versão estável.
