# Auto-cura do boot (2026-11-14)

Problema: desligar sem aviso deixa arquivos do /data cheios de zeros; `/data/system/profiles.xml` (NPE em ProfileTriggerHelper) e
`/data/misc/wifi/networkHistory.txt` (StringIndexOutOfBounds em WifiNetworkHistory) derrubam o system_server em ciclo e a caixa
fica na animação de inicialização.

Solução: `caixa-cura.rc` (só comandos internos do init, que pode escrever em /data/system; serviços de init comuns rodam no domínio
qti_init_shell e a transição para u:r:su:s0 é negada). No `post-fs-data`, antes do sistema subir: copia `profiles-padrao.xml` por cima de
`profiles.xml` e apaga `networkHistory.txt` (o sistema recria os dois; as redes Wi-Fi ficam em wpa_supplicant.conf).

Instalação (adb root; mount -o remount,rw /system; mount -o remount,ro /system no fim):
- caixa-cura.rc       -> /system/etc/init/caixa-cura.rc            (chmod 644)
- profiles-padrao.xml -> /system/etc/caixa/profiles-padrao.xml     (chmod 644; mkdir /system/etc/caixa)
(restorecon nos dois). Testado: arquivos esvaziados, reinício, a caixa subiu sozinha.
Para desativar: apagar /system/etc/init/caixa-cura.rc e reiniciar.
