# Onde parou, 28/11/2026

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
