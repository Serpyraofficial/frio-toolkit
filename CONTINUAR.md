# Onde parou, 28/11/2026

**Ramo wip-5.22 (NÃO lançar até o dono pedir):** juntando mudanças do app para lançar de uma vez. Já tem: convite discreto para avaliar no site (Avaliar em Novos.kt; card na tela inicial depois de 4 dias de uso, no máximo 3 vezes, 20 dias entre elas) e item Manutenção > Avaliar o app. No lançamento: subir versionCode/versionName, revisar NOVIDADES.txt, fazer merge em android-app.

**5.21 PUBLICADA em 28/11** (caixinha flutuante do código de pareamento, passo a passo Xiaomi/POCO). Avaliações no site e resumo por e-mail a cada 15 dias já no ar (servidor e site).

**5.20 PUBLICADA em 28/11**: tirou o serviço de Acessibilidade (o Play Protect do Brasil BLOQUEIA a instalação de APK de fora da loja que declara Acessibilidade, leitura de SMS ou de notificações). NUNCA declarar esses serviços. O modo completo agora é só o passo a passo com o código na notificação.

**5.19 PUBLICADA em 28/11** (modo completo sem Shizuku: Interno.kt, Automacao.kt, ServidorEntrada.kt; sem teste em celular real; falhas vão para Erros como 'modo completo: <etapa>' com o texto da tela). Próximo: corrigir pelo que aparecer em Erros e depois o espanhol.

**5.18 PUBLICADA em 28/11** (servidor, site, painel e app versionCode 80). Próximo: "Ativar modo completo" sem o Shizuku (seção no fim), depois o espanhol.

## Histórico da 5.18 (já feito)

Ramo: `wip-5.18`. NADA disso está publicado. O servidor no ar ainda é a versão 2faa020f, o app é a 5.17.

## Pronto e testado (só no ramo)
- servidor/worker.js:
  - planos novos (/planos?v=3): 7 dias R$4,90; 1 mês R$12,90; 3 meses R$29,90; 6 meses R$49,90; 1 ano R$79,90; vitalício R$119,90; Família 1 mês R$24,90 e 1 ano R$149,90 (3 celulares, gera 2 códigos PRE extras);
  - comissão de cupom (20% padrão, /admin/cupom-pago);
  - promoção com início agendado;
  - /mensagens com público todos/gratis/pro/vencidos (/admin/mensagem);
  - /relato (/admin/relatos);
  - /pirata (/admin/pirataria);
  - /nota (comparar com o mesmo modelo);
  - /admin/novas também devolve relatos.
- site/painel.html: aba Relatos, notificação por grupo, comissão nos cupons, promoção agendada, card de cópias modificadas.
- Testes: /home/claude/servidor/t3/teste_v518.mjs (se o espaço de trabalho ainda existir).

## Fazer antes de publicar o servidor
- Pôr cache (cerca de 5 min, como o promoAtual) em /mensagens e no GET /nota, para poupar o limite do Cloudflare (o dono viu 50% do limite diário).
- Publicar redigitando no editor do Cloudflare (sem token de API). O texto fica em blocos sem comentários e sem indentação, com cerca de 11 mil caracteres cada.

## Falta: app 5.18
- boas-vindas com Raio-X e teste de 1 hora;
- "Preparar para [jogo]";
- comparar com celulares do mesmo modelo (/nota);
- widget e bloco de Configurações rápidas do Modo Jogo;
- calculadora de sensibilidade (grátis);
- "Relatar um problema" (/relato);
- oferta do Pro quando o celular esquenta ou o FPS cai;
- notificações por grupo (ler /mensagens no VendaJob ou AtualizacaoJob);
- cartão de resultado para compartilhar, com o código de indicação;
- resumo semanal;
- conferir a assinatura do app e avisar /pirata (SHA e25d5915…c09b);
- planos v=3 e Família (o status devolve `familia` com os códigos; mostrar ao usuário).
- O plano Família não usa o suporte por WhatsApp (o dono não quer).

## Depois
- site/index.html e termos com os preços novos e o plano Família;
- espanhol por último.

## Regra do dono
- As novidades do app falam só do que muda para quem usa o app. Nada de painel ou servidor.
- Se a mudança for só no painel ou no servidor, não sai versão nova do app.

## Depois da 5.18: "Ativar modo completo" sem o Shizuku (aprovado pelo dono em 27/11)
- Conexão ADB por Depuração sem fio dentro do próprio app, sem instalar o Shizuku. Quem já usa o Shizuku continua funcionando.
- Funções que dá para fazer só com permissões normais passam a funcionar sem acesso especial:
  - DNS por VPN local;
  - Não perturbe, brilho e tela travados no Modo Jogo;
  - fechar apps pela Acessibilidade;
  - limpeza;
  - gravar a tela.
- Automação única para todas as marcas (não uma receita por marca):
  1. abrir as telas pelos atalhos oficiais do Android (DEVICE_INFO_SETTINGS, APPLICATION_DEVELOPMENT_SETTINGS);
  2. ler os textos de dentro do app de Configurações do próprio celular (getResourcesForApplication("com.android.settings"), por exemplo build_number e a tela de depuração sem fio) para saber o nome que aquela marca usa;
  3. a Acessibilidade procura na tela, rola e toca; se não achar, busca o mais parecido ou usa a busca das Configurações; o código de pareamento é reconhecido por ser um número de 6 dígitos;
  4. lista de palavras atualizável pelo painel, sem lançar versão nova; quando trava, manda a marca, a versão e a tela ao painel.
- Depois de conectar: o app dá a si mesmo WRITE_SECURE_SETTINGS e, depois de reiniciar, religa adb_wifi_enabled e se reconecta sozinho quando está no Wi-Fi.
- A pessoa ainda faz à mão:
  - ligar a Acessibilidade;
  - "Permitir configurações restritas" no Android 13 ou mais novo;
  - o PIN, se o celular pedir;
  - estar no Wi-Fi.
- Quando a automação falhar: passo a passo com destaque na tela.

## 2026-11-28: site no visual novo (Y2K)
- index, suporte, termos, privacidade e jogos/* publicados no visual escuro com grade, cromo, VT323 e janelas .exe.
- Celular do topo do site = tela inicial nova do app (Main.dc.html, escalada com .app-mini --k).
- Branch wip-site já mesclada em android-app.
- Pendente: o texto da ajuda (suporte.html) ainda fala em Shizuku para os botões; o app 5.22 segue só no beta.

## 2026-11-28 (tarde): painel novo, erros com print, D1 ligado
- CLOUDFLARE_API_TOKEN criado (pelo dono) e guardado no GitHub: servidor publica sozinho pelo workflow "Publicar servidor"; D1 "serpyra-dados" (5e6a8e15-…) ligado.
- Servidor: /erro aceita print (KV print:<id>, 60 dias), tela, rastro, extra; e-mail na hora (1/h por erro, 20/h total, desliga em config.emailErro); com D1 grava cada ocorrência na tabela erros. /print?id= público (id aleatório de 20). /site público; /admin/site, /admin/config, /admin/relatorio(s), /admin/saude; avaliações com resposta e fixada. Relatório automático (config.relatorioDias, padrão 15) salvo em relatorio:<id> e índice "relatorios".
- Painel: visual Y2K, abas Relatórios e Site, prints com lupa, CSV de vendas. Site lê /site (aviso, faixa, destaque, seção de avaliações).
- App wip-5.22 (versionCode 85, também no beta): Relatorio tira print só da tela do app (EditText, e-mails e chaves cobertos), rastro das telas, manda na hora; Manutenção > Print da tela nos erros. Privacidade atualizada.
- 2026-11-28 13:45: 5.22 (85) lançada para todos (APK d07b88f4de, assinatura conferida, sem Acessibilidade/SMS/notificações).
- 2026-11-28 14:20: 5.23 (86) lançada (abas alinhadas, presente atrás do botão 🎁, área do dono escondida: segurar o nome no Início + chave do dono definida no painel em Site > Chave do dono; /dono/entrar, /dono/novas, /dono/saude). Novidades: só 'Correções e melhorias'.
- 2026-11-28 14:35: compra pelo site (pedido com site:true, codigo WEB+5, vouchers PRE por e-mail; família = 3 códigos). Janela comprar.exe no index (?comprar=<plano> abre direto).

## 2026-11-28: SerpyraToolkit para PC (v1.0, em teste)
- pc/script/PC-Toolkit.ps1 = script do dono (v4.0); pc/ferramentas/preparar.py tira o menu/param e troca as teclas; pc/script/app.ps1 é a ponte (cores ESC[c:Cor], perguntas ESC[p]..ESC[/p], limpar ESC[2J, teclas por arquivo %TEMP%\serpyra-pc-tecla.txt).
- pc/app: WPF .NET Framework 4.8 (C#), roda como administrador, abas Início/Jogos e FPS/Ferramentas/Segurança/Pro; console embutido; Pro: teste 1h, Pix no app, código PRE/PCT, "Já tenho o Pro no celular" (/pc/pedir + /pc/confirmar). Código do PC = SHA256("serpyra-pc|"+MachineGuid) → 8 letras B32.
- Workflow "Gerar PC" (windows-latest): compila, tira fotos das telas (--fotos), testa a ponte, publica pc/dist/SerpyraToolkit-PC.exe.
- Grátis x Pro: grátis = Raio-X, Modo Jogo 2x/dia, limpeza, RAM, temperatura, tela/Hz, teste de conexão, histórico, varredura rápida, quarentena, megazord, desfazer. Pro = o resto.
- Pendente: atualização automática do programa de PC, botão no site, certificado de assinatura (aviso do Windows).
- 2026-11-28 15:05: site duplo Android + PC no ar (hero 'Um Pro. Dois mundos.', seção #pc, instalar no PC, dúvidas de PC). Compra pelo site removida do site (código no servidor continua, sem uso). /pc entrega pc/dist/SerpyraToolkit-PC.exe.
- 2026-11-28 15:30: 5.24 (87) + PC 1.1 + servidor com chave compartilhada (/chave/usar; PRE vale em vários aparelhos com a mesma validade). Atenção: commit com [skip ci] no topo não publica o site no Pages.
