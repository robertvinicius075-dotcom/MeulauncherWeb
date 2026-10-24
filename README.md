# Seu HTML como launcher de TV (APK)

`app/src/main/assets/index.html` é o **seu arquivo original, sem nenhuma linha alterada**.
No fim dele há um bloco `<script>` marcado "ADICIONADO PARA RODAR COMO APP DE TV" com só isto:

- ícones de apps reais da TV na fileira "Your apps" (UNI TV, YouTube, Netflix e Globoplay primeiro);
- aba "Apps" com todos os apps (antes o botão não fazia nada) e o ícone de configurações abrindo as Configurações da TV;
- setas e OK do controle movendo o foco; o nome do app em foco aparece ao lado de "Your apps";
- Voltar fecha o player ou volta para "For you"; Home faz o mesmo; o canal para ao abrir outro app;
- plano B se o hls.js não carregar (sem internet).

`HomeActivity.java` mostra a página numa WebView, torna o app um launcher (HOME) e oferece à página
3 funções: listar apps, abrir app e abrir configurações. Também faz funcionarem o `alert()` e o botão
"Arquivo .m3u" do seu HTML. O hls.js continua vindo da internet, como no seu arquivo.

## Gerar o APK
GitHub: envie os arquivos e rode Actions → "Compilar APK" → Run workflow; baixe o APK em Artifacts.
Android Studio: abra a pasta e use Build → Build APK(s), ou `gradle assembleDebug` (JDK 17, SDK 34).

## Instalar e definir como tela inicial
```
adb connect IP-DA-TV:5555
adb install -r app-debug.apk
adb shell cmd package set-home-activity com.meulauncher.tv/.HomeActivity
```
Ou aperte Home na TV e escolha Meu Launcher → Sempre. Teste apertando Home dentro de outro app.
Desfazer: `adb uninstall com.meulauncher.tv`.

## Limites
- Não compilado nem testado numa TV; a interface foi testada num navegador com um Android simulado.
- Sem Play Store, a WebView da TV não atualiza: se a tela abrir quebrada, veja a versão em
  Configurações → Apps → Android System WebView.
- Vídeo de fundo e player pesam numa TV de 1 GB de RAM.
- Algumas TVs recusam um launcher próprio ou ignoram o padrão escolhido.
- Os nomes dos canais de uma lista entram como HTML (como no original): use só listas de fonte confiável.
