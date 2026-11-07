# Passagem para novo chat · Assistente de voz e abertura (01/11/2026)

Fale com o usuário em **português do Brasil**, simples e direto. Leia também `LEIA-ME-V3.md` (estado das 14 etapas da interface v3).

## Situação em uma frase
A interface v3 está pronta e instalada. O que está **quebrado agora é o assistente (Alexa): ele responde só à primeira chamada e depois para de atender**. O usuário quer **voltar ao estado que funcionava** e só depois mexer em volume.

## O que está pronto e aprovado pelo usuário
- Etapas 1 a 14 da interface v3 (ver `LEIA-ME-V3.md`).
- **Abertura (aprovada):** o som de início (`som_inicio`) toca inteiro quando a interface aparece (áudio "aquecido" por um silêncio antes, `aquecerAudio()` em `InicioActivity`), e a tela fica só **preta** até a interface entrar em fade-in. Sem imagem estática. A animação de boot do Android não permite fade-out.

## O que deu errado (cronologia dos commits, do mais antigo ao mais novo)
| Commit | O que fez | Resultado |
|---|---|---|
| `e53885f` | Abertura: som inteiro + fade (com imagem de carregamento) | Som ok; a imagem estática ficou ruim |
| `1d221e4` | Tirou a imagem (só preto); voz do assistente em **canal do alarme** (`STREAM_ALARM`), volume ajustável; música do celular abaixa; desfoque em RenderScript | Alexa lenta/travando |
| `d026598`, `d487562` | Desfoque fora da thread principal; chave de preferência nova (`volumeVozNivel`, a antiga `volumeVoz` guardava 100 e virava 10) | Ainda ruim |
| `46bdeed` | **Voz de volta ao canal de música** (fixa o volume e devolve depois, sem a barra se mexer); desfoque em CPU sem RenderScript | **Usuário: "Agora está funcionando" (depois do `9241dc3`)** |
| `9241dc3` | Registro do tempo do Gemini, timeout 8 s com nova tentativa, registro do nível do microfone | **ÚLTIMO ESTADO BOM CONHECIDO** |
| `d078173`, `aa37cd7` | Volume da voz vale para toda a interação; música abaixa até 3 em fade | Mudou o jeito de mexer no volume |
| `8f34c3e` | Música abaixa até 2 e voz volta a canal separado (alarme) | Alexa parou de atender de novo |
| `02180a4` | Voz de volta ao canal de música | Usuário: música **sobe** quando chama a Alexa |
| `309eacd` | Canal do alarme de novo + gatilho `TESTE_TOM` | **Usuário: não atende (chamo e não responde)** |

## Estado exato do trabalho agora (importante)
- Na última ação, rodei `git checkout 9241dc3 -- app`: **os arquivos de `app/` estão com o conteúdo do commit `9241dc3` (último bom), só no índice do git**. **Ainda NÃO foi compilado, instalado nem commitado** (o usuário interrompeu antes). O aparelho ainda tem instalado o build ruim (`309eacd`).
- Próximos passos imediatos: compilar, instalar por USB, conferir no log que a Alexa atende várias vezes seguidas, e só então commitar ("Volta o assistente ao estado que funcionava").
- Nada foi enviado com `git push` (e não deve ser sem pedido: o push publica uma versão).

## Por que o volume é difícil (para não repetir o erro)
- Voz, toquinhos e a música do celular (Bluetooth A2DP) saem pelo **mesmo canal de música** do aparelho. Mudar o volume da voz muda o da música do celular.
- O pedido do usuário: ao chamar a Alexa, **a música abaixa para o volume 2 (com fade-out e fade-in ao voltar)**, **a música nunca pode subir**, e a voz/toquinhos/som de pesquisa têm **um volume próprio** ("Volume da voz", padrão 6) que não mexe na barra de volume.
- Só um **canal separado para a voz** consegue isso. Testei `STREAM_ALARM`: com a caixa parada, toquinhos + fala nesse canal não travam o microfone, mas **com uso real a Alexa parou de atender depois da primeira chamada** (duas vezes). A causa **não foi provada**. Hipóteses: o canal do alarme com o microfone `VOICE_COMMUNICATION` e eco/ruído, ou algo na fala online. Alternativas ainda não testadas: `STREAM_NOTIFICATION`/`STREAM_SYSTEM`, ou ganho digital (`LoudnessEnhancer`) na voz, ou pausar a música do celular enquanto a Alexa fala.
- Em `9241dc3` o comportamento era: voz no volume 6 na música (o volume do aparelho vai a 6 enquanto ela fala e volta), música do app (YouTube) abaixa por ganho (`playerMidia.volume(0.2f)`), e a música do celular **não** abaixava de forma independente.

## Como o assistente funciona (arquivos)
- `voz/Assistente.kt` (chamada offline com Vosk "Alexa", pedido, Gemini, resposta), `voz/EscutaAssistente.kt` (microfone: `VOICE_COMMUNICATION`, 2 efeitos de áudio, ganho até 28×, sensibilidade `Ajustes.sensMic`; **não foi alterado nesta rodada**), `voz/Fala.kt` (fala, volume), `voz/Respostas.kt` (`transcrever` Gemini), `voz/FaixaAssistente.kt` (tela do assistente, desfoque; visual não deve mudar), `InicioActivity.kt` (`abafar()` liga o assistente ao volume/ player), `PainelVolume.kt` (`abafarSistema`, `ignorarMudancas`).
- Registros úteis: tag `Assistente` (linhas `mic: nivel=…`, `pedido gravado`, `transcricao:`, `resposta do Gemini ignorada…`) e `VozCaixa` (`transcrever … HTTP … em … ms`). Em `9241dc3` o registro do microfone aparece a cada ~1,5 s.
- Sintomas já vistos: o Gemini às vezes leva mais de 10 s e, se o usuário chama de novo, a resposta é descartada; Wi-Fi sem internet também impede resposta.

## Pendências do usuário (depois de recuperar o assistente)
1. Volume: música a 2 com fade, voz com volume próprio, música nunca sobe. Fazer **uma coisa por vez** e testar chamando a Alexa **4 vezes seguidas** antes de seguir.
2. Desfoque do fundo ao chamar a Alexa em alta qualidade (já existe em CPU em `9241dc3`; confirmar visualmente).
3. Palavra de ativação extra ("Caixa"/"Computador"): opção removida; não fazer sem pedido.
4. Limpar gatilhos `TESTE_*` e logs antes da versão final.

## Regras de trabalho do usuário
Mudanças pequenas e testadas, um teste por vez; perguntar antes de gravar partições, apagar ou baixar; commit só local; dizer o que foi testado e o que não foi; não inventar resultados.

## Comandos
- Compilar (terminal "c1"): `& ".\compilar.cmd" *> build-log.txt` e ler com `iconv -f UTF-16 -t UTF-8 build-log.txt | grep -E "BUILD|FIM codigo|^e: "`.
- Instalar: `adb -d install -r -d app\build\outputs\apk\debug\app-debug.apk`, depois `pm grant` de RECORD_AUDIO, WRITE_SECURE_SETTINGS, ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION, e abrir `.InicioActivity`.
- Ver o registro do assistente: `adb -d logcat -d -v time -s Assistente VozCaixa`.
- Pasta copiada para continuar: `Desktop\Caixa LM 2 - Interface v3\` (pode estar desatualizada em relação a `Desktop\Caixa LM 2`).
