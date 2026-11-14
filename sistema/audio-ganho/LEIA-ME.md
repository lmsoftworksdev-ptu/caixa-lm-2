# Ganho extra da saída do fone (2026-11-11)

O 100% do app é o passo 14 de 15 do volume do Android (-3 dB). Para compensar, o ganho digital do codec
das saídas do fone (RX1 e RX2 Digital Volume) subiu de 84 (0 dB) para 87 (+3 dB) em /system/etc/mixer_paths.xml
(linhas 23 e 24). O alto-falante interno (RX3) não mudou.

Voltar ao original: copiar mixer_paths.original.xml para /system/etc/mixer_paths.xml (adb root; mount -o remount,rw /system)
e reiniciar. Teste ao vivo sem editar o arquivo: tinymix "RX1 Digital Volume" 84 (e RX2).
Mais forte: 90 = +6 dB (pode distorcer em músicas altas). Faixa do controle: 0 a 124.

## Atualização
O reforço de +3 dB no codec foi DESFEITO (voltou a 84, arquivo original restaurado) porque o app agora usa o passo 15 de 15
(0 dB). Somar os dois daria +3 dB acima do máximo digital e distorce (chiado) em músicas altas.

## Boost Option (2026-11-11)
"Boost Option" do fone: BOOST_ALWAYS -> BYPASS_ALWAYS (menos chiado, a pedido do usuário). Mudado na linha do ctl "Boost Option"
dentro de /system/etc/mixer_paths.xml (cópia atual em mixer_paths.atual.xml). Para voltar: restaurar mixer_paths.original.xml.
Teste ao vivo: tinymix "Boost Option" BOOST_ALWAYS.

## Botão de energia como play/pause (2026-11-14)
`qpnp_pon.kl` vai em /system/usr/keylayout/ (mount -o remount,rw /system; chmod 644; reiniciar). O app trata: toque curto = play/pause,
segurar 3 s = modal de desligar. Para voltar ao botão original: apagar o arquivo e reiniciar.
