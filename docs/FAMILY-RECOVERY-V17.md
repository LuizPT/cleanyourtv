# Edição Família v16 e recuperação estável v17

## Artefactos e identidade

| APK | Código/nome | Tamanho local | SHA-256 |
|---|---|---:|---|
| Edição Família | `16` / `2.8.6-family-experimental` | 407 893 bytes | `B1E95A9BF39339252A508F73125F0156B4B387D27D14D925C1EE5C7B2CED1804` |
| Recuperação CleanYourTV 2.4 | `17` / `2.4-recovery-v17` | 305 605 bytes | `6062F21B515B26064086443F168E7221A5D6A8E052195D8A7B44E26A480972DE` |

Ambos usam `applicationId com.gathin.tvhome`, `minSdk 26`, `targetSdk 34` e o certificado SHA-256 `e78cac3a9a5cb50ce96c419b8facca08e9f99bda3e45c9849656f550b78ee6a0`. O mesmo certificado foi confirmado nos APKs estável v4, experimental v10, v15 e v16 existentes. A instalação sequencial no emulador também foi aceite pelo Package Manager sem desinstalação.

O Recovery v17 foi compilado na worktree isolada `recovery/stable-v17`, baseada exclusivamente em `custom-2.4` (`92e83f6`). A única diferença de código fonte é `versionCode 17` e `versionName 2.4-recovery-v17` em `launcher/app/build.gradle`. Os 46 conteúdos do APK fora do manifesto e dos metadados de assinatura são idênticos ao APK estável v4; não houve alteração funcional em `custom-2.4`.

## Verificação de dados e PIN

O estado parental partilhado continua em `files/launcher-state.properties`. A v16 mantém `pin_salt` e `pin_hash` para a recuperação e adiciona as chaves da sequência; a implementação 2.4 lê e grava o ficheiro como `Properties`, preservando chaves que não conhece. Os dados familiares ficam em `files/family-profiles.properties`, ficheiro que o código 2.4 não apaga nem reescreve.

Num AVD Android 14/API 34 foram instalados, por atualização `adb install -r`, a v16, o Recovery v17 e uma build temporária v18 da mesma Edição Família. O launcher 2.4 abriu em primeiro plano sem exceção fatal. Um conjunto de teste sintético, criado pela própria implementação de estado da v16, manteve os ficheiros privados ao longo da sequência. A classe `LauncherState` do commit estável validou o PIN legado, as aplicações protegidas e a ordem do Home a partir do estado recuperado; a classe da Edição Família validou a sequência D-pad e confirmou a presença do hash do PIN legado após o regresso à versão v18. O ficheiro familiar e os saldos sintéticos permaneceram intactos.

Este ensaio verifica a compatibilidade do formato e do Package Manager, mas não acedeu aos dados reais da Xiaomi nem substitui um teste físico. O PIN usado no ensaio foi fictício e não corresponde ao PIN do utilizador. Não foi executado qualquer comando ADB na TV.

## Procedimento de instalação e recuperação

1. Guardar os dois APKs e verificar `SHA256SUMS.txt` antes de copiar para o dispositivo de instalação.
2. Instalar o Recovery v17 sobre a v16 com `adb install -r CleanYourTV-2.4-Recovery-v17.apk`. Não desinstalar a aplicação, não usar `pm clear`/“Limpar dados” e não usar `adb install -d`.
3. Abrir o launcher estável. O PIN numérico e as preferências partilhadas devem continuar disponíveis. A recuperação 2.4 não implementa perfis Família, mas mantém o respetivo ficheiro local.
4. Para regressar à Edição Família depois da v17, instalar uma build Família com `versionCode 18` ou superior e o mesmo certificado. A v16 não pode substituir a v17 porque tem um código inferior. O v18 produzido para este ensaio foi temporário e não é um APK de instalação nem um lançamento publicado.

`android:allowBackup="false"` impede assumir `adb backup` como salvaguarda. As regras Android não garantem que ferramentas proprietárias de transferência do fabricante respeitem as mesmas exclusões; a recuperação aqui validada depende da atualização da mesma aplicação, identidade e assinatura, sem apagar os dados locais.

## Verificações de build

- JDK 17 e Android SDK 34.
- Edição Família v16: 132 testes JVM, 13 testes instrumentados no AVD Android 14, `lintVitalRelease` e `assembleRelease` passaram.
- `lintRelease` da Família reporta 90 erros `MissingTranslation` e 68 avisos; os 90 erros são exatamente os mesmos que na base estável. Ver [LINT-AUDIT.md](LINT-AUDIT.md). O relatório completo HTML, XML e texto está na pasta local de artefactos, em `lint/v16/`.
- A v16 mede 407 893 bytes localmente, 1 707 bytes abaixo do limite CI estrito de 409 600 bytes. O tamanho Linux só fica confirmado pelo workflow de GitHub Actions após push.
- Recovery v17: testes JVM e `assembleRelease` passaram; o APK mede 305 605 bytes e foi validado com `apksigner`.
- O ambiente tinha apenas AVDs API 34 e 37; não havia imagem API 28 disponível. A Xiaomi Mi TV 4S Android 9 requer validação física posterior.
