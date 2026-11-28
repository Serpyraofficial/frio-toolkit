using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;

namespace SerpyraPC
{
    /// <summary>Roda uma acao do PC-Toolkit escondida e devolve os dados (ESC[json]...ESC[/json]) para as telas.</summary>
    public static class Ponte
    {
        static Process Abrir(string acao, string arg)
        {
            var pasta = Execucao.PrepararScripts();
            var ps = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"WindowsPowerShell\v1.0\powershell.exe");
            var si = new ProcessStartInfo(ps, "-NoProfile -NoLogo -NonInteractive -ExecutionPolicy Bypass -File \"" + Path.Combine(pasta, "app.ps1") + "\" -Acao " + acao)
            {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = new UTF8Encoding(false), WorkingDirectory = pasta
            };
            if (arg != null) si.EnvironmentVariables["SERPYRA_ARG"] = arg;
            var p = Process.Start(si);
            try { p.StandardInput.Close(); } catch { }
            new Thread(() => { try { p.StandardError.ReadToEnd(); } catch { } }) { IsBackground = true }.Start();
            return p;
        }

        /// <summary>Le os blocos de dados da saida e chama aoDado para cada um (na thread da tela).</summary>
        static void Ler(Process p, Action<Dictionary<string, object>> aoDado)
        {
            var sb = new StringBuilder();
            var buf = new char[4096];
            int n;
            try
            {
                while ((n = p.StandardOutput.Read(buf, 0, buf.Length)) > 0)
                {
                    sb.Append(buf, 0, n);
                    while (true)
                    {
                        var t = sb.ToString();
                        int a = t.IndexOf("\u001b[json]", StringComparison.Ordinal);
                        if (a < 0) { if (sb.Length > 200000) sb.Clear(); break; }
                        int b = t.IndexOf("\u001b[/json]", a, StringComparison.Ordinal);
                        if (b < 0) break;
                        var j = t.Substring(a + 7, b - a - 7);
                        sb.Remove(0, b + 8);
                        var d = Servidor.Ler(j);
                        if (d != null) Application.Current?.Dispatcher.BeginInvoke(new Action(() => aoDado(d)));
                    }
                }
            }
            catch { }
        }

        /// <summary>Roda e espera o primeiro bloco de dados (ou null se falhar).</summary>
        public static Task<Dictionary<string, object>> Dados(string acao, string arg = null, int segundos = 180)
        {
            var tcs = new TaskCompletionSource<Dictionary<string, object>>();
            try
            {
                var p = Abrir(acao, arg);
                new Thread(() =>
                {
                    Ler(p, d => tcs.TrySetResult(d));
                    Thread.Sleep(300);
                    tcs.TrySetResult(null);
                }) { IsBackground = true }.Start();
                new Thread(() => { if (!p.WaitForExit(segundos * 1000)) { try { p.Kill(); } catch { } } }) { IsBackground = true }.Start();
            }
            catch { tcs.TrySetResult(null); }
            return tcs.Task;
        }

        /// <summary>Roda uma acao que manda dados sem parar (monitor). Devolve o processo para parar depois.</summary>
        public static Process Fluxo(string acao, Action<Dictionary<string, object>> aoDado)
        {
            try
            {
                var p = Abrir(acao, null);
                new Thread(() => Ler(p, aoDado)) { IsBackground = true }.Start();
                return p;
            }
            catch { return null; }
        }

        public static void Parar(Process p) { try { if (p != null && !p.HasExited) p.Kill(); } catch { } }

        // ajudantes para ler o JSON
        public static string S(Dictionary<string, object> d, string k) => d != null && d.TryGetValue(k, out var v) && v != null ? Convert.ToString(v, System.Globalization.CultureInfo.InvariantCulture) : "";
        public static double N(Dictionary<string, object> d, string k, double padrao = double.NaN)
        {
            var s = S(d, k);
            return double.TryParse(s, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out var x) ? x : padrao;
        }
        public static Dictionary<string, object> O(Dictionary<string, object> d, string k) => d != null && d.TryGetValue(k, out var v) ? v as Dictionary<string, object> : null;
        public static List<Dictionary<string, object>> L(Dictionary<string, object> d, string k)
        {
            var l = new List<Dictionary<string, object>>();
            if (d != null && d.TryGetValue(k, out var v))
            {
                if (v is object[] a) foreach (var x in a) { if (x is Dictionary<string, object> o) l.Add(o); }
                else if (v is System.Collections.ArrayList al) foreach (var x in al) { if (x is Dictionary<string, object> o) l.Add(o); }
                else if (v is Dictionary<string, object> um) l.Add(um);
            }
            return l;
        }
    }
}
