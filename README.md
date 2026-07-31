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
- quadrilátero do placar com quatro alças arrastáveis;
- seleção independente da câmera da quadra e do placar, incluindo lentes traseiras,
  frontais e externas anunciadas pelo Android;
- controles de zoom e deslocamento do recorte, posição do placar na composição e
  servidor/chave do YouTube, persistidos localmente;
- diagnóstico das câmeras físicas e dos pares simultâneos anunciados pelo aparelho;
- testes unitários para as regras geométricas.

A interface agora é dividida em três telas: **Quadra**, onde o retângulo 16:9 é
movido por arraste e redimensionado com gesto de pinça; **Placar**, onde são escolhidos
a câmera, seu zoom e os quatro cantos; e **Ao vivo**, que reúne a composição e os
dados do YouTube. O botão de play já valida e salva a configuração, mas ainda não
envia vídeo: o compositor de duas entradas, encoder e cliente RTMPS continuam sendo
a próxima entrega. A tela informa isso explicitamente para não simular uma live.

O botão de transmissão ainda valida a configuração sem iniciar o encoder. A chave
fica nas preferências privadas do app, com backup desabilitado; uma versão de
produção deverá protegê-la com Android Keystore.

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
