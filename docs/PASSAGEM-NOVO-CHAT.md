# Passagem para um chat novo — Caixa LM Sound (Moto G3)

Cole este texto (ou peça para o novo chat ler este arquivo) no começo do chat novo. Fale em português do Brasil, de forma simples.

## O projeto
Caixa de som inteligente caseira estilo Echo Show: um **Moto G3 (XT1544, osprey_udstv)** usado deitado na horizontal (USB à direita) como tela e cérebro. Pasta do projeto: `C:\Users\LM SoftWorks\Desktop\Caixa LM 2` (app Kotlin nativo, pacote `br.com.lmsoftworks.caixa2`). A pasta `Caixa de Som LM` é o app antigo (só leitura). Protótipos HTML de referência em `Desktop\Interface LM Sound` e `Desktop\Atualizacao Fundo e Icones`.

## Estado do aparelho (tudo feito e aprovado pelo usuário)
- **ROM:** LineageOS 14.1 (Android 7.1.2) sem Google, TWRP 3.3.1, root por `adb root` (ligado nas opções do desenvolvedor). Backups em `Caixa LM 2\backup-particoes` e `rom`.
- **Boot personalizado:** logo.bin com a tela "INICIANDO" (formato MotoRun/BGR, script `boot\motologo.py`), `bootanimation.zip` (animação premium, loop sem emenda, fade-out final), brilho fixo em 255 (overlay `/system/vendor/overlay/CaixaBrilho.apk`), CABC desligado (`/system/etc/init/caixa-tela.rc`), câmera parada (`caixa-camera.rc`), sem barra de status/botões do Android (`qemu.hw.mainkeys=1` + overlay + `Quiosque.kt`).
- **App é o launcher de sistema:** `/system/priv-app/CaixaLM2/CaixaLM2.apk` (permissões privilegiadas: STATUS_BAR, BLUETOOTH_PRIVILEGED, DUMP). Se mudar permissões privilegiadas no manifesto: gravar o APK em /system, `pm uninstall br.com.lmsoftworks.caixa2`, esperar 15 s, reiniciar. Para mudanças comuns: `adb install -r -d app\build\outputs\apk\debug\app-debug.apk` + `pm grant` de ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION e RECORD_AUDIO (não precisa reiniciar).
- **Compilar:** pelo terminal do app (não pelo sandbox): `& "C:\Users\LM SoftWorks\Desktop\Caixa LM 2\compilar.cmd"`, um por vez, ~25 s.
- **60 pacotes** restantes; 26+ apps de sistema movidos para `/system/removed_apps` (lista em `docs\apps-removidos.txt`).
- **Telas prontas:** Início (hora, data, 6 ícones quadrados Lucide), Wi-Fi (lista, senha com teclado, detalhes), Bluetooth (a caixa é **receptor A2DP "LM Sound"**; pareia sozinha), Tocando agora (título, artista, álbum, capa baixada do iTunes, tempo, anterior/pausar/próxima; repeat e seek NÃO existem no AVRCP do Android 7.1), Luzes LED (cor, brilho, efeitos, prévia). Fundo desfocado que muda de cor por menu; voltar = deslizar do canto esquerdo (barrinha cinza); cabeçalho com indicadores verdes de LED/Bluetooth/Wi-Fi.
- Memória da sessão anterior em `.claude\projects\...\memory` (arquivo `caixa-led-protocolo.md`).

## Fita de LED (Zengge, BLE) — O QUE FALTA FUNCIONAR 100%
- Nome `LEDnetWF020033506AF0`, **endereço E4:98:BB:50:6A:F0**. Escrita em `0xFF01` (serviço `0xFFFF`), avisos em `0xFF02`. Protocolo completo em `docs\protocolo-led.md`. Código: `LedControle.kt`, `TelaLed.kt`, `TesteLed.kt`.
- Comandos: ligar `3b 23 00×10`, desligar `3b 24 00×10`, cor fixa `31 R G B 00 00 0f`, modo música `37 01`/`37 00` e quadro de cor contínua `36 d1 R G B 00 00 14`. Quadro: `[contador 2B] 80 00 00 [tam-1] [tam] 0b [comando] [soma do comando]`.
- **Rítmico lê o áudio da caixa** (sem microfone): descobre a sessão de áudio que está tocando via `dumpsys media.audio_flinger` (permissão DUMP; o Visualizer da sessão 0 só dá zeros neste aparelho) e liga um `Visualizer(sessão)`; mede o grave (40–180 Hz), detecta batida e acende/apaga seco (110 ms).
- **Descobertas dos testes (importante):**
  1. `connectGatt(autoConnect=true)` **nunca conecta** neste aparelho. Use `autoConnect=false` (conecta em <1 s com a fita ligada e livre).
  2. A fita **trava** (para de responder e some do ar) se receber cores **rápido demais**: ~13/s travava em 3 s; ~8/s durou ~80 s. Limite atual no código: uma cor a cada 170 ms (~6/s). Ainda falta provar que isso aguenta minutos seguidos.
  3. Depois de uma trava ou de matar o app (`am force-stop`) no meio da conexão, o Bluetooth do Android guarda uma **conexão zumbi** e a próxima conexão devolve **0 serviços** (`servicos descobertos total=0`). Só **reiniciar o Bluetooth do Android** (`service call bluetooth_manager 8` e depois `6`) limpa. Não chamar `BluetoothGatt.refresh()` (piorou). Idéia: curar sozinho ligando/desligando o adaptador depois de várias tentativas com 0 serviços.
  4. Ao reiniciar a caixa, a fita demora ~20 s para aceitar nova conexão (tempo limite do link antigo); reconectar a cada 1 s até dar certo.
  5. O app Zengge (iPhone) disputa a fita: só uma conexão por vez. O usuário já apagou o Zengge do iPhone.
- **Teste isolado por adb** (sem o resto do app): `adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_LED --es acao escanear|direto|auto|escrever|desligar|limpar|fim`; logs com `adb shell logcat -s TesteLed` e `-s CaixaLed`. Funcionou: escanear, conectar direto, listar 4 serviços, escrever, desligar.
- **Pendências em ordem:** (a) provar rítmico estável por vários minutos com a taxa limitada; (b) conexão automática confiável no boot (a fita já estará ligada); (c) autocura do estado "0 serviços"; (d) capturar efeitos prontos e velocidade com o Zengge (comandos `38 ...`), opcional.

## Etapas futuras (só quando o usuário pedir, uma por vez)
Vídeos (de onde vêm?), Ouvir/assistente de voz (Vosk + Google SpeechRecognizer no app antigo, ver pasta `Caixa de Som LM`), configurações mínimas. A tela Música "de verdade" (arquivos próprios) também.

## Como o usuário gosta de trabalhar
Mudanças pequenas e testadas; ele mesmo testa no aparelho. Nunca baixar APK de terceiros sem pedir. Pedir confirmação antes de gravar partições ou apagar. Tudo na horizontal (16:9), texto branco, cores consistentes, sem botões pequenos (gestos no lugar). Evitar reiniciar o celular à toa. Não ficar tocando na tela por adb.
