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
- diagnóstico das câmeras físicas e dos pares simultâneos anunciados pelo aparelho;
- testes unitários para as regras geométricas.

O botão de transmissão ainda é uma simulação. Não aceita nem armazena uma chave de
stream nesta etapa.

## Rodando

1. Abra o diretório no Android Studio.
2. Use JDK 17 ou mais recente e sincronize o Gradle.
3. Instale em um aparelho Android 12+ (o S22 é o alvo inicial).
4. Conceda acesso à câmera.

Pela linha de comando:

```bash
gradle test
gradle assembleDebug
```

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
