# Política de Privacidade do SportCam

Última atualização: 9 de agosto de 2026.

O SportCam é um aplicativo de transmissão ao vivo desenvolvido e mantido pelo projeto
SportCam (`eloirotava`). Dúvidas sobre privacidade podem ser enviadas pelo
[rastreador público do projeto](https://github.com/eloirotava/sportcam/issues).

## Dados acessados e finalidade

- **Câmera e microfone:** usados para capturar, compor e codificar a transmissão que o
  usuário inicia. A captura pode continuar com a tela apagada ou o aplicativo em
  segundo plano enquanto a transmissão estiver ativa.
- **Imagem escolhida pelo usuário:** um arquivo de imagem pode ser lido para aparecer
  como logotipo ou propaganda sobre o vídeo.
- **Configurações e credenciais:** preferências de câmera, composição, destino de
  transmissão, chave de ingestão e credenciais OAuth informadas pelo usuário ficam no
  armazenamento privado do aplicativo no aparelho.
- **Credenciais OAuth do Google:** quando o usuário escolhe criar uma live pelo app,
  tokens são recebidos do Google e usados somente para operar a conta do YouTube
  autorizada pelo próprio usuário.

## Envio e compartilhamento

O SportCam não opera servidor próprio, não vende dados, não exibe anúncios e não usa
SDK de análise. Durante uma transmissão, o vídeo e o áudio compostos são enviados
diretamente ao endereço configurado pelo usuário, normalmente o YouTube, por conexão
criptografada RTMPS ou HTTPS/HLS. O uso desses dados pelo serviço de destino é regido
pelos termos e pela política de privacidade desse serviço.

O logotipo selecionado é processado no aparelho; ele só deixa o aparelho quando passa
a fazer parte dos quadros da transmissão.

## Execução em segundo plano

Depois que o usuário toca em iniciar, o SportCam mantém um serviço em primeiro plano
para continuar usando câmera, microfone, codificador e rede com a tela apagada ou com
outro aplicativo visível. Uma notificação persistente identifica a transmissão. A
atividade termina quando o usuário encerra a transmissão ou fecha o processo do app.

## Retenção, proteção e exclusão

As configurações e credenciais locais permanecem no armazenamento privado do Android
até serem substituídas, até os dados do aplicativo serem apagados ou até o SportCam ser
desinstalado. O backup do aplicativo está desativado. As transmissões armazenadas no
YouTube devem ser gerenciadas ou excluídas pelo usuário na própria conta do YouTube.
O acesso OAuth também pode ser revogado nas configurações de segurança da Conta Google.

O SportCam não cria conta própria. Para apagar todos os dados mantidos pelo SportCam,
use **Ajustes do Android > Apps > SportCam > Armazenamento > Limpar dados** ou desinstale
o aplicativo.

## Crianças

O SportCam é uma ferramenta de produção de vídeo e não é direcionado a crianças. O
responsável pela transmissão deve obter as autorizações aplicáveis antes de filmar ou
publicar imagens de participantes, especialmente menores de idade.

## Alterações

Esta política pode ser atualizada quando as funções ou práticas de dados do aplicativo
mudarem. A data no início desta página identifica a revisão vigente.
