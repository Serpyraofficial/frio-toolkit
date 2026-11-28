using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Threading.Tasks;

namespace SerpyraPC
{
    /// <summary>O que o PC recebe do painel: aviso, promocao, notificacoes e atualizacao (separados do Android).</summary>
    public static class Servicos
    {
        public class Aviso { public long Id; public string Texto = "", Link = ""; }
        public class Promo { public long Id; public string Texto = "", Cupom = "", Cor = "verde"; public long Ate; }
        public class Mensagem { public long Id; public string Titulo = "", Texto = "", Publico = "todos", Cupom = ""; }
        public class Versao { public string Numero = ""; public string Sha256 = ""; public long Tamanho; }

        public static Aviso AvisoAtual; public static Promo PromoAtual; public static List<Mensagem> Mensagens = new List<Mensagem>(); public static Versao Nova;

        static Dictionary<string, object> Est => Licenca.LerEstado();
        static void Gravar(string k, object v) { var e = Licenca.LerEstado(); e[k] = v; Licenca.GravarEstado(e); }
        static string Ler(string k) { var e = Licenca.LerEstado(); return e.TryGetValue(k, out var v) && v != null ? Convert.ToString(v) : ""; }

        /// <summary>Busca tudo do servidor de uma vez (ao abrir e a cada 30 min).</summary>
        public static async Task Atualizar()
        {
            var tA = Servidor.Chamar("GET", "/aviso?plataforma=pc");
            var tP = Servidor.Chamar("GET", "/promo?plataforma=pc");
            var tM = Servidor.Chamar("GET", "/mensagens?plataforma=pc");
            var tV = Servidor.Chamar("GET", "/pc/versao");
            await Task.WhenAll(tA, tP, tM, tV);
            var a = tA.Result; AvisoAtual = a.Ok && a.Str("texto").Length > 0 ? new Aviso { Id = Num(a.Str("id")), Texto = a.Str("texto"), Link = a.Str("link") } : null;
            if (AvisoAtual != null && Ler("aviso_fechado") == AvisoAtual.Id.ToString()) AvisoAtual = null;
            var p = tP.Result; PromoAtual = p.Ok && p.Str("texto").Length > 0 ? new Promo { Id = Num(p.Str("id")), Texto = p.Str("texto"), Cupom = p.Str("cupom"), Cor = p.Str("cor"), Ate = Num(p.Str("ate")) } : null;
            Mensagens.Clear();
            if (tM.Result.Ok && tM.Result.Dados.TryGetValue("mensagens", out var lm) && lm is object[] arr)
            {
                var vistas = Ler("msg_fechadas").Split(',');
                foreach (var o in arr.OfType<Dictionary<string, object>>())
                {
                    var m = new Mensagem { Id = Num(Convert.ToString(o["id"])), Titulo = Convert.ToString(o["titulo"]), Texto = Convert.ToString(o["texto"]), Publico = Convert.ToString(o.ContainsKey("publico") ? o["publico"] : "todos"), Cupom = Convert.ToString(o.ContainsKey("cupom") ? o["cupom"] : "") };
                    if (vistas.Contains(m.Id.ToString())) continue;
                    if (!ParaMim(m.Publico)) continue;
                    Mensagens.Add(m);
                }
            }
            var v = tV.Result;
            Nova = v.Ok && MaisNova(v.Str("versao"), Programa.Versao) ? new Versao { Numero = v.Str("versao"), Sha256 = v.Str("sha256").ToLowerInvariant(), Tamanho = Num(v.Str("tamanho")) } : null;
        }

        static bool ParaMim(string publico)
        {
            switch (publico)
            {
                case "pro": return Licenca.Ativo;
                case "gratis": return !Licenca.Ativo && Licenca.Chave.Length == 0;
                case "vencidos": return !Licenca.Ativo && Licenca.Chave.Length > 0;
                default: return true;
            }
        }

        public static void FecharAviso() { if (AvisoAtual != null) { Gravar("aviso_fechado", AvisoAtual.Id.ToString()); AvisoAtual = null; } }
        public static void FecharMensagem(Mensagem m) { Gravar("msg_fechadas", string.Join(",", Ler("msg_fechadas").Split(',').Where(x => x.Length > 0).Concat(new[] { m.Id.ToString() }).Reverse().Take(40))); Mensagens.Remove(m); }

        static long Num(string s) => long.TryParse(s, out var n) ? n : 0;
        static bool MaisNova(string a, string b)
        {
            Version va, vb;
            return Version.TryParse(Normal(a), out va) && Version.TryParse(Normal(b), out vb) && va > vb;
        }
        static string Normal(string v) { v = (v ?? "").Trim(); return v.Count(c => c == '.') == 0 ? v + ".0" : v; }

        /// <summary>Baixa a versao nova, confere e troca o programa (reabre sozinho).</summary>
        public static async Task<string> InstalarNova(Action<string> progresso)
        {
            if (Nova == null) return "Nenhuma versão nova.";
            try
            {
                progresso("Baixando a versão " + Nova.Numero + "…");
                var tmp = Path.Combine(Path.GetTempPath(), "SerpyraToolkit-novo.exe");
                using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) })
                {
                    var bytes = await http.GetByteArrayAsync(Servidor.URL + "/pc");
                    string h;
                    using (var sha = SHA256.Create()) h = BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant();
                    if (Nova.Sha256.Length > 0 && h != Nova.Sha256) return "O arquivo baixado veio diferente do esperado. Tente de novo em alguns minutos.";
                    File.WriteAllBytes(tmp, bytes);
                }
                var atual = Process.GetCurrentProcess().MainModule.FileName;
                var bat = Path.Combine(Path.GetTempPath(), "serpyra-atualizar.cmd");
                File.WriteAllText(bat, "@echo off\r\nping 127.0.0.1 -n 3 >nul\r\n:de_novo\r\ncopy /y \"" + tmp + "\" \"" + atual + "\" >nul 2>&1\r\nif errorlevel 1 (ping 127.0.0.1 -n 2 >nul & goto de_novo)\r\nstart \"\" \"" + atual + "\"\r\ndel \"" + tmp + "\"\r\n(goto) 2>nul & del \"%~f0\"\r\n");
                Process.Start(new ProcessStartInfo("cmd.exe", "/c \"" + bat + "\"") { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
                progresso("Atualizando… o programa abre de novo sozinho.");
                await Task.Delay(400);
                System.Windows.Application.Current.Shutdown();
                return "";
            }
            catch (Exception e) { return "Não consegui atualizar agora: " + e.Message; }
        }

        /// <summary>Conta que o programa foi aberto hoje (painel: Quem está usando).</summary>
        public static void Abriu() => _ = Servidor.Chamar("POST", "/evento", new Dictionary<string, object> { ["codigo"] = Licenca.Codigo, ["tipo"] = "abriu", ["plataforma"] = "pc", ["pro"] = Licenca.Ativo });

        public static Task<Servidor.Resposta> Relatar(string texto, string contato, string tela)
            => Servidor.Chamar("POST", "/relato", new Dictionary<string, object> { ["codigo"] = Licenca.Codigo, ["texto"] = texto, ["contato"] = contato, ["versao"] = "pc-" + Programa.Versao, ["modelo"] = "PC Windows", ["android"] = "win", ["pro"] = Licenca.Ativo, ["tela"] = tela });

        public static void AbrirSite(string caminho) { try { Process.Start(new ProcessStartInfo("https://serpyratoolkit.pages.dev/" + caminho) { UseShellExecute = true }); } catch { } }
    }
}
