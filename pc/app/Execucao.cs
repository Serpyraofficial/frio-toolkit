using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;

namespace SerpyraPC
{
    /// <summary>
    /// Roda uma acao do PC-Toolkit (PowerShell) e mostra o andamento na janela, com cores,
    /// perguntas (campo de resposta e botoes Sim/Nao) e teclas (Parar, Manter...).
    /// </summary>
    public class Execucao : DockPanel
    {
        readonly string acao, titulo;
        readonly Action<int> aoTerminar;
        Process proc;
        readonly TextBlock saida = new TextBlock { TextWrapping = TextWrapping.Wrap, FontFamily = T.Mono, FontSize = 14, Foreground = T.B(T.Texto2), LineHeight = 21 };
        readonly ScrollViewer rolagem;
        readonly TextBlock status, pergunta;
        readonly TextBox resposta = new TextBox();
        readonly WrapPanel rapidas = new WrapPanel { Margin = new Thickness(0, 8, 0, 0) };
        readonly Border caixaPergunta;
        readonly Button btParar, btVoltar;
        readonly StringBuilder textoTodo = new StringBuilder();
        Brush corAtual;
        bool esperando, terminou;
        string ultimaPergunta = "";
        static string pastaScripts;

        public Execucao(string acao, string titulo, Action<int> aoTerminar)
        {
            this.acao = acao; this.titulo = titulo; this.aoTerminar = aoTerminar;
            LastChildFill = true;

            // topo: titulo, status e botoes
            var topo = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            btVoltar = T.Botao("Voltar", "Linha", () => Fechar());
            btVoltar.Visibility = Visibility.Collapsed;
            btParar = T.Botao("Parar", "Linha", Parar);
            btParar.Foreground = T.B(T.Vermelho);
            var bts = new StackPanel { Orientation = Orientation.Horizontal };
            bts.Children.Add(btParar); bts.Children.Add(btVoltar);
            DockPanel.SetDock(bts, Dock.Right); topo.Children.Add(bts);
            var tt = new StackPanel();
            tt.Children.Add(T.TituloCromo(titulo, 22));
            status = T.Txt("Rodando…", 15, T.Lilas, null, T.Pixel); status.FontSize = 20;
            tt.Children.Add(status);
            topo.Children.Add(tt);
            DockPanel.SetDock(topo, Dock.Top); Children.Add(topo);

            // pergunta embaixo
            var pcol = new StackPanel();
            pergunta = T.Txt("", 15, T.Texto, FontWeights.Bold);
            pcol.Children.Add(pergunta);
            var linha = new DockPanel { Margin = new Thickness(0, 8, 0, 0) };
            var enviar = T.Botao("Enviar", "Lima", () => Responder(resposta.Text));
            enviar.Margin = new Thickness(8, 0, 0, 0);
            DockPanel.SetDock(enviar, Dock.Right); linha.Children.Add(enviar);
            linha.Children.Add(resposta);
            T.AoEnter(resposta, () => Responder(resposta.Text));
            pcol.Children.Add(linha);
            pcol.Children.Add(rapidas);
            caixaPergunta = T.Cartao(pcol, T.Azul, T.Surface2);
            caixaPergunta.Visibility = Visibility.Collapsed;
            DockPanel.SetDock(caixaPergunta, Dock.Bottom); Children.Add(caixaPergunta);

            // saida
            saida.Margin = new Thickness(4);
            rolagem = new ScrollViewer { Content = saida, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(14) };
            Children.Add(T.Janela(acao + ".exe", rolagem));
        }

        static string PrepararScripts()
        {
            if (pastaScripts != null) return pastaScripts;
            var p = Path.Combine(Licenca.Pasta, "script");
            Directory.CreateDirectory(p);
            foreach (var nome in new[] { "app.ps1", "toolkit.ps1" })
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(nome))
                using (var f = File.Create(Path.Combine(p, nome))) s.CopyTo(f);
            }
            return pastaScripts = p;
        }

        public void Iniciar()
        {
            try
            {
                var pasta = PrepararScripts();
                var ps = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"WindowsPowerShell\v1.0\powershell.exe");
                var si = new ProcessStartInfo(ps, "-NoProfile -NoLogo -NonInteractive -ExecutionPolicy Bypass -File \"" + Path.Combine(pasta, "app.ps1") + "\" -Acao " + acao)
                {
                    UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
                    StandardOutputEncoding = new UTF8Encoding(false), StandardErrorEncoding = new UTF8Encoding(false), WorkingDirectory = pasta
                };
                proc = new Process { StartInfo = si, EnableRaisingEvents = true };
                proc.Exited += (s, e) => Dispatcher.BeginInvoke(new Action(Terminou));
                proc.Start();
                var tOut = new Thread(() => Ler(proc.StandardOutput)) { IsBackground = true }; tOut.Start();
                var tErr = new Thread(() => LerErros(proc.StandardError)) { IsBackground = true }; tErr.Start();
            }
            catch (Exception ex)
            {
                Escrever("Não consegui abrir o PowerShell: " + ex.Message + "\n", T.B(T.Vermelho));
                Terminou();
            }
        }

        // ---------- leitura da saida (com as marcas de cor/pergunta/limpar) ----------
        void Ler(StreamReader r)
        {
            var buf = new char[2048];
            var pend = new StringBuilder();
            try
            {
                int n;
                while ((n = r.Read(buf, 0, buf.Length)) > 0)
                {
                    pend.Append(buf, 0, n);
                    var bloco = pend.ToString();
                    // nao corta uma marca pela metade
                    int corte = bloco.Length;
                    int esc = bloco.LastIndexOf('\u001b');
                    if (esc >= 0 && bloco.IndexOf(']', esc) < 0) corte = esc;
                    else if (esc >= 0 && bloco.Substring(esc).StartsWith("\u001b[p]") && bloco.IndexOf("\u001b[/p]", esc, StringComparison.Ordinal) < 0) corte = esc;
                    var pronto = bloco.Substring(0, corte);
                    pend.Clear(); pend.Append(bloco.Substring(corte));
                    if (pronto.Length > 0) Dispatcher.BeginInvoke(new Action(() => Processar(pronto)));
                }
                if (pend.Length > 0) { var resto = pend.ToString(); Dispatcher.BeginInvoke(new Action(() => Processar(resto))); }
            }
            catch { }
        }

        void LerErros(StreamReader r)
        {
            try
            {
                string l;
                while ((l = r.ReadLine()) != null)
                {
                    if (l.StartsWith("ERRO|")) Relatorio.Falha(acao, l.Substring(5), textoTodo.ToString());
                }
            }
            catch { }
        }

        void Processar(string t)
        {
            int i = 0;
            var normal = new StringBuilder();
            while (i < t.Length)
            {
                if (t[i] == '\u001b')
                {
                    if (normal.Length > 0) { Escrever(normal.ToString(), corAtual); normal.Clear(); }
                    int fim = t.IndexOf(']', i);
                    if (fim < 0) break;
                    var marca = t.Substring(i + 2, fim - i - 2); // depois de ESC[
                    i = fim + 1;
                    if (marca == "2J") { saida.Inlines.Clear(); }
                    else if (marca.StartsWith("c:")) { var nome = marca.Substring(2); corAtual = nome.Length == 0 ? null : CorConsole(nome); }
                    else if (marca == "p")
                    {
                        int f2 = t.IndexOf("\u001b[/p]", i, StringComparison.Ordinal);
                        var q = f2 < 0 ? t.Substring(i) : t.Substring(i, f2 - i);
                        i = f2 < 0 ? t.Length : f2 + 5;
                        Perguntar(q);
                    }
                    continue;
                }
                normal.Append(t[i]); i++;
            }
            if (normal.Length > 0) Escrever(normal.ToString(), corAtual);
        }

        void Escrever(string s, Brush cor)
        {
            textoTodo.Append(s);
            if (textoTodo.Length > 400000) textoTodo.Remove(0, 100000);
            var run = new Run(s);
            if (cor != null) run.Foreground = cor;
            saida.Inlines.Add(run);
            if (saida.Inlines.Count > 4000) saida.Inlines.Remove(saida.Inlines.FirstInline);
            rolagem.ScrollToEnd();
            AtualizarTeclas();
        }

        static Brush CorConsole(string n)
        {
            switch (n)
            {
                case "Green": return T.B(T.Verde);
                case "DarkGreen": return T.B("#8FD14F");
                case "Yellow": return T.B(T.Amarelo);
                case "DarkYellow": return T.B("#E0B050");
                case "Red": return T.B(T.Vermelho);
                case "DarkRed": return T.B("#E05561");
                case "Cyan": return T.B("#8CE8FF");
                case "DarkCyan": return T.B("#7FB8D0");
                case "Magenta": return T.B("#D59CFF");
                case "DarkMagenta": return T.B("#B57BFF");
                case "Blue": return T.B(T.Azul);
                case "DarkBlue": return T.B("#6A5CD8");
                case "Gray": return T.B(T.Texto2);
                case "DarkGray": return T.B(T.Texto3);
                case "White": return T.B(T.Texto);
                case "Black": return T.B(T.Texto3);
                default: return null;
            }
        }

        // ---------- perguntas e teclas ----------
        void Perguntar(string q)
        {
            esperando = true;
            ultimaPergunta = q.Trim();
            pergunta.Text = ultimaPergunta.Length > 0 ? ultimaPergunta : "Sua resposta";
            Escrever(ultimaPergunta + ": ", T.B(T.Lilas));
            resposta.Text = "";
            rapidas.Children.Clear();
            var low = ultimaPergunta.ToLowerInvariant();
            if (low.Contains("(s/n)")) { Rapida("Sim", "S", "Lima"); Rapida("Não", "N", "Linha"); }
            else if (low.Contains("enter")) Rapida("Continuar", "", "Lima");
            caixaPergunta.Visibility = Visibility.Visible;
            resposta.Focus();
            status.Text = "Esperando sua resposta";
        }

        void Rapida(string rot, string valor, string estilo)
        {
            var b = T.Botao(rot, estilo, () => Responder(valor), 130);
            b.Margin = new Thickness(0, 0, 8, 0);
            rapidas.Children.Add(b);
        }

        void Responder(string v)
        {
            if (!esperando || proc == null || proc.HasExited) return;
            esperando = false;
            caixaPergunta.Visibility = Visibility.Collapsed;
            status.Text = "Rodando…";
            try
            {
                var bytes = new UTF8Encoding(false).GetBytes((v ?? "") + "\r\n");
                proc.StandardInput.BaseStream.Write(bytes, 0, bytes.Length);
                proc.StandardInput.BaseStream.Flush();
            }
            catch { }
        }

        WrapPanel teclas;
        void AtualizarTeclas()
        {
            // o fim do texto diz se o script espera uma tecla (monitor ao vivo, confirmar a tela)
            var fim = textoTodo.Length > 600 ? textoTodo.ToString(textoTodo.Length - 600, 600) : textoTodo.ToString();
            string modo = "";
            if (fim.Contains("Aperte S nos proximos")) modo = "tela";
            else if (fim.Contains("Pressione Q para sair")) modo = "monitor";
            if (teclas == null)
            {
                teclas = new WrapPanel { Margin = new Thickness(0, 0, 0, 10) };
                DockPanel.SetDock(teclas, Dock.Bottom);
                Children.Insert(1, teclas);
            }
            if ((string)teclas.Tag == modo) return;
            teclas.Tag = modo;
            teclas.Children.Clear();
            if (modo == "tela") { Tecla("Manter assim", "S", "Lima"); Tecla("Voltar como estava", "N", "Linha"); }
            else if (modo == "monitor") Tecla("Parar o monitor", "Q", "Lima");
        }

        void Tecla(string rot, string letra, string estilo)
        {
            var b = T.Botao(rot, estilo, () =>
            {
                try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "serpyra-pc-tecla.txt"), letra); } catch { }
                teclas.Children.Clear(); teclas.Tag = "enviada";
            });
            b.Margin = new Thickness(0, 0, 8, 0);
            teclas.Children.Add(b);
        }

        // ---------- fim ----------
        public bool Rodando => proc != null && !terminou;

        void Parar()
        {
            if (terminou) return;
            var r = MessageBox.Show(Window.GetWindow(this), "Parar agora? O que já foi feito fica como está. Se for o Modo Jogo, abra o app de novo que ele desfaz o que ficou pendente.", "Parar", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (r != MessageBoxResult.Yes) return;
            try { proc.Kill(); } catch { }
        }

        public void Matar() { try { if (proc != null && !proc.HasExited) proc.Kill(); } catch { } }

        void Terminou()
        {
            if (terminou) return;
            terminou = true;
            int codigoSaida = -1;
            try { codigoSaida = proc?.ExitCode ?? -1; } catch { }
            caixaPergunta.Visibility = Visibility.Collapsed;
            if (teclas != null) teclas.Children.Clear();
            btParar.Visibility = Visibility.Collapsed;
            btVoltar.Visibility = Visibility.Visible;
            status.Text = codigoSaida == 0 ? "Pronto ✓" : codigoSaida == 3 ? "Precisa de administrador" : "Terminou";
            status.Foreground = T.B(codigoSaida == 0 ? T.Verde : T.Amarelo);
            btVoltar.Focus();
        }

        void Fechar() => aoTerminar?.Invoke(terminou ? (proc?.ExitCode ?? -1) : -1);
    }
}
