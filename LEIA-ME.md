# Meu Painel — app para Android Auto

Três telas na multimídia:
- **Destinos**: um toque e o Waze/Maps já abre navegando.
- **Música**: atalhos de playlists do Spotify, Play/Pausa e Próxima.
- **Painel**: hora, bateria do celular e lembretes (troca de óleo, IPVA…).

## 1. Instalar no celular
1. Instale o **Android Studio** no computador (developer.android.com/studio).
2. Abra a pasta `MeuPainel` no Android Studio (File → Open) e espere o "Gradle sync" terminar.
   Se ele perguntar sobre o Gradle Wrapper, aceite criar/usar o wrapper.
3. No celular: Configurações → Sobre o telefone → toque 7x em "Número da versão"
   → volte em Opções do desenvolvedor → ative **Depuração USB**.
4. Ligue o celular no computador pelo cabo e clique em **Run ▶** no Android Studio.

## 2. Liberar o app no Android Auto
1. No celular abra Configurações → Android Auto.
2. Role até o fim e toque **10x em "Versão"** → confirme o modo desenvolvedor.
3. Menu ⋮ (canto superior) → **Configurações do desenvolvedor** → ative **"Fontes desconhecidas"**.
4. Em Android Auto → **Personalizar inicializador**, confira se "Meu Painel" está marcado.

## 3. Configurar
Abra o app **Meu Painel** no celular, edite as listas (uma linha por item, `Nome | valor`) e toque em **Salvar**.
Toque também em **"Liberar controle de música"** e ative o Meu Painel em "Acesso a notificações".

## Observações
- Os atalhos de música controlam o Spotify que já está aberto. Se não responder, dê play uma vez no Spotify.
- O Android Auto limita as listas a ~6 itens por tela enquanto o carro anda.
- O app de navegação usado é o padrão escolhido no Android Auto (Waze ou Maps).
