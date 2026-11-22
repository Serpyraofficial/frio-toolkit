# PC Toolkit Mobile 4.0 (Android, root)

Versao para celular do PC Toolkit 4.0: as mesmas secoes e funcoes, adaptadas para Android com root
(Magisk, KernelSU ou APatch). App nativo em Kotlin, sem bibliotecas externas.

## Como gerar o APK (sem instalar nada no PC)

1. Crie um repositorio no GitHub (pode ser privado).
2. Envie TODO o conteudo desta pasta (incluindo a pasta oculta `.github`).
   - Pelo site: "Add file" > "Upload files" e arraste as pastas. Se o site nao mostrar a `.github`,
     crie o arquivo `.github/workflows/build-apk.yml` pelo "Add file" > "Create new file" e cole o conteudo.
3. Abra a aba **Actions**: o workflow "Gerar APK" roda sozinho (ou clique em "Run workflow").
4. Quando ficar verde (~5 min), abra a execucao e baixe o artefato **PC-Toolkit-Mobile-APK** (um .zip com o .apk).
5. No celular: instale o .apk (permita "instalar apps desconhecidos" so para o gerenciador de arquivos),
   abra o app e **conceda o Superusuario** quando o Magisk/KernelSU pedir.

Se o build falhar, copie as ultimas linhas do log da aba Actions e me mande.

Alternativa: abrir a pasta no Android Studio e usar Build > Build APK(s).

## Mapa: PC -> Android

| PC Toolkit (Windows) | PC Toolkit Mobile (Android com root) |
|---|---|
| Assistente | Assistente em 7 etapas |
| Limpeza padrao/profunda/simulacao | Cache de todos os apps (pula jogos e apps abertos), miniaturas, temporarios, logs do sistema, `pm trim-caches` |
| Cache de shaders (com aviso) | Cache dos jogos (com o mesmo aviso: apagar causa engasgos) |
| Revisar arquivos grandes / Downloads | Igual, com **Lixeira do toolkit** (restaurar/esvaziar) |
| Tamanho dos jogos / programas instalados | Tamanho de cada jogo/app (apk + dados + obb) e desinstalar |
| CompactOS | Otimizar os apps (compilacao ART) |
| Megazord (backup + reset) | Backup conferido para cartao SD/pen drive OTG/pasta + inventario + checklist; restauracao pela tela oficial |
| Planos de energia (Maximo/Frio/Silencioso) | Governador e clock maximo da CPU por cluster + GPU Adreno (reversivel) |
| Monitor de temperatura | CPU, bateria, clock, uso por nucleo, GPU e FPS ao vivo |
| Teste de estresse | Igual (para em 92 C de CPU ou 46 C de bateria) |
| Protecao termica do Modo Jogo | Pela temperatura da bateria (limite configuravel) |
| Raio-X de FPS | Nota + correcoes: tela no Hz maximo, economia de energia, calor, animacoes, desfoque, 4x MSAA forcado, perfil de GPU, varredura de Wi-Fi, otimizacao de bateria e Modo de Jogo dos jogos, RAM, armazenamento, apps de fundo, sinal do Wi-Fi |
| Medidor de FPS (PresentMon) | Contador de quadros do SurfaceFlinger + CPU/GPU/temperatura; compara antes x depois |
| Analise de gargalo | GPU / CPU / CALOR / limite do jogo / limite da tela |
| Modo Jogo + pacote TURBO | Perfil de CPU, Nao Perturbe, pausa da sincronizacao, apps pesados fechados, Modo de Jogo DESEMPENHO, tela travada no Hz maximo, Wi-Fi baixa latencia, limpeza de memoria, jogo preso nos nucleos grandes; restaura tudo ao fechar o jogo (e se o app for fechado a forca) |
| Otimizar o Windows | Otimizar o Android para jogos |
| Graficos dos jogos | Minecraft Bedrock, Roblox, jogos Unreal + escala de resolucao/limite de FPS do sistema (Android 13+) para qualquer jogo |
| Ajustes por jogo | Desempenho, Game Driver atualizavel, escala/FPS, restringir em segundo plano |
| Monitor no Hz maximo | Tela: peak/min refresh rate |
| Rede (Nagle, placa de rede, DNS) | TCP (BBR, Fast Open), Wi-Fi baixa latencia/alto desempenho, sem varredura, DNS Privado mais rapido, teste roteador x internet |
| Latencia e input | Wi-Fi baixa latencia, tela, animacoes (no celular nao existe timer/MPO) |
| Programas de inicializacao | Apps que abrem ao ligar (desativar) + restringir em segundo plano |
| Liberar RAM | `am kill-all` + cache de arquivos |
| Cacador de ameacas | Apps fora da loja com acessibilidade/admin/SMS/notificacoes, escondidos, stalkerware, mineradores (CPU e portas de pool), acesso remoto, depuracao USB/sem fio, computadores autorizados no ADB, Play Protect, SELinux, proxy, hosts, bloqueio de tela, APKs de mod/cheat |
| Quarentena | Desativa o app (reversivel) |
| Windows Defender | Play Protect |
| Blindagem / Windows Sandbox / VirusTotal | Proteger agora (Play Protect total, bloqueia instalar APK por navegador/Telegram) / Espaco privado ou perfil de trabalho / VirusTotal para arquivo ou app instalado |
| Aparencia inteligente | Animacoes, desfoque, transparencia, tema escuro, memoria virtual (zram) |
| Manutencao (DISM/SFC, TRIM, drivers) | Caches do sistema + ART + apps que mais travam, TRIM (`sm fstrim`), atualizacoes |
| Desfazer | Desfazer tudo (todo ajuste guarda o valor original antes) |

## Limites honestos

- O contador de FPS do SurfaceFlinger nao responde em alguns aparelhos; ai o app mede so CPU/GPU/temperatura.
- Alguns fabricantes (Samsung, Xiaomi) travam o controle de CPU no kernel; o app avisa quando o kernel recusa.
- Ajustes de kernel (CPU, TCP, Wi-Fi forcado) valem ate reiniciar; configuracoes do Android ficam ate voce desfazer.
- Jogos com anti-cheat sensivel (PUBG/BGMI, Free Fire, CoD, Riot) nao tem os arquivos editados, para evitar ban.
