# SportCam

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
- placar e cronômetro filmados opcionais, cada um com quadrilátero de quatro pontos
  para planificar a origem e retângulo próprio de destino na composição;
- seleção independente da câmera da quadra, do placar e do cronômetro, incluindo lentes traseiras,
  frontais e externas anunciadas pelo Android, identificadas pelo ângulo de visão em vez da focal
  em milímetros — num sensor de celular o milímetro só significaria algo com a equivalência de 35 mm,
  e o que decide a escolha é quanto de quadra cabe no quadro;
- compartilhamento de um único stream quando duas ou três camadas escolhem a mesma câmera;
- seleção de resolução e FPS por fonte, **sem teto de resolução**: a lista traz todos os tamanhos que
  o aparelho oferece, cada um com proporção, megapixels e a taxa que ele sustenta, e a escolha é do
  operador. Camadas que compartilham câmera usam o perfil mais exigente entre elas;
- saída configurável em 360p, 540p, 720p ou 1080p e 15, 20, 24, 30 ou 60 fps,
  independente do bitrate;
- controles de zoom e deslocamento do recorte, retângulo livre do placar na composição e
  servidor/chave do YouTube, persistidos localmente;
- ícone opcional sobre a transmissão, carregado de PNG ou outra imagem, com transparência,
  posição e tamanho configuráveis e persistidos, além de opção para transformar fundo branco
  em transparente com borda suavizada;
- diagnóstico das câmeras físicas e dos pares simultâneos anunciados pelo aparelho;
- testes unitários para as regras geométricas.

A interface é dividida em **Quadra**, onde o retângulo 16:9 é
movido por arraste e redimensionado pelas duas alças diagonais; **Placar**, onde são
ajustados a área amarela capturada, a câmera e o zoom de visualização; **Cronômetro**,
com os mesmos ajustes para a área roxa; **Ícone**, que mostra a composição enquanto
ajusta a imagem, posição e tamanho; e **Ao vivo**, que reúne a composição e os
dados do YouTube. A tela **Vídeo** escolhe resolução, FPS, codec e bitrate de saída.
O botão de play codifica a composição em H.264/H.265, faz a captura principal seguir
o FPS de saída quando ela está em “Seguir saída”, captura o microfone em AAC mono a
44,1 kHz e publica por RTMPS ou ingestão HLS. Quando o aparelho anuncia o grupo
simultâneo, a tela Ao vivo abre as fontes distintas selecionadas; camadas que apontam
para a mesma câmera compartilham o stream. CPU e GPU fazem o recorte da quadra e
aplicam a homografia dos quatro pontos de cada sobreposição, sem transmitir os guias.
Enquanto a tela está ligada, o preview continua disponível durante a transmissão. Ao
apagar a tela ou mandar o app ao fundo, somente o destino visual do compositor é
suspenso; câmera, composição do encoder, áudio e upload continuam ativos.

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

## Capturar acima da resolução transmitida

A quadra não é enviada inteira: o que vai ao ar é um retângulo 16:9 recortado dela. Captura e saída
são, portanto, duas coisas diferentes, e igualar as duas desperdiça a única margem que existe.

Com captura em 1920×1080 e recorte de 2×, sobram 960×540 pixels reais, que sobem esticados até os
1280×720 da saída — a imagem fica mole por aritmética, não por falta de lente. Com o mesmo recorte
sobre um quadro de 3840 px de largura, sobram 1920×1080 reais para reduzir a 720p; reduzir ainda
média o ruído, então o resultado sai mais limpo que 720p capturado direto.

Por isso o app não impõe teto de resolução. Cada aparelho anuncia os tamanhos que oferece e, para
cada um, a duração mínima do quadro — a lista mostra a taxa que dali resulta, e o operador escolhe.
Um S22 e um S25 Ultra não têm o mesmo limite, e fixar no código o que um deles aprovou seria decidir
pelo outro. Quando o tamanho escolhido declara taxa menor que a pedida, o app avisa e transmite
assim mesmo: declaração de aparelho erra nos dois sentidos, e quem confere é o jogo.

Deixada em **Automática**, a resolução vira uma conta em vez de um teto: o menor tamanho que ainda
entrega pixel real ao enquadramento atual, entre os que sustentam a taxa pedida. Recorte de 2× para
sair em 720p pede 3200 px de largura; abaixo disso a imagem estica, acima disso não melhora.

O custo aparece na composição por CPU, onde converter YUV para bitmap se paga por pixel: converter o
sensor inteiro para descartar quase tudo no recorte seguinte torraria o aparelho sem ganho nenhum.
A conversão então acontece na largura que a composição realmente consome, e não na de captura, de
modo que subir a resolução do sensor não encarece o laço. Na GPU o recorte é coordenada de textura
e já era de graça.

Vale lembrar que existe um caminho ainda mais barato para o mesmo efeito, quando o HAL o oferece:
pedir o recorte ao próprio sensor por `SCALER_CROP_REGION`, que devolve a região já recortada no
tamanho pedido, sem mover os pixels excedentes. O inventário de câmeras já detecta esse suporte em
[`PerPhysicalZoom`](app/src/main/java/dev/cascam/camera/CameraCapabilities.kt); `CONTROL_ZOOM_RATIO`
não serve sozinho porque é sempre centralizado, e o recorte da quadra tem deslocamento.

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

### Aba TESTE: descobrindo empiricamente o que o aparelho aceita

Declaração não é garantia, e nenhuma das quatro condições acima é observável só lendo a
documentação do aparelho. A aba **TESTE** existe para responder na prática, no aparelho
que vai transmitir o jogo: ela monta uma lista de pares candidatos, **liga cada par de
verdade** e só considera aprovado o par que entregar frames dos dois lados com imagens
comprovadamente diferentes — há HAL que aceita o binding e devolve o mesmo buffer nos
dois fluxos, o que passaria por sucesso em qualquer teste que só olhasse exceções.

A varredura tem duas fases, porque as duas perguntas têm APIs diferentes:

1. **Camera2 direto**, para dois sensores físicos da mesma câmera lógica (`0/2` + `0/6`).
   É o par que interessa para quadra + placar, já que ultra-wide e teleobjetiva moram sob
   a mesma lógica traseira, e é a única via com garantia documentada: abrir a câmera
   **lógica**, criar **uma** sessão e trocar um stream YUV dela por dois streams de mesmo
   formato e **mesmo tamanho**, cada um amarrado a um sensor por
   `OutputConfiguration.setPhysicalCameraId`. Os tamanhos testados são os que existem nos
   dois sensores ao mesmo tempo — pedir um tamanho que só um deles oferece reprova o par
   por detalhe de tabela, não por limite real;
2. **CameraX**, para pares de câmeras lógicas em modo simultâneo — na prática traseira +
   frontal, que é o que o CameraX de fato suporta.

A divisão não é preciosismo. `Camera2Interop.Extender.setPhysicalCameraId` num
`ImageAnalysis` pode ser silenciosamente ignorado: os dois use cases recebem o mesmo
stream da câmera lógica e o teste vê dois fluxos idênticos que passariam por "funcionou"
em qualquer verificação que só olhasse exceções. A documentação do framework é explícita
em que passar disso exige "per-device testing and tuning using trial and error".

A comparação entre os dois fluxos usa um hash perceptual do plano Y, e **só compara
quadros com contraste**: uma imagem chapada — sensor ainda escuro logo depois de abrir,
lente tampada, parede lisa — produz a mesma assinatura degenerada em qualquer câmera, e
comparar dois quadros chapados dava distância zero, fazendo câmeras distintas parecerem a
mesma fonte. O veredito usa a mediana das distâncias válidas, não o mínimo, e quando não
sobra nenhuma comparação válida o relatório diz isso em vez de reprovar o par.

Cada par é varrido de 1920x1080 para 1280x720 e depois 640x480, parando na primeira
resolução que funciona — é assim que o relatório responde se resolução importa e qual é
o teto real do par. O relatório final traz o inventário das lentes com ângulo horizontal
aproximado, o que o Android declara, o resultado de cada tentativa com fps e distância
entre as imagens, e uma recomendação de qual câmera usar em cada papel. O botão **Copiar
relatório** joga tudo na área de transferência.

Referências: [CameraX — câmera concorrente](https://developer.android.com/media/camera/camerax/configuration#concurrent-camera),
[`CameraManager.getConcurrentCameraIds`](https://developer.android.com/reference/android/hardware/camera2/CameraManager#getConcurrentCameraIds())
e [Samsung Director's View](https://www.samsung.com/us/support/answer/ANS00088122/).

## HLS direto para o YouTube

O YouTube também aceita **HLS como protocolo de ingestão**, além de RTMPS. O protocolo
pode ser escolhido na tela **Ao vivo**. Não se
trata de servir a playlist pelo celular para espectadores: o encoder deve enviar por
HTTP a playlist e os segmentos ao endpoint de upload HLS fornecido pelo YouTube
Studio. A implementação do SportCam:

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
3. informe Client ID e Client Secret no SportCam e toque **Autorizar YouTube**;
4. ao tocar **Autorizar YouTube** o app copia o código do dispositivo para a área de
   transferência e abre a página do Google; basta colar. O código também fica visível
   em fonte grande e selecionável no painel, com os botões **Copiar código** (recopia a
   qualquer momento, útil depois de voltar do navegador) e **Abrir página**;
5. escolha título, privacidade, protocolo e toque **Criar live e transmitir**.

O app cria `liveBroadcast` com início/parada automáticos, cria `liveStream`, faz o
`bind`, recebe endereço/chave de ingestão e começa a transmissão. Tokens e segredo
ficam nas preferências privadas sem backup; antes de distribuição pública deverão ser
migrados para armazenamento criptografado pelo Android Keystore.

Dois erros comuns nesse fluxo:

- **"Acesso bloqueado: o app não concluiu o processo de verificação do Google"** — o
  scope `youtube` é sensível, então enquanto a tela de consentimento estiver em
  *Testing* só contas listadas em **Usuários de teste** conseguem autorizar. Adicione
  a sua conta ali. Verificação só é exigida para distribuir a terceiros.
- **"Unable to resolve host"** durante a espera pela aprovação — o SportCam fica em
  segundo plano enquanto você aprova no navegador, e a Economia de dados, o modo de
  espera de apps ou uma troca de Wi-Fi para dados móveis cortam a rede do processo
  exatamente aí. O app não aborta mais nesse caso: continua tentando até o código
  expirar de fato. Voltar para o SportCam depois de aprovar resolve na hora; para não
  depender disso, libere o app em Ajustes › Apps › SportCam › Dados móveis.

### Tela apagada e economia de dados

Durante a transmissão, um foreground service de câmera/microfone mantém uma
notificação persistente e um `PARTIAL_WAKE_LOCK`. A composição exporta frames quando
as análises de câmera chegam, sem depender do redesenho da tela; portanto o botão de
energia pode apagar o display sem encerrar câmera, encoder ou upload. O app não usa
mais `FLAG_KEEP_SCREEN_ON`, então o timeout normal do Android também apaga a tela
sozinho enquanto a live continua. Encerrar pela
notificação ainda será acrescentado em uma etapa posterior.

O seletor de bitrate oferece 250 kbps, 500 kbps, 1,5 Mbps, 3 Mbps, 4,5 Mbps,
6 Mbps e 9 Mbps. No modo **Automático**, o SportCam escolhe uma taxa coerente com a
resolução e o FPS selecionados. O bitrate não reduz mais a resolução escondido: a
saída usa exatamente 640×360, 960×540, 1280×720 ou 1920×1080 conforme a tela Vídeo.
Taxas de até 500 kbps usam AAC a 32 kbps; acima disso, AAC a 128 kbps.

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

## Google Play

O projeto já gera Android App Bundle para a Play Store, usa API 36 e contém as
declarações de serviço em primeiro plano para câmera e microfone. A política de
privacidade, os textos da ficha e o roteiro completo do Play Console estão em
[`docs/google-play-checklist.md`](docs/google-play-checklist.md). Depois de cadastrar a
chave de upload como secrets do repositório, rode manualmente o Action
**Build Google Play Bundle** para obter o `.aab` assinado.

### Compilando em um GitHub Codespace

O repositório mantém apenas código-fonte: não versionamos o Gradle Wrapper JAR, SDK,
Build Tools nem outros binários. Para o ambiente Ubuntu 22.04 x86_64 exibido por
`uname -a` no Codespaces, o script fonte abaixo baixa as cópias Linux corretas para
o diretório do usuário e cria `local.properties` e `.sportcam-env`, ambos ignorados
pelo Git:

```bash
chmod +x scripts/bootstrap-codespaces.sh
./scripts/bootstrap-codespaces.sh
source .sportcam-env
gradle test
gradle assembleDebug
```

O bootstrap instala JDK 17 pelos pacotes do próprio Ubuntu (ou JDK 21 como fallback
em distribuições como Debian 13) e baixa Gradle 8.11.1,
Android Command-line Tools, Platform 35 e Build Tools 35 para o seu Codespace. Nada
disso é commitado. Em um terminal novo, basta repetir `source .sportcam-env` antes de
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
source .sportcam-env
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
`.sportcam-env` coloca `$JAVA_HOME/bin` no início do `PATH`, afetando somente o terminal
em que ele for carregado.

Se você já gerou `.sportcam-env` com uma versão anterior do bootstrap, apague-o e gere
novamente depois de atualizar o código:

```bash
rm -f .sportcam-env
./scripts/bootstrap-codespaces.sh
source .sportcam-env
which java
java -version
```

`which java` deve retornar o Java 17 selecionado pelo bootstrap ou, no Debian 13,
`/usr/lib/jvm/java-21-openjdk-amd64/bin/java`.

### Build em ambientes com rede de processos isolada

Alguns sandboxes redirecionam até conexões locais entre processos. Neles, Gradle,
o compilador Kotlin e o compilador Java podem falhar com mensagens como `Cannot
accept connection from remote address` ou `Never received a connection from Gradle
Worker Daemon`. Depois de executar o bootstrap e preencher o cache Gradle, use uma
network namespace temporária com loopback próprio:

```bash
./scripts/build-local-sandbox.sh --prepare-cache assembleDebug
```

O script usa `/tmp/sportcam-gradle-home` como cache gravável e executa o Gradle em
modo offline. Use `--prepare-cache` na primeira execução ou depois de alterar
dependências; nas seguintes, basta `./scripts/build-local-sandbox.sh assembleDebug`.
Ele requer permissão para `unshare --net`; em um Linux comum, sem esse tipo de
isolamento, continue usando simplesmente `gradle assembleDebug`.

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
