# BlackView TV

Monitor de câmeras IP para Android TV, com mosaico ao vivo, tela cheia e gravação contínua (DVR) opcional. Feito para câmeras Wi-Fi do ecossistema Yoosee/GWIPC que expõem RTSP depois que a senha "NVR" é configurada no firmware, sem depender do app Yoosee nem de servidores externos.

## Funcionalidades

- Mosaico 3×2 com até 5 câmeras e um bloco de relógio/status
- Tela cheia com áudio, navegação pelo controle remoto (D-pad)
- Reconexão automática com indicadores de estado
- DVR opcional (desligado por padrão):
  - grava em segundo plano, sem decodificar o vídeo (cópia direta H.265/H.264 + G.711 para AVI)
  - arquivos de 15, 30 ou 60 minutos alinhados ao relógio
  - destino no armazenamento interno ou em pendrive/HD USB
  - exclusão automática das gravações mais antigas (espaço livre mínimo ou limite total)
- Lista de gravações e reprodução contínua entre arquivos
- Tela de Ajustes pelo controle remoto: câmeras (IP, senha, ativa) e DVR

> Em TVs com *Low Power Standby* (Android 14), a rede dos apps é cortada no standby: o DVR só grava enquanto a TV está ligada. Para gravar 24 h, rode o app num aparelho sempre ligado (por exemplo, um celular Android antigo).

## Requisitos

- Android 8.0+ (minSdk 26); testado numa TCL 65C755 com Android 14
- Câmeras com RTSP em `rtsp://admin:<senha>@<ip>:554/onvif1` (transporte UDP)
- JDK 17 e Android SDK 34 para compilar

## Compilar e instalar

```bash
# Opcional: senha inicial das câmeras (não versionado)
echo "blackview.cameraPassword=SUA_SENHA" >> local.properties

gradle assembleDebug       # Gradle 8.5 (o projeto não inclui wrapper)
adb connect <ip-da-tv>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` também precisa de `sdk.dir=/caminho/para/android-sdk`.

## Uso pelo controle remoto

| Tecla | Ação |
|---|---|
| OK | Tela cheia (câmera) / Gravações (bloco do relógio) |
| Segurar OK no bloco do relógio | Ajustes |
| PLAY / MUTE | Liga/desliga o áudio da câmera selecionada |
| REC / vermelho | Liga/desliga o DVR da câmera selecionada |
| VOLTAR | Volta ao mosaico |

## Estrutura

```
app/src/main/java/br/com/blackview/tv/
├── MainActivity.kt         mosaico e tela cheia
├── SettingsActivity.kt     ajustes (câmeras e DVR)
├── RecordingsActivity.kt   lista de gravações
├── PlaybackActivity.kt     reprodução
├── data/                   configuração, armazenamento, gravações
├── dvr/                    serviço de gravação contínua
├── player/                 player ao vivo (LibVLC)
└── ui/                     componentes de tela
```

Histórico técnico detalhado (investigação dos protocolos, limitações das câmeras, decisões): [app_monitorar_cameras.md](app_monitorar_cameras.md).

## Tecnologias

Kotlin, Android Views, [LibVLC for Android](https://code.videolan.org/videolan/vlc-android) 3.6, Coroutines.
