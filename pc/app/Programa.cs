using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using Path = System.Windows.Shapes.Path;

namespace SerpyraPC
{
    public static class Programa
    {
        public const string Versao = "1.0";

        [STAThread]
        public static void Main()
        {
            var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
            T.CarregarEstilos(app);
            app.DispatcherUnhandledException += (s, e) =>
            {
                Relatorio.Travou(e.Exception);
                MessageBox.Show("Algo deu errado aqui. Já mandamos o aviso para corrigir.\n\n" + e.Exception.Message, "SerpyraToolkit", MessageBoxButton.OK, MessageBoxImage.Warning);
                e.Handled = true;
            };
            app.Run(new Principal());
        }
    }

    public class Principal : Window
    {
        readonly ContentControl conteudo = new ContentControl();
        readonly StackPanel abas = new StackPanel();
        readonly StackPanel cabDireita = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        string abaAtual = "inicio";
        Execucao rodando;
        string avisoPro = "";

        public Principal()
        {
            Title = "SerpyraToolkit";
            Width = 1180; Height = 800; MinWidth = 920; MinHeight = 620;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = T.Grade();
            FontFamily = T.Normal;
            Foreground = T.B(T.Texto);
            UseLayoutRounding = true;
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);

            var raiz = new Grid();
            raiz.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(236) });
            raiz.ColumnDefinitions.Add(new ColumnDefinition());
            raiz.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            raiz.RowDefinitions.Add(new RowDefinition());

            // cabecalho
            var cab = new DockPanel { Margin = new Thickness(24, 18, 24, 10) };
            DockPanel.SetDock(cabDireita, Dock.Right); cab.Children.Add(cabDireita);
            var marca = new StackPanel { Orientation = Orientation.Horizontal };
            marca.Children.Add(Estrela(24, T.Verde));
            var nome = T.TituloCromo("SerpyraToolkit", 24); nome.Margin = new Thickness(10, 0, 0, 0); nome.VerticalAlignment = VerticalAlignment.Center;
            marca.Children.Add(nome);
            var pc = new TextBlock { Text = "para PC", FontFamily = T.Pixel, FontSize = 20, Foreground = T.B(T.Lilas), Margin = new Thickness(10, 6, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            marca.Children.Add(pc);
            cab.Children.Add(marca);
            Grid.SetColumnSpan(cab, 2); raiz.Children.Add(cab);

            // abas na lateral
            var lateral = new Border { Background = T.B(T.Surface2), BorderBrush = T.B(T.Outline), BorderThickness = new Thickness(1.5), CornerRadius = new CornerRadius(20), Margin = new Thickness(18, 6, 0, 18), Padding = new Thickness(10) };
            var lcol = new DockPanel();
            var rod = new TextBlock { Text = "v" + Programa.Versao + " · PC " + Licenca.CodigoBonito, FontFamily = T.Pixel, FontSize = 17, Foreground = T.B(T.Texto3), Margin = new Thickness(8, 8, 0, 4) };
            DockPanel.SetDock(rod, Dock.Bottom); lcol.Children.Add(rod);
            lcol.Children.Add(abas);
            lateral.Child = lcol;
            Grid.SetRow(lateral, 1); raiz.Children.Add(lateral);

            Grid.SetRow(conteudo, 1); Grid.SetColumn(conteudo, 1);
            conteudo.Margin = new Thickness(18, 6, 18, 18);
            raiz.Children.Add(conteudo);
            Content = raiz;

            Closing += (s, e) =>
            {
                if (rodando != null && rodando.Rodando)
                {
                    var r = MessageBox.Show(this, "Uma tarefa ainda está rodando. Fechar mesmo assim? Se for o Modo Jogo, abra o app de novo que ele desfaz o que ficou pendente.", "SerpyraToolkit", MessageBoxButton.YesNo, MessageBoxImage.Question);
                    if (r != MessageBoxResult.Yes) { e.Cancel = true; return; }
                    rodando.Matar();
                }
            };

            Loaded += async (s, e) =>
            {
                Ir("inicio");
                if (await Licenca.Atualizar()) { Cabecalho(); if (rodando == null) Ir(abaAtual); }
            };
            Cabecalho();
        }

        static Path Estrela(double tam, Color cor)
        {
            var g = Geometry.Parse("M12,1 L14,10 L23,12 L14,14 L12,23 L10,14 L1,12 L10,10 Z");
            return new Path { Data = g, Fill = T.B(cor), Width = tam, Height = tam, Stretch = Stretch.Uniform, VerticalAlignment = VerticalAlignment.Center };
        }

        void Cabecalho()
        {
            cabDireita.Children.Clear();
            if (Licenca.Ativo)
            {
                var chip = T.Chip("PRO", T.Verde, T.Bg);
                chip.ToolTip = "Pro até " + Licenca.ValidadeTxt;
                cabDireita.Children.Add(chip);
            }
            else
            {
                var b = T.Botao("Ativar o Pro", "Lima", () => Ir("pro"));
                b.Padding = new Thickness(16, 7, 16, 7);
                cabDireita.Children.Add(b);
            }
        }

        // ---------- navegacao ----------
        void Abas()
        {
            abas.Children.Clear();
            foreach (var (id, rot, ic) in new[] { ("inicio", "Início", "⌂"), ("jogos", "Jogos e FPS", "◉"), ("ferramentas", "Ferramentas", "⚙"), ("seguranca", "Segurança", "⛨"), ("pro", "Pro", "✦") })
            {
                var on = id == abaAtual && rodando == null;
                var sp = new StackPanel { Orientation = Orientation.Horizontal };
                sp.Children.Add(new TextBlock { Text = ic, FontSize = 17, Width = 26, Foreground = T.B(on ? T.Bg : T.Lilas), VerticalAlignment = VerticalAlignment.Center });
                sp.Children.Add(new TextBlock { Text = rot, FontSize = 15.5, FontWeight = on ? FontWeights.Bold : FontWeights.SemiBold, Foreground = T.B(on ? T.Bg : T.Texto2), VerticalAlignment = VerticalAlignment.Center });
                var b = new Button { Content = sp, Style = (Style)Application.Current.Resources["Aba"], Margin = new Thickness(0, 0, 0, 4), HorizontalContentAlignment = HorizontalAlignment.Left };
                if (on) { b.Background = T.B(T.Verde); b.BorderBrush = T.B(T.Verde); }
                T.AutomationProperties(b, rot);
                var alvo = id;
                b.Click += (s, e) =>
                {
                    if (rodando != null && rodando.Rodando) { MessageBox.Show(this, "Espere a tarefa terminar ou toque em Parar.", "SerpyraToolkit"); return; }
                    rodando = null; Ir(alvo);
                };
                abas.Children.Add(b);
            }
        }

        void Ir(string aba)
        {
            abaAtual = aba;
            Relatorio.Passo(aba);
            var col = new StackPanel { Margin = new Thickness(6, 0, 10, 24), MaxWidth = 980, HorizontalAlignment = HorizontalAlignment.Left };
            switch (aba)
            {
                case "jogos": TelaJogos(col); break;
                case "ferramentas": TelaFerramentas(col); break;
                case "seguranca": TelaSeguranca(col); break;
                case "pro": TelaPro(col); break;
                default: TelaInicio(col); break;
            }
            conteudo.Content = new ScrollViewer { Content = col, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
            Abas();
        }

        // ---------- rodar uma funcao do PC-Toolkit ----------
        void Rodar(string acao, string titulo, bool pro)
        {
            if (pro && !Licenca.Ativo) { avisoPro = titulo + " é do Pro. Teste grátis por 1 hora ou ative abaixo."; Ir("pro"); return; }
            if (acao == "modojogo" && !Licenca.Ativo)
            {
                if (Licenca.UsosHoje("modojogo") >= 2) { avisoPro = "No grátis o Modo Jogo vale 2 vezes por dia. Com o Pro é sem limite."; Ir("pro"); return; }
                Licenca.ContarUso("modojogo");
            }
            Relatorio.Passo("▶ " + titulo);
            var volta = abaAtual;
            rodando = new Execucao(acao, titulo, codigo => { rodando = null; Ir(volta); });
            conteudo.Content = rodando;
            Abas();
            rodando.Iniciar();
        }

        // ---------- telas ----------
        void TelaInicio(StackPanel col)
        {
            var nota = NotaFps();
            // Raio-X
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            var anel = new Grid { Width = 128, Height = 128, Margin = new Thickness(0, 0, 22, 0) };
            anel.Children.Add(new Ellipse { Stroke = T.B("#26245A"), StrokeThickness = 12 });
            if (nota > 0)
            {
                var arco = Arco(128, 12, nota / 100.0);
                anel.Children.Add(arco);
            }
            var num = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            var tn = T.TituloCromo(nota > 0 ? nota.ToString() : "?", 40); tn.HorizontalAlignment = HorizontalAlignment.Center;
            num.Children.Add(tn);
            num.Children.Add(new TextBlock { Text = "/100", FontFamily = T.Pixel, FontSize = 18, Foreground = T.B(T.Texto2), HorizontalAlignment = HorizontalAlignment.Center });
            anel.Children.Add(num);
            g.Children.Add(anel);
            var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            info.Children.Add(new TextBlock { Text = "RAIO-X DE FPS", FontFamily = T.Pixel, FontSize = 20, Foreground = T.B(T.Lilas) });
            info.Children.Add(T.Txt(nota <= 0 ? "Descubra o que segura o seu PC" : nota >= 85 ? "Excelente para jogar" : nota >= 65 ? "Bom, dá para melhorar" : "Seu PC pode render mais", 22, T.Texto, FontWeights.Bold));
            var sub = T.Txt("Confere driver, energia, Windows, tela, rede e mais. Corrige com um clique.", 14, T.Texto2); sub.Margin = new Thickness(0, 4, 0, 12);
            info.Children.Add(sub);
            var bts = new StackPanel { Orientation = Orientation.Horizontal };
            bts.Children.Add(T.Botao(nota > 0 ? "Refazer o Raio-X" : "Fazer o Raio-X", "Lima", () => Rodar("raiox", "Raio-X de FPS", false)));
            info.Children.Add(bts);
            Grid.SetColumn(info, 1); g.Children.Add(info);
            col.Children.Add(T.Cartao(g));

            // preparar e jogar
            var pj = new DockPanel();
            var play = new Grid { Width = 54, Height = 54 };
            play.Children.Add(new Ellipse { Fill = T.B(T.Bg) });
            play.Children.Add(new Path { Data = Geometry.Parse("M0,0 L14,9 L0,18 Z"), Fill = T.B(T.Verde), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(4, 0, 0, 0) });
            DockPanel.SetDock(play, Dock.Right); pj.Children.Add(play);
            var pjt = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            pjt.Children.Add(new TextBlock { Text = "PREPARAR E JOGAR", FontFamily = T.Display, FontSize = 26, Foreground = T.B(T.Bg) });
            pjt.Children.Add(new TextBlock { Text = "Modo Jogo TURBO: fecha o que pesa, prioriza o jogo e desfaz tudo no fim", FontSize = 14, Foreground = T.B("#1C1650"), TextWrapping = TextWrapping.Wrap });
            pj.Children.Add(pjt);
            var bpj = new Button { Content = pj, Style = (Style)Application.Current.Resources["Roxo"], Padding = new Thickness(22, 18, 18, 18), HorizontalContentAlignment = HorizontalAlignment.Stretch, Margin = new Thickness(0, 0, 0, 6) };
            T.AutomationProperties(bpj, "Preparar e jogar");
            bpj.Click += (s, e) => Rodar("modojogo", "Modo Jogo", false);
            col.Children.Add(bpj);
            if (!Licenca.Ativo) col.Children.Add(new TextBlock { Text = "Grátis: " + Math.Max(0, 2 - Licenca.UsosHoje("modojogo")) + " de 2 Modo Jogo hoje", FontFamily = T.Pixel, FontSize = 17, Foreground = T.B(T.Texto3), Margin = new Thickness(4, 0, 0, 0) });

            col.Children.Add(T.Secao("Ações rápidas"));
            var ug = new UniformGrid4();
            ug.Add(Rapida("medir_fps.exe", "Medir FPS", "Média, 1% low e engasgos", () => Rodar("bench", "Medir FPS", true), true));
            ug.Add(Rapida("limpeza.exe", "Limpeza rápida", "Libera espaço com segurança", () => Rodar("limparapida", "Limpeza rápida", false), false));
            ug.Add(Rapida("temperatura.exe", "Temperatura ao vivo", "CPU e placa de vídeo", () => Rodar("monitor", "Temperatura ao vivo", false), false));
            ug.Add(Rapida("rede.exe", "Teste de conexão", "Ping, jitter e perda", () => Rodar("conexao", "Teste de conexão", false), false));
            col.Children.Add(ug.Painel);
        }

        Border Rapida(string exe, string titulo, string sub, Action acao, bool pro)
        {
            var sp = new StackPanel();
            var tdp = new DockPanel();
            if (pro && !Licenca.Ativo) { var c = T.Chip("PRO", T.Verde, T.Bg); DockPanel.SetDock(c, Dock.Right); tdp.Children.Add(c); }
            tdp.Children.Add(T.Txt(titulo, 17, T.Texto, FontWeights.Bold));
            sp.Children.Add(tdp);
            var s = T.Txt(sub, 13.5, T.Texto2); s.Margin = new Thickness(0, 3, 0, 12); sp.Children.Add(s);
            var b = T.Botao("Abrir", "Linha", acao); b.HorizontalAlignment = HorizontalAlignment.Left; b.Padding = new Thickness(16, 7, 16, 7);
            sp.Children.Add(b);
            return T.Janela(exe, sp, new Thickness(0, 0, 12, 12));
        }

        class UniformGrid4
        {
            public readonly System.Windows.Controls.Primitives.UniformGrid Painel = new System.Windows.Controls.Primitives.UniformGrid { Columns = 2 };
            public void Add(UIElement e) => Painel.Children.Add(e);
        }

        static Path Arco(double tam, double esp, double frac)
        {
            frac = Math.Max(0.001, Math.Min(0.999, frac));
            var r = (tam - esp) / 2; var c = tam / 2;
            var ang = frac * 2 * Math.PI;
            var fim = new Point(c + r * Math.Sin(ang), c - r * Math.Cos(ang));
            var fig = new PathFigure { StartPoint = new Point(c, c - r) };
            fig.Segments.Add(new ArcSegment(fim, new Size(r, r), 0, frac > 0.5, SweepDirection.Clockwise, true));
            var geo = new PathGeometry(); geo.Figures.Add(fig);
            return new Path { Data = geo, Stroke = T.B(T.Verde), StrokeThickness = esp, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Width = tam, Height = tam };
        }

        static int NotaFps()
        {
            try
            {
                var arq = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "PC-Toolkit-config.json");
                if (!File.Exists(arq)) return 0;
                var d = Servidor.Ler(File.ReadAllText(arq));
                return d != null && d.TryGetValue("UltimaNotaFps", out var v) && v != null ? Convert.ToInt32(v) : 0;
            }
            catch { return 0; }
        }

        void Lista(StackPanel col, string titulo, string sub, IEnumerable<(string acao, string nome, string desc, bool pro)> itens)
        {
            col.Children.Add(T.TituloCromo(titulo, 26));
            var s = T.Txt(sub, 14.5, T.Texto2); s.Margin = new Thickness(0, 4, 0, 14); col.Children.Add(s);
            foreach (var (acao, nome, desc, pro) in itens)
            {
                var a = acao; var n = nome; var p = pro;
                col.Children.Add(T.Item(nome, desc, pro, Licenca.Ativo, () => Rodar(a, n, p)));
            }
        }

        void TelaJogos(StackPanel col) => Lista(col, "Jogos e FPS", "Tudo para a partida: Steam, Epic, Xbox, Riot, Minecraft, FiveM e qualquer jogo aberto.", new[]
        {
            ("modojogo", "Modo Jogo TURBO", "Escolha o jogo: fecha o que pesa, prioriza o jogo e desfaz tudo no fim", false),
            ("raiox", "Raio-X de FPS", "Nota do PC e correção com um clique", false),
            ("bench", "Medir FPS / antes x depois", "FPS médio, 1% low, engasgos e gargalo CPU x placa de vídeo", true),
            ("graficos", "Gráficos por jogo", "Fortnite, Valorant, CS2, Apex, LoL, Roblox, Minecraft, GTA e mais", true),
            ("tela", "Monitor no Hz máximo", "Confere a taxa da tela e o limite de FPS ideal", false),
            ("latencia", "Latência e resposta", "Menos atraso entre o clique e a tela", true),
            ("tweaks", "Ajustes extras para jogos", "Prioridade, agendador e o que o Windows faz em segundo plano", true),
            ("conexao", "Teste de conexão", "Ping, jitter e perda de pacotes", false),
            ("rede", "Internet para jogos", "DNS mais rápido, placa de rede e restaurar", true),
            ("historico", "Histórico de FPS", "Suas medições e sessões", false),
            ("centralfps", "Central de FPS completa", "Todas as opções do PC-Toolkit num só lugar", true),
        });

        void TelaFerramentas(StackPanel col) => Lista(col, "Ferramentas", "Limpeza, temperatura, aparência e manutenção do Windows.", new[]
        {
            ("assistente", "Assistente: fazer tudo", "Limpeza + FPS + aparência + segurança, passo a passo", true),
            ("limpeza", "Limpeza do PC", "Padrão, profunda, simulação, arquivos grandes e downloads", false),
            ("ram", "Liberar memória", "Solta a memória presa antes de jogar", false),
            ("temperatura", "Temperatura e refrigeração", "Diagnóstico, monitor ao vivo, perfis frios e teste", false),
            ("aparencia", "Aparência e desempenho inteligente", "Efeitos visuais, processador e memória virtual", true),
            ("inicializacao", "Programas que abrem com o Windows", "Deixe o PC ligar mais rápido", true),
            ("manutencao", "Manutenção do Windows", "Reparar sistema, discos e drivers", true),
            ("megazord", "Backup e reset de fábrica", "Passo a passo seguro antes de formatar", false),
            ("desfazer", "Desfazer as mudanças", "Volta tudo que o SerpyraToolkit mudou no Windows", false),
        });

        void TelaSeguranca(StackPanel col) => Lista(col, "Segurança", "Caça vírus, minerador de bitcoin e invasores, sem desligar o seu antivírus.", new[]
        {
            ("scan", "Varredura rápida", "Processos, rede e inicialização suspeitos", false),
            ("scanfull", "Varredura completa", "Arquivos, contas, eventos e persistência", true),
            ("blindagem", "Blindagem", "VirusTotal e reforço do Defender e do SmartScreen", true),
            ("quarentena", "Quarentena", "O que foi isolado nas varreduras", false),
            ("seguranca", "Central de segurança", "Todas as opções de segurança", true),
        });

        // ---------- Pro ----------
        void TelaPro(StackPanel col)
        {
            col.Children.Add(T.TituloCromo("SerpyraToolkit Pro", 26));
            var s = T.Txt("O mesmo Pro vale no celular e no PC.", 14.5, T.Texto2); s.Margin = new Thickness(0, 4, 0, 14); col.Children.Add(s);
            if (avisoPro.Length > 0) { var a = T.Txt(avisoPro, 15, T.Amarelo, FontWeights.SemiBold); a.Margin = new Thickness(0, 0, 0, 12); col.Children.Add(a); avisoPro = ""; }

            if (Licenca.Ativo)
            {
                var st = new StackPanel();
                st.Children.Add(T.Txt("Pro ativo ✓", 22, T.Verde, FontWeights.Bold));
                st.Children.Add(T.Txt(Licenca.Vitalicio ? "Vitalício: não vence." : "Válido " + (Licenca.ValidadeTxt.StartsWith("até") ? "" : "até ") + Licenca.ValidadeTxt + ". Renovando pelo app ou pelo site, os dias se somam.", 14.5, T.Texto2));
                col.Children.Add(T.Cartao(st, T.Verde, T.Surface2));
            }

            var msg = T.Txt("", 14.5, T.Texto2); msg.Margin = new Thickness(0, 8, 0, 0);

            if (!Licenca.Ativo)
            {
                // teste gratis
                var t = new StackPanel();
                t.Children.Add(T.Txt("Teste grátis por 1 hora", 18, T.Texto, FontWeights.Bold));
                t.Children.Add(T.Txt("Todas as funções do Pro, sem cadastro.", 14, T.Texto2));
                var bt = T.Botao("Testar grátis", "Lima", null); bt.HorizontalAlignment = HorizontalAlignment.Left; bt.Margin = new Thickness(0, 12, 0, 0);
                var mt = T.Txt("", 14, T.Texto2); mt.Margin = new Thickness(0, 8, 0, 0);
                bt.Click += async (o, e) =>
                {
                    bt.IsEnabled = false; mt.Text = "Ativando…";
                    var r = await Servidor.Chamar("POST", "/teste", new Dictionary<string, object> { ["codigo"] = Licenca.Codigo });
                    if (r.Ok && Licenca.Conferir(r.Str("chave")) > 0) { long.TryParse(r.Str("ate"), out var ate); Licenca.Guardar(r.Str("chave"), ate); Cabecalho(); Ir("pro"); }
                    else { mt.Text = r.Erro; mt.Foreground = T.B(T.Vermelho); bt.IsEnabled = true; }
                };
                t.Children.Add(bt); t.Children.Add(mt);
                col.Children.Add(T.Janela("teste.exe", t));

                // ja tenho pro no celular
                col.Children.Add(VincularCelular());
                // comprar
                col.Children.Add(Comprar());
            }
            // codigo
            col.Children.Add(TemCodigo());

            var info = T.Txt("Código deste PC: " + Licenca.CodigoBonito, 14, T.Texto3); info.FontFamily = T.Pixel; info.FontSize = 18; info.Margin = new Thickness(4, 6, 0, 0);
            col.Children.Add(info);
        }

        Border VincularCelular()
        {
            var sp = new StackPanel();
            sp.Children.Add(T.Txt("Já tenho o Pro no celular", 18, T.Texto, FontWeights.Bold));
            sp.Children.Add(T.Txt("Digite o e-mail da compra. Chega um código de 6 números nele. O Pro continua no seu celular.", 14, T.Texto2));
            var email = T.Campo("E-mail da compra"); email.Margin = new Thickness(0, 12, 0, 0);
            var otp = T.Campo("Código de 6 números"); otp.Visibility = Visibility.Collapsed; otp.Margin = new Thickness(0, 8, 0, 0);
            var m = T.Txt("", 14, T.Texto2); m.Margin = new Thickness(0, 8, 0, 0);
            var b = T.Botao("Mandar o código", "Lima", null); b.HorizontalAlignment = HorizontalAlignment.Left; b.Margin = new Thickness(0, 10, 0, 0);
            bool fase2 = false;
            b.Click += async (o, e) =>
            {
                b.IsEnabled = false;
                if (!fase2)
                {
                    m.Text = "Mandando…"; m.Foreground = T.B(T.Texto2);
                    var r = await Servidor.Chamar("POST", "/pc/pedir", new Dictionary<string, object> { ["email"] = email.Text.Trim(), ["codigo"] = Licenca.Codigo });
                    if (r.Ok && r.Str("chave").Length > 0 && Licenca.Conferir(r.Str("chave")) > 0) { Licenca.Guardar(r.Str("chave")); Cabecalho(); Ir("pro"); return; }
                    if (r.Ok) { fase2 = true; otp.Visibility = Visibility.Visible; otp.Focus(); b.Content = "Confirmar"; m.Text = "Pronto: veja o código no seu e-mail (olhe o spam também)."; m.Foreground = T.B(T.Verde); }
                    else { m.Text = r.Erro; m.Foreground = T.B(T.Vermelho); }
                }
                else
                {
                    m.Text = "Conferindo…"; m.Foreground = T.B(T.Texto2);
                    var r = await Servidor.Chamar("POST", "/pc/confirmar", new Dictionary<string, object> { ["email"] = email.Text.Trim(), ["codigo"] = Licenca.Codigo, ["otp"] = otp.Text.Trim() });
                    if (r.Ok && Licenca.Conferir(r.Str("chave")) > 0) { Licenca.Guardar(r.Str("chave")); Cabecalho(); Ir("pro"); return; }
                    m.Text = r.Erro; m.Foreground = T.B(T.Vermelho);
                }
                b.IsEnabled = true;
            };
            sp.Children.Add(Rotulo("E-mail da compra")); sp.Children.Add(email); sp.Children.Add(otp); sp.Children.Add(b); sp.Children.Add(m);
            return T.Janela("celular.exe", sp);
        }

        static TextBlock Rotulo(string t) { var r = T.Txt(t, 13.5, T.Texto2, FontWeights.SemiBold); r.Margin = new Thickness(0, 10, 0, -6); return r; }

        Border TemCodigo()
        {
            var sp = new StackPanel();
            sp.Children.Add(T.Txt("Tem um código?", 18, T.Texto, FontWeights.Bold));
            sp.Children.Add(T.Txt("Código de ativação do site (PRE…), presente, cortesia ou chave (PCT-…).", 14, T.Texto2));
            var cod = T.Campo("Código"); cod.Margin = new Thickness(0, 12, 0, 0);
            var m = T.Txt("", 14, T.Texto2); m.Margin = new Thickness(0, 8, 0, 0);
            Button b = null;
            async void Usar()
            {
                var c = cod.Text.Trim();
                if (c.Length == 0) return;
                b.IsEnabled = false; m.Text = "Conferindo…"; m.Foreground = T.B(T.Texto2);
                if (c.StartsWith("PCT-", StringComparison.OrdinalIgnoreCase))
                {
                    if (Licenca.Conferir(c) > 0) { Licenca.Guardar(c); Cabecalho(); Ir("pro"); return; }
                    m.Text = "Essa chave não é deste PC. Chaves do celular não servem aqui: use \"Já tenho o Pro no celular\"."; m.Foreground = T.B(T.Vermelho);
                }
                else
                {
                    var r = await Servidor.Chamar("POST", "/presente", new Dictionary<string, object> { ["codigo"] = Licenca.Codigo, ["voucher"] = c });
                    if (r.Ok && Licenca.Conferir(r.Str("chave")) > 0) { long.TryParse(r.Str("ate"), out var ate); Licenca.Guardar(r.Str("chave"), ate); Cabecalho(); Ir("pro"); return; }
                    m.Text = r.Erro; m.Foreground = T.B(T.Vermelho);
                }
                b.IsEnabled = true;
            }
            b = T.Botao("Usar", "Lima", Usar); b.HorizontalAlignment = HorizontalAlignment.Left; b.Margin = new Thickness(0, 10, 0, 0);
            T.AoEnter(cod, Usar);
            sp.Children.Add(cod); sp.Children.Add(b); sp.Children.Add(m);
            return T.Janela("codigo.exe", sp);
        }

        Border Comprar()
        {
            var sp = new StackPanel();
            sp.Children.Add(T.Txt("Assinar com Pix", 18, T.Texto, FontWeights.Bold));
            sp.Children.Add(T.Txt("Sem cobrança automática: cada Pix libera os dias do plano. O mesmo Pro vale no celular: no app, use \"Trocou de celular?\" com este e-mail.", 14, T.Texto2));
            var planos = new StackPanel { Margin = new Thickness(0, 12, 0, 0) };
            var carregando = T.Txt("Carregando os planos…", 14, T.Texto3); planos.Children.Add(carregando);
            var email = T.Campo("Seu e-mail");
            var cupom = T.Campo("Cupom (opcional)");
            var m = T.Txt("", 14, T.Texto2); m.Margin = new Thickness(0, 8, 0, 0);
            var area = new StackPanel();
            string escolhido = "a1";
            var b = T.Botao("Gerar Pix", "Lima", null); b.HorizontalAlignment = HorizontalAlignment.Left; b.Margin = new Thickness(0, 12, 0, 0);
            DispatcherTimer timer = null;
            Dispatcher.InvokeAsync(async () =>
            {
                var r = await Servidor.Chamar("GET", "/planos?v=3");
                planos.Children.Clear();
                if (!r.Ok || !(r.Dados.TryGetValue("planos", out var pl) && pl is object[] lista)) { planos.Children.Add(T.Txt("Não consegui carregar os planos. Confira a internet.", 14, T.Vermelho)); return; }
                foreach (Dictionary<string, object> p in lista.OfType<Dictionary<string, object>>())
                {
                    var id = Convert.ToString(p["id"]);
                    var rb = new RadioButton { GroupName = "planos", IsChecked = id == escolhido, Margin = new Thickness(0, 0, 0, 6), Foreground = T.B(T.Texto), VerticalContentAlignment = VerticalAlignment.Center };
                    var dp = new DockPanel { MinWidth = 420 };
                    var preco = T.Txt(Convert.ToString(p["preco"]), 15, T.Texto, FontWeights.Bold); DockPanel.SetDock(preco, Dock.Right); dp.Children.Add(preco);
                    dp.Children.Add(T.Txt(Convert.ToString(p["nome"]) + (Convert.ToInt32(p["familia"]) > 0 ? "  ·  3 aparelhos" : ""), 15, T.Texto));
                    rb.Content = dp;
                    rb.Checked += (o, e) => { escolhido = id; b.Content = "Gerar Pix de " + Convert.ToString(p["preco"]); };
                    if (id == escolhido) b.Content = "Gerar Pix de " + Convert.ToString(p["preco"]);
                    planos.Children.Add(rb);
                }
            });
            b.Click += async (o, e) =>
            {
                var em = email.Text.Trim();
                if (!System.Text.RegularExpressions.Regex.IsMatch(em, @"^[^\s@]+@[^\s@]+\.[^\s@]{2,}$")) { m.Text = "Digite um e-mail válido."; m.Foreground = T.B(T.Vermelho); return; }
                b.IsEnabled = false; m.Text = "Gerando o Pix…"; m.Foreground = T.B(T.Texto2);
                var r = await Servidor.Chamar("POST", "/pedido", new Dictionary<string, object> { ["codigo"] = Licenca.Codigo, ["email"] = em, ["plano"] = escolhido, ["cupom"] = cupom.Text.Trim() });
                b.IsEnabled = true;
                if (!r.Ok || r.Str("pedido").Length == 0) { m.Text = r.Erro; m.Foreground = T.B(T.Vermelho); return; }
                m.Text = "";
                var pedido = r.Str("pedido");
                area.Children.Clear();
                area.Children.Add(T.Txt(r.Str("plano") + " · " + r.Str("valor") + " · pedido " + r.Str("numero"), 15, T.Texto, FontWeights.SemiBold));
                var qr = r.Str("qr");
                try
                {
                    if (qr.Length > 0)
                    {
                        var b64 = qr.Contains(",") ? qr.Substring(qr.IndexOf(',') + 1) : qr;
                        var bmp = new BitmapImage(); bmp.BeginInit(); bmp.StreamSource = new MemoryStream(Convert.FromBase64String(b64)); bmp.CacheOption = BitmapCacheOption.OnLoad; bmp.EndInit();
                        area.Children.Add(new Border { Background = Brushes.White, CornerRadius = new CornerRadius(12), Padding = new Thickness(8), Width = 236, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 10, 0, 10), Child = new Image { Source = bmp, Width = 220, Height = 220 } });
                    }
                }
                catch { }
                var cola = new TextBox { Text = r.Str("brCode"), IsReadOnly = true, TextWrapping = TextWrapping.Wrap, FontFamily = T.Mono, FontSize = 12, Height = 70 };
                area.Children.Add(Rotulo("Pix copia e cola")); area.Children.Add(cola);
                var cp = T.Botao("Copiar código Pix", "Linha", () => { try { Clipboard.SetText(cola.Text); } catch { } }); cp.HorizontalAlignment = HorizontalAlignment.Left; cp.Margin = new Thickness(0, 8, 0, 0);
                area.Children.Add(cp);
                var esp = new TextBlock { Text = "● Esperando o pagamento", FontFamily = T.Pixel, FontSize = 20, Foreground = T.B(T.Lilas), Margin = new Thickness(0, 10, 0, 0) };
                area.Children.Add(esp);
                timer?.Stop();
                var inicio = DateTime.UtcNow;
                timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
                timer.Tick += async (o2, e2) =>
                {
                    if ((DateTime.UtcNow - inicio).TotalMinutes > 45) { timer.Stop(); esp.Text = "O Pix expirou. Gere outro."; return; }
                    var st = await Servidor.Chamar("GET", "/status?pedido=" + Uri.EscapeDataString(pedido));
                    if (st.Str("status") == "pago" && Licenca.Conferir(st.Str("chave")) > 0)
                    {
                        timer.Stop(); Licenca.Guardar(st.Str("chave")); Cabecalho();
                        MessageBox.Show(this, "Pagamento confirmado! O Pro está ativo neste PC até " + Licenca.ValidadeTxt + ". A chave também foi para o seu e-mail.", "SerpyraToolkit Pro");
                        Ir("pro");
                    }
                    else if (st.Str("status").Length > 0 && st.Str("status") != "pendente" && st.Str("status") != "pago") { timer.Stop(); esp.Text = "O Pix expirou ou foi cancelado. Gere outro."; }
                };
                timer.Start();
            };
            sp.Children.Add(planos);
            sp.Children.Add(Rotulo("Seu e-mail (a chave vai para ele)")); sp.Children.Add(email);
            sp.Children.Add(Rotulo("Cupom (opcional)")); sp.Children.Add(cupom);
            sp.Children.Add(b); sp.Children.Add(m); sp.Children.Add(area);
            return T.Janela("pro.exe", sp);
        }
    }
}
