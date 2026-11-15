# CleanYourTV Família v26 — investigação dos regressos e do relógio

Data: 2026-11-15. Esta nota regista a comparação da fonte v26 (`cc03d20`) com as regressões e correções validadas na AVD API 34. Não houve ligação ADB à Xiaomi Mi TV 4S.

## Diagnóstico

### Adulto regressava ao seletor

Na v26, `MainActivity.familyScreenReceiver` invalidava sempre a janela de reentrada do Adulto e alterava o perfil ativo ao receber `SCREEN_ON`/`SCREEN_OFF`, sem filtrar duplicados, eventos fora de ordem ou o estado interativo real. Isto torna um broadcast transitório suficiente para revogar a sessão.

Separadamente, `FamilySessionMonitorService.pollForeground()` podia chamar `returnHome()` antes de a política considerar o perfil: falta de acesso Usage Stats, falha ao ler `UsageEvents`, `mustReturnHome`, estado de relógio incerto ou erro de armazenamento. Na v26, `FamilyMonitorPolicy.decide()` também avaliava falta de Usage Stats e `Foreground.UNKNOWN` antes da exceção do Adulto sem limite. Assim, uma observação temporariamente indisponível podia expulsar o Adulto. Ao regressar ao launcher, `finishFamilyUsageSession()` conclui a sessão iniciada para lançar a aplicação e limpa `activeFamilyProfile`; um `SCREEN_ON` duplicado invalidava também a janela de reentrada de 15 minutos.

O retorno ao Home por estes caminhos é reproduzido em código/testes; o padrão exato de broadcasts e falhas de `UsageStatsManager` na Xiaomi permanece por confirmar. Um despertar verdadeiro continua a exigir autenticação. O estado persistido `mustReturnHome` não é, por si, a causa: iniciar um perfil novo limpa o valor antigo; depois de uma suspensão real, o bloqueio é intencional.

### Atualização do relógio após reinício

A v26 esperava que uma chamada posterior de observação (por exemplo, «Atualizar», retoma ou alteração de hora) voltasse a avaliar a janela de estabilidade de 15 segundos. Não havia callback de recuperação agendado enquanto o seletor permanecia aberto. Por isso, o relógio podia estar estável, mas o ecrã continuava a mostrar o estado antigo até novas interações.

## Correções na v28

- `FamilyScreenTransitionPolicy` aceita apenas transições compatíveis com o estado interativo e com o estado de suspensão persistido; ignora duplicados e eventos fora de ordem. Se um firmware emitir o broadcast imediatamente antes de o `PowerManager` refletir a mudança, Activity e serviço voltam a verificar uma vez após 250 ms.
- Activity e serviço mantêm o Adulto ativo perante `UsageStats` ausente ou `Foreground.UNKNOWN`. O serviço deixa de consultar foreground por quota quando a sessão é Adulto, mas continua a observar suspensão/retoma. Criança e Xbox mantêm o comportamento de falha fechada.
- Uma suspensão/retoma verdadeira marca novamente a necessidade de passar pelo launcher e autenticar o Adulto. O regresso voluntário ao Home continua a respeitar a janela atual de reentrada.
- A Activity agenda uma nova observação automática quando a recuperação do relógio for segura após a janela de estabilidade. Sessões ambíguas, erros de armazenamento e estado não reconciliado continuam a exigir revisão; não são apagados automaticamente.

## Evidência de regressão

Os testes de regressão foram executados primeiro contra a fonte v26 limpa e falharam nos casos que a correção pretende resolver:

| Teste na v26 | Resultado observado |
|---|---|
| `outOfOrderScreenOffWhileInteractiveDoesNotMarkWakeOrClearAdult` | Falhou: evento fora de ordem alterou o estado do Adulto. |
| `duplicateScreenOnReceiverDoesNotClearAdultOrInvalidateReentry` | Falhou: duplicado invalidou a reentrada. |
| `automaticClockRecoveryUpdatesTheSelectorWithoutRefreshClicks` | Falhou: o seletor não recuperou sozinho após 15 s. |
| `activityAndMonitorServiceKeepAdultSessionAfterThirtyMinutesWithoutUsageStats` | Falhou: o percurso Activity/serviço expulsou o Adulto após ausência de Usage Stats. |
| `adultUnknownForegroundAfterThirtySecondsMustRemainAllowed` | Falhou: política v26 devolveu `RETURN_HOME` em vez de `KEEP_RUNNING`. |

Na versão corrigida, a suite JVM terminou com **165 testes aprovados**. A suite instrumentada na AVD API 34 teve **25 testes executados sem falhas e 2 testes opt-in de atualização sintética omitidos**. Incluiu suspensão/retoma real do emulador, duplicados/ordem dos broadcasts, 30 minutos de sessão Adulto com Usage Stats indisponível, regresso voluntário ao Home e recuperação automática do relógio sem «Atualizar».

## Artefactos e limitações

- Família v28: `versionCode 28`, `versionName 2.9.2-family-experimental`, `com.gathin.tvhome`, SHA-256 `C48D4270CAC6E7B0C70FCCAC010F8B496FC26E928AE059BCA2C666019865E555`, **409705 bytes**.
- Recovery v29: criado numa worktree baseada no commit estável `92e83f6`; só `versionCode 29` e `versionName 2.4-recovery-v29` foram alterados. SHA-256 `E55802F588469BA85E5C46C32590BFED08485D6A5D3C3AC68E7D734D56CE7081`, **305605 bytes**.
- Ambos usam `applicationId com.gathin.tvhome`, suportam API 26+ (incluindo API 28) e têm o certificado SHA-256 `e78cac3a9a5cb50ce96c419b8facca08e9f99bda3e45c9849656f550b78ee6a0`, igual ao estável v4, Família v26 e Recovery v27.
- `lintVitalRelease` e `assembleRelease` passaram. O APK Família atual excede em 105 bytes o limite da CI de 409600; não foi feita redução artificial nem se afirma aprovação nessa verificação.
- Só havia uma AVD API 34 disponível para esta execução; não havia AVD API 28 nem Xiaomi acessível por ADB. O momento, a ordem e a duração dos broadcasts no firmware da Xiaomi e a ocorrência real de falha de Usage Stats continuam a exigir confirmação física. Nenhum APK foi instalado na televisão.
