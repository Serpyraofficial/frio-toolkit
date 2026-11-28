using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using Path = System.Windows.Shapes.Path;

namespace SerpyraPC
{
    public static class Programa
    {
        public const string Versao = "2.0";

        [STAThread]
        public static void Main(string[] args)
        {
            string fotos = null;
            for (int i = 0; i < args.Length - 1; i++) if (args[i] == "--fotos") fotos = args[i + 1];
            var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
            T.CarregarEstilos(app);
            app.DispatcherUnhandledException += (s, e) =>
            {
                Relatorio.Travou(e.Exception);
                MessageBox.Show("Algo deu errado aqui. Já mandamos o aviso para corrigir.\n\n" + e.Exception.Message, "SerpyraToolkit", MessageBoxButton.OK, MessageBoxImage.Warning);
                e.Handled = true;
            };
            var w = new Principal();
            if (fotos != null) w.Fotos(fotos);
            app.Run(w);
        }
    }

    public partial class Principal : Window
    {
        readonly ContentControl conteudo = new ContentControl();
        readonly StackPanel abas = new StackPanel();
        readonly StackPanel cabDireita = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        readonly Border faixaPromo = new Border { Visibility = Visibility.Collapsed, Height = 34, ClipToBounds = true, Cursor = Cursors.Hand, Margin = new Thickness(18, 0, 18, 6), CornerRadius = new CornerRadius(12) };
        string abaAtual = "inicio";
        readonly List<Action> pilha = new List<Action>();
        Execucao rodando;
        System.Diagnostics.Process fluxo;
        string avisoPro = "", cupomPro = "";
        public bool modoFotos;

        public Principal()
        {
            Title = "SerpyraToolkit";
            Width = 1220; Height = 820; MinWidth = 960; MinHeight = 640;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = T.Grade();
            FontFamily = T.Normal;
            Foreground = T.B(T.Texto);
            UseLayoutRounding = true;
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);

            var raiz = new Grid();
            raiz.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(240) });
            raiz.ColumnDefinitions.Add(new ColumnDefinition());
            raiz.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            raiz.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            raiz.RowDefinitions.Add(new RowDefinition());

            var cab = new DockPanel { Margin = new Thickness(24, 16, 24, 10) };
            DockPanel.SetDock(cabDireita, Dock.Right); cab.Children.Add(cabDireita);
            var marca = new StackPanel { Orientation = Orientation.Horizontal };
            marca.Children.Add(Estrela(26, T.Verde));
            var nome = T.TituloCromo("SerpyraToolkit", 26); nome.Margin = new Thickness(10, 0, 0, 0); nome.VerticalAlignment = VerticalAlignment.Center;
            marca.Children.Add(nome);
            marca.Children.Add(new TextBlock { Text = "para PC", FontFamily = T.Pixel, FontSize = 21, Foreground = T.B(T.Lilas), Margin = new Thickness(10, 7, 0, 0), VerticalAlignment = VerticalAlignment.Center });
            cab.Children.Add(marca);
            Grid.SetColumnSpan(cab, 2); raiz.Children.Add(cab);

            Grid.SetRow(faixaPromo, 1); Grid.SetColumnSpan(faixaPromo, 2); raiz.Children.Add(faixaPromo);

            var lateral = new Border { Background = T.B(T.Surface2), BorderBrush = T.B(T.Outline), BorderThickness = new Thickness(1.5), CornerRadius = new CornerRadius(22), Margin = new Thickness(18, 6, 0, 18), Padding = new Thickness(10) };
            var lcol = new DockPanel();
            var rodape = new StackPanel();
            rodape.Children.Add(AbaPequena("chat", "Relatar um problema", () => Abrir(TelaRelatar)));
            rodape.Children.Add(AbaPequena("estrela", "Avaliar no site", () => Servicos.AbrirSite("#avaliacoes")));
            rodape.Children.Add(new TextBlock { Text = "v" + Programa.Versao + " · PC " + Licenca.CodigoBonito, FontFamily = T.Pixel, FontSize = 17, Foreground = T.B(T.Texto3), Margin = new Thickness(10, 8, 0, 2) });
            DockPanel.SetDock(rodape, Dock.Bottom); lcol.Children.Add(rodape);
            lcol.Children.Add(abas);
            lateral.Child = lcol;
            Grid.SetRow(lateral, 2); raiz.Children.Add(lateral);

            Grid.SetRow(conteudo, 2); Grid.SetColumn(conteudo, 1);
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
                Ponte.Parar(fluxo);
            };

            Loaded += async (s, e) =>
            {
                IrAba("inicio");
                if (modoFotos) return;
                Servicos.Abriu();
                await Licenca.Atualizar();
                await Servicos.Atualizar();
                Cabecalho(); Promo();
                if (rodando == null && pilha.Count <= 1 && abaAtual == "inicio") RedesenharAtual();
                var t = new DispatcherTimer { Interval = TimeSpan.FromMinutes(30) };
                t.Tick += async (o, a) => { await Licenca.Atualizar(); await Servicos.Atualizar(); Cabecalho(); Promo(); };
                t.Start();
            };
            Cabecalho();
        }

        Button AbaPequena(string ic, string rot, Action acao)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            sp.Children.Add(T.Icone(ic, T.Texto3, 16));
            sp.Children.Add(new TextBlock { Text = rot, FontSize = 13.5, Foreground = T.B(T.Texto2), Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center });
            var b = new Button { Content = sp, Style = (Style)Application.Current.Resources["Aba"], Padding = new Thickness(12, 7, 12, 7), HorizontalContentAlignment = HorizontalAlignment.Left };
            T.AutomationProperties(b, rot);
            b.Click += (s, e) => { if (Ocupado()) return; acao(); };
            return b;
        }

        static Path Estrela(double tam, Color cor)
            => new Path { Data = Geometry.Parse("M12,1 L14,10 L23,12 L14,14 L12,23 L10,14 L1,12 L10,10 Z"), Fill = T.B(cor), Width = tam, Height = tam, Stretch = Stretch.Uniform, VerticalAlignment = VerticalAlignment.Center };

        void Cabecalho()
        {
            cabDireita.Children.Clear();
            if (Licenca.Ativo)
            {
                var chip = T.Chip("PRO", T.Verde, T.Bg);
                chip.ToolTip = "Pro " + (Licenca.Vitalicio ? "vitalício" : "até " + Licenca.ValidadeTxt);
                cabDireita.Children.Add(chip);
            }
            else
            {
                var b = T.Botao("Ativar o Pro", "Lima", () => { if (!Ocupado()) IrAba("pro"); });
                b.Padding = new Thickness(16, 7, 16, 7);
                cabDireita.Children.Add(b);
            }
        }

        void Promo()
        {
            var p = Servicos.PromoAtual;
            if (p == null || (p.Ate > 0 && DateTimeOffset.FromUnixTimeMilliseconds(p.Ate) < DateTimeOffset.Now)) { faixaPromo.Visibility = Visibility.Collapsed; return; }
            var cor = p.Cor == "amarelo" ? T.Amarelo : p.Cor == "vermelho" ? T.Vermelho : p.Cor == "azul" ? T.Azul : T.Verde;
            faixaPromo.Background = T.B(cor);
            var txt = new TextBlock { Text = "★  " + p.Texto + (p.Cupom.Length > 0 ? "   ·   cupom " + p.Cupom : "") + "   ★  ", FontFamily = T.Pixel, FontSize = 21, Foreground = T.B(T.Bg) };
            var canvas = new Canvas { Height = 34 };
            Canvas.SetTop(txt, 4);
            canvas.Children.Add(txt);
            faixaPromo.Child = canvas;
            faixaPromo.Visibility = Visibility.Visible;
            faixaPromo.MouseLeftButtonUp -= PromoClique; faixaPromo.MouseLeftButtonUp += PromoClique;
            Dispatcher.BeginInvoke(new Action(() => Correr(txt)), DispatcherPriority.Loaded);
        }
        void PromoClique(object s, MouseButtonEventArgs e) { if (Ocupado()) return; cupomPro = Servicos.PromoAtual?.Cupom ?? ""; IrAba("pro"); }
        void Correr(TextBlock txt)
        {
            txt.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            var w = faixaPromo.ActualWidth > 0 ? faixaPromo.ActualWidth : 1100;
            var tr = new TranslateTransform(); txt.RenderTransform = tr;
            if (SystemParameters.ClientAreaAnimation && !modoFotos)
                tr.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(w, -txt.DesiredSize.Width, TimeSpan.FromSeconds(Math.Max(10, (w + txt.DesiredSize.Width) / 90))) { RepeatBehavior = RepeatBehavior.Forever });
            else tr.X = 16;
        }

        // ---------- navegacao ----------
        bool Ocupado()
        {
            if (rodando != null && rodando.Rodando) { MessageBox.Show(this, "Espere a tarefa terminar ou toque em Parar.", "SerpyraToolkit"); return true; }
            return false;
        }

        void Abas()
        {
            abas.Children.Clear();
            foreach (var (id, rot, ic) in new[] { ("inicio", "Início", "inicio"), ("jogos", "Jogos", "jogo"), ("ferramentas", "Ferramentas", "ferramenta"), ("seguranca", "Segurança", "escudo"), ("pro", "Pro", "estrela") })
            {
                var on = id == abaAtual;
                var sp = new StackPanel { Orientation = Orientation.Horizontal };
                var icn = T.Icone(ic, on ? T.Bg : T.Lilas, 20); icn.Margin = new Thickness(0, 0, 12, 0);
                sp.Children.Add(icn);
                sp.Children.Add(new TextBlock { Text = rot, FontSize = 16, FontWeight = on ? FontWeights.Bold : FontWeights.SemiBold, Foreground = T.B(on ? T.Bg : T.Texto2), VerticalAlignment = VerticalAlignment.Center });
                var b = new Button { Content = sp, Style = (Style)Application.Current.Resources["Aba"], Margin = new Thickness(0, 0, 0, 4), HorizontalContentAlignment = HorizontalAlignment.Left, Padding = new Thickness(14, 12, 14, 12) };
                if (on) { b.Background = T.B(T.Verde); b.BorderBrush = T.B(T.Verde); }
                T.AutomationProperties(b, rot);
                var alvo = id;
                b.Click += (s, e) => { if (Ocupado()) return; IrAba(alvo); };
                abas.Children.Add(b);
            }
        }

        void IrAba(string aba)
        {
            abaAtual = aba;
            pilha.Clear();
            Action tela;
            switch (aba)
            {
                case "jogos": tela = TelaJogos; break;
                case "ferramentas": tela = TelaFerramentas; break;
                case "seguranca": tela = TelaSeguranca; break;
                case "pro": tela = TelaPro; break;
                default: tela = TelaInicio; break;
            }
            Abas();
            Abrir(tela);
        }

        void Abrir(Action tela) { Ponte.Parar(fluxo); fluxo = null; rodando = null; pilha.Add(tela); tela(); }
        void Voltar() { Ponte.Parar(fluxo); fluxo = null; rodando = null; if (pilha.Count > 1) pilha.RemoveAt(pilha.Count - 1); if (pilha.Count > 0) pilha[pilha.Count - 1](); else IrAba(abaAtual); }
        void RedesenharAtual() { if (pilha.Count > 0) pilha[pilha.Count - 1](); }

        /// <summary>Tela rolavel com titulo cromado e Voltar quando nao e a primeira da aba.</summary>
        StackPanel Pagina(string titulo, string sub, string nomeRastro = null)
        {
            Relatorio.Passo(nomeRastro ?? titulo ?? abaAtual);
            var col = new StackPanel { Margin = new Thickness(6, 0, 12, 28), MaxWidth = 1000, HorizontalAlignment = HorizontalAlignment.Stretch };
            if (titulo != null)
            {
                var top = new DockPanel { Margin = new Thickness(0, 0, 0, 16) };
                if (pilha.Count > 1)
                {
                    var bv = new Button { Content = T.Icone("voltar", T.Texto, 20), Style = (Style)Application.Current.Resources["Linha"], Padding = new Thickness(10), Margin = new Thickness(0, 0, 14, 0), Width = 46, Height = 46, VerticalAlignment = VerticalAlignment.Top };
                    T.AutomationProperties(bv, "Voltar");
                    bv.Click += (s, e) => Voltar();
                    DockPanel.SetDock(bv, Dock.Left); top.Children.Add(bv);
                }
                var tc = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                tc.Children.Add(T.TituloCromo(titulo, 26));
                if (!string.IsNullOrEmpty(sub)) { var st = T.Txt(sub, 14.5, T.Texto2); st.Margin = new Thickness(0, 4, 0, 0); tc.Children.Add(st); }
                top.Children.Add(tc);
                col.Children.Add(top);
            }
            conteudo.Content = new ScrollViewer { Content = col, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, PanningMode = PanningMode.VerticalOnly };
            return col;
        }

        // ---------- rodar uma funcao do PC-Toolkit na janelinha de texto ----------
        void Rodar(string acao, string titulo, bool pro, string arg = null, string arg2 = null)
        {
            if (pro && !Licenca.Ativo) { avisoPro = titulo + " é do Pro. Teste grátis por 1 hora ou ative abaixo."; IrAba("pro"); return; }
            if ((acao == "modojogo" || acao == "modojogo-jogo") && !Licenca.Ativo)
            {
                if (Licenca.UsosHoje("modojogo") >= 2) { avisoPro = "No grátis o Modo Jogo vale 2 vezes por dia. Com o Pro é sem limite."; IrAba("pro"); return; }
                Licenca.ContarUso("modojogo");
            }
            Relatorio.Passo("▶ " + titulo);
            Ponte.Parar(fluxo); fluxo = null;
            Action volta = pilha.Count > 0 ? pilha[pilha.Count - 1] : (Action)TelaInicio;
            rodando = new Execucao(acao, titulo, codigo => { rodando = null; volta(); }, arg, arg2);
            conteudo.Content = rodando;
            rodando.Iniciar();
        }

        // ---------- teste automatico (GitHub): fotos das telas ----------
        public void Fotos(string pasta)
        {
            modoFotos = true;
            Directory.CreateDirectory(pasta);
            WindowStartupLocation = WindowStartupLocation.Manual; Left = 0; Top = 0; ShowActivated = false;
            Servicos.AvisoAtual = new Servicos.Aviso { Id = 1, Texto = "Exemplo de aviso do painel para o pessoal do PC" };
            Servicos.PromoAtual = new Servicos.Promo { Id = 1, Texto = "Black Friday: 30% no Pro anual", Cupom = "BLACK30", Cor = "verde" };
            Servicos.Mensagens.Add(new Servicos.Mensagem { Id = 2, Titulo = "Novidade no PC", Texto = "Exemplo de notificação enviada pelo painel.", Cupom = "PC10" });
            Servicos.Nova = new Servicos.Versao { Numero = "9.9" };
            resumoCache = Servidor.Ler("{\"memLivre\":9.4,\"memTotal\":16,\"discoLivre\":212,\"discoTotal\":476,\"cpu\":\"AMD Ryzen 5 5600\",\"gpu\":\"NVIDIA GeForce RTX 3060\"}");
            jogosCache = Ponte.L(Servidor.Ler("{\"jogos\":[{\"Nome\":\"Counter-Strike 2\",\"Origem\":\"Steam\"},{\"Nome\":\"Fortnite\",\"Origem\":\"Epic\"},{\"Nome\":\"VALORANT\",\"Origem\":\"Riot\"},{\"Nome\":\"Minecraft\",\"Origem\":\"Minecraft\"},{\"Nome\":\"Grand Theft Auto V\",\"Origem\":\"Rockstar\"}]}"), "jogos");
            raioxCache = Servidor.Ler("{\"nota\":68,\"antes\":0,\"itens\":[{\"Nome\":\"Plano de energia\",\"Estado\":\"PROBLEMA\",\"Detalhe\":\"Equilibrado: o processador economiza durante o jogo\",\"Corrige\":true,\"Auto\":true,\"Peso\":10},{\"Nome\":\"Game DVR do Xbox\",\"Estado\":\"ATENCAO\",\"Detalhe\":\"Gravando em segundo plano\",\"Corrige\":true,\"Auto\":true,\"Peso\":6},{\"Nome\":\"Driver de vídeo\",\"Estado\":\"ATENCAO\",\"Detalhe\":\"Tem mais de 6 meses: atualize pelo app da NVIDIA\",\"Corrige\":false,\"Auto\":false,\"Peso\":5},{\"Nome\":\"Monitor no Hz máximo\",\"Estado\":\"OK\",\"Corrige\":false},{\"Nome\":\"Modo de Jogo do Windows\",\"Estado\":\"OK\",\"Corrige\":false},{\"Nome\":\"Temperatura\",\"Estado\":\"OK\",\"Corrige\":false}]}");
            var passos = new List<Action>();
            void foto(string n) => passos.Add(() => Foto(System.IO.Path.Combine(pasta, n + ".png")));
            void espera(int k) { for (int j = 0; j < k; j++) passos.Add(() => { }); }
            passos.Add(() => { Promo(); IrAba("inicio"); }); espera(3); foto("inicio");
            passos.Add(() => IrAba("jogos")); espera(1); foto("jogos");
            passos.Add(() => Abrir(TelaMeusJogos)); espera(1); foto("meusjogos");
            passos.Add(() => Abrir(TelaRaioX)); espera(1); foto("raiox");
            passos.Add(() => Abrir(TelaTemperatura)); espera(4); foto("temperatura");
            passos.Add(() => Abrir(TelaRede)); espera(1); foto("rede");
            passos.Add(() => IrAba("seguranca")); foto("seguranca");
            passos.Add(() => Abrir(TelaRelatar)); foto("relatar");
            passos.Add(() => IrAba("ferramentas")); foto("ferramentas");
            passos.Add(() => IrAba("pro")); espera(1); foto("pro");
            passos.Add(() => Rodar("limpeza", "Limpeza do PC", false)); espera(4); foto("menu");
            passos.Add(() => { rodando?.Matar(); Ponte.Parar(fluxo); Application.Current.Shutdown(0); });
            int i = 0;
            var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            t.Tick += (s, e) => { if (i < passos.Count) { try { passos[i](); } catch (Exception ex) { File.AppendAllText(System.IO.Path.Combine(pasta, "erros.txt"), ex + "\n"); } i++; } else t.Stop(); };
            Loaded += (s, e) => t.Start();
        }

        void Foto(string arq)
        {
            UpdateLayout();
            var rtb = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(this);
            var enc = new PngBitmapEncoder(); enc.Frames.Add(BitmapFrame.Create(rtb));
            using (var f = File.Create(arq)) enc.Save(f);
        }
    }
}
