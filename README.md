# CasCam

Protótipo Android para transformar um Galaxy S22 em uma câmera de transmissão de
jogos. A composição planejada usa:

- câmera ultra-wide para a quadra, recortada em 16:9;
- câmera principal para o placar, corrigido por uma transformação de perspectiva
  definida por quatro pontos;
- encoder H.264/AAC e envio ao YouTube por **RTMPS**.

> O YouTube recebe live streams por RTMP/RTMPS, não por HLS. HLS é normalmente o
> formato de reprodução na saída. Por isso o destino do aplicativo é RTMPS; uma
> saída HLS própria pode ser adicionada depois por meio de um servidor intermediário.

## Estado atual

Esta primeira fatia é propositalmente pequena, mas executável:

- projeto Android nativo (Kotlin, minSdk 31);
- preview CameraX com pedido de permissão em runtime;
- enquadramento 16:9 configurável sobre a imagem;
- quadrilátero de quatro pontos para capturar/planificar o placar e retângulo de duas
  alças para seu destino na composição;
- seleção independente da câmera da quadra e do placar, incluindo lentes traseiras,
  frontais e externas anunciadas pelo Android;
- controles de zoom e deslocamento do recorte, retângulo livre do placar na composição e
  servidor/chave do YouTube, persistidos localmente;
- diagnóstico das câmeras físicas e dos pares simultâneos anunciados pelo aparelho;
- testes unitários para as regras geométricas.

A interface agora é dividida em três telas: **Quadra**, onde o retângulo 16:9 é
movido por arraste e redimensionado pelas duas alças diagonais; **Placar**, onde são
ajustados a área amarela capturada e o retângulo azul de destino, além da câmera e seu
zoom; e **Ao vivo**, que reúne a composição e os
dados do YouTube. O botão de play agora codifica a composição em H.264 (720p, 15 fps,
3 Mbps), captura o microfone em AAC mono a 44,1 kHz, empacota áudio e vídeo em
mensagens RTMP e publica por RTMPS no servidor e chave configurados. Metadados do
stream e cabeçalhos AVC/AAC são enviados antes da mídia. Quando o aparelho expõe o par simultâneo, a tela Ao vivo já abre
as duas fontes, converte os frames YUV, faz o crop real da quadra e aplica a homografia
dos quatro pontos do placar para retificá-lo no PiP, sem os guias de configuração.
Esta primeira composição é desenhada na CPU para validar o resultado no aparelho; a
versão GPU será necessária para alimentar o encoder com desempenho sustentado. A tela
informa o estado explicitamente para não simular uma live e permanece ligada durante
o uso, sem depender de toques periódicos.

Os seletores distinguem câmeras **lógicas** e sensores **físicos** anunciados dentro
delas. Isso evita que ultra-wide e principal acabem resolvendo para a mesma câmera
lógica no S22. Quando dois sensores físicos pertencem à mesma câmera lógica, cada
`ImageAnalysis` recebe explicitamente seu `physicalCameraId`; para frontal + traseira,
o app usa o par concorrente de câmeras lógicas.

Na tela Ao vivo, o status mostra os dois IDs realmente solicitados. O app também
compara assinaturas perceptuais dos frames: se o driver aceitar a sessão mas entregar
a mesma imagem para os dois `ImageAnalysis`, ele mostra um aviso explícito. Para
câmeras lógicas diferentes, o app procura o par nos objetos efetivamente anunciados
por `ProcessCameraProvider.availableConcurrentCameraInfos` e usa exatamente esses
objetos no binding. O diagnóstico também apresenta os IDs publicados por
`CameraManager.concurrentCameraIds`; caso o CameraX não ofereça o mesmo par, informa
a incompatibilidade e mantém somente a quadra.

O botão alterna entre iniciar e encerrar a transmissão e mostra falhas de conexão ou
codificação na própria tela. A chave fica nas preferências privadas do app, com backup desabilitado; uma versão de
produção deverá protegê-la com Android Keystore.

## Galaxy S22 e duas câmeras

O S22 é capaz de manter duas câmeras ativas em cenários da própria Samsung (por
exemplo, o Director's View), mas isso não garante que todo par de lentes esteja
liberado para qualquer aplicativo. Para um app Camera2/CameraX, os requisitos reais
são:

1. Android anunciar `FEATURE_CAMERA_CONCURRENT`;
2. o par de IDs lógicos constar em `CameraManager.concurrentCameraIds`;
3. o mesmo grupo aparecer em `ProcessCameraProvider.availableConcurrentCameraInfos`;
4. resolução, formato e quantidade de use cases caberem na combinação de streams que
   o HAL aceita simultaneamente.

O diagnóstico da tela agora mostra separadamente esses três primeiros níveis. Se o
S22 mostra `0 + 1` e `0 + 3` tanto no Camera2 quanto no CameraX, há suporte público do
HAL para esses pares lógicos; uma imagem duplicada nesse caso indica problema no
binding/configuração dos use cases, e não ausência genérica de multicâmera. Sensores
físicos internos à mesma câmera lógica continuam sendo outro caso e não devem ser
confundidos com os pares lógicos concorrentes.

Referências: [CameraX — câmera concorrente](https://developer.android.com/media/camera/camerax/configuration#concurrent-camera),
[`CameraManager.getConcurrentCameraIds`](https://developer.android.com/reference/android/hardware/camera2/CameraManager#getConcurrentCameraIds())
e [Samsung Director's View](https://www.samsung.com/us/support/answer/ANS00088122/).

## HLS direto para o YouTube

O YouTube também aceita **HLS como protocolo de ingestão**, além de RTMPS. O protocolo
pode ser escolhido na tela **Ao vivo**. Não se
trata de servir a playlist pelo celular para espectadores: o encoder deve enviar por
HTTP a playlist e os segmentos ao endpoint de upload HLS fornecido pelo YouTube
Studio. A implementação do CasCam:

1. selecionar **HLS** como protocolo da transmissão no YouTube Studio e copiar a URL
   de ingestão/chave gerada para aquela live;
2. reutiliza os H.264/AAC já produzidos pelo app;
3. multiplexar os pacotes em MPEG-TS, preservando PTS/DTS;
4. fechar segmentos curtos, inicialmente de 2 segundos, sempre começando em um
   keyframe;
5. gerar e atualizar uma media playlist HLS com sequência crescente;
6. fazer upload HTTP dos segmentos e da playlist para o prefixo indicado pelo Studio,
   com repetição segura, descarte de arquivos antigos e reconexão.

HLS aumenta a latência e usa um segmentador MPEG-TS, playlist deslizante e uploader
HTTP com três tentativas por arquivo. A composição e os encoders são compartilhados
pelos dois transportes. A URL HLS deve vir do Studio —
o endpoint RTMPS atual não aceita playlists ou segmentos.

Em HLS, o seletor de codec permite **H.264/AVC** ou **H.265/HEVC**. Para HEVC, o app
solicita o encoder de hardware `video/hevc`, extrai VPS/SPS/PPS, usa NAL units Annex B
e anuncia stream type `0x24` na PMT MPEG-TS. Em RTMPS o seletor permanece travado em
H.264, pois o transporte FLV implementado não carrega HEVC. A disponibilidade real
do encoder H.265 ainda depende do aparelho; o Galaxy S22 oferece esse encoder por
hardware.

### Início automático no YouTube

Para uma transmissão já criada, ative **Início automático** e **Parada automática**
no YouTube Studio. Assim, o recebimento do primeiro stream válido inicia a live sem
deixar o Studio aberto. Criar/agendar e iniciar uma live inteiramente pelo APK exige
login OAuth do Google e chamadas autenticadas à YouTube Live Streaming API
(`liveBroadcasts`, `liveStreams`, `bind` e `transition`); a chave de ingestão sozinha
não autoriza essas operações administrativas e não deve ser usada como substituta do
OAuth. O app agora implementa esse fluxo administrativo pelo OAuth Device Flow:

1. no Google Cloud, habilite **YouTube Data API v3**, configure a tela de consentimento
   e adicione sua conta como usuário de teste enquanto o app estiver em testes;
2. crie um OAuth Client do tipo **TVs and Limited Input devices**;
3. informe Client ID e Client Secret no CasCam e toque **Autorizar YouTube**;
4. conclua o código mostrado pelo app na página Google aberta no navegador;
5. escolha título, privacidade, protocolo e toque **Criar live e transmitir**.

O app cria `liveBroadcast` com início/parada automáticos, cria `liveStream`, faz o
`bind`, recebe endereço/chave de ingestão e começa a transmissão. Tokens e segredo
ficam nas preferências privadas sem backup; antes de distribuição pública deverão ser
migrados para armazenamento criptografado pelo Android Keystore.

### Tela apagada e economia de dados

Durante a transmissão, um foreground service de câmera/microfone mantém uma
notificação persistente e um `PARTIAL_WAKE_LOCK`. A composição exporta frames quando
as análises de câmera chegam, sem depender do redesenho da tela; portanto o botão de
energia pode apagar o display sem encerrar câmera, encoder ou upload. O app não usa
mais `FLAG_KEEP_SCREEN_ON`, então o timeout normal do Android também apaga a tela
sozinho enquanto a live continua. Encerrar pela
notificação ainda será acrescentado em uma etapa posterior.

O seletor de bitrate oferece 250 kbps, 500 kbps, 1,5 Mbps e 3 Mbps. No modo
**Automático**, redes não tarifadas usam 3 Mbps e redes marcadas como tarifadas
(normalmente dados móveis) usam 350 kbps. Até 500 kbps, a saída cai automaticamente
para 640×360 e o AAC para 32 kbps; acima disso usa 1280×720 e AAC a 128 kbps.

Referências: [YouTube — HLS ingestion protocol](https://support.google.com/youtube/answer/10349430)
e [configurações recomendadas de encoder](https://support.google.com/youtube/answer/2853702).

## Rodando

1. Abra o diretório no Android Studio.
2. Use JDK 17 ou mais recente e sincronize o Gradle.
3. Instale em um aparelho Android 12+ (o S22 é o alvo inicial).
4. Conceda acesso à câmera.

Pela linha de comando, com o Android SDK instalado:

```bash
gradle test
gradle assembleDebug
```

O APK de debug será criado em `app/build/outputs/apk/debug/app-debug.apk`.

### Compilando em um GitHub Codespace

O repositório mantém apenas código-fonte: não versionamos o Gradle Wrapper JAR, SDK,
Build Tools nem outros binários. Para o ambiente Ubuntu 22.04 x86_64 exibido por
`uname -a` no Codespaces, o script fonte abaixo baixa as cópias Linux corretas para
o diretório do usuário e cria `local.properties` e `.cascam-env`, ambos ignorados
pelo Git:

```bash
chmod +x scripts/bootstrap-codespaces.sh
./scripts/bootstrap-codespaces.sh
source .cascam-env
gradle test
gradle assembleDebug
```

O bootstrap instala JDK 17 pelos pacotes do próprio Ubuntu e baixa Gradle 8.11.1,
Android Command-line Tools, Platform 35 e Build Tools 35 para o seu Codespace. Nada
disso é commitado. Em um terminal novo, basta repetir `source .cascam-env` antes de
compilar. Para baixar o APK, abra a aba **Explorer** do Codespaces, navegue até
`app/build/outputs/apk/debug/app-debug.apk`, clique com o botão direito e escolha
**Download**. A primeira compilação precisa de acesso à internet para baixar o SDK,
o Gradle e as dependências Maven.

Se o Gradle falhar mostrando apenas uma versão como `25.0.2`, o Codespace selecionou
seu Java global, que é novo demais para o Android Gradle Plugin utilizado. Atualize o
repositório e rode novamente o bootstrap; ele fixa `JAVA_HOME` no OpenJDK 17 e encerra
qualquer daemon que tenha sido iniciado com Java 25. Confirme antes do build:

```bash
./scripts/bootstrap-codespaces.sh
source .cascam-env
java -version
gradle --stop
gradle assembleDebug
```

A primeira linha de `java -version` deve começar com `openjdk version "17`. Os avisos
sobre `System::load`/`--enable-native-access` vinham do Gradle rodando no Java 25 e
não impediam o build por si só; o erro era a versão incompatível da JVM.

O JDK 25 instalado no Codespaces pode continuar existindo e ser usado por outros
projetos. Não o usamos neste build porque o Gradle 8.11.1/AGP 8.9.1 foi escolhido
para a toolchain Android do projeto e não consegue rodar no Java 25 — a falha que
mostra apenas `25.0.2` acontece antes mesmo de compilar o app. O arquivo
`.cascam-env` coloca `$JAVA_HOME/bin` no início do `PATH`, afetando somente o terminal
em que ele for carregado.

Se você já gerou `.cascam-env` com uma versão anterior do bootstrap, apague-o e gere
novamente depois de atualizar o código:

```bash
rm -f .cascam-env
./scripts/bootstrap-codespaces.sh
source .cascam-env
which java
java -version
```

`which java` deve retornar `/usr/lib/jvm/java-17-openjdk-amd64/bin/java`.

Se preferir executar cada etapa manualmente, abra
`scripts/bootstrap-codespaces.sh`: ele contém os comandos completos, sem esconder a
instalação em uma imagem ou binário do repositório.

## Riscos técnicos e próximos passos

Dois sensores ao mesmo tempo **não são garantidos** apenas porque o aparelho possui
duas câmeras. O firmware precisa expor o par em `CameraManager.concurrentCameraIds`;
o app mostra esse diagnóstico. Também há limites térmicos e de largura de banda no
S22, então o primeiro perfil de transmissão deverá ser 720p30 e precisa de um teste
de longa duração no aparelho real.

Próximas entregas:

1. selecionar o par físico ultra-wide + wide quando ele for anunciado pelo S22;
2. compor os dois frames em OpenGL, aplicando crop e homografia;
3. codificar H.264/AAC com `MediaCodec`;
4. publicar por RTMPS com reconexão, métricas e armazenamento seguro da chave;
5. validar áudio, aquecimento, bateria e estabilidade por 90 minutos.
