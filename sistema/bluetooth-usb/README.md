# Wi-Fi interno e Bluetooth USB da Caixa LM 2

Pacote de restauração para o Moto G3 `osprey_udstv`, LineageOS 14.1
`14.1-20190220-NIGHTLY-osprey` e kernel `3.10.49-g415000d`.
O Wi-Fi interno permanece em `wlan0`. O Realtek RTL8821CU muda de
`0bda:1a2b` para `0bda:c820` e fornece apenas o Bluetooth para o Android.
O perfil A2DP Sink recebe áudio do celular.

## Depois de reinstalar a ROM

1. Inicie a ROM compatível e habilite a depuração USB e o root para ADB.
2. Use `adb devices` para obter o serial. Extraia o ZIP inteiro.
3. No PowerShell, confira a compatibilidade com
   `powershell -ExecutionPolicy Bypass -File .\Restaurar-BluetoothUSB.ps1 -Serial SEU_SERIAL`.
4. Para aplicar, repita com `-Aplicar`. Use `-Reiniciar` para reiniciar no fim ou
   reinicie pela interface com o dongle conectado.

O instalador confere os hashes dos arquivos, recusa uma imagem de boot
desconhecida e guarda o boot anterior no aparelho. Em outra versão da ROM
é necessário gerar um boot compatível. Atualizar apenas o APK da Caixa
preserva a configuração de sistema existente.

## Conferência após reiniciar

- `getprop init.svc.rtlbt` mostra `running` e
  `getprop sys.rtl8821cu.status` mostra `usb-ready`.
- `wlan0` recebe IP pelo Wi-Fi interno. `/sys/class/bluetooth/hci0` aponta
  para o controlador USB. O Android mantém `A2dpSinkService` disponível.
- O dongle deve ficar com `power/control=on` na porta OTG `1-1` para
  evitar a suspensão observada nesse aparelho.

A troca de modo e a ponte HCI estão em `fontes/`. O pacote inclui os
binários validados na caixa e a imagem de boot correspondente. O LED
físico do dongle não é usado como indicador de Bluetooth.

O boot foi gerado a partir do `boot.img` da ROM indicada acima. O repacker
preserva o kernel e o DTB e acrescenta o monitor, os módulos e as regras
SELinux. O módulo `btusb` vem do kernel Motorola/LineageOS `415000d`;
o módulo Realtek usa o código RTL8821CU 5.4.1 (`brektrou/rtl8821CU`,
commit `8d20f83`). A biblioteca HCI usa a interface de fornecedor do
LineageOS 14.1.
