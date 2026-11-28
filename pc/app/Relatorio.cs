using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace SerpyraPC
{
    /// <summary>Avisa o servidor quando algo falha (aparece no painel, aba Erros), com print da janela e o caminho.</summary>
    public static class Relatorio
    {
        static readonly List<string> rastro = new List<string>();
        static DateTime ultimoEnvio = DateTime.MinValue;

        public static void Passo(string s)
        {
            lock (rastro)
            {
                if (rastro.Count > 0 && rastro[rastro.Count - 1] == s) return;
                rastro.Add(s); while (rastro.Count > 12) rastro.RemoveAt(0);
            }
        }

        public static void Falha(string onde, string detalhe, string saida)
        {
            Application.Current?.Dispatcher.BeginInvoke(new Action(() => Enviar("falha", onde, detalhe, saida, null)));
        }

        public static void Travou(Exception e)
        {
            try { Enviar("crash", "travou", e.GetType().Name + ": " + e.Message, "", e); } catch { }
        }

        static string Print()
        {
            try
            {
                var w = Application.Current?.MainWindow;
                if (w == null || w.ActualWidth < 10) return "";
                var esc = Math.Min(1.0, 900.0 / w.ActualWidth);
                var rtb = new RenderTargetBitmap((int)(w.ActualWidth * esc), (int)(w.ActualHeight * esc), 96 * esc, 96 * esc, PixelFormats.Pbgra32);
                rtb.Render(w);
                var enc = new JpegBitmapEncoder { QualityLevel = 55 };
                enc.Frames.Add(BitmapFrame.Create(rtb));
                using (var ms = new MemoryStream()) { enc.Save(ms); return ms.Length < 200000 ? Convert.ToBase64String(ms.ToArray()) : ""; }
            }
            catch { return ""; }
        }

        static async void Enviar(string tipo, string onde, string detalhe, string saida, Exception e)
        {
            if ((DateTime.UtcNow - ultimoEnvio).TotalSeconds < 20) return;
            ultimoEnvio = DateTime.UtcNow;
            try
            {
                List<string> r; lock (rastro) r = rastro.ToList();
                var ultimas = string.Join("\n", (saida ?? "").Split('\n').Reverse().Take(8).Reverse()).Trim();
                var pilha = e != null ? string.Join("\n", (e.StackTrace ?? "").Split('\n').Take(6)) : ultimas;
                var erro = new Dictionary<string, object>
                {
                    ["tipo"] = tipo, ["onde"] = ("PC: " + onde), ["msg"] = detalhe.Length > 200 ? detalhe.Substring(0, 200) : detalhe,
                    ["pilha"] = pilha.Length > 1500 ? pilha.Substring(0, 1500) : pilha, ["tela"] = r.LastOrDefault() ?? "", ["rastro"] = r,
                    ["em"] = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalMilliseconds,
                    ["extra"] = new Dictionary<string, object> { ["windows"] = Environment.OSVersion.Version.ToString(), ["temPro"] = Licenca.Ativo ? "sim" : "não", ["memoria"] = Memoria() },
                    ["print"] = e == null ? Print() : ""
                };
                await Servidor.Chamar("POST", "/erro", new Dictionary<string, object>
                {
                    ["codigo"] = Licenca.Codigo, ["versao"] = "pc-" + Programa.Versao, ["modelo"] = "PC Windows " + (Environment.Is64BitOperatingSystem ? "64" : "32"),
                    ["android"] = "win", ["erros"] = new[] { erro }
                });
            }
            catch { }
        }

        static string Memoria()
        {
            try
            {
                var ci = new Microsoft.VisualBasic.Devices.ComputerInfo();
                return string.Format("{0:0.0} de {1:0.0} GB livres", ci.AvailablePhysicalMemory / 1e9, ci.TotalPhysicalMemory / 1e9);
            }
            catch { return ""; }
        }
    }
}
