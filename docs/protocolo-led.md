# Protocolo da fita de LED (Zengge, capturado em 2026-10-24)

App oficial: ZENGGE 1.4.7 (com.zengge.blev2). Captura: registro HCI do Moto G3 + analise.

## Dispositivo
- Nome BLE: `LEDnetWF020033506AF0` — endereco BLE real `E4:98:BB:50:6A:F0` (a primeira leitura do log HCI, `98:BB:50:6A:F0:00`, estava deslocada um byte)
- GATT: escrita em `0xFF01` (handle 0x0017, Write / Write-no-response); notificacoes em `0xFF02` (handle 0x0014, ativar CCCD 0x0015 com `01 00`).
- (Outras caracteristicas vistas: 0xFF10/0xFF11/0xFF22, nao usadas pelo app nos testes.)

## Formato do quadro (escrito em FF01)
`[contador 2B big-endian] 80 00 00 [len1] [len2] 0b [comando...] [checksum]`
- len2 = numero de bytes de `0b` ate o checksum; len1 = len2 - 1.
- checksum = soma dos bytes do COMANDO (depois do `0b`, ate antes do checksum) modulo 256.
- contador sobe de 1 em 1 a cada quadro.

## Sequencia ao conectar
1. Escrever `01 00` no CCCD 0x0015.
2. `0001 80 00 00 02 03 07 22 22` (handshake) e `... 0c 0d 0b 10 YY MM DD hh mm ss ... 06 00 0f cc`-tipo com data/hora (enviada 2x; o app manda hora do celular).
3. `0004 80 00 00 04 05 0a 81 8a 8b 96` (consulta de estado); a fita responde por notificacao com JSON `{"code":0,"p...`.

## Comandos confirmados (ordem das acoes do usuario batendo com a captura)
| Acao | Comando (apos `0b`) |
|---|---|
| Desligar | `3b 24 00 00 00 00 00 00 00 00 00 00 5f` |
| Ligar | `3b 23 00 00 00 00 00 00 00 00 00 00 5e` |
| Cor RGB | `31 RR GG BB 00 00 0f chk` (vermelho `31 ff 00 00 00 00 0f 3f`, verde `31 00 ff 00 ...3f`, azul `31 00 00 ff ...3f`, branco `31 ff ff ff 00 00 0f 3d`) |
| Brilho | NAO ha comando proprio: o app escala RGB (ex.: branco `bd bd bd`, `74..`, `41..`, `1d..`, `00..`) |
| Microfone ligar | `37 01 38` (modo musica pelo microfone do celular) |
| Microfone desligar | `37 00 37` |
| Outros modos musica | `37 02 39`, `37 03 3a` (nao identificados) |
| Fluxo do modo musica | a ~10 Hz: `36 d1 RR GG BB 00 00 14 chk` (cor calculada pelo app a partir do som) |

## Pendente
- Velocidade/efeitos internos: a captura do modo microfone nao trouxe comando de velocidade; falta capturar efeitos prontos (comando `38 ...` apareceu na 1a captura, parece modo/velocidade: `38 27 10 32`, `38 28 01 64`).
- Confirmar `37 02`/`37 03`.
