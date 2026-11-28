using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using Path = System.Windows.Shapes.Path;

namespace SerpyraPC
{
    public partial class Principal
    {
        static List<Dictionary<string, object>> jogosCache;
        static Dictionary<string, object> resumoCache;
        static Dictionary<string, object> raioxCache;

        // ======================= INICIO =======================
        void TelaInicio()
        {
            var col = Pagina(null, null, "Início");

            // atualizacao do programa
            if (Servicos.Nova != null)
            {
                var sp = new DockPanel();
                var b = T.Botao("Atualizar agora", "Lima", null); b.VerticalAlignment = VerticalAlignment.Center;
                DockPanel.SetDock(b, Dock.Right); sp.Children.Add(b);
                var tx = new StackPanel();
                tx.Children.Add(T.Txt("Versão " + Servicos.Nova.Numero + " disponível", 17, T.Texto, FontWeights.Bold));
                var st = T.Txt("Atualiza em poucos segundos e abre de novo sozinho.", 14, T.Texto2); tx.Children.Add(st);
                sp.Children.Add(tx);
                b.Click += async (s, e) => { b.IsEnabled = false; var r = await Servicos.InstalarNova(m => st.Text = m); if (r.Length > 0) { st.Text = r; st.Foreground = T.B(T.Vermelho); b.IsEnabled = true; } };
                col.Children.Add(T.Cartao(sp, T.Verde, T.Surface2));
            }

            // aviso do painel
            if (Servicos.AvisoAtual != null)
            {
                var a = Servicos.AvisoAtual;
                var dp = new DockPanel();
                var x = new Button { Content = "×", Style = (Style)Application.Current.Resources["Aba"], FontSize = 22, Padding = new Thickness(10, 0, 10, 2), Foreground = T.B(T.Texto2) };
                T.AutomationProperties(x, "Fechar aviso");
                x.Click += (s, e) => { Servicos.FecharAviso(); TelaInicio(); };
                DockPanel.SetDock(x, Dock.Right); dp.Children.Add(x);
                var ic = T.Icone("sino", T.Amarelo, 22); ic.Margin = new Thickness(0, 0, 12, 0); DockPanel.SetDock(ic, Dock.Left); dp.Children.Add(ic);
                var tx = T.Txt(a.Texto, 16, T.Texto, FontWeights.SemiBold); tx.VerticalAlignment = VerticalAlignment.Center;
                if (a.Link.StartsWith("https://")) { tx.Cursor = System.Windows.Input.Cursors.Hand; tx.TextDecorations = TextDecorations.Underline; tx.MouseLeftButtonUp += (s, e) => { try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(a.Link) { UseShellExecute = true }); } catch { } }; }
                dp.Children.Add(tx);
                var c = T.Cartao(dp, T.Amarelo, T.Cor("#1E1A2A")); c.Padding = new Thickness(18, 12, 10, 12);
                col.Children.Add(c);
            }

            // notificacoes do painel
            foreach (var m in Servicos.Mensagens.ToList())
            {
                var dp = new DockPanel();
                var x = new Button { Content = "×", Style = (Style)Application.Current.Resources["Aba"], FontSize = 22, Padding = new Thickness(10, 0, 10, 2), Foreground = T.B(T.Texto2), VerticalAlignment = VerticalAlignment.Top };
                var mm = m;
                x.Click += (s, e) => { Servicos.FecharMensagem(mm); TelaInicio(); };
                DockPanel.SetDock(x, Dock.Right); dp.Children.Add(x);
                var tx = new StackPanel();
                tx.Children.Add(T.Txt(m.Titulo, 16.5, T.Texto, FontWeights.Bold));
                var t2 = T.Txt(m.Texto, 14.5, T.Texto2); t2.Margin = new Thickness(0, 2, 0, 0); tx.Children.Add(t2);
                if (m.Cupom.Length > 0)
                {
                    var bc = T.Botao("Usar o cupom " + m.Cupom, "Lima", () => { cupomPro = mm.Cupom; IrAba("pro"); });
                    bc.HorizontalAlignment = HorizontalAlignment.Left; bc.Margin = new Thickness(0, 10, 0, 0); bc.Padding = new Thickness(14, 7, 14, 7);
                    tx.Children.Add(bc);
                }
                dp.Children.Add(tx);
                col.Children.Add(T.Cartao(dp, T.Azul, T.Surface2));
            }

            // Raio-X + resumo do PC
            var g = new Grid { Margin = new Thickness(0, 0, 0, 12) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(3, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(2, GridUnitType.Star) });
            var nota = NotaFps();
            var rx = new DockPanel();
            var anel = Anel(nota, 120); anel.Margin = new Thickness(0, 0, 20, 0); DockPanel.SetDock(anel, Dock.Left); rx.Children.Add(anel);
            var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            info.Children.Add(new TextBlock { Text = "RAIO-X DE FPS", FontFamily = T.Pixel, FontSize = 20, Foreground = T.B(T.Lilas) });
            info.Children.Add(new TextBlock { Text = nota <= 0 ? "Descubra o que segura o seu PC" : nota >= 85 ? "Excelente" : nota >= 65 ? "Bom, dá para melhorar" : "Seu PC pode render mais", FontFamily = T.Display, FontSize = 19, Foreground = T.B(T.Texto), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 2, 0, 6) });
            info.Children.Add(T.Txt("Confere driver, energia, Windows, tela, temperatura e rede, e corrige com um clique.", 13.5, T.Texto2));
            var bts = new WrapPanel { Margin = new Thickness(0, 12, 0, 0) };
            var b1 = T.Botao(nota > 0 ? "Refazer o Raio-X" : "Fazer o Raio-X", "Lima", () => Abrir(TelaRaioX)); b1.Padding = new Thickness(16, 9, 16, 9); b1.Margin = new Thickness(0, 0, 8, 0);
            bts.Children.Add(b1);
            info.Children.Add(bts);
            rx.Children.Add(info);
            var crx = T.Cartao(rx); crx.Margin = new Thickness(0, 0, 12, 0);
            g.Children.Add(crx);
            var res = new StackPanel();
            res.Children.Add(new TextBlock { Text = "SEU PC", FontFamily = T.Pixel, FontSize = 20, Foreground = T.B(T.Lilas), Margin = new Thickness(0, 0, 0, 6) });
            var linhasRes = new StackPanel();
            res.Children.Add(linhasRes);
            void preencher(Dictionary<string, object> d)
            {
                linhasRes.Children.Clear();
                if (d == null) { linhasRes.Children.Add(T.Txt("Lendo o seu PC…", 14, T.Texto3)); return; }
                LinhaRes(linhasRes, "memoria", "Memória", Ponte.S(d, "memLivre") + " de " + Ponte.S(d, "memTotal") + " GB livres");
                LinhaRes(linhasRes, "tela", "Disco C:", Ponte.S(d, "discoLivre") + " de " + Ponte.S(d, "discoTotal") + " GB livres");
                LinhaRes(linhasRes, "raio", "Processador", Curto(Ponte.S(d, "cpu"), 34));
                LinhaRes(linhasRes, "jogo", "Vídeo", Curto(Ponte.S(d, "gpu"), 34));
            }
            preencher(resumoCache);
            if (resumoCache == null && !modoFotos) _ = Carregar("resumo-json", d => { resumoCache = d; preencher(d); });
            var cres = T.Cartao(res); Grid.SetColumn(cres, 1); g.Children.Add(cres);
            col.Children.Add(g);

            // preparar e jogar
            col.Children.Add(BotaoGrande("PREPARAR E JOGAR", "Escolha o jogo: Modo Jogo TURBO fecha o que pesa, prioriza o jogo e desfaz tudo no fim", () => Abrir(TelaMeusJogos)));
            if (!Licenca.Ativo) col.Children.Add(new TextBlock { Text = "Grátis: " + Math.Max(0, 2 - Licenca.UsosHoje("modojogo")) + " de 2 Modo Jogo hoje", FontFamily = T.Pixel, FontSize = 17, Foreground = T.B(T.Texto3), Margin = new Thickness(4, 0, 0, 0) });

            // acoes rapidas
            col.Children.Add(T.Secao("Ações rápidas"));
            var ug = new UniformGrid { Columns = 4 };
            ug.Children.Add(Rapida("medir_fps.exe", "fps", T.Verde, "Medir FPS", "Média, 1% low e engasgos", () => Rodar("bench", "Medir FPS", true), true));
            ug.Children.Add(Rapida("limpeza.exe", "vassoura", T.Azul, "Limpeza rápida", "Libera espaço com segurança", () => Rodar("limparapida", "Limpeza rápida", false), false));
            ug.Children.Add(Rapida("temperatura.exe", "termometro", T.Amarelo, "Temperatura", "CPU e placa de vídeo ao vivo", () => Abrir(TelaTemperatura), false));
            ug.Children.Add(Rapida("rede.exe", "wifi", T.Lilas, "Conexão", "Ping, jitter e perda", () => Abrir(TelaRede), false));
            col.Children.Add(ug);

            // meus jogos
            col.Children.Add(T.Secao("Meus jogos"));
            var mj = new WrapPanel();
            if (jogosCache != null && jogosCache.Count > 0)
            {
                foreach (var j in jogosCache.Take(8)) mj.Children.Add(ChipJogo(Ponte.S(j, "Nome"), Ponte.S(j, "Origem")));
                var ver = T.Botao("Ver todos", "Linha", () => Abrir(TelaMeusJogos)); ver.Padding = new Thickness(14, 8, 14, 8); mj.Children.Add(ver);
            }
            else
            {
                var p = T.Botao("Procurar os jogos deste PC", "Linha", () => Abrir(TelaMeusJogos)); p.Padding = new Thickness(16, 9, 16, 9);
                mj.Children.Add(p);
            }
            col.Children.Add(mj);
        }

        static string Curto(string s, int n) => s.Length > n ? s.Substring(0, n - 1) + "…" : s;

        static void LinhaRes(Panel p, string ic, string rot, string val)
        {
            var dp = new DockPanel { Margin = new Thickness(0, 4, 0, 4) };
            var i = T.Icone(ic, T.Lilas, 18); i.Margin = new Thickness(0, 0, 10, 0); DockPanel.SetDock(i, Dock.Left); dp.Children.Add(i);
            var r = new TextBlock { Text = rot, FontSize = 13.5, Foreground = T.B(T.Texto3), Width = 92, VerticalAlignment = VerticalAlignment.Center }; DockPanel.SetDock(r, Dock.Left); dp.Children.Add(r);
            dp.Children.Add(new TextBlock { Text = val, FontSize = 14, Foreground = T.B(T.Texto), TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center });
            p.Children.Add(dp);
        }

        Button BotaoGrande(string titulo, string sub, Action acao)
        {
            var pj = new DockPanel();
            var play = new Grid { Width = 58, Height = 58 };
            play.Children.Add(new Ellipse { Fill = T.B(T.Bg) });
            play.Children.Add(new Path { Data = Geometry.Parse("M0,0 L14,9 L0,18 Z"), Fill = T.B(T.Verde), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(4, 0, 0, 0) });
            DockPanel.SetDock(play, Dock.Right); pj.Children.Add(play);
            var pjt = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            pjt.Children.Add(new TextBlock { Text = titulo, FontFamily = T.Display, FontSize = 28, Foreground = T.B(T.Bg) });
            pjt.Children.Add(new TextBlock { Text = sub, FontSize = 14, Foreground = T.B("#1C1650"), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 2, 12, 0) });
            pj.Children.Add(pjt);
            var b = new Button { Content = pj, Style = (Style)Application.Current.Resources["Roxo"], Padding = new Thickness(24, 20, 20, 20), HorizontalContentAlignment = HorizontalAlignment.Stretch, Margin = new Thickness(0, 0, 0, 6) };
            T.AutomationProperties(b, titulo);
            b.Click += (s, e) => acao();
            return b;
        }

        Border Rapida(string exe, string ic, Color cor, string titulo, string sub, Action acao, bool pro)
        {
            var sp = new StackPanel();
            var top = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
            if (pro && !Licenca.Ativo) { var c = T.Chip("PRO", T.Verde, T.Bg); c.VerticalAlignment = VerticalAlignment.Top; DockPanel.SetDock(c, Dock.Right); top.Children.Add(c); }
            top.Children.Add(new Border { HorizontalAlignment = HorizontalAlignment.Left, Child = T.IconeQuadrado(ic, cor, 44) });
            sp.Children.Add(top);
            sp.Children.Add(T.Txt(titulo, 16.5, T.Texto, FontWeights.Bold));
            var s2 = T.Txt(sub, 13, T.Texto2); s2.Margin = new Thickness(0, 2, 0, 0); sp.Children.Add(s2);
            var b = new Button { Content = sp, Style = (Style)Application.Current.Resources["Item"], Padding = new Thickness(0), BorderThickness = new Thickness(0), Background = Brushes.Transparent, HorizontalContentAlignment = HorizontalAlignment.Stretch };
            T.AutomationProperties(b, titulo);
            b.Click += (s, e) => acao();
            return T.Janela(exe, b, new Thickness(0, 0, 12, 12));
        }

        Button ChipJogo(string nome, string origem)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            sp.Children.Add(T.Icone("jogo", T.Verde, 18));
            sp.Children.Add(new TextBlock { Text = Curto(nome, 26), FontSize = 14.5, FontWeight = FontWeights.SemiBold, Foreground = T.B(T.Texto), Margin = new Thickness(8, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center });
            var b = new Button { Content = sp, Style = (Style)Application.Current.Resources["Linha"], Padding = new Thickness(14, 9, 14, 9), Margin = new Thickness(0, 0, 8, 8), ToolTip = "Jogar " + nome + " no Modo Jogo (" + origem + ")" };
            T.AutomationProperties(b, "Jogar " + nome);
            b.Click += (s, e) => Rodar("modojogo-jogo", "Modo Jogo: " + nome, false, nome);
            return b;
        }

        static Grid Anel(int nota, double tam)
        {
            var anel = new Grid { Width = tam, Height = tam, VerticalAlignment = VerticalAlignment.Center };
            anel.Children.Add(new Ellipse { Stroke = T.B("#26245A"), StrokeThickness = tam / 10 });
            if (nota > 0) anel.Children.Add(Arco(tam, tam / 10, nota / 100.0, nota >= 85 ? T.Verde : nota >= 65 ? T.Amarelo : T.Vermelho));
            var num = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            var tn = T.TituloCromo(nota > 0 ? nota.ToString() : "?", tam / 3.1); tn.HorizontalAlignment = HorizontalAlignment.Center;
            num.Children.Add(tn);
            num.Children.Add(new TextBlock { Text = "/100", FontFamily = T.Pixel, FontSize = tam / 7, Foreground = T.B(T.Texto2), HorizontalAlignment = HorizontalAlignment.Center });
            anel.Children.Add(num);
            return anel;
        }

        static Path Arco(double tam, double esp, double frac, Color cor)
        {
            frac = Math.Max(0.001, Math.Min(0.999, frac));
            var r = (tam - esp) / 2; var c = tam / 2;
            var ang = frac * 2 * Math.PI;
            var fim = new Point(c + r * Math.Sin(ang), c - r * Math.Cos(ang));
            var fig = new PathFigure { StartPoint = new Point(c, c - r) };
            fig.Segments.Add(new ArcSegment(fim, new Size(r, r), 0, frac > 0.5, SweepDirection.Clockwise, true));
            var geo = new PathGeometry(); geo.Figures.Add(fig);
            return new Path { Data = geo, Stroke = T.B(cor), StrokeThickness = esp, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Width = tam, Height = tam };
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

        async System.Threading.Tasks.Task Carregar(string acao, Action<Dictionary<string, object>> pronto, string arg = null)
        {
            var d = await Ponte.Dados(acao, arg);
            pronto(d);
        }

        static Border Carregando(string texto)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            var p = new TextBlock { Text = "●", FontSize = 18, Foreground = T.B(T.Verde), Margin = new Thickness(0, 0, 10, 0) };
            var an = new System.Windows.Media.Animation.DoubleAnimation(1, 0.2, TimeSpan.FromSeconds(0.7)) { AutoReverse = true, RepeatBehavior = System.Windows.Media.Animation.RepeatBehavior.Forever };
            p.BeginAnimation(UIElement.OpacityProperty, an);
            sp.Children.Add(p);
            sp.Children.Add(new TextBlock { Text = texto, FontFamily = T.Pixel, FontSize = 21, Foreground = T.B(T.Lilas) });
            return T.Cartao(sp);
        }

        // ======================= RAIO-X =======================
        void TelaRaioX()
        {
            var col = Pagina("Raio-X de FPS", "Confere o que costuma derrubar o FPS e corrige com um clique. Nada muda sem você mandar.");
            var area = new StackPanel();
            col.Children.Add(area);
            if (raioxCache != null) DesenharRaioX(area, raioxCache);
            else
            {
                area.Children.Add(Carregando("Conferindo o seu PC… leva uns 20 segundos"));
                if (!modoFotos) _ = Carregar("raiox-json", d =>
                {
                    if (d == null) { area.Children.Clear(); area.Children.Add(T.Cartao(T.Txt("Não consegui fazer o Raio-X agora. Tente de novo.", 15, T.Vermelho))); return; }
                    raioxCache = d; if (pilha.Count > 0 && pilha[pilha.Count - 1] == (Action)TelaRaioX) { area.Children.Clear(); DesenharRaioX(area, d); }
                });
            }
        }

        void DesenharRaioX(StackPanel area, Dictionary<string, object> d)
        {
            var nota = (int)Ponte.N(d, "nota", 0); var antes = (int)Ponte.N(d, "antes", 0);
            var itens = Ponte.L(d, "itens");
            var rank = new Dictionary<string, int> { ["PROBLEMA"] = 0, ["ATENCAO"] = 1, ["INFO"] = 2, ["OK"] = 3 };
            var pend = itens.Where(x => Ponte.S(x, "Estado") != "OK").OrderBy(x => rank.TryGetValue(Ponte.S(x, "Estado"), out var r) ? r : 2).ThenByDescending(x => Ponte.N(x, "Peso", 0)).ToList();
            var oks = itens.Where(x => Ponte.S(x, "Estado") == "OK").ToList();

            var topo = new DockPanel();
            var anel = Anel(nota, 130); anel.Margin = new Thickness(0, 0, 22, 0); DockPanel.SetDock(anel, Dock.Left); topo.Children.Add(anel);
            var tx = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            tx.Children.Add(new TextBlock { Text = nota >= 85 ? "Excelente para jogar" : nota >= 65 ? "Bom, dá para melhorar" : "Seu PC pode render mais", FontFamily = T.Display, FontSize = 22, Foreground = T.B(T.Texto) });
            tx.Children.Add(T.Txt(pend.Count(x => Ponte.S(x, "Estado") != "INFO") + " ponto(s) para olhar · " + oks.Count + " ok" + (antes > 0 && antes != nota ? " · antes: " + antes : ""), 14.5, T.Texto2));
            var refazer = T.Botao("Conferir de novo", "Linha", () => { raioxCache = null; TelaRaioX(); }); refazer.HorizontalAlignment = HorizontalAlignment.Left; refazer.Margin = new Thickness(0, 12, 0, 0); refazer.Padding = new Thickness(14, 7, 14, 7);
            tx.Children.Add(refazer);
            topo.Children.Add(tx);
            area.Children.Add(T.Cartao(topo));

            var marcados = new List<string>();
            if (pend.Count > 0)
            {
                area.Children.Add(T.Secao("O que dá para melhorar"));
                foreach (var it in pend)
                {
                    var est = Ponte.S(it, "Estado"); var nome = Ponte.S(it, "Nome"); var corrige = Ponte.S(it, "Corrige") == "True";
                    var cor = est == "PROBLEMA" ? T.Vermelho : est == "ATENCAO" ? T.Amarelo : T.Lilas;
                    var dp = new DockPanel();
                    if (corrige)
                    {
                        var cb = new CheckBox { IsChecked = Ponte.S(it, "Auto") == "True", VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(10, 0, 0, 0), Content = new TextBlock { Text = "Corrigir", Foreground = T.B(T.Texto), FontWeight = FontWeights.SemiBold } };
                        if (cb.IsChecked == true) marcados.Add(nome);
                        cb.Checked += (s, e) => { if (!marcados.Contains(nome)) marcados.Add(nome); };
                        cb.Unchecked += (s, e) => marcados.Remove(nome);
                        DockPanel.SetDock(cb, Dock.Right); dp.Children.Add(cb);
                    }
                    var q = new Border { Width = 10, CornerRadius = new CornerRadius(5), Background = T.B(cor), Margin = new Thickness(0, 2, 14, 2) }; DockPanel.SetDock(q, Dock.Left); dp.Children.Add(q);
                    var c2 = new StackPanel();
                    c2.Children.Add(T.Txt(nome, 15.5, T.Texto, FontWeights.Bold));
                    var det = Ponte.S(it, "Detalhe"); if (det.Length > 0) { var dt = T.Txt(det, 13.5, T.Texto2); dt.Margin = new Thickness(0, 2, 0, 0); c2.Children.Add(dt); }
                    if (!corrige && est != "INFO") { var g2 = T.Txt("Depende de você ou do hardware: siga a explicação.", 12.5, T.Texto3); g2.Margin = new Thickness(0, 3, 0, 0); c2.Children.Add(g2); }
                    dp.Children.Add(c2);
                    var cc = T.Cartao(dp); cc.Padding = new Thickness(14, 12, 16, 12); cc.Margin = new Thickness(0, 0, 0, 8);
                    area.Children.Add(cc);
                }
                if (pend.Any(x => Ponte.S(x, "Corrige") == "True"))
                {
                    var ponto = new CheckBox { IsChecked = true, Margin = new Thickness(2, 6, 0, 10), Content = new TextBlock { Text = "Criar um ponto de restauração do Windows antes (recomendado)", Foreground = T.B(T.Texto2) } };
                    area.Children.Add(ponto);
                    var bc = T.Botao("Corrigir o que está marcado", "Lima", () =>
                    {
                        if (marcados.Count == 0) { MessageBox.Show(this, "Marque pelo menos um item para corrigir.", "Raio-X"); return; }
                        raioxCache = null;
                        Rodar("raiox-corrigir", "Corrigindo o Raio-X", false, string.Join("|", marcados), ponto.IsChecked == true ? "ponto" : "");
                    });
                    bc.HorizontalAlignment = HorizontalAlignment.Left; area.Children.Add(bc);
                }
            }
            if (oks.Count > 0)
            {
                area.Children.Add(T.Secao("Tudo certo (" + oks.Count + ")"));
                var wp = new WrapPanel();
                foreach (var it in oks) { var c = T.Chip("✓ " + Ponte.S(it, "Nome"), T.Cor("#1F2E1A"), T.Verde); c.Margin = new Thickness(0, 0, 8, 8); ((TextBlock)c.Child).FontSize = 17; wp.Children.Add(c); }
                area.Children.Add(wp);
            }
            var dica = T.Txt("Dica: meça o FPS antes e depois (Jogos > Medir FPS) para ver o ganho real no seu jogo.", 13.5, T.Texto3); dica.Margin = new Thickness(0, 14, 0, 0);
            area.Children.Add(dica);
        }

        // ======================= JOGOS =======================
        void TelaJogos()
        {
            var col = Pagina("Jogos", "Steam, Epic, GOG, Ubisoft, EA, Rockstar, Riot, Xbox, Minecraft, FiveM e o jogo que estiver aberto.");
            col.Children.Add(BotaoGrande("PREPARAR E JOGAR", "Escolha um dos seus jogos e jogue no Modo Jogo TURBO", () => Abrir(TelaMeusJogos)));
            col.Children.Add(T.Secao("Para jogar melhor"));
            foreach (var (acao, nome, desc, pro, ic, cor) in new[]
            {
                ("raiox", "Raio-X de FPS", "Nota do PC e correção com um clique", false, "fps", T.Verde),
                ("bench", "Medir FPS / antes x depois", "FPS médio, 1% low, engasgos e gargalo CPU x placa de vídeo", true, "fps", T.Verde),
                ("graficos", "Gráficos por jogo", "Fortnite, Valorant, CS2, Apex, LoL, Roblox, Minecraft, GTA e mais", true, "jogo", T.Azul),
                ("tela", "Monitor no Hz máximo", "Confere a taxa da tela e o limite de FPS ideal", false, "tela", T.Lilas),
                ("latencia", "Latência e resposta", "Menos atraso entre o clique e a tela", true, "raio", T.Amarelo),
                ("tweaks", "Ajustes extras para jogos", "Prioridade, agendador e o que o Windows faz em segundo plano", true, "ferramenta", T.Azul),
                ("conexao", "Internet para jogos", "Teste de conexão, DNS mais rápido e placa de rede", false, "wifi", T.Lilas),
                ("historico", "Histórico de FPS", "Suas medições e sessões do Modo Jogo", false, "atualizar", T.Texto2),
                ("modojogo", "Modo Jogo manual", "Para um jogo que não apareceu na lista", false, "jogo", T.Verde),
                ("centralfps", "Central de FPS completa", "Todas as opções do PC-Toolkit num só lugar", true, "estrela", T.Verde),
            })
            {
                var a = acao; var n = nome; var p = pro;
                Action go = a == "raiox" ? (Action)(() => Abrir(TelaRaioX)) : a == "conexao" ? (Action)(() => Abrir(TelaRede)) : () => Rodar(a, n, p);
                col.Children.Add(T.Item(nome, desc, pro, Licenca.Ativo, go, ic, cor));
            }
        }

        void TelaMeusJogos()
        {
            var col = Pagina("Meus jogos", "Toque num jogo para jogar no Modo Jogo TURBO. Quando você fechar o jogo, tudo volta ao normal.");
            var busca = new TextBox { Margin = new Thickness(0, 0, 0, 12) };
            T.AutomationProperties(busca, "Procurar jogo");
            var dpb = new DockPanel { Margin = new Thickness(0, 0, 0, 4) };
            var bAt = T.Botao("Procurar de novo", "Linha", () => { jogosCache = null; TelaMeusJogos(); }); bAt.Margin = new Thickness(8, 0, 0, 12); bAt.Padding = new Thickness(14, 8, 14, 8);
            DockPanel.SetDock(bAt, Dock.Right); dpb.Children.Add(bAt);
            dpb.Children.Add(busca);
            col.Children.Add(dpb);
            var grade = new WrapPanel();
            col.Children.Add(grade);
            void desenhar()
            {
                grade.Children.Clear();
                var q = busca.Text.Trim().ToLowerInvariant();
                var l = (jogosCache ?? new List<Dictionary<string, object>>()).Where(j => q.Length == 0 || Ponte.S(j, "Nome").ToLowerInvariant().Contains(q)).ToList();
                if (jogosCache != null && l.Count == 0) grade.Children.Add(T.Txt(jogosCache.Count == 0 ? "Não achei jogos instalados. Use \"Modo Jogo manual\" em Jogos e escolha o jogo aberto." : "Nenhum jogo com esse nome.", 15, T.Texto2));
                foreach (var j in l) grade.Children.Add(CartaoJogo(Ponte.S(j, "Nome"), Ponte.S(j, "Origem")));
            }
            busca.TextChanged += (s, e) => desenhar();
            if (jogosCache != null) desenhar();
            else
            {
                grade.Children.Add(Carregando("Procurando os jogos deste PC…"));
                if (!modoFotos) _ = Carregar("jogos-json", d =>
                {
                    jogosCache = Ponte.L(d, "jogos");
                    if (pilha.Count > 0 && pilha[pilha.Count - 1] == (Action)TelaMeusJogos) desenhar();
                });
            }
        }

        Border CartaoJogo(string nome, string origem)
        {
            var sp = new StackPanel { Width = 196 };
            var top = new DockPanel();
            top.Children.Add(T.IconeQuadrado("jogo", T.Verde, 40));
            sp.Children.Add(top);
            var n = T.Txt(Curto(nome, 40), 15.5, T.Texto, FontWeights.Bold); n.Margin = new Thickness(0, 10, 0, 0); n.Height = 42; sp.Children.Add(n);
            sp.Children.Add(new TextBlock { Text = origem, FontFamily = T.Pixel, FontSize = 17, Foreground = T.B(T.Lilas) });
            var b = T.Botao("Jogar", "Lima", () => Rodar("modojogo-jogo", "Modo Jogo: " + nome, false, nome)); b.Margin = new Thickness(0, 10, 0, 0); b.Padding = new Thickness(12, 8, 12, 8);
            T.AutomationProperties(b, "Jogar " + nome);
            sp.Children.Add(b);
            var c = T.Cartao(sp); c.Margin = new Thickness(0, 0, 12, 12); c.Padding = new Thickness(16);
            return c;
        }

        // ======================= TEMPERATURA (ao vivo) =======================
        void TelaTemperatura()
        {
            var col = Pagina("Temperatura ao vivo", "Atualiza a cada 2 segundos. A leitura da placa de vídeo aparece em placas NVIDIA.");
            var ug = new UniformGrid { Columns = 3 };
            TextBlock bloco(string titulo, string ic, Color cor, out TextBlock sub)
            {
                var sp = new StackPanel();
                var t = new DockPanel(); var q = T.IconeQuadrado(ic, cor, 36); DockPanel.SetDock(q, Dock.Right); t.Children.Add(q);
                t.Children.Add(new TextBlock { Text = titulo, FontFamily = T.Pixel, FontSize = 20, Foreground = T.B(T.Lilas), VerticalAlignment = VerticalAlignment.Center });
                sp.Children.Add(t);
                var v = new TextBlock { Text = "–", FontFamily = T.Display, FontSize = 34, Foreground = T.B(T.Texto), Margin = new Thickness(0, 6, 0, 0) };
                sp.Children.Add(v);
                sub = T.Txt("", 13.5, T.Texto2); sp.Children.Add(sub);
                var c = T.Cartao(sp); c.Margin = new Thickness(0, 0, 12, 12); ug.Children.Add(c);
                return v;
            }
            var vCpu = bloco("PROCESSADOR", "termometro", T.Amarelo, out var sCpu);
            var vUso = bloco("USO DA CPU", "raio", T.Verde, out var sUso);
            var vGpu = bloco("PLACA DE VÍDEO", "jogo", T.Azul, out var sGpu);
            var vMem = bloco("MEMÓRIA", "memoria", T.Lilas, out var sMem);
            var vLim = bloco("LIMITE DE DESEMPENHO", "fps", T.Vermelho, out var sLim);
            var vTop = bloco("PESANDO AGORA", "olho", T.Texto2, out var sTop);
            vTop.FontSize = 16; vTop.FontFamily = T.Normal; vTop.FontWeight = FontWeights.SemiBold;
            col.Children.Add(ug);
            var graf = new Polyline { Stroke = T.B(T.Amarelo), StrokeThickness = 2.5, Height = 90, Stretch = Stretch.None };
            var cgraf = new Canvas { Height = 100, ClipToBounds = true };
            cgraf.Children.Add(graf);
            var gc = new StackPanel(); gc.Children.Add(new TextBlock { Text = "TEMPERATURA DA CPU (ÚLTIMOS 2 MINUTOS)", FontFamily = T.Pixel, FontSize = 18, Foreground = T.B(T.Lilas) }); gc.Children.Add(cgraf);
            col.Children.Add(T.Cartao(gc));
            var hist = new List<double>();
            var bts = new WrapPanel { Margin = new Thickness(0, 6, 0, 0) };
            var b1 = T.Botao("Perfis frios, teste e diagnóstico", "Linha", () => Rodar("temperatura", "Temperatura e refrigeração", false)); b1.Margin = new Thickness(0, 0, 8, 8);
            bts.Children.Add(b1);
            col.Children.Add(bts);
            if (modoFotos) { vCpu.Text = "62 °C"; sCpu.Text = "3.900 MHz"; vUso.Text = "18%"; vGpu.Text = "55 °C"; sGpu.Text = "uso 34% · ventoinha 40%"; vMem.Text = "9,4 GB"; sMem.Text = "livres de 16 GB"; vLim.Text = "100%"; sLim.Text = "sem corte por calor"; vTop.Text = "chrome 6%\nDiscord 2%"; return; }
            fluxo = Ponte.Fluxo("temp-fluxo", d =>
            {
                var cpu = Ponte.O(d, "cpu"); var gpu = Ponte.O(d, "gpu"); var mem = Ponte.O(d, "mem");
                var t = Ponte.N(cpu, "TempC");
                vCpu.Text = double.IsNaN(t) ? "sem sensor" : Math.Round(t) + " °C"; vCpu.Foreground = T.B(double.IsNaN(t) ? T.Texto3 : t >= 90 ? T.Vermelho : t >= 80 ? T.Amarelo : T.Texto);
                sCpu.Text = Ponte.S(cpu, "FreqMHz").Length > 0 ? Ponte.S(cpu, "FreqMHz") + " MHz" : "";
                vUso.Text = Ponte.S(cpu, "UsoCpu").Length > 0 ? Ponte.S(cpu, "UsoCpu") + "%" : "–";
                if (gpu != null) { vGpu.Text = Ponte.S(gpu, "Temp") + " °C"; sGpu.Text = "uso " + Ponte.S(gpu, "Uso") + "% · ventoinha " + Ponte.S(gpu, "Fan") + "% · " + Ponte.S(gpu, "Watts") + " W"; }
                else { vGpu.Text = "–"; sGpu.Text = "leitura só em placas NVIDIA"; }
                if (mem != null) { vMem.Text = Ponte.S(mem, "Livre") + " GB"; sMem.Text = "livres de " + Ponte.S(mem, "Total") + " GB"; }
                var lim = Ponte.N(cpu, "LimiteCpu");
                vLim.Text = double.IsNaN(lim) ? "–" : lim + "%"; sLim.Text = double.IsNaN(lim) ? "" : lim < 95 ? "o processador está sendo freado (calor ou energia)" : "sem corte por calor";
                vLim.Foreground = T.B(!double.IsNaN(lim) && lim < 95 ? T.Vermelho : T.Texto);
                vTop.Text = string.Join("\n", Ponte.L(d, "top").Select(x => Ponte.S(x, "Nome") + "  " + Ponte.S(x, "Uso") + "%"));
                if (!double.IsNaN(t))
                {
                    hist.Add(t); if (hist.Count > 60) hist.RemoveAt(0);
                    var w = Math.Max(200, cgraf.ActualWidth); var mn = Math.Min(30, hist.Min()); var mx = Math.Max(100, hist.Max());
                    graf.Points = new PointCollection(hist.Select((v, i) => new Point(i * w / 59.0, 95 - (v - mn) / (mx - mn) * 90)));
                }
            });
        }

        // ======================= REDE =======================
        void TelaRede()
        {
            var col = Pagina("Internet para jogos", "Mede ping, jitter (variação) e perda de pacotes até servidores grandes.");
            var area = new StackPanel(); col.Children.Add(area);
            area.Children.Add(Carregando("Medindo a sua conexão… uns 15 segundos"));
            void desenhar(Dictionary<string, object> d)
            {
                area.Children.Clear();
                if (d == null) { area.Children.Add(T.Cartao(T.Txt("Não consegui medir agora. Confira se a internet está ligada.", 15, T.Vermelho))); return; }
                var ug = new UniformGrid { Columns = 3 };
                foreach (var a in Ponte.L(d, "alvos"))
                {
                    var media = Ponte.N(a, "Media", -1); var perda = Ponte.N(a, "Perda", 100); var jit = Ponte.N(a, "Jitter", -1);
                    var sp = new StackPanel();
                    sp.Children.Add(new TextBlock { Text = Ponte.S(a, "Nome").ToUpperInvariant() + " · " + Ponte.S(a, "Host"), FontFamily = T.Pixel, FontSize = 18, Foreground = T.B(T.Lilas) });
                    var cor = media < 0 ? T.Vermelho : media <= 30 ? T.Verde : media <= 70 ? T.Amarelo : T.Vermelho;
                    sp.Children.Add(new TextBlock { Text = media < 0 ? "sem resposta" : media + " ms", FontFamily = T.Display, FontSize = 30, Foreground = T.B(cor), Margin = new Thickness(0, 6, 0, 2) });
                    sp.Children.Add(T.Txt("jitter " + (jit < 0 ? "–" : jit + " ms") + " · perda " + perda + "%", 13.5, perda > 0 ? T.Amarelo : T.Texto2));
                    var c = T.Cartao(sp); c.Margin = new Thickness(0, 0, 12, 12); ug.Children.Add(c);
                }
                area.Children.Add(ug);
                var dns = Ponte.L(d, "dns");
                var dnsTxt = d.TryGetValue("dns", out var dv) && dv is object[] da ? string.Join(", ", da.Select(x => Convert.ToString(x))) : Ponte.S(d, "dns");
                area.Children.Add(T.Txt("DNS que o seu PC usa agora: " + (dnsTxt.Length > 0 ? dnsTxt : "automático da operadora"), 14, T.Texto2));
                var bts = new WrapPanel { Margin = new Thickness(0, 14, 0, 0) };
                var b0 = T.Botao("Medir de novo", "Linha", () => Abrir(TelaRede)); b0.Margin = new Thickness(0, 0, 8, 8); bts.Children.Add(b0);
                var b1 = T.Botao("Achar o DNS mais rápido" + (Licenca.Ativo ? "" : "  · PRO"), "Lima", () => Rodar("dns", "DNS mais rápido", true)); b1.Margin = new Thickness(0, 0, 8, 8); bts.Children.Add(b1);
                var b2 = T.Botao("Ajustar a placa de rede e mais" + (Licenca.Ativo ? "" : "  · PRO"), "Linha", () => Rodar("rede", "Internet para jogos", true)); b2.Margin = new Thickness(0, 0, 8, 8); bts.Children.Add(b2);
                var b3 = T.Botao("Teste completo (texto)", "Linha", () => Rodar("conexao", "Teste de conexão", false)); b3.Margin = new Thickness(0, 0, 8, 8); bts.Children.Add(b3);
                area.Children.Add(bts);
            }
            if (modoFotos) { desenhar(Servidor.Ler("{\"alvos\":[{\"Nome\":\"Cloudflare\",\"Host\":\"1.1.1.1\",\"Media\":12,\"Jitter\":1.4,\"Perda\":0},{\"Nome\":\"Google\",\"Host\":\"8.8.8.8\",\"Media\":18,\"Jitter\":2.1,\"Perda\":0},{\"Nome\":\"Quad9\",\"Host\":\"9.9.9.9\",\"Media\":41,\"Jitter\":6.0,\"Perda\":10}],\"dns\":[\"192.168.0.1\"]}")); return; }
            _ = Carregar("rede-json", d => { if (pilha.Count > 0 && pilha[pilha.Count - 1] == (Action)TelaRede) desenhar(d); });
        }

        // ======================= FERRAMENTAS E SEGURANCA =======================
        void TelaFerramentas()
        {
            var col = Pagina("Ferramentas", "Limpeza, temperatura, aparência e manutenção do Windows.");
            foreach (var (acao, nome, desc, pro, ic, cor) in new[]
            {
                ("assistente", "Assistente: fazer tudo", "Limpeza + FPS + aparência + segurança, passo a passo", true, "estrela", T.Verde),
                ("limpeza", "Limpeza do PC", "Padrão, profunda, simulação, arquivos grandes e downloads", false, "vassoura", T.Azul),
                ("ram", "Liberar memória", "Solta a memória presa antes de jogar", false, "memoria", T.Lilas),
                ("temp", "Temperatura ao vivo", "CPU, placa de vídeo, memória e o que está pesando", false, "termometro", T.Amarelo),
                ("temperatura", "Refrigeração e perfis frios", "Diagnóstico, perfis de energia frios e teste de estresse", false, "termometro", T.Amarelo),
                ("aparencia", "Aparência e desempenho inteligente", "Efeitos visuais, processador e memória virtual", true, "olho", T.Lilas),
                ("inicializacao", "Programas que abrem com o Windows", "Deixe o PC ligar mais rápido", true, "raio", T.Verde),
                ("manutencao", "Manutenção do Windows", "Reparar sistema, discos e drivers", true, "ferramenta", T.Azul),
                ("megazord", "Backup e reset de fábrica", "Passo a passo seguro antes de formatar", false, "atualizar", T.Vermelho),
                ("desfazer", "Desfazer as mudanças", "Volta tudo que o SerpyraToolkit mudou no Windows", false, "voltar", T.Texto2),
            })
            {
                var a = acao; var n = nome; var p = pro;
                Action go = a == "temp" ? (Action)(() => Abrir(TelaTemperatura)) : () => Rodar(a, n, p);
                col.Children.Add(T.Item(nome, desc, pro, Licenca.Ativo, go, ic, cor));
            }
        }

        void TelaSeguranca()
        {
            var col = Pagina("Segurança", "Caça vírus, minerador de bitcoin e invasores, sem desligar o seu antivírus.");
            foreach (var (acao, nome, desc, pro, ic, cor) in new[]
            {
                ("scan", "Varredura rápida", "Processos, rede e inicialização suspeitos", false, "olho", T.Verde),
                ("scanfull", "Varredura completa", "Arquivos, contas, eventos e persistência", true, "escudo", T.Azul),
                ("blindagem", "Blindagem", "VirusTotal e reforço do Defender e do SmartScreen", true, "escudo", T.Verde),
                ("quarentena", "Quarentena", "O que foi isolado nas varreduras", false, "memoria", T.Amarelo),
                ("seguranca", "Central de segurança", "Todas as opções de segurança", true, "estrela", T.Lilas),
            })
            {
                var a = acao; var n = nome; var p = pro;
                col.Children.Add(T.Item(nome, desc, pro, Licenca.Ativo, () => Rodar(a, n, p), ic, cor));
            }
        }

        // ======================= RELATAR =======================
        void TelaRelatar()
        {
            var col = Pagina("Relatar um problema", "Conte o que aconteceu. A mensagem chega direto para a gente, com a versão do programa.");
            var sp = new StackPanel();
            var txt = new TextBox { AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, Height = 160, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            T.AutomationProperties(txt, "O que aconteceu");
            var contato = new TextBox();
            T.AutomationProperties(contato, "Seu e-mail para resposta (opcional)");
            var m = T.Txt("", 14, T.Texto2); m.Margin = new Thickness(0, 8, 0, 0);
            var b = T.Botao("Enviar", "Lima", null); b.HorizontalAlignment = HorizontalAlignment.Left; b.Margin = new Thickness(0, 12, 0, 0);
            b.Click += async (s, e) =>
            {
                if (txt.Text.Trim().Length < 5) { m.Text = "Conte um pouco mais do que aconteceu."; m.Foreground = T.B(T.Vermelho); return; }
                b.IsEnabled = false; m.Text = "Enviando…"; m.Foreground = T.B(T.Texto2);
                var r = await Servicos.Relatar(txt.Text.Trim(), contato.Text.Trim(), "PC");
                if (r.Ok) { m.Text = "Recebido! Obrigado. Se deixou um e-mail, a gente responde por lá."; m.Foreground = T.B(T.Verde); txt.Text = ""; }
                else { m.Text = r.Erro; m.Foreground = T.B(T.Vermelho); b.IsEnabled = true; }
            };
            sp.Children.Add(Rotulo("O que aconteceu")); sp.Children.Add(txt);
            sp.Children.Add(Rotulo("Seu e-mail para resposta (opcional)")); sp.Children.Add(contato);
            sp.Children.Add(b); sp.Children.Add(m);
            col.Children.Add(T.Janela("relatar.exe", sp));
        }

        static TextBlock Rotulo(string t) { var r = T.Txt(t, 13.5, T.Texto2, FontWeights.SemiBold); r.Margin = new Thickness(0, 12, 0, 6); return r; }
    }
}
