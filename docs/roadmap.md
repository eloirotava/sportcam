# A fazer

## Configuração remota pelo navegador

- [x] Servir no próprio SportCam uma interface web responsiva para configurar e
  operar a transmissão a partir de outro celular ou computador.
- [x] Exibir prévias da quadra, do placar e do cronômetro, com os mesmos ajustes
  de câmera, resolução, FPS, recorte, perspectiva, posição e tamanho existentes
  no aplicativo. A prévia é sob demanda: o telefone fica fixo no tripé, a cena não
  muda, e um quadro buscado uma vez basta para arrastar os quatro cantos por cima.
- [x] Permitir configurar vídeo, ícone e YouTube, além de iniciar, acompanhar e
  encerrar a transmissão remotamente.
- [x] Aplicar e persistir mudanças sem exigir acesso físico ao telefone; manter a
  interface local como alternativa de segurança. O `BroadcastConfigurationStore`
  avisa a tela do aparelho quando a mudança veio do site.
- [x] Proteger o acesso com autenticação. Ficou a autenticação básica do próprio
  navegador, com usuário e senha definidos na tela Remoto. A chave do YouTube e os
  demais segredos saem mascarados das respostas e voltam intactos ao salvar.
- [x] Mostrar perda de conexão e reconectar sem intervenção: o túnel reconecta com
  espera crescente de 1 a 30 segundos, e a tela Remoto mostra o estado da sessão.

### Alcance do túnel cf-p

O site é alcançado pelo [cf-p](https://github.com/eloirotava/cf-p), túnel reverso
próprio, com o cliente da wire v2 reimplementado em Kotlin — o binário Rust pesa
~1,1 MB por causa de `rustls`, `tokio` e `tungstenite`, que o Android já tem, e
exigiria NDK nos dois workflows. O `OPEN` do cf-p traz um destino arbitrário e o
protocolo não restringe nada, então o app só aceita o próprio site no loopback.

Do lado do servidor, `brasil.rotava.com` precisa de uma entrada para este cliente
no `server.yaml`, com `listen` no hostname e `target` na mesma porta configurada no
app, mais o bloco no Caddy apontando para o roteador HTTP.

### O que falta

- [ ] Fechar o servidor em `127.0.0.1`. Hoje ele escuta em `0.0.0.0` para o site
  ser testado pela rede local; a constante `LOOPBACK_ONLY` em `HttpServer` inverte
  isso quando o túnel estiver validado em jogo.
- [ ] Prévia da composição em GPU sai por `PixelCopy` sobre a `SurfaceView`, o que
  exige a tela ligada. Uma superfície offscreen no `GlCompositor` resolveria.

### Critérios de conclusão

O operador deve conseguir abrir a URL ou ler um QR code, enquadrar todas as
fontes pela prévia, salvar a configuração e controlar a live sem tocar novamente
no telefone usado como câmera.

### Aprendizado da primeira live

Sempre que a posição física permitir, o placar deve ser capturado sem a rede da
quadra à frente. A rede reduz o contraste e deixa o recorte visualmente mais
carregado. A prévia remota torna esse ajuste de posição bem mais simples antes do
jogo.
