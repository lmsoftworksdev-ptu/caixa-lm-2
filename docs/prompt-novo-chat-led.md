# Prompt para o novo chat — refazer a conexão da fita LED copiando o app Zengge

> Cole tudo abaixo no começo do chat novo. Fale em português do Brasil, de forma simples e direta. O usuário testa no aparelho; faça mudanças pequenas e peça um teste de cada vez.

---

## 1. O projeto

Caixa de som inteligente caseira (estilo Echo Show) feita com um **Moto G3 (XT1544)** deitado na horizontal. App Kotlin nativo, pacote `br.com.lmsoftworks.caixa2`, em **`C:\Users\LM SoftWorks\Desktop\Caixa LM 2`** (é o launcher do sistema, gravado em `/system/priv-app/CaixaLM2`; ROM LineageOS 14.1 / Android 7.1.2 sem Google; `adb root` ligado, mas **some a cada reinício do celular**: rodar `adb root` de novo). Leia primeiro `docs\PASSAGEM-NOVO-CHAT.md` (estado geral do aparelho e jeito do usuário trabalhar), `docs\protocolo-led.md` e `docs\protocolo-led-capturado-zengge.md`.

Como compilar e instalar (sem reiniciar o celular):
- Compilar pelo **terminal do app** (não pelo sandbox), um por vez, ~25 s: `& "C:\Users\LM SoftWorks\Desktop\Caixa LM 2\compilar.cmd"`.
- Instalar: `adb install -r -d app\build\outputs\apk\debug\app-debug.apk` e depois `adb shell pm grant br.com.lmsoftworks.caixa2 android.permission.ACCESS_COARSE_LOCATION` (idem `ACCESS_FINE_LOCATION` e `RECORD_AUDIO`), e `adb shell input keyevent KEYCODE_HOME`.

## 2. O que você deve fazer

**Refazer do zero a conexão Bluetooth LE com a fita de LED**, copiando **exatamente** o que o app oficial **Zengge** (`com.zengge.blev2` v1.4.7) faz. Não adivinhe: o código decompilado do Zengge está na pasta `referencia-zengge\codigo-zengge-decompilado\zengge\hagallbjarkan\` (biblioteca BLE dele). **Leia essa biblioteca inteira, linha por linha, e reproduza o comportamento**, em uma classe nova (sugestão: `FitaBle.kt`), mantendo a interface pública que as telas já usam em `LedControle` (`iniciar()`, `parar()`, `conectar()`, `tentarDeNovo()`, `definirCor/Modo/Brilho/Sensibilidade/Ligado`, e as propriedades `conectado`, `conectando`, `online`, `conectandoParaTela`, `erro`, `cor`, `modo`, `brilho`, `ligado`, `sensibilidade`).

Arquivos principais do Zengge para ler (todos em `...\zengge\hagallbjarkan\`):
- `gatt\impl\HBConnectionImpl.java` — a conexão GATT (já lido; resumo abaixo)
- `handler\zghb\ZGHBDeviceHandler.java` — **quando e como ele decide conectar** (`fetch`, cache LRU de conexões, `connect`, `deviceStatusCheck`, `onFilter`, pausa/retomada). **Ainda não foi lido por inteiro: leia.**
- `scan\DeviceScanner.java`, `utils\BleScanner.java`, `utils\BleComponentUtils.java` — a busca de aparelhos
- `handler\zghb\ZGHBReceiveCallback.java`, `ZGHBWriteUtils.java`, `handler\BaseHandler.java`, `handler\TimeCapsule*.java`, `utils\LockUtils.java`, `utils\SequenceUtils.java`, `core\LRUCache.java` — fila de comandos, recebimento, temporizadores
- `device\ZGHBDevice.java` — como ele lê o anúncio (dados do fabricante) da fita
- Em `zengge\wifi\...` há as telas e o modo música/microfone (nem todos os arquivos copiaram por limite de caminho do Windows; se precisar, decompile de novo o APK `C:\Users\LM SoftWorks\Downloads\zengge-1-4-7.apk` com jadx — ver seção 8).

## 3. Estado atual do código (importante)

- `app\src\main\java\br\com\lmsoftworks\caixa2\LedControle.kt` está na **versão ORIGINAL de conexão** (a de antes desta sessão), de propósito. **Todas as tentativas de consertar a reconexão foram removidas.** A cópia da versão experimental (com todas as tentativas) está em `referencia-zengge\LedControle.tentativas-24-25out.kt.txt` — só para consulta, **não** reaproveite sem checar com o código do Zengge.
- Foram **mantidos** (não são de conexão e o usuário aprovou): log em arquivo (`files\led.log` do app), o efeito **Rítmico por batida do grave** (40–150 Hz) com **barra de Sensibilidade** na tela `TelaLed`, fade do quadro `36` por efeito (`0x08` no rítmico; `0x14` no arco-íris/respirar), ritmo de envio ~9 quadros/s, **apagar a fita ao desligar/reiniciar a caixa** (receptor de `ACTION_SHUTDOWN`, escreve `3b 24`), e a **correção do dia da semana** do comando de relógio (segunda=1…domingo=7; o código antigo mandava 0 no domingo).
- Pode mexer à vontade na camada de conexão. Se trocar a classe, mantenha o resto (cores, efeitos, áudio, telas).

## 4. A fita

`LEDnetWF020033506AF0`, endereço `E4:98:BB:50:6A:F0`, firmware 37, productId 51 (módulo tipo ESP). Serviço `0xFFFF`: `0xFF01` escrita (handle `0x0017`), `0xFF02` avisos/notify (handle `0x0014`, CCCD `0x0015`). Quadro: `[contador 2B] 80 00 00 [tam-1] [tam] [tipo] [comando] [soma do comando]`; tipo `0b` comando, `0a` consulta, `07` info. Comandos confirmados:
- Ligar `3b 23 00×10`, desligar `3b 24 00×10`, `3b 25 ...` = o botão liga/desliga do Zengge.
- Cor fixa `31 R G B 00 00 0f`; brilho = escalar RGB.
- Música/ritmo: `37 00 00` (entrar) e fluxo `36 d1 R G B 00 00 FF` (~9/s; `FF` = tempo do fade: `14` Zengge, `08` curto, `02` seco demais).
- Efeito pronto `38 [id] [vel 01..1f] [brilho 00..64]` (ids vistos: `25`, `26`).
- Início de sessão do Zengge (ordem exata): `22 22` (WriteCmd) → logo atrás o **relógio** `10 14 1A MM DD hh mm ss DS 00 0f` (WriteReq com resposta; `DS` 1..7) → depois `81 8a 8b` (consulta de estado).
- A fita responde com avisos JSON: ao `22 22` com `{"code":0,"payload":{"MACID":...,"MAC":...,"MID":"AK001-ZJ2158",...}}`; ao `81 8a 8b` com `{"code":0,"payload":"8133 23 61 23 01 R G B ..."}`. **Logo depois de ligarmos os avisos (CCCD) a fita às vezes manda sozinha um aviso de estado (59 bytes) em ~0,2 s** — chega antes de qualquer comando.
- ~6 s depois de conectar a fita pede atualização de parâmetros (30 ms, timeout 5 s); o celular aceita.

## 5. O problema (o que não funciona)

Medições (todas lidas dos logs, não de impressão):
- **Reiniciar ou desligar a caixa** (fita continua na tomada): reconecta **3–8 s depois de o app abrir**. Funcionou 4 de 5 vezes.
- **Tirar a fita da tomada 5 s e ligar com a caixa ligada**: com o Zengge volta em **1,6–9 s, 3 de 3 vezes**. Com o nosso app, em muitas versões, a fita **aceita a conexão e fica muda** por **68–100 s** (ou não volta); o link cai a cada ~22 s (`status=8`, timeout) e o app reconecta em loop. Nessas conexões o rádio mostra o ATT funcionando (descoberta, MTU, CCCD, até a confirmação do relógio) mas **a fita não manda nenhum aviso** e ignora o `22 22`.
- **Instalar o app por adb** (mata o processo no meio da conexão): a fita fica muda ~40–120 s. Só se resolve desligando o Bluetooth do celular antes de instalar (desconexão limpa) ou esperando.
- **Teste decisivo:** com o nosso app mudo por 15 min, instalei o Zengge (nosso controle pausado) **sem mexer na tomada** e ele conectou em **0,7 s**. Então **a fita está boa; a diferença é do nosso lado.**
- Houve conexões do nosso app **idênticas, byte a byte no rádio, às do Zengge** que funcionaram (aviso em ~0,14 s). Ou seja, o ATT/GATT em si não é o problema; falta achar o que muda o estado da fita ou do Android.

## 6. O que já foi tentado (e o resultado) — não repita sem motivo

1. Pedir MTU 186 antes de ligar os avisos; relógio/`22 22`/consulta na ordem do Zengge; esperar 300 ms depois do CCCD — igual ao Zengge, **não resolveu sozinho**.
2. `disconnect()` antes de `close()` (700 ms) — resolveu "conexão fantasma de 2 ms" e o caso de **reinício da caixa**; não resolveu religar a fita.
3. Buscar a fita por scan filtrado antes de conectar; scan contínuo de fundo sem filtro (como o Zengge); conexão direta pendente — **nenhum resolveu**.
4. Reaproveitar o mesmo `BluetoothGatt` com `connect()` — não ajudou.
5. Repetir `discoverServices` a cada 1,5 s; repetir `22 22` a cada 2 s (sondar) — não ajudou.
6. Chamar `refresh()` (método escondido) antes de `disconnect()`/`close()` — o **Zengge faz isso**; nesta sessão não bastou. (Notas antigas diziam que `refresh()` "piorou" — não confirmado.)
7. Desligar notificação (`setCharacteristicNotification(false)`) antes de fechar; autocura reiniciando o adaptador Bluetooth quando a fita não manda aviso em 10 s — às vezes cura em ~10 s, às vezes não.
8. Bug **meu** corrigido: o aviso espontâneo da fita chegava **antes** de o app ficar "pronto" (por causa do atraso de 300 ms) e era ignorado, deixando o app esperando para sempre. Guardar o aviso resolve isso.
9. Bug **meu** corrigido: dia da semana 0 no domingo.
10. Testado: iPhone com Bluetooth desligado, modo Fixo (sem análise de áudio) — **sem diferença**. Então não é áudio A2DP nem carga de CPU da análise.
11. Escrita com resposta (relógio) às vezes nunca era confirmada e travava a fila do Android: fila serial com timeout resolve esse travamento.

## 7. Diferenças que **ainda sobram** entre o Zengge e o nosso app (investigue)

- Quando e como o Zengge conecta: ele guarda conexões num **cache LRU** (`ZGHBDeviceHandler.fetch`), conecta **sob demanda** e desconecta ao expirar? Quem dispara `connect`? O status "ONLINE/OFFLINE" vem do **anúncio** (scan), não da conexão. Leia `ZGHBDeviceHandler`, `TimeCapsule*`, `LRUCache`.
- O alvo da conexão é o `BluetoothDevice` do **`ScanResult`** (`zGHBDevice.getResult().getDevice()`), não `getRemoteDevice(endereço)`.
- Ele usa uma **thread própria com `wait()/notify()`** e `connectGatt(context, false, callback, TRANSPORT_LE)` + `refresh()+disconnect()+close()` em **toda** desconexão (`releaseConnection`).
- Fila de escrita **estritamente serial**: só manda o próximo depois do `onCharacteristicWrite` do anterior.
- Mantém **scan contínuo** (`SCAN_MODE_LOW_LATENCY`, sem filtro) o tempo todo, mesmo conectado.
- O app dele não é launcher nem está em `/system/priv-app`; o nosso é o launcher (processo sempre vivo) e também roda análise de áudio (`dumpsys` a cada 1,5–4 s) e o Wi-Fi/A2DP. Verifique se algo no **nosso processo** (outros usos de Bluetooth em `BluetoothControle.kt`, `MusicaControle.kt`, scans, `registerApp` acumulados) interfere.
- Verifique `BluetoothControle.kt`: o app da caixa também é **receptor A2DP** ("LM Sound") e mexe no adaptador.

## 8. Ferramentas e técnicas que funcionaram

- **Captura do rádio (HCI snoop):** `adb shell settings put secure bluetooth_hci_log 1` e reiniciar o Bluetooth (`adb shell service call bluetooth_manager 8`, esperar 5 s, `... 6`). Arquivo: `/data/misc/bluetooth/logs/btsnoop_hci.log` (precisa `adb root`; copiar para `/sdcard` e `adb pull`). **O arquivo grava em blocos: os últimos eventos podem demorar a aparecer.** Reiniciar o Bluetooth zera o arquivo. Scripts Python em `referencia-zengge\scripts-analise-rf\` (`btsnoop.py` lista ATT; `cmp.py <arquivo> HH:MM:SS` mostra uma conexão a partir daquele horário; `adv.py` mostra os anúncios da fita; `conns.py` resume cada conexão). O horário do snoop é o do relógio do celular **+4h22m42s** (varia; compare com o log do app). Rodar com `py -I`.
- **Log do app que sobrevive a reinício:** `adb shell "cat /data/data/br.com.lmsoftworks.caixa2/files/led.log"` (precisa `adb root`). Cópia da última sessão: `referencia-zengge\led.log-do-app-ultima-sessao.txt`.
- **Comparar com o Zengge ao vivo:** pausar o nosso controle com `adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_LED --es acao limpar`, instalar o APK (`adb install -r -d "C:\Users\LM SoftWorks\Downloads\zengge-1-4-7.apk"`), abrir (`adb shell monkey -p com.zengge.blev2 -c android.intent.category.LAUNCHER 1`). O próprio Zengge escreve no `logcat` as linhas `HBConnectionImpl: Connecting...`, `connection state`, `discover services`, `onMtuChange`, `enable notification`, `writer.bytes : ...` e `LowerTransportLayerDecoder ... {json}` — dá a linha do tempo dele e todos os bytes que ele manda. **Desinstalar o Zengge ao terminar** (`adb shell pm uninstall com.zengge.blev2`); ele disputa a fita com o nosso app (só uma conexão por vez). Antes de trocar de app, **desligar o Bluetooth do celular (`service call bluetooth_manager 8`)**, trocar, e ligar (`... 6`), para a desconexão ser limpa.
- **Decompilar de novo o APK** (se precisar do que não copiou): jadx 1.5.0 de `https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip` (com JDK em `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`), `jadx.bat --no-res --no-debug-info -j 2 -d <saida> "<apk>"`; são 6.785 classes, ~5 min. **Aspas no caminho com espaço** ao chamar pelo PowerShell. Baixar ferramentas de terceiros só com OK do usuário.

## 9. Como testar (critério de sucesso)

Use o log em arquivo e o rádio para **medir**; o usuário relata o que viu. Cenários, um por vez, esperando a fita ficar verde entre eles:
1. **Reiniciar a caixa** (fita ligada): conectar em poucos segundos depois do app abrir.
2. **Desligar a caixa por ~1 minuto** e ligar: idem.
3. **Tirar a fita da tomada 5 s e ligar** (caixa ligada), 3 vezes seguidas: voltar em **≤ ~5 s**, como o Zengge. **É o cenário que falha.**
4. **Fita já ligada e caixa ligando** e o oposto (caixa ligada primeiro).
5. Rítmico com música (iPhone → Bluetooth "LM Sound") por vários minutos sem cair.
A tela só deve mostrar "Fita conectada" (verde) quando a fita **realmente responde** (aviso do FF02); o usuário reclamou de "alarme falso" de conectado.

## 10. Regras do usuário

- Mudanças pequenas e testadas; ele testa no aparelho. **Não ficar tocando na tela por adb.** Evitar reiniciar o celular à toa.
- **Instalar o app por adb mata a conexão e deixa a fita muda ~1–2 min**: combine tudo que puder numa instalação e avise antes. Se a fita travar de verdade, tirar da tomada por 5 s resolve.
- Pedir confirmação antes de gravar partições ou apagar; nunca baixar APK/ferramenta de terceiros sem pedir.
- O usuário está cansado de testes que não terminam: **leia o código do Zengge primeiro, copie, e só então peça testes.** Explique em português simples o que descobriu.
