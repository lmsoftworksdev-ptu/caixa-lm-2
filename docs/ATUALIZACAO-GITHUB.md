# Atualização pelo próprio app (GitHub)

Como funciona: o GitHub compila o app sozinho e publica o APK em **Releases**. Quando o app da caixa abre (1 vez por dia) ou
quando uma busca de vídeo falha, ele confere o último release. Se for mais novo que o instalado, aparece
**"Atualização necessária"**; tocando em **Atualizar** ele baixa e instala sozinho. Não precisa de cabo nem de computador.

O GitHub confere todo dia se o **NewPipe Extractor** (o motor do YouTube) lançou versão nova. Se lançou, ele troca a versão,
compila e publica sozinho.

## Configuração (uma vez só)

1. Crie uma conta em github.com e um repositório **público** chamado `caixa-lm-2` (o app baixa o APK sem senha, por isso
   público; o NewPipe é GPL, então o código aberto também combina com a licença).
2. Em **Settings > Secrets and variables > Actions > New repository secret** crie `DEBUG_KEYSTORE_BASE64` com o conteúdo
   gerado por este comando no PowerShell (é a chave que assina o app já instalado na caixa; sem ela o Android recusa a
   atualização). Nunca coloque o arquivo `debug.keystore` dentro do repositório.

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore"))
   ```
3. Envie o projeto para o repositório (o `.gitignore` já deixa de fora as pastas pesadas e particulares).
4. Em `app/src/main/java/br/com/lmsoftworks/caixa2/Atualizador.kt`, troque `SEU-USUARIO/caixa-lm-2` pelo seu
   `usuario/repositorio` e instale o app uma vez pelo cabo.
5. Em **Actions > Compilar e publicar > Run workflow**, rode uma vez para testar. Surge o release `v101` (ou maior).

## Observações

- O número do release (`v101`) é o `versionCode`. A caixa só atualiza se ele for maior que o instalado.
- Se você instalar pelo cabo uma versão de teste com número menor, use `adb install -r -d`.
- Se o YouTube mudar algo e o NewPipe ainda não tiver corrigido, a busca fica fora do ar por alguns dias; o app avisa
  "Não consegui buscar agora".
