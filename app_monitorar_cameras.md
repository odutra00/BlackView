Projeto BlackView TV
Histórico de investigação e especificação inicial

Objetivo: desenvolver um aplicativo próprio para Android TV que permita monitorar cinco câmeras IP Wi-Fi da marca Jortan, atualmente acessíveis pelo aplicativo Yoosee, sem depender obrigatoriamente dele.

Ambiente de desenvolvimento: Linux (Onix).

Televisão de destino: TCL 65C755, Android 14.

1. Objetivo do projeto

Desenvolver um aplicativo chamado provisoriamente BlackView TV, instalado diretamente na televisão TCL, para substituir o Yoosee na visualização das cinco câmeras.

Funcionalidades desejadas:

Visualização simultânea das cinco câmeras.
Mosaico de câmeras, preferencialmente em grade 3×2.
Visualização individual em tela cheia.
Seleção de câmeras pelo controle remoto da TV.
Reconexão automática quando uma câmera perder conexão.
Indicadores de disponibilidade e conexão.
Controle PTZ (movimentação horizontal e vertical), se possível.
Áudio bidirecional, caso os protocolos permitam.
Acesso local e remoto.
Não depender dos servidores comerciais do Yoosee, se houver alternativa tecnicamente viável.
Código aberto e possibilidade de manutenção própria.

A prioridade é descobrir como as câmeras realmente transmitem vídeo e como implementar uma solução independente.

2. Ambiente de hardware e software
2.1. Computador de desenvolvimento
Sistema operacional: Linux.
Ferramentas disponíveis: ADB, Nmap, Netcat, FFmpeg, FFprobe e tcpdump.
Desenvolvimento e testes realizados pelo terminal.
2.2. Televisão

Modelo: TCL 65C755.

Informações obtidas por ADB:

text
Firmware:
V8-0012T01-LF1V643.003175

Android:
14

Arquitetura:
armeabi-v7a


Recursos declarados pelo sistema:

text
feature:android.hardware.bluetooth
feature:android.hardware.bluetooth_le
feature:android.hardware.usb.host


Portanto, a televisão declara suporte a:

Bluetooth clássico.
Bluetooth Low Energy.
USB Host.

A compatibilidade efetiva com dispositivos USB e protocolos específicos ainda precisa ser testada.

A TV já está acessível por ADB via rede local.

3. Inventário das câmeras

São cinco câmeras IP Wi-Fi da marca Jortan, do tipo lâmpada, comercializadas para utilização com o aplicativo Yoosee.

A aparência e a marca são semelhantes, mas não há confirmação de que todas sejam exatamente do mesmo modelo ou plataforma interna.

O modelo JT-8177 apareceu como uma possível referência comercial, mas não deve ser considerado o modelo confirmado das cinco unidades.

3.1. Câmeras e endereços IP
Câmera	Firmware	Endereço IP
1	40.1.27	192.168.0.141
2	40.0.40	192.168.0.122
3	40.0.40	192.168.0.103
4	40.0.40	192.168.0.137
5	33.0.25	192.168.0.150

Observação: a primeira câmera foi inicialmente informada como 4.0.1.27, mas posteriormente corrigida para 40.1.27. Convém confirmar essa identificação.

Todas estão conectadas ao mesmo roteador Wi-Fi, na rede 192.168.0.0/24.

O usuário consegue visualizar todas remotamente pelo Yoosee, mesmo fora da rede local.

4. Investigação de protocolos

O objetivo é descobrir se as câmeras oferecem algum dos seguintes mecanismos:

RTSP.
ONVIF.
Protocolo proprietário Yoosee/P2P.
Outro protocolo de transmissão local.

Não está confirmado que RTSP ou ONVIF estejam efetivamente disponíveis.

4.1. Nmap na câmera 2

Foi realizado o seguinte teste:

bash
nmap -Pn -sT -sV -p 80,443,554,8000,8080,8899,34567 \
192.168.0.122


Resultado:

text
PORT      STATE  SERVICE
80/tcp    closed http
443/tcp   closed https
554/tcp   open   rtsp?
8000/tcp  closed http-alt
8080/tcp  closed http-proxy
8899/tcp  closed ospf-lite
34567/tcp closed dhanalakshmi


A câmera responde e a porta TCP 554 está aberta.

O nome de rede apresentado pelo Nmap foi:

text
GWIPC-XXXXXXXXXX


Esse nome pode ser uma pista para identificar a plataforma ou fabricante OEM, mas ainda não há confirmação.

4.2. Investigação das portas ONVIF

Foi executado:

bash
nmap -Pn -sT -sV -p 5000,5001,554 \
192.168.0.122


Resultado:

text
5000/tcp closed upnp
5001/tcp closed commplex-link
554/tcp open rtsp?


As portas 5000 e 5001 estão fechadas.

Isso não prova que ONVIF esteja indisponível. O serviço pode utilizar outra porta ou estar desativado.

O usuário já habilitou ONVIF anteriormente para tentar utilizar as câmeras no aplicativo Vista das Câmeras, mas não conseguiu fazê-las funcionar. Atualmente não localiza a opção para habilitar ONVIF no Yoosee.

Não sabemos se existe uma senha específica para ONVIF.

4.3. Testes RTSP

Foi testado:

bash
ffprobe -rtsp_transport tcp \
-v error \
-show_streams \
"rtsp://192.168.0.122:554/onvif1"


Resultado:

text
[rtsp] method OPTIONS failed: 405 Method Not Allowed
Server returned 4XX Client Error


Também foi testado:

bash
ffprobe -rtsp_transport tcp \
-v error -show_streams \
"rtsp://192.168.0.122:554/onvif2"


Resultado semelhante: HTTP/RTSP 405 Method Not Allowed.

4.4. Testes RTSP manuais

Foi enviado:

bash
printf 'OPTIONS rtsp://192.168.0.122:554/ RTSP/1.0\r\nCSeq: 1\r\n\r\n' \
| nc -v 192.168.0.122 554


Resposta:

text
RTSP/1.0 405 Method Not Allowed
CSeq: 1
Allow: OPTIONS, DESCRIBE, SETUP, TEARDOWN,
PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER,USER_CMD_SET


Também foram enviados comandos DESCRIBE:

bash
printf 'DESCRIBE rtsp://192.168.0.122:554/onvif1 RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n\r\n' \
| nc -v 192.168.0.122 554


E:

bash
printf 'DESCRIBE rtsp://192.168.0.122:554/onvif2 RTSP/1.0\r\nCSeq: 3\r\nAccept: application/sdp\r\n\r\n' \
| nc -v 192.168.0.122 554


Ambos retornaram:

text
RTSP/1.0 405 Method Not Allowed
Allow: OPTIONS, DESCRIBE, SETUP, TEARDOWN,
PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER,USER_CMD_SET

Interpretação atual

A porta 554 contém um serviço que responde com mensagens no formato RTSP e anuncia métodos convencionais, além de USER_CMD_SET.

Entretanto, os métodos OPTIONS e DESCRIBE foram recusados com 405.

Ainda não foi obtido um SDP nem um fluxo de vídeo.

Não concluir que o serviço seja um servidor RTSP convencional funcional.

Não assumir que USER_CMD_SET corresponda necessariamente ao controle PTZ. Seu significado ainda precisa ser investigado.

5. Aplicativo Yoosee e acesso remoto

As cinco câmeras funcionam no aplicativo Yoosee.

O usuário consegue visualizá-las remotamente, fora da rede Wi-Fi doméstica.

É possível que o Yoosee utilize comunicação P2P proprietária, servidores de sinalização ou retransmissão.

Ainda não sabemos se:

O vídeo é transmitido diretamente entre celular e câmera.
Existe um serviço P2P intermediário.
O vídeo utiliza uma conexão local proprietária.
O Yoosee mantém uma conexão direta ou utiliza servidores externos para retransmissão.

O usuário já tentou configurar ONVIF para utilizar o aplicativo Vista das Câmeras, mas não obteve sucesso.

6. Investigação de tráfego de rede

Foi tentada uma captura pelo computador Linux:

bash
sudo tcpdump -i any -nn host 192.168.0.122 \
-w yoosee-camera2.pcap


Resultado:

text
1 packet captured
1 packet received by filter
0 packets dropped by kernel


A captura foi praticamente vazia.

Contexto:

O roteador é o ponto de acesso Wi-Fi.
Celular e câmeras estão na mesma rede.
O computador não possui uma placa Wi-Fi com monitor mode disponível.
O tráfego entre dispositivos Wi-Fi pode ser comutado internamente pelo roteador e não passar pelo computador.

O usuário utiliza um celular Android para executar o Yoosee.

Foi sugerido utilizar PCAPdroid no Android para observar as conexões do aplicativo, mas ainda não foi executado.

Outra possibilidade é capturar diretamente no roteador, caso suporte tcpdump, SSH ou port mirroring.

7. Estratégia proposta para investigar a comunicação

Priorizar uma investigação não destrutiva.

Sugestão de sequência:

Identificar o modelo e a plataforma interna das câmeras.
Investigar se o firmware permite RTSP convencional.
Descobrir se ONVIF está disponível e qual sua porta.
Observar o tráfego gerado pelo Yoosee no Android.
Identificar conexões locais e externas.
Verificar a possibilidade de utilizar uma integração comunitária existente.
Avaliar o protocolo P2P somente se não houver alternativa mais simples.

Não atualizar ou modificar os firmwares das câmeras.

Não desativar mecanismos de segurança nem expor portas das câmeras diretamente na internet.

8. Arquitetura desejada para o BlackView TV

Uma arquitetura possível:

text
       CINCO CÂMERAS JORTAN
                |
          Wi-Fi local
                |
       +----------------+
       | Gateway Linux  |
       | (se necessário)|
       +----------------+
                |
          Rede local
                |
       +----------------+
       | BlackView TV   |
       | Android 14     |
       | Kotlin         |
       | Media3         |
       +----------------+
                |
          TCL 65C755


O gateway Linux é opcional e dependerá dos protocolos descobertos.

Acesso remoto

O usuário deseja manter o acesso remoto.

Uma alternativa preferida é:

WireGuard para acesso remoto seguro.
Vídeo diretamente das câmeras pela rede local.
Nenhuma exposição direta das portas das câmeras à internet.
Possibilidade de gateway Linux para intermediar os fluxos.

É importante considerar largura de banda e capacidade de decodificação ao reproduzir cinco fluxos simultaneamente.

9. Tecnologias consideradas para o aplicativo
Kotlin.
Android Studio.
Jetpack Compose para interface.
Android Media3/ExoPlayer para reprodução.
RTSP, caso seja disponibilizado.
ONVIF, caso seja suportado.
WireGuard para acesso remoto.
Possível gateway Linux com FFmpeg ou GStreamer.

A interface deve ser desenvolvida para Android TV, com navegação pelo controle remoto e foco em estabilidade.

10. Interface desejada

Nome provisório: BlackView TV.

Tela principal:

Mosaico 3×2.
Cinco câmeras.
Sexto espaço para relógio, informações de rede ou status.
Identificação de cada câmera.
Indicadores de câmera online/offline.
Seleção individual.
Tela cheia.
Reconexão automática.

Recursos adicionais desejáveis:

PTZ.
Áudio.
Presets.
Alternância entre mosaico e visualização individual.
Inicialização automática com a TV.
11. Contexto adicional

O usuário utiliza Linux e prefere desenvolver e administrar tudo por terminal.

Não deseja depender de Windows.

O usuário também possui dois projetos antigos de hardware de eletrocardiografia:

BlackHEART: placa ECG com comunicação USB OTG.
BlueHeart: placa ECG com Bluetooth 4.1.

Esses projetos não são relacionados diretamente às câmeras, mas demonstram interesse em hardware, comunicação entre dispositivos e integração com Android.

12. Próximas tarefas prioritárias

A próxima IA que assumir o projeto deve continuar a partir destas tarefas:

Prioridade 1 — Descobrir o protocolo real da câmera

Investigar a plataforma GWIPC.
Analisar o comportamento do serviço da porta 554.
Procurar documentação e integrações comunitárias para Yoosee/GWIPC.
Verificar se RTSP e ONVIF podem ser habilitados no firmware atual.

Prioridade 2 — Capturar tráfego do Yoosee

Avaliar PCAPdroid no Android.
Verificar se o roteador oferece captura de pacotes.
Identificar conexões locais e externas durante a visualização de uma câmera.

Prioridade 3 — Fazer uma prova de conceito de vídeo

Antes de desenvolver a interface completa, conseguir reproduzir uma câmera no Linux.

Depois repetir os testes nas outras quatro câmeras.

Prioridade 4 — Desenvolver o BlackView TV

Somente depois de confirmar o protocolo e a forma de autenticação, iniciar a implementação Android.

13. Restrições e cuidados
Não presumir que todas as câmeras tenham o mesmo firmware.
Não presumir que o modelo comercial JT-8177 corresponda às cinco unidades.
Não assumir que a porta 554 aberta significa RTSP convencional funcional.
Não assumir que ONVIF esteja disponível.
Não modificar firmware.
Não remover configurações do Yoosee que possam comprometer o funcionamento atual.
Não expor as câmeras diretamente à internet.
Preservar o funcionamento atual do Yoosee enquanto o BlackView TV não estiver operacional.

Estado atual do projeto: investigação de protocolos e conectividade. Nenhum fluxo de vídeo independente do Yoosee foi confirmado até o momento.

Princípio de desenvolvimento: primeiro descobrir e validar a comunicação real com as câmeras; depois implementar o aplicativo.


--------------------------------------------------------




ADENDO 1 — BlackView TV
Descoberta e validação de RTSP em três câmeras

Data: 02/11/2026

Status: Três câmeras com acesso RTSP independente confirmado.

1. Resumo da descoberta

Foi descoberto que três das cinco câmeras Jortan disponibilizam acesso RTSP independente do aplicativo Yoosee.

As três câmeras possuem configuração de senha para conexão NVR no próprio firmware.

Após configurar essa senha, foi possível acessar os fluxos de vídeo diretamente pela rede local, utilizando FFprobe e FFplay no Linux.

Câmeras confirmadas:

Câmera 1: 192.168.0.141
Câmera 3: 192.168.0.103
Câmera 5: 192.168.0.150

As câmeras 2 e 4 não apresentam atualmente a opção de configuração NVR conhecida. Elas ficam fora do escopo inicial de desenvolvimento.

Esta descoberta confirma a viabilidade de desenvolver o BlackView TV utilizando RTSP local.

2. Inventário atualizado
Câmera	Firmware	IP	Configuração NVR
1	40.1.27	192.168.0.141	Disponível
2	40.0.40	192.168.0.122	Não disponível
3	40.0.40	192.168.0.103	Disponível
4	40.0.40	192.168.0.137	Não disponível
5	33.0.25	192.168.0.150	Disponível

As três câmeras com NVR configurável foram testadas com sucesso.

As câmeras 2 e 4 continuam funcionando pelo Yoosee, mas não fazem parte da primeira versão do BlackView TV.

3. Configuração de autenticação

Nas três câmeras confirmadas, foi configurada uma senha para conexão NVR.

A interface do firmware solicita a senha, mas não oferece campo para criação de usuário.

O acesso RTSP foi realizado utilizando:

text
Usuário: admin
Senha: <SENHA_NVR> (senha de teste usada no desenvolvimento; omitida)


Essa senha foi configurada nas três câmeras para facilitar os testes.

Importante: a senha acima é uma credencial temporária de desenvolvimento. Antes de colocar o sistema em uso definitivo, recomenda-se substituí-la por uma senha forte e exclusiva.

Não armazenar credenciais diretamente no código-fonte ou em repositórios públicos.

4. Parâmetros RTSP

Configuração identificada:

Parâmetro	Valor
Protocolo	RTSP
Porta	554
Caminho principal	/onvif1
Usuário	admin
Autenticação	HTTP Digest
Transporte funcional	UDP
Credencial	Senha NVR

URL de teste:

text
rtsp://admin:SENHA@IP:554/onvif1


Para a senha de teste atual:

text
rtsp://admin:<SENHA_NVR>@192.168.0.103:554/onvif1


Atenção: a confirmação detalhada de codecs e características de áudio/vídeo foi realizada na câmera 3. Não presumir que todos os parâmetros sejam idênticos nas outras duas sem executar FFprobe individualmente.

5. Câmera 3 — 192.168.0.103

Firmware: 40.0.40

Foi a primeira câmera em que o acesso RTSP independente foi descoberto e validado.

Autenticação

Resposta sem credenciais:

text
RTSP/1.0 401 Unauthorized
WWW-Authenticate: Digest realm="HIipCamera",nonce="..."


Realm identificado:

text
HIipCamera

Características do vídeo
Propriedade	Valor
Codec	HEVC / H.265
Perfil	Main
Resolução	1920 × 1080
Proporção	16:9
Pixel format	yuv420p
Frame rate nominal	10 fps
Frame rate médio informado	15 fps
Características do áudio
Propriedade	Valor
Codec	PCM A-law
Formato	G.711 A-law
Sample rate	16.000 Hz
Canais	Mono
Bits por amostra	8
Reprodução

Confirmada com sucesso utilizando FFplay:

bash
ffplay -rtsp_transport udp \
"rtsp://admin:SENHA@192.168.0.103:554/onvif1"

6. Câmera 1 — 192.168.0.141

Firmware: 40.1.27

A câmera possui configuração NVR e foi testada com sucesso utilizando a mesma abordagem RTSP.

Status:

Configuração NVR disponível.
Senha NVR configurada.
Acesso RTSP independente confirmado.
Reprodução de vídeo confirmada.

Ainda é necessário executar FFprobe para documentar individualmente resolução, codecs, frame rate e características do áudio.

7. Câmera 5 — 192.168.0.150

Firmware: 33.0.25

A câmera também possui configuração NVR e foi testada com sucesso utilizando a mesma abordagem RTSP.

Status:

Configuração NVR disponível.
Senha NVR configurada.
Acesso RTSP independente confirmado.
Reprodução de vídeo confirmada.

Ainda é necessário executar FFprobe para documentar individualmente resolução, codecs, frame rate e características do áudio.

8. Câmeras fora do escopo inicial

As câmeras abaixo não apresentam a opção de configuração NVR conhecida:

Câmera	IP	Firmware
2	192.168.0.122	40.0.40
4	192.168.0.137	40.0.40

A câmera 2 apresentou anteriormente respostas 405 Method Not Allowed aos métodos OPTIONS e DESCRIBE, mesmo com a porta 554 aberta.

Não foi confirmado um fluxo RTSP funcional nessas duas câmeras.

Decisão de projeto: não investir tempo na engenharia reversa das câmeras 2 e 4 neste momento. Concentrar os esforços nas três unidades com NVR funcional.

9. Escopo atualizado do BlackView TV

Uma decisão importante foi tomada: a primeira versão do aplicativo será exclusivamente para acesso pela rede local.

Não será implementado acesso remoto nesta etapa.

Isso elimina a necessidade inicial de:

WireGuard.
Gateway externo.
Servidor intermediário.
Comunicação com servidores Yoosee.
Configuração de acesso pela internet.

A arquitetura será mais simples:

text
     CÂMERA 1
  192.168.0.141
        |
        |
     CÂMERA 3
  192.168.0.103
        |
        |
     CÂMERA 5
  192.168.0.150
        |
        |
    ROTEADOR
  TP-Link Archer C60
        |
        |
   Wi-Fi / LAN
        |
        |
   TCL 65C755
    Android 14
        |
        |
  BLACKVIEW TV


A TV acessará diretamente os três fluxos RTSP pela rede local.

10. Características desejadas do aplicativo

Primeira versão:

Três câmeras simultâneas.
Layout de mosaico.
Visualização individual em tela cheia.
Seleção pelo controle remoto.
Identificação de cada câmera.
Indicadores de conexão.
Reconexão automática.
Reprodução de áudio, se desejado.
Inicialização simples e rápida.

A interface deve ser otimizada para Android TV.

A arquitetura deve permitir adicionar as outras duas câmeras posteriormente, caso seja possível habilitar NVR nelas.

11. Tecnologias consideradas
Kotlin.
Android Studio.
Jetpack Compose.
Android Media3/ExoPlayer.
RTSP.
Autenticação Digest.
Transporte UDP.
Decodificação H.265.

É necessário validar a compatibilidade do Media3 com o RTSP específico dessas câmeras e com o decodificador de hardware da TCL.

Se necessário, avaliar uma implementação alternativa de reprodução ou gateway local.

12. Próximas tarefas
Executar FFprobe individualmente nas câmeras 1 e 5.
Registrar codecs, resolução, áudio e frame rate.
Testar reprodução simultânea das três câmeras no Linux.
Avaliar consumo de CPU, memória e largura de banda.
Verificar a capacidade da TCL de decodificar três fluxos H.265 simultaneamente.
Desenvolver a primeira versão do BlackView TV para Android TV.
Implementar o mosaico e os controles pelo controle remoto.
13. Estado atualizado
Item	Situação
Câmera 1 — RTSP	Confirmado
Câmera 3 — RTSP	Confirmado
Câmera 5 — RTSP	Confirmado
Autenticação NVR	Confirmada nas três
Vídeo independente do Yoosee	Confirmado
Reprodução no Linux	Confirmada nas três
Reprodução simultânea	Pendente
Compatibilidade com TCL	Pendente
Aplicativo Android TV	Pendente
Câmeras 2 e 4	Fora do escopo inicial
Acesso remoto	Fora do escopo inicial

Conclusão: o BlackView TV já possui três fontes de vídeo independentes do Yoosee comprovadas. A primeira versão será um monitor local para Android TV, com três câmeras e arquitetura simplificada.


--------------------------------------------------------

ADENDO 2 — BlackView TV
Implementação e Validação do Aplicativo Android TV

Data: 11/11/2026

Status: Aplicativo BlackView TV implementado, compilado e validado com sucesso na televisão TCL 65C755 (Android 14), com streaming simultâneo das câmeras ativas via RTSP UDP H.265 por aceleração de hardware.

1. Resumo da Implementação

Foi desenvolvido o projeto Android nativo completo em Kotlin para Android TV, localizado em na raiz deste repositório:
- Identificador do aplicativo: `br.com.blackview.tv`
- Nome de exibição: BlackView TV
- Alvo: Android TV (Android 14, armeabi-v7a / arm64-v8a)
- Motor de reprodução: LibVLC Android (`org.videolan.android:libvlc-all:3.6.2`)

2. Infraestrutura de Desenvolvimento Configurada

No ambiente Linux local:
- Java: OpenJDK 17 (`/usr/bin/java`)
- Android SDK: instalado localmente em `android-sdk/` com plataforma `android-34` e `build-tools 34.0.0`
- Gradle: versão 8.5 instalada em `gradle-8.5/`
- Conexão ADB: estabelecida com sucesso com a TV TCL (`192.168.0.166:5555`)
- Builds do projeto podem ser executados de forma reprodutível via terminal com:
  `ANDROID_USER_HOME=/tmp/.android ./gradle-8.5/bin/gradle assembleDebug --offline`

3. Arquitetura do Aplicativo

- `CameraConfig.kt` / `CameraRepository.kt`: modelo de dados e repositório com persistência em SharedPreferences para credenciais e IPs das cinco câmeras.
- `CameraPlayer.kt`: gerenciador de playback baseado em LibVLC com opções otimizadas para câmeras Jortan:
  * Forçar transporte UDP (`--rtsp-tcp=0`)
  * Baixa latência de buffer (`--network-caching=300`)
  * Decodificação por hardware via MediaCodec (`--codec=mediacodec_ndk,mediacodec_jni,all`)
  * Reconexão automática em segundo plano com backoff progressivo caso a conexão caia.
- `CameraTileView.kt`: célula individual do mosaico com animação de foco pelo D-pad, badges de status ("AO VIVO", "CONECTANDO", "RECONECTANDO", "OFFLINE", "DESATIVADA") e controle de áudio.
- `MainActivity.kt`: tela principal com mosaico 3×2, expansão em tela cheia com OSD informativo, retorno ágil via tecla BACK, relógio em tempo real e atalhos de controle remoto.

4. Resultados da Validação na TCL 65C755

- O APK de depuração (`app-debug.apk`) foi instalado na televisão TCL 65C755 (SoC MediaTek Pentonic 700 / MT5896).
- Câmera 1 (192.168.0.141) e Câmera 3 (192.168.0.103) conectaram-se simultaneamente no mosaico com decodificação por hardware nativa ativa (`c2.mtk.hevc.decoder`) a 1080p 15fps.
- As Câmeras 2 e 4 permanecem configuradas no código com status "Desativada / NVR Pendente" sem consumir recursos de decodificação.
- A Câmera 5 (192.168.0.150) demonstrou o mecanismo de reconexão automática funcionando perfeitamente enquanto desenergizada/offline.
- Navegação pelo controle remoto testada e validada pelo usuário no ambiente real.

5. Tarefas e Backlog para as Próximas Sessões

- [x] Adicionar icone ao aplicativo presente na pasta iconesblackview
- [ ] Correção do buffer residual de tela cheia: adicionado bloqueio de quadro prévio (scrim preto transitório em `fullscreenBlackScrim`) para evitar que o frame da câmera anterior apareça brevemente ao abrir uma nova câmera em tela cheia.
- [ ] Otimização de latência: refinar parâmetros de buffer de início do RTSP UDP para diminuir o tempo de chaveamento entre o mosaico e a tela cheia.
- [ ] Gravações no cartão micro-SD: investigar se o firmware GWIPC das câmeras expõe os arquivos de vídeo gravados no cartão micro-SD via RTSP (ex.: caminhos como `/onvif2` ou parâmetros de replay ONVIF), HTTP/CGI ou servidor web embutido.
- [ ] Interface gráfica de ajustes: adicionar diálogo ou tela na TV para alterar IPs, senhas e portas das câmeras diretamente pelo controle remoto sem precisar recompilar.
- [ ] Investigação das Câmeras 2 e 4: averiguar se atualizações de firmware ou métodos alternativos permitem expor NVR/RTSP nessas duas unidades.


--------------------------------------------------------

ADENDO 3 — Investigação do Acesso às Gravações do Cartão Micro-SD

Data: 18/11/2026

Status: Mapeamento detalhado de portas, protocolos e serviços ONVIF/RTSP/P2P concluído nas câmeras ativas. Identificado como as gravações são gerenciadas pelo firmware e definidas as abordagens técnicas para a próxima sessão.

1. Varredura de Portas nas Câmeras Ativas (Câmera 1 e Câmera 3)

Foi realizada varredura abrangente de portas nas câmeras 192.168.0.103 (Câmera 3) e 192.168.0.141 (Câmera 1):
- Portas TCP abertas:
  * 554/tcp: Servidor RTSP.
  * 5000/tcp: Serviço ONVIF (Device, Media, PTZ, Events).
  * 50000/tcp: Porta binária do protocolo proprietário Gwelltimes/Yoosee.
  * Portas HTTP comuns (80, 8080, 8000, 443) encontram-se fechadas (não há servidor web HTTP convencional para download direto de arquivos do cartão SD).
- Portas UDP abertas:
  * 3702/udp: WS-Discovery ONVIF.
  * 8899/udp: Broadcast e descoberta de dispositivos Gwelltimes / Xiongmai.

2. Avaliação dos Serviços ONVIF (Porta 5000)

Foram executadas requisições SOAP diretas ao serviço ONVIF em `http://192.168.0.103:5000/onvif/device_service`:
- A resposta `GetCapabilities` e `GetServices` retornou com sucesso (Status 200).
- Perfis de mídia identificados:
  * `IPCProfilesToken0`: MainStream (`rtsp://192.168.0.103:554/onvif1` — 1080p H.265 15fps).
  * `IPCProfilesToken1`: SubStream (`rtsp://192.168.0.103:554/onvif2` — 640x360 H.265 15fps).
- Conclusão sobre ONVIF Profile G: A câmera não implementa os serviços padronizados de gravação do ONVIF (`RecordingService`, `SearchService` e `ReplayService`). Portanto, o acesso às gravações locais do cartão SD não está exposto via padrão ONVIF.

3. Avaliação do Servidor RTSP (Porta 554)

Foi realizada sondagem de diversos caminhos RTSP comuns de gravação e VOD (`/playback`, `/replay`, `/record`, `/sdcard`, `/vod`, `/11`, `/12`):
- Todos os caminhos de replay direto retornaram `400 Bad Request`.
- O servidor RTSP da câmera atua exclusivamente como difusor dos fluxos ao vivo (`/onvif1` e `/onvif2`).

4. Funcionamento Real do Cartão SD (Protocolo Gwelltimes P2P)

A investigação técnica revelou o mecanismo utilizado pelo aplicativo Yoosee e pelo software CMSClient para gerenciar o cartão micro-SD:
- O armazenamento e a linha do tempo de gravações são gerenciados internamente pelo firmware através do protocolo binário proprietário Gwelltimes (`p2p-core`), que utiliza a porta 50000 (TCP) e transporte UDP.
- Nesse protocolo:
  * A consulta ao índice de arquivos de gravação salvos no cartão SD é disparada pelo comando `getRecordFiles(deviceId, password)`.
  * O streaming do trecho selecionado para reprodução remota é iniciado pelo comando `playbackConnect(deviceId, password, filename, position, ...)`.
  * O Device ID de cada câmera corresponde ao número identificado em seu hostname de rede (ex.: para a Câmera 3 `GWIPC-XXXXXXXXXX`, o ID é `XXXXXXXXXX`; para a Câmera 1 `GWIPC-XXXXXXXXXX`, o ID é `XXXXXXXXXX`).

5. Estratégias Definidas para Implementação na Próxima Sessão

Para habilitar a reprodução e visualização de gravações no BlackView TV, foram mapeadas três abordagens técnicas viáveis:

- Abordagem A (Integração Direta do SDK Gwell / P2P-Core):
  Integrar a biblioteca nativa do ecossistema Gwell (`p2p-core` / `libp2p-core.so`) no projeto Android do BlackView TV para consultar diretamente a lista de arquivos do micro-SD via LAN (`getRecordFiles`) e alimentar a tela de reprodução.
- Abordagem B (Gravação Contínua Local / NVR Próprio):
  Como o BlackView TV já recebe os fluxos RTSP H.265 1080p das câmeras com estabilidade comprovada, implementar gravação local contínua em formato MP4/MKV (seja em armazenamento USB conectado à própria TV TCL ou via script no PC Linux). Essa abordagem torna o usuário 100% independente do cartão micro-SD das câmeras e de protocolos proprietários.
- Abordagem C (Sondagem de Replay via Comandos RTSP / USER_CMD_SET):
  Investigar se os comandos customizados suportados pelo RTSP da câmera (`USER_CMD_SET`, anunciado nos cabeçalhos `Allow`) permitem chavear o stream RTSP para modo de reprodução de arquivo histórico.

6. Recursos Gráficos do Projeto

Foi criada a pasta `iconesblackview/` contendo os novos logotipos do aplicativo (`BlackView_300.png`, `BlackView_600.png`, `BlackView_96.png` e versões vetoriais em `.svg` e `.pdf`), prontos para serem vinculados como o ícone e banner definitivos do BlackView TV no Android TV.


--------------------------------------------------------

ADENDO 4 — DVR Contínuo na TV (Abordagem B consolidada) e Ajustes pelo Controle Remoto

Data: 18/11/2026

Status: Gravação contínua implementada como serviço em primeiro plano, validada na TCL 65C755 (câmeras 1 e 3 gravando e exibindo ao vivo simultaneamente). Destino configurável: armazenamento interno ou pendrive/HD USB conectado à TV. Senhas removidas do código-fonte. Projeto versionado em git.

0. Correção do registro anterior

O código já continha, antes desta sessão, uma primeira versão da Abordagem B não registrada no ADENDO 3:
- Gravação manual (botão REC/vermelho) via `MediaPlayer.record()` do LibVLC, que gerava arquivos `vlc-record-*.avi`.
- Telas `RecordingsActivity` (lista/filtros/exclusão) e `PlaybackActivity` (reprodução com ±10 s e velocidade).
- O scrim preto da tela cheia (`fullscreenBlackScrim`) já estava implementado.

Limitações dessa versão (corrigidas agora): a gravação parava ao abrir a tela de gravações (o `onStop()` da MainActivity parava os players), não havia gravação contínua, a retenção nunca era executada, a listagem lia metadados de cada arquivo na thread principal e os filtros/nomes de câmeras estavam fixos no código.

1. Descobertas técnicas desta sessão

1.1. Contêiner de gravação
- Um arquivo do `record()` do VLC Android foi analisado com FFprobe: AVI contendo HEVC 1920×1080 + PCM A-law 16 kHz, ~1,3 Mbps.
- O VLC escolhe AVI porque o muxer MPEG-TS não aceita G.711 A-law. O DVR passou a usar AVI explicitamente.
- Consumo medido: ~0,55 GB/hora por câmera → 3 câmeras ≈ 1,6 GB/h ≈ 40 GB/dia.
- Armadilha encontrada e resolvida: com stream output (`#std{mux=avi}`) os primeiros arquivos saíram só com áudio. Motivo: o SDP das câmeras é inválido para H.265 (`sprop-parameter-sets` contém um SPS de H.264 baseline e não há VPS/SPS/PPS HEVC), então o packetizer do VLC só cria o stream de vídeo quando recebe os parâmetros no próprio fluxo, junto a um quadro-chave. O GOP é de 60 quadros (~9,3 s) e, com perdas UDP, o vídeo surgiu até 23 s depois do áudio. O muxer AVI não aceita novos streams depois de iniciado ("cannot add a new stream"). Solução: `--sout-mux-caching=45000` como opção GLOBAL da instância LibVLC do DvrService (como opção de mídia não tem efeito). O watchdog tolera 90 s até o primeiro crescimento do arquivo. Resultado validado: AVI com HEVC 1920×1080 + A-law, quadros decodificáveis.
- A câmera 5 (firmware 33.0.25) transmite H.264, não H.265 (visto no log do packetizer).
- Cada arquivo começa no primeiro quadro-chave: há uma lacuna de ~1–3 s na troca de segmento mais até ~9 s sem vídeo (só áudio) no início de cada arquivo.

1.2. Limite de sessões RTSP (câmera 3, firmware 40.0.40)
- Testado com FFmpeg: duas sessões simultâneas funcionam; a terceira recebe `SETUP 500 Internal Server Error`.
- Consequência: mosaico/tela cheia (1 sessão) + DVR (1 sessão) usam toda a capacidade. Um terceiro cliente (ex.: ffplay no PC) será recusado enquanto o app estiver aberto e gravando.

1.3. Standby da TV bloqueia a rede (IMPORTANTE)
- A TCL usa o "Low Power Standby" do Android 14 (`dumpsys power`: mIsEnabled=true, timeout 5 s). Quando a TV entra em standby, o firewall `low_power_standby` bloqueia a rede de todos os apps fora da lista de permissões (apenas o UID 10072 do sistema está liberado).
- Resultado observado: com a TV em standby, os gravadores não conseguem conectar ("Failed to connect to RTSP server"). Ao ligar a TV, o serviço se reconecta automaticamente (backoff de 5 s/15 s/60 s).
- Portanto, na TV o DVR grava somente enquanto ela está ligada.
- DECISÃO DO USUÁRIO (18/11/2026): o Low Power Standby da TV NUNCA deve ser desativado.
- O DVR continua disponível na TV e pode ser ligado a qualquer momento em Ajustes → "Gravação contínua". Na TV ele grava somente enquanto ela está ligada (inclusive com outro app em primeiro plano — ainda não testado); em standby a gravação pausa e retoma sozinha quando a TV liga, deixando uma lacuna no período de standby.
- Estado atual na TV: DVR DESLIGADO, por escolha do usuário.
- Para gravação 24 h, a opção prevista é rodar o app num celular Android antigo dedicado (sempre na tomada, Wi-Fi, gravando no armazenamento interno/cartão/USB-OTG).
- Verificado após os testes: `settings get global low_power_standby_enabled` = 1 e `dumpsys power` mIsEnabled=true (nunca foi alterado); DVR desligado pela tela Ajustes e serviço encerrado.

1.4. Câmera 5
- Durante os testes a câmera 5 (192.168.0.150) ficou intermitente ("No route to host" também a partir do PC). É problema de rede/energia da câmera, não do app.
- Informação do usuário: a câmera 5 fica em local de sinal Wi-Fi fraco e raramente conecta — comportamento esperado, não é defeito do app. O DVR tenta novamente com backoff (5 s → 15 s → 60 s) e retoma sozinho quando ela aparece.

2. Arquitetura implementada

Pacote `br.com.blackview.tv.dvr`:
- `DvrService`: serviço em primeiro plano (`foregroundServiceType="specialUse"`), com notificação, WakeLock parcial e WifiLock. Cria um `SegmentRecorder` por câmera selecionada, executa a retenção a cada minuto e reage à conexão/remoção do pendrive (broadcasts `MEDIA_MOUNTED`/`UNMOUNTED`/`EJECT` + verificação periódica).
- `SegmentRecorder`: uma sessão RTSP própria por câmera, com stream output `#std{access=file,mux=avi}` — cópia direta sem decodificação (não usa o decodificador de hardware nem CPU significativa). Arquivos alinhados ao relógio (15/30/60 min). Watchdog reinicia a sessão se o arquivo parar de crescer por 30 s. Arquivos vazios de tentativas falhas são removidos.
- `DvrState`: estado compartilhado (StateFlow) consumido pelas telas (badge ● REC, status no bloco de informações e na tela de gravações).
- `BootReceiver`: inicia o DVR ao ligar a TV (BOOT_COMPLETED / QUICKBOOT_POWERON) e após atualização do app.

Pacote `data`:
- `DvrSettings`: liga/desliga, destino, duração dos arquivos, espaço livre mínimo, limite total, iniciar com a TV, câmeras gravadas.
- `StorageLocations`: lista os destinos graváveis via `Context.getExternalFilesDirs()` — armazenamento interno e cada pendrive montado (`/storage/XXXX-XXXX/Android/data/br.com.blackview.tv/files`). Não exige permissões. O LibVLC precisa de caminho de arquivo real, por isso não se usa o Storage Access Framework.
- `RecordingManager`: organização `recordings/camera_<id>/cam<id>_<yyyyMMdd-HHmmss>.avi`; lista as gravações de todos os destinos disponíveis; hora de início pelo nome do arquivo (sem abrir o vídeo); retenção circular (exclui as mais antigas até respeitar o espaço livre mínimo e o limite total; nunca exclui o arquivo em gravação).

Telas:
- `SettingsActivity` (nova): DVR (liga/desliga, destino interno/USB, duração, espaço mínimo, limite, iniciar com a TV, câmeras a gravar, estimativa de horas de histórico) e câmeras (ativa, nome, IP, senha NVR). Acesso: segurar OK no bloco do relógio, botão "⚙ Ajustes" na tela de gravações, ou teclas MENU/AZUL/SETTINGS. Abre automaticamente se uma câmera ativa estiver sem senha.
- `MainActivity`: botão REC/vermelho liga/desliga o DVR da câmera em foco; badge ● REC nos tiles; status do DVR no bloco do relógio; contador "câmeras ao vivo" corrigido; recria o mosaico quando a configuração muda.
- `RecordingsActivity`: filtros gerados a partir das câmeras cadastradas, carregamento em segundo plano, indicação "● GRAVANDO" e destino de cada arquivo, atualização a cada minuto.
- `PlaybackActivity`: reprodução contínua (ao terminar um segmento abre o próximo da mesma câmera), velocidade até 8×, tempo em H:MM:SS.

3. Pendrive / HD USB
- Conecte o pendrive na TV, abra Ajustes → "Destino das gravações" e alterne até aparecer "USB: …".
- Os arquivos ficam em `Android/data/br.com.blackview.tv/files/recordings/` no pendrive e podem ser copiados num PC (AVI H.265, abre no VLC/mpv/ffplay).
- Atenção: (1) desinstalar o app apaga essa pasta; (2) em FAT32 o limite é 4 GB por arquivo — um segmento de 60 min tem ~0,6 GB, sem problema; (3) se o pendrive for removido, o DVR pausa e retoma sozinho quando ele volta (não grava no interno como alternativa).
- Ainda não testado com pendrive real (nenhum estava conectado na sessão).
- Estimativa de histórico com 3 câmeras: 64 GB ≈ 1,5 dia; 128 GB ≈ 3 dias; 256 GB ≈ 6 dias; 1 TB ≈ 25 dias.

4. Credenciais
- A senha não está mais no código. Na primeira execução, a senha inicial vem de `local.properties` (não versionado): `blackview.cameraPassword=...`, exposta como `BuildConfig.DEFAULT_CAMERA_PASSWORD`. Depois pode ser alterada na tela Ajustes.
- `allowBackup` desativado (as senhas ficam em SharedPreferences).
- Antes da publicação no GitHub (repositório público), senha, IDs de dispositivo e capturas de tela das câmeras foram removidos deste documento e do histórico. Recomenda-se trocar a senha NVR de teste das câmeras por uma forte e atualizá-la em Ajustes.

5. Repositório
- `git init` na raiz do projeto; `.gitignore` exclui `android-sdk/`, `gradle-8.5/`, `.gradle/`, builds, APKs e `local.properties`.

6. Backlog atualizado
- [x] Ícone do aplicativo.
- [x] Scrim preto na tela cheia.
- [x] Gravação contínua local (Abordagem B) com retenção e pendrive.
- [x] Interface de ajustes (IPs, senhas, DVR) pelo controle remoto.
- [x] Estratégia de standby decidida: TV sem DVR; DVR como opção para celular antigo (seção 1.3).
- [ ] Testar o app/DVR num celular Android antigo (minSdk 26 = Android 8.0; layout pensado para TV/paisagem; verificar otimização de bateria do fabricante).
- [ ] Testar com pendrive real (FAT32/exFAT) e remoção a quente.
- [ ] Linha do tempo por dia/hora na tela de gravações (hoje é lista cronológica).
- [ ] Usar o substream `/onvif2` (640×360) no mosaico para reduzir decodificação; manter `/onvif1` na tela cheia e no DVR.
- [ ] Otimização de latência na troca mosaico ↔ tela cheia.
- [ ] **PRÓXIMA FEATURE — Reprodução das gravações do cartão micro-SD das câmeras** (ver ADENDO 3, Abordagens A e C).
  - Bloqueio atual (18/11/2026): a única câmera com cartão SD instalado é uma das que não têm a opção NVR no firmware (câmera 2 ou 4); não há como testar agora.
  - Observação técnica: o acesso ao SD pelo protocolo Gwell P2P (porta 50000, `getRecordFiles`/`playbackConnect`) não depende do RTSP/NVR — usa o Device ID (hostname GWIPC) e a senha do dispositivo no Yoosee. Portanto a Abordagem A pode ser viável mesmo numa câmera sem NVR; a Abordagem C (USER_CMD_SET no RTSP) exige RTSP funcional.
  - Para testar: instalar um cartão SD numa câmera com NVR (1 ou 3) ou investigar o P2P na própria câmera sem NVR.
- [ ] Câmeras 2 e 4.
