using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace SerpyraPC
{
    public partial class Principal
    {
        void TelaPro()
        {
            var col = Pagina("SerpyraToolkit Pro", "O mesmo Pro vale no celular e no PC.");
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
                    if (r.Ok && Licenca.Conferir(r.Str("chave")) > 0) { long.TryParse(r.Str("ate"), out var ate); Licenca.Guardar(r.Str("chave"), ate); Cabecalho(); IrAba("pro"); }
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
            if (Licenca.Ativo) col.Children.Add(Indique());

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
                    if (r.Ok && r.Str("chave").Length > 0 && Licenca.Conferir(r.Str("chave")) > 0) { Licenca.Guardar(r.Str("chave")); Cabecalho(); IrAba("pro"); return; }
                    if (r.Ok) { fase2 = true; otp.Visibility = Visibility.Visible; otp.Focus(); b.Content = "Confirmar"; m.Text = "Pronto: veja o código no seu e-mail (olhe o spam também)."; m.Foreground = T.B(T.Verde); }
                    else { m.Text = r.Erro; m.Foreground = T.B(T.Vermelho); }
                }
                else
                {
                    m.Text = "Conferindo…"; m.Foreground = T.B(T.Texto2);
                    var r = await Servidor.Chamar("POST", "/pc/confirmar", new Dictionary<string, object> { ["email"] = email.Text.Trim(), ["codigo"] = Licenca.Codigo, ["otp"] = otp.Text.Trim() });
                    if (r.Ok && Licenca.Conferir(r.Str("chave")) > 0) { Licenca.Guardar(r.Str("chave")); Cabecalho(); IrAba("pro"); return; }
                    m.Text = r.Erro; m.Foreground = T.B(T.Vermelho);
                }
                b.IsEnabled = true;
            };
            sp.Children.Add(Rotulo("E-mail da compra")); sp.Children.Add(email); sp.Children.Add(otp); sp.Children.Add(b); sp.Children.Add(m);
            return T.Janela("celular.exe", sp);
        }


        Border TemCodigo()
        {
            var sp = new StackPanel();
            sp.Children.Add(T.Txt("Tem um código?", 18, T.Texto, FontWeights.Bold));
            sp.Children.Add(T.Txt("Chave do Pro (PCT-…), mesmo a do celular, ou código de presente e cortesia (PRE…).", 14, T.Texto2));
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
                    if (Licenca.Conferir(c) > 0) { Licenca.Guardar(c); Cabecalho(); IrAba("pro"); return; }
                    // chave de outro aparelho (celular ou outro PC): vale aqui com a mesma validade
                    var r = await Servidor.Chamar("POST", "/chave/usar", new Dictionary<string, object> { ["codigo"] = Licenca.Codigo, ["chave"] = c });
                    if (r.Ok && Licenca.Conferir(r.Str("chave")) > 0) { long.TryParse(r.Str("ate"), out var ate2); Licenca.Guardar(r.Str("chave"), ate2); Cabecalho(); IrAba("pro"); return; }
                    m.Text = r.Erro; m.Foreground = T.B(T.Vermelho);
                }
                else
                {
                    var r = await Servidor.Chamar("POST", "/presente", new Dictionary<string, object> { ["codigo"] = Licenca.Codigo, ["voucher"] = c });
                    if (r.Ok && Licenca.Conferir(r.Str("chave")) > 0) { long.TryParse(r.Str("ate"), out var ate); Licenca.Guardar(r.Str("chave"), ate); Cabecalho(); IrAba("pro"); return; }
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
            sp.Children.Add(T.Txt("Sem cobrança automática: cada Pix libera os dias do plano. A chave chega no seu e-mail e vale também no celular: cole em \"Tem um código?\" no app.", 14, T.Texto2));
            var planos = new StackPanel { Margin = new Thickness(0, 12, 0, 0) };
            var carregando = T.Txt("Carregando os planos…", 14, T.Texto3); planos.Children.Add(carregando);
            var email = T.Campo("Seu e-mail");
            var cupom = T.Campo("Cupom (opcional)"); if (cupomPro.Length > 0) { cupom.Text = cupomPro; cupomPro = ""; }
            var ehPresente = new CheckBox { Margin = new Thickness(0, 12, 0, 0), Content = new TextBlock { Text = "🎁 É presente? O código vai para o e-mail de outra pessoa", Foreground = T.B(T.Texto2) } };
            var emailPres = T.Campo("E-mail de quem vai ganhar"); emailPres.Visibility = Visibility.Collapsed; emailPres.Margin = new Thickness(0, 8, 0, 0);
            ehPresente.Checked += (o, e) => emailPres.Visibility = Visibility.Visible; ehPresente.Unchecked += (o, e) => emailPres.Visibility = Visibility.Collapsed;
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
                if (ehPresente.IsChecked == true && !System.Text.RegularExpressions.Regex.IsMatch(emailPres.Text.Trim(), @"^[^\s@]+@[^\s@]+\.[^\s@]{2,}$")) { m.Text = "Digite o e-mail de quem vai ganhar o presente."; m.Foreground = T.B(T.Vermelho); return; }
                b.IsEnabled = false; m.Text = "Gerando o Pix…"; m.Foreground = T.B(T.Texto2);
                var r = await Servidor.Chamar("POST", "/pedido", new Dictionary<string, object> { ["codigo"] = Licenca.Codigo, ["email"] = em, ["plano"] = escolhido, ["cupom"] = cupom.Text.Trim(), ["presente"] = ehPresente.IsChecked == true ? emailPres.Text.Trim() : "" });
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
                        MessageBox.Show(this, (ehPresente.IsChecked == true ? "Pagamento confirmado! O código do presente foi para " + emailPres.Text.Trim() + "." : "Pagamento confirmado! O Pro está ativo neste PC até " + Licenca.ValidadeTxt + ". A chave também foi para o seu e-mail."), "SerpyraToolkit Pro");
                        IrAba("pro");
                    }
                    else if (st.Str("status").Length > 0 && st.Str("status") != "pendente" && st.Str("status") != "pago") { timer.Stop(); esp.Text = "O Pix expirou ou foi cancelado. Gere outro."; }
                };
                timer.Start();
            };
            sp.Children.Add(planos);
            sp.Children.Add(Rotulo("Seu e-mail (a chave vai para ele)")); sp.Children.Add(email);
            sp.Children.Add(Rotulo("Cupom (opcional)")); sp.Children.Add(cupom); sp.Children.Add(ehPresente); sp.Children.Add(emailPres);
            sp.Children.Add(b); sp.Children.Add(m); sp.Children.Add(area);
            return T.Janela("pro.exe", sp);
        }

        Border Indique()
        {
            var sp = new StackPanel();
            sp.Children.Add(T.Txt("Indique e ganhe", 18, T.Texto, FontWeights.Bold));
            sp.Children.Add(T.Txt("Seu amigo ganha 10% de desconto com o seu código e você ganha dias de Pro a cada compra.", 14, T.Texto2));
            var cod = "IND" + Licenca.Codigo;
            var dp = new DockPanel { Margin = new Thickness(0, 12, 0, 0) };
            var cp = T.Botao("Copiar", "Linha", () => { try { Clipboard.SetText(cod); } catch { } }); cp.Padding = new Thickness(14, 7, 14, 7); DockPanel.SetDock(cp, Dock.Right); dp.Children.Add(cp);
            dp.Children.Add(new TextBlock { Text = cod, FontFamily = T.Pixel, FontSize = 28, Foreground = T.B(T.Verde), VerticalAlignment = VerticalAlignment.Center });
            sp.Children.Add(dp);
            var j = T.Janela("indique.exe", sp);
            if (!modoFotos)
            {
                j.Visibility = Visibility.Collapsed;
                Dispatcher.InvokeAsync(async () =>
                {
                    var r = await Servidor.Chamar("GET", "/cupom?c=" + cod + "&plano=m1");
                    if (r.Ok) j.Visibility = Visibility.Visible;
                });
            }
            return j;
        }

    }
}
