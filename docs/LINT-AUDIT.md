# Auditoria Android Lint — Edição Família

**Data:** 2026-10-26
**Ramo auditado:** `feature/family-profiles`
**Referência atual:** `d833e0e` (Edição Família v16)
**Alvo:** Xiaomi Mi TV 4S, Android TV 9 (API 28), interface PT-PT

## Resultado da execução

Executado com JDK 17, SDK 34 e Gradle Wrapper 8.9. A auditoria anterior em `c752486` encontrou 90 erros e 67 avisos. A verificação desta revisão v16 encontrou **90 erros e 68 avisos**:

```text
.\gradlew.bat --no-daemon :app:lintRelease
Lint 8.5.2: 90 errors, 68 warnings
Resultado: falhou por causa dos 90 erros MissingTranslation
```

O primeiro erro é `date_format` em `values/strings.xml:21`, sem tradução catalã. Não foram reportados outros erros no ramo Família. O aviso do SDK sobre um ficheiro XML de ferramentas versão 4, lido por uma ferramenta que conhece até à versão 3, apareceu durante a execução, mas não é uma categoria Android Lint e não está incluído nos totais.

Os relatórios HTML, XML e texto desta execução foram conservados junto dos artefactos locais da v16:

- Pasta local: `CleanYourTV-family-recovery-v16-v17-2026-10-26/lint/v16/`

### Comparação com `custom-2.4`

Foi executado o mesmo `:app:lintRelease` na worktree estável, sem alterar o código:

| Medida | `custom-2.4` | Família v16 | Diferença v16 vs. estável |
|---|---:|---:|---:|
| Erros | 92 | 90 | −2 |
| Avisos | 44 | 68 | +24 |
| `MissingTranslation` | 90 | 90 | mesmas 90 ocorrências |

Os 90 erros `MissingTranslation` são exatamente os mesmos na v16 e em `custom-2.4`; não foram introduzidos novos erros de Lint. A v16 não apresenta os dois erros herdados `CoarseFineLocation` e `ByteOrderMark`, nem o aviso herdado `UnusedTranslation`. Também não apresenta `DataExtractionRules`. Face à versão estável, há +22 `UnusedResources` (32 contra 10), +2 `PluralsCandidate` e +2 recomendações `GradleDependency`, compensados pelos avisos `DataExtractionRules` e `UnusedTranslation` ausentes: +24 avisos líquidos.

## Tabela completa por categoria

“Severidade / classe” separa a severidade reportada pelo Lint da classificação de impacto pedida para esta auditoria. **P1** é bloqueador de pipeline ou de segurança antes de distribuição mais ampla; **P2** merece correção planeada; **P3** pode aguardar ou ser aceite com a justificação indicada.

**Não foi identificado nenhum problema Crítico pelo Lint.** Nos caminhos da tabela, `src/...` é relativo a `launcher/app`; `app/build.gradle` corresponde a `launcher/app/build.gradle`.

| ID | Severidade / classe | Quantidade | Ficheiros e exemplos | Impacto real | Prioridade e recomendação |
|---|---|---:|---|---|---|
| `MissingTranslation` | Erro / Baixo no PT-PT | 90 | `src/main/res/values/strings.xml:21–80,106–137`; exemplos `date_format`, `vpn`, `parental_select_apps`, `context_protect` | PT-PT não é afetado. Alemão, catalão, espanhol, francês, sérvio, tailandês e chinês têm entradas incompletas e recorrem ao inglês. O Lint falha, portanto pipelines que exigem `lintRelease` verde ficam bloqueadas. | **P1 para CI/publicação multilingue.** Completar as traduções dos idiomas que o produto declara suportar; manter as strings e os argumentos. Não ocultar o problema com supressão global. |
| `DataExtractionRules` | Aviso / **Importante — resolvido** | 0 (era 1) | Manifesto: `android:allowBackup="false"`, `android:fullBackupContent="@xml/backup_rules_legacy"`, `android:dataExtractionRules="@xml/data_extraction_rules"`; regras em `src/main/res/xml/backup_rules_legacy.xml` e `data_extraction_rules.xml` | A configuração atual exclui os domínios privados de cloud e D2D no Android 12+ e fornece regras legadas para Android 9–11. O Lint já não reporta o aviso. A Xiaomi API 28 usa `allowBackup=false` e as regras de `fullBackupContent`. | **Fechado para esta build.** Em alguns fabricantes, transferências proprietárias fora do mecanismo Android podem não respeitar estas regras; isso não pode ser garantido sem teste no equipamento. A documentação Android descreve a exceção D2D [aqui](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore) e as regras [aqui](https://developer.android.com/identity/data/autobackup). |
| `DiscouragedApi` | Aviso / Baixo | 1 | `src/main/AndroidManifest.xml:53`, `screenOrientation="landscape"` | A activity principal força paisagem. É coerente com o launcher Leanback/TV; pode limitar tablets ou outras formas de ecrã, que não são o alvo desta edição. | **P3, aceitar para TV.** Reavaliar apenas se o projeto passar a suportar dispositivos móveis. |
| `GradleDependency` | Aviso / Baixo | 4 | `app/build.gradle:38–39` (`recyclerview:1.3.2`, `dynamicanimation:1.0.0`); `:43–44` (`androidx.test:runner:1.5.2`, `androidx.test.ext:junit:1.1.5`) | Há versões mais recentes, mas o aviso não identifica vulnerabilidade. As duas últimas dependências são exclusivamente de testes e não entram no APK release. | **P3.** Atualizar em tarefa separada, verificando compatibilidade com `compileSdk 34`, regressões e tamanho do APK; não atualizar apenas para silenciar Lint. |
| `IconLauncherShape` | Aviso / Baixo | 1 | `src/main/res/mipmap-xhdpi/ic_launcher.jpg` | O desenho ocupa quase todo o quadrado do ícone; efeito apenas visual na máscara do launcher. | **P3, aceitar ou ajustar na próxima revisão visual.** |
| `NotifyDataSetChanged` | Aviso / **Importante** | 3 | `src/main/java/com/gathin/tvhome/AppAdapter.java:175,282,293` | Três atualizações notificam a lista inteira. Em listas maiores pode haver trabalho de layout desnecessário ou maior custo de atualização; não existe evidência de atraso ou perda de foco na TV atual. | **P2 se houver jank; caso contrário P3.** Preferir notificações por intervalo/item ou `DiffUtil`, preservando seleção e foco D-pad; medir antes de refatorar. |
| `OldTargetApi` | Aviso / Baixo | 1 | `app/build.gradle:12`, `targetSdk 34` | O Lint instalado recomenda um target mais recente. Na Xiaomi API 28, esse aviso não ativa comportamento de Android mais recente; as mudanças dependentes do target só importam ao executar em versões Android novas. | **P3.** Manter 34 para esta edição e atualizar o target apenas com validação nos Android TV suportados, sobretudo serviço foreground e permissões especiais. |
| `Overdraw` | Aviso / Baixo | 2 | `src/main/res/layout/activity_main.xml:6`, `dream_clock.xml:6` | O layout pinta preto enquanto o tema já pinta fundo. É um desenho redundante, com custo gráfico pequeno numa interface estática. | **P3.** Remover um dos fundos apenas se uma inspeção visual confirmar que não muda o ecrã. |
| `PluralsCandidate` | Aviso / Baixo | 3 | `src/main/res/values/strings.xml:78,94,100`; `pin_locked`, `sequence_progress_accessibility`, `sequence_locked` | Um dos avisos é a mensagem de progresso acessível “1 de 10 movimentos”, onde “movimentos” continua corretamente no plural. As mensagens em inglês com “%d seconds” podem dizer “1 seconds”; PT-PT usa a forma compacta “%1$d s”. | **P3.** Usar `<plurals>` para a duração em segundos se a apresentação inglesa precisar de gramática singular/plural; manter a mensagem de progresso como está. |
| `RtlEnabled` | Aviso / **Importante para RTL** | 1 | `src/main/AndroidManifest.xml`, atributo `android:supportsRtl` ausente | O projeto contém recursos árabes e atributos RTL, mas não habilita explicitamente a direção RTL. Não afeta português de Portugal, que é LTR. | **P2 para idiomas RTL.** Definir `supportsRtl` e validar todos os ecrãs numa configuração RTL antes de declarar esse suporte completo. |
| `RtlHardcoded` | Aviso / **Importante para RTL** | 3 | `src/main/res/layout/activity_main.xml:56,124,139`, `gravity="left"` | Estes textos ficam alinhados à esquerda mesmo em RTL. Comentários no layout indicam que a decisão foi deliberada porque o launcher não ativa RTL; a limitação é coerente com o aviso `RtlEnabled`. | **P2 para idiomas RTL; Baixo no alvo PT-PT.** Ao habilitar RTL, trocar por `start`/`end` e rever o alinhamento em árabe. |
| `SmallSp` | Aviso / **Importante** | 1 | `src/main/res/layout/item_card_row.xml:76`, `@id/digest`, `10sp` | Texto secundário pequeno, potencialmente difícil de ler à distância numa TV. Não afeta a navegação por setas/OK, mas reduz legibilidade. | **P2.** Confirmar a distância de visualização na TV e aumentar para pelo menos 11–12sp se continuar legível no cartão. |
| `UnusedAttribute` | Aviso / Baixo | 15 | `AndroidManifest.xml:43` (`localeConfig`); 14 `fontVariationSettings` em `activity_main.xml`, `dialog_card_prompt.xml`, `dream_clock.xml`, `item_app.xml`, `item_card_row.xml`, `item_input.xml`, `popup_cards.xml` | `localeConfig` só é usado a partir da API 33 e é ignorado na TV API 28. `fontVariationSettings` só é ignorado nas APIs 26–27; **a API 28 da Xiaomi suporta-o**. O Lint compara com `minSdk 26`, não com este aparelho. | **P3, manter.** Não remover `fontVariationSettings` por causa deste aviso; conservar `localeConfig` para Android mais recente. |
| `UnusedResources` | Aviso / Baixo | 32 | `src/main/res/drawable/family_profile_tile_bg.xml`, `ic_family_adult.xml` e `src/main/res/values/strings.xml`; lista completa abaixo | Há dois drawables e 30 strings sem referência estática. 10 strings já eram avisos no `custom-2.4`; 20 strings e os dois drawables são adicionais na Edição Família. O release tem `shrinkResources true`, pelo que estes recursos não usados devem ser removidos do APK. | **P3.** Confirmar que não há lookup dinâmico antes de remover qualquer recurso. Não remover textos necessários aos fluxos de migração. |
| `VectorPath` | Aviso / Baixo | 1 | `src/main/res/drawable/ic_settings.xml:6`, caminho vetorial de 904 caracteres | Pode aumentar ligeiramente o custo de inflação do ícone de definições; não afeta a autorização parental nem o uso diário de forma observada. | **P3.** Aceitar ou simplificar após comparar visualmente em TV; rasterizar só se a qualidade e densidade continuarem adequadas. |

### Recursos assinalados como não usados

`family_profile_tile_bg`, `ic_family_adult`, `standby`, `cant_open`, `launcher_tools`, `order_apps`, `app_library`, `add_to_dock`, `remove_from_dock`, `on_home_dock`, `dock_full`, `pin_setup_title`, `pin_confirm_title`, `pin_change`, `pin_entry_help`, `pin_mismatch`, `pin_saved`, `pin_not_saved`, `pin_locked`, `pin_unlock_app`, `pin_manage_app`, `parental_select_help`, `pin_setup_required`, `context_protect`, `context_unprotect`, `app_protected`, `app_unprotected`, `family_pin_required`, `family_profile_adult_hint`, `family_profile_option`, `family_adult_auth`, `family_time_added`.

As 10 strings já assinaladas no `custom-2.4` são `standby`, `cant_open`, `launcher_tools`, `order_apps`, `app_library`, `add_to_dock`, `remove_from_dock`, `on_home_dock`, `dock_full` e `pin_setup_required`. As outras 20 strings e os dois drawables são diferenças novas do ramo Família; muitas strings são rótulos da UI PIN anterior ou estados substituídos pela sequência e pelos ecrãs familiares atuais.

## PT-PT e integridade dos recursos

Foi feita uma validação estrutural dos dois XML, incluindo chaves e placeholders:

- `values/strings.xml`: 289 recursos; `values-pt-rPT/strings.xml`: 289.
- Chaves em falta ou excedentárias em PT-PT: **0**; nomes duplicados: **0**.
- Argumentos formatados diferentes nas chaves comuns (`%1$d`, `%1$s`, etc.): **0**.
- XML PT-PT válido; as novas strings de sequência e da Edição Família estão traduzidas.
- Os 90 `MissingTranslation` não incluem PT-PT. A contagem por locale em falta é: Catalão 90; Alemão 88; Espanhol 89; Francês 89; Sérvio 82; Tailandês 89; Chinês 89; Árabe, Polaco, Russo e Turco 63 cada.
- Várias strings inglesas novas têm `tools:ignore="MissingTranslation"` individual. Não é uma supressão global: essas chaves estão traduzidas para PT-PT, mas os utilizadores dos restantes idiomas recebem inglês como fallback.

## Manifesto, `Config.java` e funções familiares

Os diagnósticos atuais do manifesto são `DiscouragedApi`, `RtlEnabled` e `UnusedAttribute`; `DataExtractionRules` foi resolvido pelas regras explícitas de backup. Não há diagnóstico atribuído a `Config.java`. Também não há resultado de Lint em `MainActivity.java`, `LauncherState.java`, `FamilySessionMonitorService.java`, `FamilyForegroundTracker.java`, `FamilyUsageAccess.java`, `FamilyTimeOverlay.java` ou nas políticas de autenticação e tempo.

Isto significa apenas que estes IDs do Lint não encontraram um padrão estático problemático nesses ficheiros; **não é uma validação funcional ou de segurança**:

| Área | O que conclui o Lint | Limite que exige validação funcional/física |
|---|---|---|
| PIN/sequência e operações administrativas | Nenhum aviso nas classes de autorização/configuração. | Lint não prova ausência de bypass, segurança da derivação, persistência de bloqueios ou sucesso de migração. |
| Monitor e contagem | Nenhum aviso nas classes do monitor e política de tempo. | Não verifica se Usage Access foi concedido, se o firmware encerra o processo, nem a reconciliação após suspensão/reinício/mudança de hora. |
| Sobreposição | Nenhum aviso em `FamilyTimeOverlay`. | `SYSTEM_ALERT_WINDOW` depende de autorização do adulto e do comportamento do firmware; Lint não confirma que a janela aparece sobre cada aplicação. |
| Limites e avisos | Nenhum diagnóstico estático específico. | Não valida cálculo por dia/janela nem o retorno ao Home quando o monitor é interrompido. |
| HDMI/Xbox | Nenhum aviso sobre entradas HDMI. | A aplicação só gere o acesso iniciado pelo launcher; não controla nem bloqueia a entrada HDMI física. |
| Comando/D-pad | Nenhum aviso em `DpadSequencePolicy` ou na lógica de autenticação. | `SmallSp` pode reduzir a legibilidade de texto auxiliar; o Lint não testa foco, repetição de teclas ou navegação real do comando. |

## Avaliação específica da Xiaomi Mi TV 4S

A aplicação declara `minSdk 26`, `targetSdk 34`; a Xiaomi Android TV 9 é API 28. `fontVariationSettings` está disponível nesse aparelho. `localeConfig` e o comportamento de backup D2D do Android 12 não estão disponíveis nele. A localização PT-PT tem cobertura completa de nomes e argumentos.

Não foi encontrado um aviso de Lint que indique bypass de sequência, falha direta de autenticação, erro de cálculo de tempo, falha de sobreposição ou incompatibilidade de comando na API 28. Os avisos de maior relevância quotidiana são a legibilidade de `10sp` e, caso as listas de aplicações sejam grandes, as notificações completas do `AppAdapter`; ambos são recomendações de qualidade, não falhas comprovadas.

Os riscos reais que o Lint não mede continuam a ser a revogação do Acesso à utilização/sobreposição nas Definições, decisões do firmware sobre processos em segundo plano e a impossibilidade de bloquear fisicamente HDMI. A documentação da Edição Família deve continuar a apresentar estes limites como tais.

## Resumo executivo

### Corrigir obrigatoriamente

1. **90 erros `MissingTranslation` para uma pipeline que exige Lint verde.** Traduzir os recursos que faltam nos idiomas que se pretende suportar; no uso PT-PT não há texto parental em falta.

### Já resolvido nesta build

- `DataExtractionRules`: mantido `allowBackup=false`; foram ligadas regras explícitas para cloud e D2D no Android 12+, mais `fullBackupContent` para Android 9–11. Lint já não emite este aviso. O firmware pode ter fluxos de migração próprios, fora das regras do Android.

### Corrigir posteriormente

- Rever `10sp` do texto secundário no cartão e medir legibilidade à distância.
- Avaliar atualização granular de `AppAdapter` se houver jank ou listas maiores.
- Completar plurais ingleses de segundos e harmonizar RTL se árabe for uma localização suportada.
- Revalidar `targetSdk` e dependências com uma matriz de dispositivos Android mais recentes.

### Aceitar justificadamente

- Orientação fixa em paisagem para launcher de TV.
- Atributos de locale e peso de fonte que são específicos de API; os de fonte funcionam na API 28.
- Overdraw de fundo, forma do ícone e path vetorial, sem impacto prático observado.
- Strings sem referência estática enquanto o release usa `shrinkResources true`, após confirmar que não existem lookups dinâmicos.

### Riscos reais no uso diário

Este relatório não encontrou falha Lint diretamente ligada à autenticação, mas **Lint não certifica os controlos parentais**. A monitorização pode ser limitada pela autorização de Acesso à utilização e pelo firmware; a sobreposição precisa de autorização própria; HDMI não pode ser bloqueado fisicamente pelo launcher. Estes são limites de plataforma/operação, não avisos Android Lint.

## Estado da árvore

A auditoria original foi read-only. Nesta atualização foram alterados apenas os metadados de versão release, os dois atributos de manifesto relativos a backup, os dois XML de regras e este documento. Não foram alterados código funcional, PIN, lógica de persistência, permissões ou configuração do Lint. Os relatórios gerados permanecem em `app/build`. Os testes e a instalação de atualização foram feitos apenas num emulador; nenhum APK foi instalado na TV.
