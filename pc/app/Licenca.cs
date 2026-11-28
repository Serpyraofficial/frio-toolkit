using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using Microsoft.Win32;

namespace SerpyraPC
{
    /// <summary>Conversa com o servidor do SerpyraToolkit (o mesmo do app de Android).</summary>
    public static class Servidor
    {
        public const string URL = "https://serpyratoolkit.officialcarloswendrik.workers.dev";
        static readonly HttpClient Http;
        static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

        static Servidor()
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | (SecurityProtocolType)12288;
            Http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            Http.DefaultRequestHeaders.UserAgent.ParseAdd("SerpyraToolkitPC/" + Programa.Versao);
        }

        public class Resposta
        {
            public int Status;
            public Dictionary<string, object> Dados = new Dictionary<string, object>();
            public bool Ok => Status >= 200 && Status < 300;
            public string Str(string k) => Dados.TryGetValue(k, out var v) && v != null ? Convert.ToString(v) : "";
            public string Erro => Str("erro").Length > 0 ? Str("erro") : (Status == 0 ? "Sem conexão com a internet. Tente de novo." : "Não deu certo agora (" + Status + "). Tente de novo.");
        }

        public static async Task<Resposta> Chamar(string metodo, string rota, object corpo = null)
        {
            var r = new Resposta();
            try
            {
                var req = new HttpRequestMessage(new HttpMethod(metodo), URL + rota);
                if (corpo != null) req.Content = new StringContent(Json.Serialize(corpo), Encoding.UTF8, "application/json");
                var resp = await Http.SendAsync(req).ConfigureAwait(false);
                r.Status = (int)resp.StatusCode;
                var t = await resp.Content.ReadAsStringAsync().ConfigureAwait(false);
                try { if (Json.DeserializeObject(t) is Dictionary<string, object> d) r.Dados = d; } catch { }
            }
            catch { r.Status = 0; }
            return r;
        }

        public static string Serializar(object o) => Json.Serialize(o);
        public static Dictionary<string, object> Ler(string t) { try { return Json.DeserializeObject(t) as Dictionary<string, object>; } catch { return null; } }
    }

    /// <summary>Pro do PC: chave assinada pelo servidor, presa a este computador.</summary>
    public static class Licenca
    {
        const string B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        const string PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEe1+AJ9msQvTj5AdyZO0SzXl0SNyekG3tBq3nUeQHdWSvy3u7i8k6qTJsZ8iU890vHizszVAKeAxs4fjOVXVnFQ==";
        public static readonly string Pasta = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SerpyraToolkit");
        static string Arq => Path.Combine(Pasta, "licenca.txt");
        static string ArqEstado => Path.Combine(Pasta, "estado.json");

        static string codigo;
        /// <summary>Codigo de 8 letras deste PC (sai do identificador do Windows, sempre o mesmo).</summary>
        public static string Codigo
        {
            get
            {
                if (codigo != null) return codigo;
                string id = "";
                try
                {
                    using (var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).OpenSubKey(@"SOFTWARE\Microsoft\Cryptography"))
                        id = Convert.ToString(k?.GetValue("MachineGuid") ?? "");
                }
                catch { }
                if (string.IsNullOrEmpty(id)) id = Environment.MachineName + Environment.UserName;
                byte[] h;
                using (var sha = SHA256.Create()) h = sha.ComputeHash(Encoding.UTF8.GetBytes("serpyra-pc|" + id));
                var sb = new StringBuilder();
                for (int i = 0; i < 8; i++) sb.Append(B32[h[i] % 32]);
                return codigo = sb.ToString();
            }
        }
        public static string CodigoBonito => Codigo.Substring(0, 4) + "-" + Codigo.Substring(4);

        static long Hoje => (long)Math.Floor((DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalDays);
        static long AgoraMs => (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;

        /// <summary>Confere a assinatura: devolve o dia de validade ou -1.</summary>
        public static long Conferir(string chave)
        {
            try
            {
                var t = (chave ?? "").Trim().Replace(" ", "");
                if (t.StartsWith("PCT-", StringComparison.OrdinalIgnoreCase)) t = t.Substring(4);
                var p = t.Split('.');
                if (p.Length != 3) return -1;
                var cod = p[0].ToUpperInvariant().Replace("-", "");
                if (cod != Codigo) return -1;
                if (!long.TryParse(p[1], out var dia) || dia <= 0) return -1;
                var s64 = p[2].Replace('-', '+').Replace('_', '/');
                s64 += new string('=', (4 - s64.Length % 4) % 4);
                var sig = Convert.FromBase64String(s64);
                if (sig.Length != 64) return -1;
                var spki = Convert.FromBase64String(PUB);
                var ponto = spki.Skip(spki.Length - 65).ToArray();
                if (ponto[0] != 4) return -1;
                var blob = new byte[8 + 64];
                BitConverter.GetBytes(0x31534345).CopyTo(blob, 0); // ECS1: chave publica ECDSA P-256
                BitConverter.GetBytes(32).CopyTo(blob, 4);
                Array.Copy(ponto, 1, blob, 8, 64);
                using (var k = CngKey.Import(blob, CngKeyBlobFormat.EccPublicBlob))
                using (var ec = new ECDsaCng(k) { HashAlgorithm = CngAlgorithm.Sha256 })
                    return ec.VerifyData(Encoding.UTF8.GetBytes(cod + "." + dia), sig) ? dia : -1;
            }
            catch { return -1; }
        }

        public static string Chave { get { try { return File.Exists(Arq) ? File.ReadAllLines(Arq).FirstOrDefault() ?? "" : ""; } catch { return ""; } } }
        static long Ate { get { try { var l = File.ReadAllLines(Arq); return l.Length > 1 && long.TryParse(l[1], out var a) ? a : 0; } catch { return 0; } } }

        public static void Guardar(string chave, long ate = 0)
        {
            Directory.CreateDirectory(Pasta);
            File.WriteAllLines(Arq, new[] { chave.Trim(), ate > 0 ? ate.ToString() : "" });
        }
        public static void Apagar() { try { File.Delete(Arq); } catch { } }

        public static long Dia => Conferir(Chave);
        public static bool Ativo
        {
            get
            {
                var d = Dia;
                if (d < 0 || d < Hoje) return false;
                var a = Ate; return a <= 0 || AgoraMs <= a;
            }
        }
        public static bool Vitalicio => Dia >= 99999;
        public static string ValidadeTxt
        {
            get
            {
                var a = Ate;
                if (a > 0) return "até " + DateTimeOffset.FromUnixTimeMilliseconds(a).ToLocalTime().ToString("HH:mm 'de' dd/MM");
                var d = Dia; if (d >= 99999) return "vitalício";
                return d < 0 ? "" : new DateTime(1970, 1, 1).AddDays(d).ToString("dd/MM/yyyy");
            }
        }

        /// <summary>Confere no servidor (renovou no celular? foi bloqueado?). Roda ao abrir.</summary>
        public static async Task<bool> Atualizar()
        {
            var r = await Servidor.Chamar("GET", "/licenca?codigo=" + Codigo);
            if (r.Status == 200 && r.Str("status") == "ativa")
            {
                var ch = r.Str("chave");
                long.TryParse(r.Str("ate"), out var ate);
                if (Conferir(ch) > 0 && (ch != Chave)) { Guardar(ch, ate); return true; }
            }
            else if (r.Status == 410 && r.Str("status") == "revogada" && Chave.Length > 0) { Apagar(); return true; }
            return false;
        }

        // ---------- limite do Modo Jogo gratis (2 por dia) ----------
        public static int UsosHoje(string chave)
        {
            var e = LerEstado();
            return e.TryGetValue("dia_" + chave, out var d) && Convert.ToString(d) == Hoje.ToString() && e.TryGetValue("n_" + chave, out var n) ? Convert.ToInt32(n) : 0;
        }
        public static void ContarUso(string chave)
        {
            var e = LerEstado();
            var n = UsosHoje(chave) + 1;
            e["dia_" + chave] = Hoje.ToString(); e["n_" + chave] = n;
            GravarEstado(e);
        }
        public static Dictionary<string, object> LerEstado()
        {
            try { return File.Exists(ArqEstado) ? (Servidor.Ler(File.ReadAllText(ArqEstado)) ?? new Dictionary<string, object>()) : new Dictionary<string, object>(); }
            catch { return new Dictionary<string, object>(); }
        }
        public static void GravarEstado(Dictionary<string, object> e)
        {
            try { Directory.CreateDirectory(Pasta); File.WriteAllText(ArqEstado, Servidor.Serializar(e)); } catch { }
        }
    }
}
