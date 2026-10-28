# CleanYourTV Familiar v22 — otimização de tamanho

Data: 2026-10-28. A otimização mantém `applicationId` `com.gathin.tvhome`, `versionCode` 22, `versionName` `2.8.9-family-experimental` e o certificado de assinatura já utilizado nas versões Família e Recovery.

## Alteração

O diff de APK mostrou crescimento entre v20 e v22 no DEX e na tabela de recursos, sem novos drawables ou dependências. A compressão ZIP máxima estimava poupança insuficiente. A alteração com ganho verificável foi subsetting do recurso local Outfit (`res/font/outfit.ttf`): foram removidos apenas glifos não usados pelas strings e pela interface do projeto. Os glifos utilizados, as tabelas de fonte variável e a faixa de peso foram preservados. O APK não recebe bibliotecas ou código adicional por causa disto.

A fonte passou de 110.884 para 99.488 bytes brutos. O APK final ficou 5.648 bytes abaixo da v22 original, já incluindo a pequena melhoria de foco do botão Atualizar. O foco permanece no botão após atualização manual. O timeout de espera do teste UI foi aumentado para acomodar o arranque mais lento do seletor no emulador; não afeta a aplicação distribuída.

| Artefacto | Bytes | Margem face a 409.600 |
| --- | ---: | ---: |
| Família v20 | 409.481 | 119 |
| Família v22 original (preservada) | 412.077 | -2.477 |
| Família v22 otimizada | 406.429 | 3.171 |

O APK otimizado está 1.571 bytes abaixo de 408.000. A margem medida localmente não substitui a execução real do GitHub Actions/Linux; a confirmação remota será verificada depois do push.

## Validação local

- JDK 17, Android SDK 34.
- `:app:testReleaseUnitTest`: 135 testes aprovados.
- `:app:connectedDebugAndroidTest`: 17 testes aprovados em `emulator-5554`, Android 14/API 34. Os testes instrumentados têm uma guarda para ignorar TVs físicas.
- `:app:assembleRelease` e `lintVitalRelease`: concluídos com sucesso.
- `:app:lintRelease`: ainda falha com 90 erros e 68 avisos existentes; o primeiro erro é `MissingTranslation` de `date_format` em Catalão. Não foram removidas traduções nem ocultados avisos.
- Assinatura v2 válida; certificado SHA-256 `e78cac3a9a5cb50ce96c419b8facca08e9f99bda3e45c9849656f550b78ee6a0`.
- Instalação em emulador sem desinstalação nem limpeza: v20 → v22 otimizada → Recovery v23. Os ficheiros privados sintéticos `launcher-state.properties` e `family-profiles.properties` mantiveram SHA-256 idêntico; o verificador de produção confirmou que o PIN legado de ensaio continua válido e as chaves do PIN, ordem do Home, apps protegidas, limites, saldos e barra temporal persistiram.
- `FamilyProfileStoreTest.longScreenOffAcrossNextDayPreservesBalancesAndDoesNotChargeSuspension` simula o ecrã suspenso perto das 23:00 e a retoma às 14:00 do dia seguinte: os minutos de suspensão não são cobrados, os saldos diários não se duplicam e o launcher deve regressar ao Home. `FamilyProfileUiTest.childCanExitToSelectorButHomeKeepsChildProfileActive` confirma que Home mantém Criança e que «Sair do perfil» regressa ao seletor.

O SDK local só contém imagens de sistema Android 34 e 37.1; não foi possível validar API 28 sem descarregar uma imagem adicional. Não foi instalado qualquer APK na Xiaomi Mi TV 4S. As simulações e o teste de emulador não substituem essa validação física.

## Artefactos

A v22 original permanece intacta. O APK otimizado foi guardado separadamente como `CleanYourTV-2.8.9-Family-Experimental-v22-optimized.apk` junto do Recovery v23 e dos relatórios na pasta de artefactos local. SHA-256: `034115e153057e98a274e192e92d25bb5f636754ee704289091344f346c45149`.
