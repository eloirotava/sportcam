# Publicação do SportCam na Google Play

## O que já está preparado no projeto

- pacote Android `dev.cascam`, `versionCode 7`, versão `0.0.7`;
- `targetSdk` e `compileSdk` 36;
- Android App Bundle (`.aab`) de release com assinatura por variáveis de ambiente;
- Action manual **Build Google Play Bundle**;
- permissões e tipos de foreground service de câmera e microfone no manifest;
- pedido de notificação no Android 13+ e aviso claro dentro do app;
- política de privacidade pública e acessível dentro do app;
- textos sugeridos para a ficha da loja.

## Criar e guardar a chave de upload

Execute uma vez em uma máquina confiável e guarde o arquivo e as senhas fora do Git:

```bash
keytool -genkeypair -v -keystore sportcam-upload.jks -alias sportcam-upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Nunca perca essa chave. No repositório GitHub, configure estes Actions secrets:

- `PLAY_UPLOAD_KEYSTORE_BASE64`: conteúdo de `base64 -w 0 sportcam-upload.jks`;
- `PLAY_UPLOAD_STORE_PASSWORD`;
- `PLAY_UPLOAD_KEY_ALIAS` (por exemplo, `sportcam-upload`);
- `PLAY_UPLOAD_KEY_PASSWORD`.

Depois rode manualmente **Actions > Build Google Play Bundle > Run workflow**. Baixe o
artefato `.aab` e envie-o ao Play Console. Ative o **Play App Signing** na criação do
aplicativo; a chave acima será a chave de upload, não a chave final mantida pelo Google.

## Declarações no Play Console

### Foreground service

Em **Conteúdo do app > Foreground service**, declare os tipos **Camera** e
**Microphone**.

Texto sugerido para câmera:

> O usuário inicia explicitamente uma transmissão esportiva. O SportCam mantém a
> captura das câmeras para compor e enviar quadra, placar e cronômetro mesmo com a tela
> apagada. Se interrompida, a transmissão ao vivo perde vídeo imediatamente.

Texto sugerido para microfone:

> O usuário inicia explicitamente uma transmissão esportiva com áudio ambiente. O
> microfone permanece ativo durante a live, inclusive com a tela apagada. Se
> interrompido, a transmissão perde o áudio imediatamente.

Grave um vídeo curto mostrando: abrir o SportCam, autorizar câmera/microfone, tocar em
**Iniciar transmissão**, apagar a tela ou abrir outro app, exibir a notificação
persistente e voltar para encerrar. Publique o vídeo como não listado e informe o link
nas duas declarações.

### Segurança de dados

Preencha o formulário conforme a versão realmente enviada. Pontos para conferir:

- câmera e microfone são processados para a função solicitada pelo usuário;
- áudio e vídeo deixam o aparelho e chegam ao destino escolhido, normalmente YouTube;
- o app não tem servidor próprio, anúncios ou analytics;
- tráfego de transmissão usa RTMPS ou HTTPS/HLS;
- configurações, chaves e tokens ficam no armazenamento privado do aparelho;
- a exclusão local é feita limpando os dados ou desinstalando; conteúdo no YouTube é
  excluído na conta do próprio usuário.

Não marque respostas automaticamente sem revisar a definição vigente de “coleta” e
“compartilhamento” do formulário: o enquadramento de um envio iniciado pelo usuário
para a própria conta do YouTube depende das opções apresentadas pelo Play Console.

### Outros itens manuais

- publicar a URL desta política no campo **Política de privacidade**;
- informar e-mail de suporte e categoria do app;
- concluir a verificação da tela de consentimento OAuth do Google antes de liberar a
  criação de lives para contas que não estejam cadastradas como usuários de teste;
- responder classificação indicativa, público-alvo e conteúdo de apps;
- declarar que o app contém ou exibe conteúdo gerado pelo usuário somente se a ficha
  vigente do Console enquadrar transmissões do próprio usuário nessa categoria;
- enviar ícone 512×512, imagem de destaque 1024×500 e capturas de tela de celular;
- se a conta pessoal foi criada depois de 13/11/2023, concluir teste fechado com pelo
  menos 12 participantes inscritos continuamente por 14 dias antes da produção;
- testar primeiro nas faixas **Interna** e **Fechada**, especialmente câmera dupla,
  tela apagada, aquecimento e uma live real longa.

Em 31 de agosto de 2026, novos apps e atualizações passam a exigir API 36. O SportCam
já está preparado para esse nível, mas deve ser testado novamente no S22 antes do
envio à produção.
