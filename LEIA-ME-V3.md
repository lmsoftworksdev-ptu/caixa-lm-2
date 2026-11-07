# Caixa LM 2 · Interface v3 (estado em 01/11/2026)

Fale com o usuário em **português do Brasil**, de forma simples e direta. Este arquivo vale mais que os documentos antigos de `docs/passagem/`.

## Onde ler primeiro
1. `docs/passagem/LEIA-ME-PRIMEIRO-estado-v2.md` — o que funciona (lógica que NÃO pode quebrar), como compilar/instalar e as armadilhas.
2. `docs/passagem/PROMPT-IMPLEMENTAR-INTERFACE-V3.md` e `design/interface_caixa_lm2_v3.html` — especificação e protótipo.
3. Este arquivo — o que já foi feito na v3 e o que mudou em relação ao protótipo.

## Estado das 14 etapas
| Etapa | Situação |
|---|---|
| 1–2 Tokens, componentes, fundo | Feitas. `object Tema` em `Cfg.kt`; `FundoView` com **6 manchas, raio 1,5×** do protótipo (pedido do usuário). |
| 3 Barra de cima | Feita, **maior que o protótipo** (botões 76, ícone 36, hora 38; pedido do usuário). Wi-Fi sem conexão = ícone normal em vermelho. |
| 4 Início | Feita. Cartão da direita com clima, **cartão de música** (play/pause, deslizar para a esquerda fecha o player), alarme e lembrete. |
| 5 Teclado e tela de texto | Feitas (`Teclado.kt`, `TelaTexto.kt`). Mídia: moldura a partir de y 166; microfone da busca fica em cima do teclado. |
| 6 Wi-Fi e Bluetooth | Feitas (duas colunas, janelas `ModalV3`). |
| 7 Tocando agora, seletor música↔vídeo, player | Feitas. O áudio da música do app vem do **clipe** (busca pontuada por título, artista, "ao vivo" e duração; +2,5 s no começo). Volta sem recarregar. Fade ao pausar/trocar. |
| 8 Mídia | Feita. Busca por voz com texto ao vivo e sons de início/fim. |
| 9 Luzes LED | Feita. Ambilight sem escolha de cor; segue o fundo do menu; borda é só a linha. |
| 10 Alarmes, Lembretes, Listas | Feita. Nomes de alarme opcionais e editáveis (segurar), hora de dormir na aba Alarmes, "FDS", "Finalizados". |
| 11 Previsão do tempo | Feita. Hoje, próximas 36 h (rolável, em linha), 15 dias com Mín/Máx escritos; lua à noite. |
| 12 Configurações | Feita. Tela, Som, Assistente, Conexões, Sistema (cidade do clima, registro, restaurar, nome editável…). `Ajustes.kt` guarda tudo. |
| 13 Sobreposições | Feita **exceto o visual do assistente de voz**, que o usuário pediu para NÃO mudar. Energia, alarme tocando, aviso (`Aviso`), atualização, ocioso: ok. |
| 14 Abertura | Gravada em 01/11/2026: `bootanimation.zip` v3 em `/system/media/` e `logo-lmsound-v3.img` na partição `logo`. Backups: `backup-sistema/bootanimation-antes-v3.zip` e `backup-particoes/logo.img`. |

## Decisões do usuário (valem sempre)
- Assistente: nome de chamada **Alexa**, sem mudar o visual atual (`FaixaAssistente`). Voz da caixa sempre no **volume 6** (o volume do aparelho vai a 6 enquanto ela fala e depois volta).
- Nada de blur ao vivo; sem barra de rolagem nas listas (só deslizar); texto longo desliza (`TextoRolante`, devagar, com degradê nas pontas).
- Ícones animados (clima, equalizador etc.) a 30 quadros por segundo; param no modo ocioso (`Animacao.pausada`).

## Pendências e ideias (não feitas)
- Palavra de ativação "Caixa"/"Computador" (a opção foi tirada do menu).
- Visual v3 do assistente (círculo de vidro com o vídeo dentro), se o usuário quiser um dia.
- Cidade do clima ainda pode vir do IP (Unaí em vez de Paracatu) até o usuário fixar em Configurações > Tela.
- `versionCode` instalado localmente é 1: "Atualizar" instala o que estiver no GitHub (que não tem este trabalho até o `git push`).
- Limpar gatilhos `TESTE_*` e logs de ritmo antes de uma versão final.

## Armadilhas novas
- **Bluetooth travado:** se o celular toca mas título/capa não chegam e a prioridade celular×caixa falha, o Bluetooth do aparelho travou (ficou ligado horas e foi ligado/desligado várias vezes). **Reiniciar o aparelho resolve** (ligar/desligar só o Bluetooth não resolve). O app também pede sozinho a conexão do perfil de áudio quando o celular conecta (`garantirPerfil`).
- Prioridade celular×caixa: usa o estado do áudio A2DP (`celularTocando`), o foco de áudio e `pausarCelular()`; não depende dos dados da música.
- Heredoc do `bash` estraga barras invertidas: para scripts de edição use a ferramenta de escrever arquivos.
- Compilar: `& ".\compilar.cmd" *> build-log.txt` (log em UTF-16). Instalar por USB: `adb -d install -r -d app\build\outputs\apk\debug\app-debug.apk` e depois `pm grant` das 4 permissões; o Wi-Fi do `adb` às vezes trava no `install`.

## Git
Todo o trabalho está commitado na `main` local (nenhum `git push` foi feito). Marca da v2: `estavel-2026-11-01`.
