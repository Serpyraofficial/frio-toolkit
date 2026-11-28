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
        public static Button Item(string titulo, string sub, bool pro, bool temPro, Action acao)
        {
            var g = new DockPanel();
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

        public static void AoEnter(TextBox tb, Action acao) => tb.KeyDown += (s, e) => { if (e.Key == Key.Enter) { acao(); e.Handled = true; } };
    }
}
