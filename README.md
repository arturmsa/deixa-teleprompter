# Deixa Teleprompter

Teleprompter com câmera, gravação de vídeo, rolagem automática ou por voz, pausas em linhas em branco e biblioteca de roteiros. Funciona no navegador (Chrome no Android).

**Abrir:** https://arturmsa.github.io/deixa-teleprompter/

## Dicas de roteiro
- Linha em branco: pausa curta na rolagem.
- `[entre colchetes]`: deixa de cena destacada, como `[sorria]`.

## Atalhos (teclado ou controle Bluetooth)
Espaço rola/pausa · ↑ ↓ velocidade · ← → ou PgUp/PgDn linhas · R grava · Home início.

## App Android (APK)
Cada envio para `main` compila o APK no GitHub Actions e publica em **Releases**:
https://github.com/arturmsa/deixa-teleprompter/releases/latest

O app embute o mesmo `index.html` num WebView nativo e acrescenta:
- vídeos salvos direto na galeria (Filmes/Deixa)
- rolagem por voz com o reconhecedor do Android
- botões de volume como controle remoto (+ rola/pausa, − volta linhas)
- tela sempre ligada e modo tela cheia

Código nativo em `android/`. A chave de assinatura (`android/app/deixa.keystore`) está no repositório para simplificar; antes de publicar na Play Store, gere uma chave nova e guarde-a fora do repositório.
