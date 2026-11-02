# Passagem: música do iPhone sem título/capa (Bluetooth da caixa)

Documento para continuar em outro chat. Estado em 2026-11-02, branch `main`. Leia este arquivo inteiro antes de mexer em Bluetooth.

## O problema (o que falta resolver)

Quando o iPhone toca música pela caixa (LM Sound, Moto G3, Android 7.1.2), às vezes a música **toca mas a tela não mostra título, capa nem estado** (aparece só "Tocando do celular" ou nada), e o vídeo/música do app não pausa direito quando o iPhone dá play. É **intermitente**: sobrevive a reiniciar caixa e iPhone, e depois de algumas conexões passa a funcionar.

O que o usuário quer: conectou o iPhone, **funciona de primeira**, sem o usuário precisar fazer nada nem dar play de novo.

## Causa encontrada (pelos logs, não é bug do app)

Os dados da música chegam pelo AVRCP (canal de controle do Bluetooth), que é tratado pelo serviço `com.android.bluetooth` do Android (`AvrcpControllerService`, `A2dpMediaBrowserService`). O app só lê isso com `MediaBrowser`/`MediaController` (ver `MusicaControle.kt`).

Comparando conexões boas e ruins no logcat (tags `AvrcpControllerService`, `BluetoothAvrcpControllerJni`, `bt_btif`):

- **Conexão boa** (iPhone parado, logo após conectar): o serviço recebe do iPhone `btavrcp_play_status_changed_callback`, `btavrcp_playerapplicationsetting_callback` e `btavrcp_track_changed_callback` em ~0,2 s. Depois o título/capa chegam normalmente (`broadcastMetaDataChanged`).
- **Conexão ruim**: depois de `btavrcp_register_notification_absvol_callback` (volume) **não vem nada**. Em alguns casos chegam só `btavrcp_play_position_changed_callback` (posição), e o estado vira "tocando" (3) por causa dela, mas **nunca vem `track_changed`** nem `play_status_changed`. Sem `track_changed` não há metadados (`mmd: null` no `A2dpMediaBrowserService`).
- O trecho de log (SDP, `AVRC_Open`, `bta_av_rc_opened`, features) é **igual** nas boas e ruins, inclusive o papel (`role 0` quando a caixa abre a conexão, `role 1` quando o iPhone abre). Então não é quem abre a conexão, nem a ordem do SDP. Parece o registro sequencial de notificações do Bluedroid do Android 7.1 ficando pela metade (corrida com o registro de volume absoluto do iPhone, ou resposta do iPhone perdida). Aparece também a linha `Blacklist for AVRCP1.5 = 1` (nas duas).
- No estado ruim o `PlaybackState` do `MediaController` fica `state=1` (STOPPED) com `actions=0` e metadata vazia.

## O que resolve na prática (descoberto pelo usuário e confirmado em log)

**Desligar e ligar o Bluetooth DA CAIXA** (só o dela, o iPhone reconecta sozinho) refaz o registro e, na maioria das vezes, a conexão fica boa. Refazer só a conexão A2DP do iPhone (disconnect/connect do perfil sink) **não resolve** (5 de 5 falharam). Desligar o Bluetooth do iPhone sozinho também não é garantia.

## O que está implementado para contornar (instalado na caixa, commitado)

Em `InicioActivity.kt` (bloco "O Bluetooth do Android daqui às vezes registra só parte dos avisos", dentro do `tique` de 500 ms), `BluetoothControle.kt` e `ModalConexao.kt`:

1. **Toda conexão nova** (`!conexaoAjustada` e celular conectado há 1,5 s) abre o modal **"Conectando a <nome>"** (`ModalConexao`) e chama `bluetooth.reiniciarPorFalhaDeMidia()`: desconecta os aparelhos, desliga o Bluetooth do jeito limpo (`desligarLimpo`), liga de novo, e depois de 3,5 s a caixa chama o celular (`iniciarReconexao`) se ele não voltou sozinho.
2. O modal fecha quando o celular reconecta e fica estável 4 s (ou depois de 35 s). Conta `reiniciosBt`, máximo 3 dentro do modal e 5 no total por conexão; zera quando o título chega ou o celular fica mais de 8 s fora.
3. **Verificação dentro do modal:** depois de reconectar, se `musica.semEstadoDoCelular` (estado do MediaController parado/nulo), refaz o ciclo ainda atrás do modal.
4. Redes de segurança fora do modal: conectado e sem estado por 8 s, ou celular tocando sem título por 7 s, repete o ciclo (com modal).
5. **Reconexão automática** (`aoPerderConexao`/`iniciarReconexao`): se o último celular cair e não houver outro conectado, a caixa tenta voltar a cada 6 s (primeiro 30 s), depois a cada 20 s, até 10 min. Não corre atrás de aparelho que a pessoa desconectou/esqueceu no menu (`desconectadosPorMim`).

### Por que ainda falha (último teste do usuário)

O primeiro ciclo roda (modal aparece), mas a conexão que sai dele **às vezes ainda vem ruim** (a falha é aleatória). O usuário deu play, não apareceu, e só o ciclo seguinte (fallback depois do play) consertou. A verificação do item 3 foi escrita assumindo que, numa conexão **boa** com o iPhone parado, o `MediaController` mostra `PAUSED` (2). **Isso é falso**: num log de conexão boa, o `play_status_changed` chega mas o serviço **não** faz `broadcastPlayBackStateChanged` até o A2DP começar/parar, então o estado do MediaController continua `1` mesmo na conexão boa. Portanto **`semEstadoDoCelular` não distingue boa de ruim com o iPhone ocioso** e pode causar ciclos desnecessários (até 3) dentro do modal. **Precisa de outro discriminador.**

## Próximos passos sugeridos

1. **Achar um discriminador confiável logo após reconectar, sem depender do play.** Candidatos:
   - `BluetoothAvrcpController` (perfil 12, via `getProfileProxy`) tem `getPlaybackState(device)` e `getPlayerSettings(device)` (confirmado no `framework.jar` do aparelho). Em conexão boa o serviço guardou play status e app settings; na ruim, não. Já existe `BluetoothControle.consultarAvrcp { estado, config -> }` (usa proxy rápido e fecha) e um gancho de diagnóstico no `tique` que loga `CaixaDiag: 5s: estado=.. config=..` e `12s:`. **Falta correlacionar com a verdade do log** (`btavrcp_play_status_changed_callback`/`track_changed` presentes ou não) em várias conexões. Se `config=true` ⇔ conexão boa, trocar `semEstadoDoCelular` por isso.
   - Para fazer o experimento sem o usuário: criar o arquivo `/sdcard/Android/data/br.com.lmsoftworks.caixa2/files/sem_ciclo` (desliga o ajuste automático; remover depois). `adb shell svc bluetooth` **não existe** neste Android; alternar o Bluetooth pelo menu da caixa ou pelo iPhone.
2. Se der: ciclo no modal só repete quando o discriminador disser "ruim"; o modal fecha quando disser "bom" (e nunca deixa o usuário dar play numa conexão ruim).
3. Atacar a causa no sistema: ativar o **registro HCI do Bluetooth** (Opções do desenvolvedor, ou `settings put secure bluetooth_hci_log 1`) e comparar os PDUs AVRCP (GetCapabilities/RegisterNotification) de uma conexão boa e uma ruim. Investigar se o iPhone responde com erro/rejeição, ou se a caixa nem envia o registro. Ideias: evitar a corrida com o volume absoluto, propriedades do stack, `interop_database.conf` (`Blacklist for AVRCP1.5`).
4. Se o aviso "Conectando" ficar longo (hoje ~15-20 s), diminuir esperas em `reiniciarPorFalhaDeMidia` (1,5 s antes de ligar, 3,5 s até chamar o celular). Cuidado: reiniciar o Bluetooth demais já travou o serviço do Android neste aparelho (só reiniciar a caixa resolve; o app avisa pelo `aoTravado`).
5. Remover depois os logs temporários (`Idioma`, `Nivelador`, `CaixaBt` extras, `CaixaVol`, `CaixaDiag`) e o gancho `sem_ciclo`.

## O que JÁ FUNCIONA (validado pelo usuário, não mexer sem motivo)

- **Volume:** o volume da caixa manda ao conectar/dar play (janela de nivelamento, `PainelVolume.imporPor`): o iPhone é nivelado para o da caixa (maior ou menor); mudanças depois da janela, no iPhone ou na caixa, valem. O slider do iPhone acompanha sozinho.
- **Prioridade quem deu play por último manda:** play no iPhone pausa o vídeo/música do app (`aoCelularComecou`, confirmação de 1,5 s); play no app pausa o iPhone (`PlayerMidia.aoComecou` -> `MusicaControle.appComecou`, com janela de 8 s contra o "eco" do Bluetooth devolvendo o som). Alexa pedindo música com o iPhone tocando pausa o celular e não deixa voltar (foco de áudio pedido logo no `iniciarItemAgora`).
- Alexa/assistente: pausa com fade e devolve volume, volume por voz, sem repetir.
- Transição em cubo sem tranco, menu Receitas (3 fontes), tela inicial com 5 botões, teclados (som, cursor, apagar repetindo), esmaecer entre música e vídeo, nivelamento de áudio, data/hora com fuso automático/manual, títulos em português, cores de capa fiéis e fita de LED.
- Desligamento/reinício limpo (`sequenciaEnergia`): fecha assistente, player, música, LED, Bluetooth e Wi-Fi, e restaura as redes ao ligar (`Ajustes.restaurarRedes`). **Não testado por reinício real.** Clima com nova tentativa progressiva depois do reinício também **sem teste real**.
- Cartão genérico "Tocando do celular" quando o som chega sem dados; o botão dele agora pausa de verdade (`MusicaControle.alternar` considera `celularSomTocando`).

## Instalado mas NÃO validado pelo usuário

- Reinício limpo da caixa e volta do clima.
- Segundo e terceiro ciclo do modal (a verificação por `semEstadoDoCelular` está errada, ver acima).
- Reconexão automática depois de queda real (só testada com o Bluetooth do iPhone desligado e ligado).
- Microfone: durante um experimento a permissão `RECORD_AUDIO` foi **revogada** por adb. Para devolver: `adb shell pm grant br.com.lmsoftworks.caixa2 android.permission.RECORD_AUDIO` (o usuário ainda não respondeu o resultado do experimento; hipótese antiga de que o microfone atrapalhava o A2DP do iPhone parece descartada pelos logs).

## Como compilar e instalar

Pelo terminal do usuário (o sandbox do assistente não faz o loopback do Gradle): `.\compilar.cmd *> build-log.txt` na raiz, depois `adb install -r app/build/outputs/apk/debug/app-debug.apk`. `adb` em `%LOCALAPPDATA%\Android\Sdk\platform-tools`. Logs úteis: `adb logcat -v time | grep -E "CaixaBt|CaixaVol|CaixaDiag|AvrcpController|A2dpMediaBrowser|btavrcp_|bt_btif"`.

## Mapa dos arquivos

- `BluetoothControle.kt`: estado do adaptador, A2DP playing (`celularTocando`/`celularConfirmado`), `desligarLimpo`, `definirLigado`, `reiniciarPorFalhaDeMidia`, reconexão automática, `consultarAvrcp`.
- `MusicaControle.kt`: MediaBrowser/MediaController do A2DP, origem da música (celular x app), `semEstadoDoCelular`, `appComecou`, `aoCelularComecou`.
- `InicioActivity.kt`: orquestra tudo (bloco do ajuste Bluetooth no `tique`, assistente, volume, energia).
- `ModalConexao.kt`: o aviso "Conectando a ...".
- `PainelVolume.kt`: nivelamento do volume; `PlayerMidia.kt`: player do app e foco de áudio.
- `LEIA-ME-V3.md` e `PASSAGEM-ASSISTENTE-E-ABERTURA.md` (soltos, não versionados) estão **desatualizados**; este arquivo vale mais.
