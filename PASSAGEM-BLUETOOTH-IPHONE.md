# Passagem: música do iPhone sem título/capa (Bluetooth da caixa)

Estado em 2026-11-02, branch `main`. **Problema resolvido** (validado pelo log: 3 de 3 conexões boas depois do reinício). Este arquivo registra a causa, a solução e o que NÃO repetir.

## O problema

Quando o iPhone tocava pela caixa (LM Sound, Moto G3, Android 7.1.2), às vezes a música tocava mas a tela não mostrava título, capa nem estado, e o vídeo/música do app não pausava direito quando o iPhone dava play. Era intermitente.

## Causa (medida no logcat, 35 conexões)

O AVRCP controller do Android 7.1.2 só funciona na **primeira conexão depois que o processo `com.android.bluetooth` inicia**.

- Primeira conexão depois do reinício do Bluetooth: boa em 10 de 11.
- Reconexão dentro da mesma pilha: ruim em 23 de 24.
- Vale para conexão iniciada pela caixa ou pelo iPhone.

Conexão **boa**: a caixa e o iPhone trocam ~12 pacotes AVRCP (`LCB lcb=1 event=2/6`) e chegam `btavrcp_play_status_changed_callback`, `playerapplicationsetting_callback` e `track_changed_callback`. Conexão **ruim**: só 2 e 2; depois do `register_notification_absvol_callback` não vem nada, e o `PlaybackState` fica em 1 com `getPlayerSettings().mSettings == 0` (na boa: estado 2 e `mSettings == 6`).

Hipóteses testadas e **refutadas**: o `AVRC_Open role` (o `role: 1` aparece ao fechar a conexão anterior); o iPhone abrir o AVRCP (5 de 5 conexões iniciadas pelo iPhone vieram ruins); refazer só A2DP/AVRCP do aparelho (`BluetoothAvrcpController` não tem `disconnect(BluetoothDevice)`); quem inicia a conexão.

## Solução atual (2026-11-02): tela "Tocando em <celular>"

O reinício do Bluetooth para "renovar a pilha" foi TESTADO e REMOVIDO: funcionava na maioria das vezes, mas derrubava a fita de LED (ela demora a reconectar e pisca), e mesmo logo depois do reinício uma primeira tentativa abortada do iPhone já "queimava" a pilha nova. Também foi refutado que a fita de LED atrapalhe: com a conexão da fita desligada por completo, as reconexões seguiram ruins (3 de 3).

Agora a caixa aceita que a conexão às vezes vem sem dados e trata isso na tela:

- Conexão boa (o iPhone manda título/capa): a tela "Tocando agora" de sempre.
- Conexão sem dados (o som toca, o Android ignora o título: `Metadata is not broadcasted, ignoring` no logcat): `PainelSemDados.kt`, uma tela "Tocando em <nome do aparelho>" com barras de música animadas, fundo que muda de cor sozinho e botões anterior, play/pause e próxima (`MusicaControle.anterior/alternar/proxima`, que mandam AVRCP passthrough e funcionam mesmo sem metadados).
- Quem decide: `TelaMusica.atualizar` mostra o painel quando há celular conectado, `bt.celularTocando` e nenhum título; ele fica até o aparelho sair ou os dados chegarem. A `InicioActivity` abre a tela de música sozinha quando o celular toca sem dados por 2,5 s (`abriuSemDados`, com 8 s de pausa antes de contar como outra vez).
- Ao desconectar: `MusicaControle.celularDesconectou()` limpa o cartão; a tela de música volta a pedir para conectar um celular. Não muda de tela.

## Volume (também corrigido em 2026-11-02)

O volume da caixa prevalece sobre o do iPhone na janela de nivelamento (`PainelVolume.imporPor`, 20 s ao conectar, 12 s ao dar play). Dois bugs corrigidos: um volume 0 mandado pelo iPhone era aceito como "mudo" (`restaurarDoCelular`), e `aoConectado` só disparava quando o ACL ainda não tinha adicionado o aparelho (agora dispara em toda conexão de áudio).

## O que JÁ FUNCIONA (não mexer sem motivo)

- Prioridade de quem deu play por último (`aoCelularComecou`, `PlayerMidia.aoComecou` → `MusicaControle.appComecou`, janela de 8 s contra o eco do Bluetooth).
- Alexa/assistente com fade, volume por voz, transição em cubo, receitas, teclados, nivelamento de áudio, data/hora, fita de LED.
- Desligamento/reinício limpo (`sequenciaEnergia`) e clima depois do reinício: **não testados por reinício real**.

## Como investigar de novo

- Logcat: `adb logcat -v time | grep -E "CaixaBt|CaixaVol|AvrcpController|A2dpMediaBrowser|btavrcp_|bt_btif"`. Contar `LCB lcb=1 event=2/6` depois de `bta_av_rc_opened` separa conexão boa de ruim.
- O registro HCI (`btsnoop`) não funciona sem root neste aparelho; `adb shell service call bluetooth_manager` e `svc bluetooth` também não.
- Compilar: `.\compilar.cmd *> build-log.txt` pelo terminal do usuário (o sandbox do assistente não faz o loopback do Gradle), depois `adb install -r app/build/outputs/apk/debug/app-debug.apk`. `adb` em `%LOCALAPPDATA%\Android\Sdk\platform-tools`.
- `LEIA-ME-V3.md` e `PASSAGEM-ASSISTENTE-E-ABERTURA.md` (soltos, não versionados) estão desatualizados.

## Mapa dos arquivos

`BluetoothControle.kt` (adaptador, A2DP, `garantirPerfil`), `MusicaControle.kt` (MediaBrowser/MediaController, `celularDesconectou`), `InicioActivity.kt` (orquestra; hooks `aoConectado`/`aoDesconectado`, abertura da tela sem dados), `PainelVolume.kt` (nivelamento de volume), `PainelSemDados.kt` e `TelaMusica.kt` (tela sem dados).
