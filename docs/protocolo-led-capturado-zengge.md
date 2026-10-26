# Protocolo da fita LED — capturado do app Zengge 1.4.7 (24/10/2026)

Captura feita com HCI snoop do Moto G3 enquanto o usuário usava o app oficial
(`com.zengge.blev2`). Arquivos brutos: `captura-zengge/`. Fita: `LEDnetWF020033506AF0`
(E4:98:BB:50:6A:F0), firmware 37, productId 51.

## Conexão (o que o Zengge faz e o nosso app não fazia)
1. `LE Create Connection` com intervalo **30–50 ms**, latência 0, supervisão **20 s** (0x07D0).
2. Descobre serviços (`ReadByGroupReq`). Serviço 0xFFFF: FF01 = escrita (handle 0x0017),
   FF02 = notificação (handle 0x0014, CCCD 0x0015).
3. **MTU Request 186** (resposta 247).
4. **Liga notificações**: escreve `01 00` no CCCD (handle 0x0015). *(provável causa das travas do outro chat)*
5. Comandos de início, nesta ordem:
   - `22 22` (tipo 0x07) — pedido de informação; resposta é JSON com MAC/versão.
   - **Acerto de relógio** (WriteReq, com resposta) `10 AA AA MM DD hh mm ss DS 00 0f` + soma:
     ano em 2 bytes decimais (`14 1a` = 20 26), mês, dia, hora, min, seg, dia-da-semana.
     Enviado 2 vezes (WriteReq).
   - `81 8a 8b 96` (tipo 0x0a) — consulta de estado.
6. Só depois manda cores/efeitos.

## Quadro
`[contador 2B, incrementa a cada envio] 80 00 00 [tam-1] [tam] [tipo] [comando...] `
- tipo `0b` = comando normal, `0a` = consulta, `07` = info.
- `tam` = tamanho de (tipo + comando + soma)... (nos exemplos: `08 09 0b 31 .. ` => tam-1=08, tam=09).
- Último byte do comando = soma (8 bits) dos bytes do comando.
- Escreve em FF01 com **Write Command (sem resposta)**, exceto o acerto de relógio.

## Comandos confirmados
| Ação | Comando (sem quadro) |
|---|---|
| Ligar | `3b 23 00×10` soma `5e` |
| Desligar | `3b 24 00×10` soma `5f` |
| Cor fixa | `31 R G B 00 00 0f` soma (ex.: azul `31 00 00 ff 00 00 0f 3f`) |
| Brilho (cor fixa) | só escala R,G,B; o app manda ~10/s ao arrastar (ff→00 em passos) |
| Efeito pronto | `38 [efeito] [velocidade] [brilho]` + soma. Efeitos vistos: `25`, `26`. Velocidade `01` (rápido) a `1f` (lento). Brilho `00`–`64`. |
| Modo música (mic do celular / arquivo) | `37 00 00`(início) e depois quadros `36 d1 b0 b1 b2 00 00 14` + soma, ~9 por segundo |
| Consulta de estado | `81 8a 8b 96` |

Resposta de estado (notificação JSON): `{"code":0,"payload":"81 33 23 61 23 01 R G B ..."}`
— byte `23`=ligada (`24`=desligada), `R G B` logo depois de `23 01`.

## Taxa de envio
O Zengge manda **~9–10 quadros/s por mais de 70 s seguidos** no modo música, e 10/s no
arrasto de brilho, sem travar a fita. Portanto o limite de ~6/s do outro chat era um
sintoma: a fita travava porque a conexão não seguia o roteiro acima (notificação
desligada, MTU padrão, intervalo de conexão diferente, sem relógio/consulta inicial).

## Aplicado em LedControle.kt (24/10/2026)
- Conexão na mesma ordem do Zengge: serviços → MTU 186 → avisos (CCCD) → `22 22` → relógio ×2 (com resposta) → consulta `81 8a 8b` → ligar → `37 00 00` → fluxo `36 d1`.
- Modo contínuo: um quadro a cada 110 ms no máximo, repetindo a última cor a cada 1 s se nada mudou.
- Quadro recusado pelo Android volta ao começo da fila (antes era perdido).
- **Cuidado de teste:** `adb install` / `force-stop` no meio da conexão deixa a fita presa (conecta e não responde). Cura: tirar a fita da tomada por 5 s.

## Conexão robusta (24–25/10/2026)
- **Encerrar sempre com `disconnect()` e só depois `close()`** (700 ms). Só `close()` deixa a conexão presa no Android 7: a próxima "conecta" em 2 ms sem falar com a fita e nada responde.
- **Buscar antes de conectar** (scan LOW_LATENCY filtrado pelo endereço). Fita presa a uma conexão antiga não anuncia; só conecta quando ela anuncia. Sem anúncio em 15 s, recomeça.
- O Zengge, quando a fita perde energia, deixa um pedido de conexão pendente na hora (`LE_CREATE_CONN`) e reconecta em <1 s quando ela volta.
- Fade do quadro `36 d1 .. 00 00 FF`: `FF`=`14` suave (arco-íris/respirar), `08` curto (rítmico), `02` seco demais.
- Rítmico = detecção de batida do grave (40–150 Hz) com sensibilidade ajustável na tela.

## Fechamento da conexão (25/10/2026)
- **Ao desligar/reiniciar a caixa** (ACTION_SHUTDOWN): apaga a fita (`3b 24`), espera 400 ms e faz `disconnect()` (700 ms). Sem a desconexão limpa a fita fica "presa" ao link velho, para de anunciar e some por 40 s a mais de 1 min. Com ela: reinício → fita pronta ~3 s depois de o app abrir.
- A tela só mostra "conectada" quando a fita responde (notificação FF02 ao `22 22`); se não responder em 3 s, refaz a conexão. Cor/ligar só são enviados depois dessa confirmação.
- Autocura: ≥20 respostas "0 serviços" seguidas → reinicia o adaptador Bluetooth (no máximo 1x por minuto).
- Log em arquivo que sobrevive ao reinício: `/data/data/br.com.lmsoftworks.caixa2/files/led.log` (`adb root`, `adb shell cat ...`).
- O botão "desligar" do Zengge é o comando `3b 25` (liga/desliga), não uma desconexão Bluetooth.

## Pendente de mapear
Demais efeitos (lista completa de ids), significado exato dos 3 bytes do quadro `36 d1`
(modo música), modo DIY/cena, temporizador.
