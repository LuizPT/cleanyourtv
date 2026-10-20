# CleanYourTV 2.4 — Custom Edition

Personalização de **LuizPT** baseada no [CleanYourTV original, de Gathin23](https://github.com/Gathin23/cleanyourtv). O projeto original e os respetivos créditos são preservados; este fork mantém a licença MIT indicada em [`LICENSE`](LICENSE).

Esta edição destina-se a Android TV. Foi validada numa **Xiaomi Mi TV 4S com Android TV 9 (API 28)**. A compilação local requer **JDK 17** e **Android SDK Platform 34**.

## Funcionalidades

- **Organizar aplicações:** mantenha OK premido numa aplicação do ecrã inicial para a mover, ocultar, proteger com PIN ou abrir as informações da aplicação.
- **Biblioteca de aplicações:** consulte aplicações instaladas e os estados «No ecrã inicial», «Oculta» e «Protegida»; volte a adicionar aplicações ocultas ao ecrã inicial.
- **Preferências persistentes:** a ordem, as aplicações ocultas e as protegidas mantêm-se após reiniciar a TV.
- **Controlo parental:** configure na TV um PIN de 4 a 6 dígitos, sem PIN predefinido. O PIN é derivado com salt e as tentativas falhadas têm limitação progressiva.
- **Português de Portugal:** interface em português de Portugal, com inglês como alternativa.

## Compilar e instalar

Instale JDK 17, Android SDK Platform 34 e Android Platform Tools (ADB). Ative a depuração ADB na TV e ligue o computador à mesma rede, se usar ADB por Wi-Fi.

No Windows, a partir da raiz do repositório:

```powershell
cd launcher
.\gradlew.bat assembleRelease
$TvIp = "192.168.1.50" # substitua pelo endereço da sua TV
adb connect "${TvIp}:5555"
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

No macOS ou Linux, use `./gradlew assembleRelease` dentro de `launcher`, depois ligue-se à TV com `adb connect 192.168.1.50:5555` (substitua o endereço) e instale `app/build/outputs/apk/release/app-release.apk` com `adb install -r`.

Na TV, selecione CleanYourTV como aplicação de ecrã inicial. A instalação por cima de uma versão existente preserva os dados apenas se o `applicationId` e o certificado de assinatura forem os mesmos. Não são fornecidos APKs neste repositório. Consulte também a [documentação de resolução de problemas](docs/TROUBLESHOOTING.md).

## Testes

Na validação anterior desta versão, **54 testes automatizados passaram**. Não foram repetidos para esta atualização documental.

## Limitações do controlo parental

O PIN controla apenas as aberturas e ações de gestão efetuadas através do CleanYourTV. As Definições do Android, outro ecrã inicial, o Assistente, as notificações, as aplicações recentes ou uma aplicação protegida podem abrir aplicações sem passar pelo PIN. Este mecanismo não bloqueia o acesso ao sistema Android nem substitui os controlos parentais do dispositivo. Consulte a [documentação do controlo parental](docs/parental-controls.md).

## Créditos e licenças

- O CleanYourTV original, de Gathin23, e as contribuições preservadas neste fork são abrangidos pela licença [MIT](LICENSE).
- A fonte Outfit é disponibilizada ao abrigo da [SIL Open Font License](launcher/FONT-LICENSE-OFL.txt).
- As dependências AndroidX RecyclerView e DynamicAnimation são abrangidas pela licença Apache 2.0; consulte [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
