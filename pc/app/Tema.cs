using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;

namespace SerpyraPC
{
    /// <summary>Cores, fontes e pecas visuais (o mesmo visual do app de Android).</summary>
    public static class T
    {
        public static readonly Color Bg = Cor("#07081A"), Surface = Cor("#0F1036"), Surface2 = Cor("#12113A"), Outline = Cor("#3A3480"),
            Barra = Cor("#1C1A52"), Texto = Cor("#E9ECFF"), Texto2 = Cor("#B8BEF0"), Texto3 = Cor("#8B90C2"), Verde = Cor("#C8FF6B"),
            Azul = Cor("#8C7BFF"), Lilas = Cor("#A99CFF"), Amarelo = Cor("#FFD166"), Vermelho = Cor("#FF7A86");

        public static Color Cor(string h) => (Color)ColorConverter.ConvertFromString(h);
        public static SolidColorBrush B(Color c) { var b = new SolidColorBrush(c); b.Freeze(); return b; }
        public static SolidColorBrush B(string h) => B(Cor(h));

        static readonly Uri Base = new Uri("pack://application:,,,/");
        public static readonly FontFamily Display = new FontFamily(Base, "./fontes/#Unbounded, Segoe UI Black");
        public static readonly FontFamily Pixel = new FontFamily(Base, "./fontes/#VT323, Consolas");
        public static readonly FontFamily Normal = new FontFamily(Base, "./fontes/#Space Grotesk, Segoe UI");
        public static readonly FontFamily Mono = new FontFamily("Cascadia Mono, Consolas");

        /// <summary>Cromo: degrade claro-escuro-claro nas letras grandes.</summary>
        public static Brush Cromo()
        {
            var g = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0, 1) };
            g.GradientStops.Add(new GradientStop(Cor("#FFFFFF"), 0));
            g.GradientStops.Add(new GradientStop(Cor("#C9D4FF"), 0.38));
            g.GradientStops.Add(new GradientStop(Cor("#6E7BB8"), 0.52));
            g.GradientStops.Add(new GradientStop(Cor("#E9EEFF"), 0.70));
            g.GradientStops.Add(new GradientStop(Cor("#9AA8E0"), 1));
            g.Freeze(); return g;
        }

        /// <summary>Fundo escuro com a grade roxa.</summary>
        public static Brush Grade()
        {
            var linhas = new GeometryGroup();
            linhas.Children.Add(new LineGeometry(new Point(0, 0), new Point(32, 0)));
            linhas.Children.Add(new LineGeometry(new Point(0, 0), new Point(0, 32)));
            var d = new DrawingGroup();
            d.Children.Add(new GeometryDrawing(B(Bg), null, new RectangleGeometry(new Rect(0, 0, 32, 32))));
            d.Children.Add(new GeometryDrawing(null, new Pen(B(Color.FromArgb(0x24, 0x8C, 0x7B, 0xFF)), 1), linhas));
            var b = new DrawingBrush(d) { TileMode = TileMode.Tile, Viewport = new Rect(0, 0, 32, 32), ViewportUnits = BrushMappingMode.Absolute, Stretch = Stretch.None };
            b.Freeze(); return b;
        }

        public static TextBlock Txt(string t, double tam, Color cor, FontWeight? peso = null, FontFamily f = null)
            => new TextBlock { Text = t, FontSize = tam, Foreground = B(cor), FontWeight = peso ?? FontWeights.Normal, FontFamily = f ?? Normal, TextWrapping = TextWrapping.Wrap };

        public static TextBlock TituloCromo(string t, double tam)
            => new TextBlock { Text = t.ToUpperInvariant(), FontSize = tam, FontFamily = Display, Foreground = Cromo(), TextWrapping = TextWrapping.Wrap };

        public static TextBlock Secao(string t)
            => new TextBlock { Text = "✦ " + t.ToUpperInvariant(), FontSize = 20, FontFamily = Pixel, Foreground = B(Lilas), Margin = new Thickness(0, 22, 0, 8) };

        /// <summary>Janelinha .exe: barra de titulo com o nome e o x, e o conteudo embaixo.</summary>
        public static Border Janela(string nomeExe, UIElement conteudo, Thickness? margem = null)
        {
            var col = new DockPanel();
            var barra = new Border { Background = B(Barra), BorderBrush = B(Outline), BorderThickness = new Thickness(0, 0, 0, 1.5), Padding = new Thickness(12, 3, 12, 3), CornerRadius = new CornerRadius(14, 14, 0, 0) };
            var bp = new DockPanel();
            var x = new TextBlock { Text = "_ □ ×", FontFamily = Pixel, FontSize = 18, Foreground = B(Texto3) };
            DockPanel.SetDock(x, Dock.Right); bp.Children.Add(x);
            bp.Children.Add(new TextBlock { Text = nomeExe, FontFamily = Pixel, FontSize = 18, Foreground = B(Lilas) });
            barra.Child = bp;
            DockPanel.SetDock(barra, Dock.Top);
            col.Children.Add(barra);
            var corpo = new Border { Padding = new Thickness(16), Child = conteudo };
            col.Children.Add(corpo);
            return new Border { Background = B(Surface), BorderBrush = B(Outline), BorderThickness = new Thickness(1.5), CornerRadius = new CornerRadius(16), Child = col, Margin = margem ?? new Thickness(0, 0, 0, 12), SnapsToDevicePixels = true };
        }

        public static Border Cartao(UIElement conteudo, Color? borda = null, Color? fundo = null)
            => new Border { Background = B(fundo ?? Surface), BorderBrush = B(borda ?? Outline), BorderThickness = new Thickness(1.5), CornerRadius = new CornerRadius(18), Padding = new Thickness(18), Child = conteudo, Margin = new Thickness(0, 0, 0, 12) };

        // ---------- botoes (estilos em XAML, lidos uma vez) ----------
        const string Estilos = @"
<ResourceDictionary xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml'>
  <Style x:Key='Base' TargetType='Button'>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Padding' Value='18,11'/>
    <Setter Property='FontSize' Value='15'/>
    <Setter Property='FontWeight' Value='Bold'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Border x:Name='b' Background='{TemplateBinding Background}' BorderBrush='{TemplateBinding BorderBrush}' BorderThickness='{TemplateBinding BorderThickness}' CornerRadius='14' Padding='{TemplateBinding Padding}' SnapsToDevicePixels='True'>
            <ContentPresenter HorizontalAlignment='{TemplateBinding HorizontalContentAlignment}' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Opacity' Value='0.88'/></Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='#C8FF6B'/></Trigger>
            <Trigger Property='IsPressed' Value='True'><Setter TargetName='b' Property='Opacity' Value='0.7'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter TargetName='b' Property='Opacity' Value='0.45'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style x:Key='Lima' TargetType='Button' BasedOn='{StaticResource Base}'>
    <Setter Property='Background' Value='#C8FF6B'/><Setter Property='Foreground' Value='#07081A'/><Setter Property='BorderBrush' Value='#C8FF6B'/><Setter Property='BorderThickness' Value='2'/><Setter Property='HorizontalContentAlignment' Value='Center'/>
  </Style>
  <Style x:Key='Roxo' TargetType='Button' BasedOn='{StaticResource Base}'>
    <Setter Property='Background' Value='#8C7BFF'/><Setter Property='Foreground' Value='#07081A'/><Setter Property='BorderBrush' Value='#8C7BFF'/><Setter Property='BorderThickness' Value='2'/><Setter Property='HorizontalContentAlignment' Value='Center'/>
  </Style>
  <Style x:Key='Linha' TargetType='Button' BasedOn='{StaticResource Base}'>
    <Setter Property='Background' Value='#0F1036'/><Setter Property='Foreground' Value='#E9ECFF'/><Setter Property='BorderBrush' Value='#3A3480'/><Setter Property='BorderThickness' Value='1.5'/><Setter Property='HorizontalContentAlignment' Value='Center'/>
  </Style>
  <Style x:Key='Item' TargetType='Button' BasedOn='{StaticResource Base}'>
    <Setter Property='Background' Value='#0F1036'/><Setter Property='Foreground' Value='#E9ECFF'/><Setter Property='BorderBrush' Value='#3A3480'/><Setter Property='BorderThickness' Value='1.5'/><Setter Property='Padding' Value='16,14'/><Setter Property='HorizontalContentAlignment' Value='Stretch'/><Setter Property='FontWeight' Value='Normal'/>
  </Style>
  <Style x:Key='Aba' TargetType='Button' BasedOn='{StaticResource Base}'>
    <Setter Property='Background' Value='Transparent'/><Setter Property='Foreground' Value='#B8BEF0'/><Setter Property='BorderBrush' Value='Transparent'/><Setter Property='BorderThickness' Value='1.5'/><Setter Property='Padding' Value='14,11'/><Setter Property='HorizontalContentAlignment' Value='Left'/>
  </Style>
  <Style TargetType='TextBox'>
    <Setter Property='Background' Value='#07081A'/><Setter Property='Foreground' Value='#E9ECFF'/><Setter Property='CaretBrush' Value='#C8FF6B'/><Setter Property='BorderBrush' Value='#3A3480'/><Setter Property='BorderThickness' Value='1.5'/><Setter Property='Padding' Value='10,9'/><Setter Property='FontSize' Value='15'/>
  </Style>
  <Style TargetType='ScrollBar'><Setter Property='Opacity' Value='0.5'/></Style>
</ResourceDictionary>";

        public static void CarregarEstilos(Application app)
        {
            var d = (ResourceDictionary)XamlReader.Parse(Estilos);
            app.Resources.MergedDictionaries.Add(d);
        }

        public static Button Botao(string texto, string estilo, Action acao, double? largura = null)
        {
            var b = new Button { Content = texto, Style = (Style)Application.Current.Resources[estilo], FontFamily = Normal };
            if (largura.HasValue) b.Width = largura.Value;
            b.Click += (s, e) => acao?.Invoke();
            return b;
        }

        /// <summary>Item de lista: titulo, descricao e selo Pro.</summary>
        public static Button Item(string titulo, string sub, bool pro, bool temPro, Action acao, string icone = null, Color? corIcone = null)
        {
            var g = new DockPanel();
            if (icone != null) { var q = IconeQuadrado(icone, corIcone ?? Lilas, 42); q.Margin = new Thickness(0, 0, 14, 0); DockPanel.SetDock(q, Dock.Left); g.Children.Add(q); }
            if (pro && !temPro)
            {
                var selo = new Border { Background = B(Verde), CornerRadius = new CornerRadius(99), Padding = new Thickness(8, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center, Child = new TextBlock { Text = "PRO", FontFamily = Pixel, FontSize = 16, Foreground = B(Bg) } };
                DockPanel.SetDock(selo, Dock.Right); g.Children.Add(selo);
            }
            var seta = new TextBlock { Text = "›", FontSize = 22, Foreground = B(Texto3), Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            DockPanel.SetDock(seta, Dock.Right); g.Children.Add(seta);
            var col = new StackPanel();
            col.Children.Add(Txt(titulo, 16, Texto, FontWeights.Bold));
            if (!string.IsNullOrEmpty(sub)) { var s = Txt(sub, 13, Texto2); s.Margin = new Thickness(0, 2, 0, 0); col.Children.Add(s); }
            g.Children.Add(col);
            var b = new Button { Content = g, Style = (Style)Application.Current.Resources["Item"], Margin = new Thickness(0, 0, 0, 8), HorizontalContentAlignment = HorizontalAlignment.Stretch };
            AutomationProperties(b, titulo);
            b.Click += (s, e) => acao?.Invoke();
            return b;
        }

        public static void AutomationProperties(FrameworkElement e, string nome) => System.Windows.Automation.AutomationProperties.SetName(e, nome);

        public static Border Chip(string t, Color fundo, Color texto)
            => new Border { Background = B(fundo), CornerRadius = new CornerRadius(99), Padding = new Thickness(12, 1, 12, 1), VerticalAlignment = VerticalAlignment.Center, Child = new TextBlock { Text = t, FontFamily = Pixel, FontSize = 19, Foreground = B(texto) } };

        public static TextBox Campo(string dica, bool senha = false)
        {
            var tb = new TextBox { MinWidth = 200 };
            System.Windows.Automation.AutomationProperties.SetName(tb, dica);
            tb.Tag = dica;
            return tb;
        }

        // ---------- icones de linha (24x24), no mesmo estilo do app ----------
        public static readonly System.Collections.Generic.Dictionary<string, string> Icones = new System.Collections.Generic.Dictionary<string, string>
        {
            ["inicio"] = "M3,11 L12,4 L21,11 L21,20 L3,20 Z",
            ["jogo"] = "M7,7 L17,7 A4.5,4.5 0 0 1 21.4,12.4 L20.8,15.4 A2.6,2.6 0 0 1 16.3,16.6 L14.5,15 L9.5,15 L7.7,16.6 A2.6,2.6 0 0 1 3.2,15.4 L2.6,12.4 A4.5,4.5 0 0 1 7,7 Z M8,10 L8,13 M6.5,11.5 L9.5,11.5",
            ["ferramenta"] = "M14.7,6.3 A4,4 0 0 0 9.3,11.7 L3.5,17.5 L6.5,20.5 L12.3,14.7 A4,4 0 0 0 17.7,9.3 L15.2,11.8 L12.2,8.8 Z",
            ["escudo"] = "M12,3 L20,6 L20,11 C20,16 16.5,19.5 12,21 C7.5,19.5 4,16 4,11 L4,6 Z M9,12 L11,14 L15,10",
            ["estrela"] = "M12,2 L14.5,9.5 L22,12 L14.5,14.5 L12,22 L9.5,14.5 L2,12 L9.5,9.5 Z",
            ["raio"] = "M13,2 L4,14 L11,14 L10,22 L20,9 L13,9 Z",
            ["vassoura"] = "M14,3 L21,10 M17.5,6.5 L9,15 M9,15 C6,15 4,17 3,21 C7,20 9,18 9,15 Z",
            ["termometro"] = "M10,14 L10,4 A2,2 0 0 1 14,4 L14,14 A4,4 0 1 1 10,14 Z M12,9 L12,17",
            ["wifi"] = "M2,9 A15,15 0 0 1 22,9 M5,12.5 A10,10 0 0 1 19,12.5 M8.5,16 A5,5 0 0 1 15.5,16 M12,19.5 L12,19.6",
            ["fps"] = "M3.5,17 A8.5,8.5 0 1 1 20.5,17 M12,17 L16.5,10 M12,15.2 A1.8,1.8 0 1 0 12,18.8 A1.8,1.8 0 1 0 12,15.2",
            ["sino"] = "M6,16 L6,11 A6,6 0 0 1 18,11 L18,16 L20,18 L4,18 Z M10,21 L14,21",
            ["chat"] = "M21,15 A2,2 0 0 1 19,17 L7,17 L3,21 L3,5 A2,2 0 0 1 5,3 L19,3 A2,2 0 0 1 21,5 Z",
            ["atualizar"] = "M20,11 A8,8 0 1 0 17.7,16.7 M20,4 L20,11 L13,11",
            ["memoria"] = "M5,7 L19,7 L19,17 L5,17 Z M8,7 L8,4 M12,7 L12,4 M16,7 L16,4 M8,17 L8,20 M12,17 L12,20 M16,17 L16,20",
            ["tela"] = "M3,5 L21,5 L21,16 L3,16 Z M9,20 L15,20 M12,16 L12,20",
            ["voltar"] = "M15,5 L8,12 L15,19",
            ["presente"] = "M20,12 L20,21 L4,21 L4,12 M2,7 L22,7 L22,12 L2,12 Z M12,21 L12,7",
            ["olho"] = "M2,12 C5,6 19,6 22,12 C19,18 5,18 2,12 Z M12,9.5 A2.5,2.5 0 1 0 12,14.5 A2.5,2.5 0 1 0 12,9.5"
        };

        public static System.Windows.Shapes.Path Icone(string nome, Color cor, double tam = 22)
        {
            return new System.Windows.Shapes.Path { Data = Geometry.Parse(Icones.ContainsKey(nome) ? Icones[nome] : Icones["estrela"]), Stroke = B(cor), StrokeThickness = 1.9, StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Width = tam, Height = tam, Stretch = Stretch.Uniform, VerticalAlignment = VerticalAlignment.Center };
        }

        /// <summary>Icone dentro de um quadrado arredondado (como no app).</summary>
        public static Border IconeQuadrado(string nome, Color cor, double tam = 44)
            => new Border { Width = tam, Height = tam, CornerRadius = new CornerRadius(tam * 0.3), Background = B(Color.FromArgb(0x26, cor.R, cor.G, cor.B)), BorderBrush = B(Color.FromArgb(0x55, cor.R, cor.G, cor.B)), BorderThickness = new Thickness(1), Child = Icone(nome, cor, tam * 0.5), VerticalAlignment = VerticalAlignment.Top };

        public static void AoEnter(TextBox tb, Action acao) => tb.KeyDown += (s, e) => { if (e.Key == Key.Enter) { acao(); e.Handled = true; } };
    }
}
