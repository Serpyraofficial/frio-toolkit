# Onde parou (5.18), 23/11/2026

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
