# ======================================================================
# SerpyraToolkit para PC - ponte entre a janela e o PC-Toolkit.
# A janela roda:  powershell -File app.ps1 -Acao <id>
# Cores, perguntas e limpeza de tela viram marcas que a janela entende:
#   ESC[c:Cor] texto ESC[c:]       -> texto colorido
#   ESC[2J                          -> limpar a tela
#   ESC[p] pergunta ESC[/p]         -> a janela mostra a pergunta e o campo de resposta
# Arquivo ASCII de proposito (Windows PowerShell 5.1).
# ======================================================================
param([string]$Acao = '')

$ErrorActionPreference = 'SilentlyContinue'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
try { [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$script:ESC = [char]27
$script:TeclaArq = Join-Path $env:TEMP 'serpyra-pc-tecla.txt'
Remove-Item $script:TeclaArq -Force -ErrorAction SilentlyContinue

# o PC-Toolkit (preparado: sem o menu de texto)
. (Join-Path $PSScriptRoot 'toolkit.ps1')

# ---------- saida para a janela ----------
function Write-Host {
    param(
        [Parameter(Position = 0, ValueFromRemainingArguments = $true)] $Object,
        $ForegroundColor, $BackgroundColor,
        [switch] $NoNewline,
        $Separator = ' '
    )
    $t = ''
    if ($null -ne $Object) { $t = (@($Object) | ForEach-Object { [string]$_ }) -join [string]$Separator }
    if ($null -ne $ForegroundColor -and [string]$ForegroundColor -ne '') {
        $t = $script:ESC + '[c:' + [string]$ForegroundColor + ']' + $t + $script:ESC + '[c:]'
    }
    if ($NoNewline) { [Console]::Out.Write($t) } else { [Console]::Out.Write($t + "`n") }
    [Console]::Out.Flush()
}

function Clear-Host {
    [Console]::Out.Write($script:ESC + '[2J')
    [Console]::Out.Flush()
}

function Read-Host {
    param([Parameter(Position = 0)] $Prompt, [switch] $AsSecureString)
    [Console]::Out.Write($script:ESC + '[p]' + [string]$Prompt + $script:ESC + '[/p]')
    [Console]::Out.Flush()
    $l = [Console]::In.ReadLine()
    if ($null -eq $l) { $l = '' }
    [Console]::Out.Write([string]$l + "`n"); [Console]::Out.Flush()
    if ($AsSecureString) { return (ConvertTo-SecureString $l -AsPlainText -Force) }
    return $l
}

# teclas: a janela grava a letra num arquivo (botoes Parar, Manter, Desfazer)
function Tecla-Disponivel { return (Test-Path $script:TeclaArq) }
function Ler-Tecla {
    $t = ''
    try { $t = [string](Get-Content $script:TeclaArq -Raw -ErrorAction Stop) } catch { }
    Remove-Item $script:TeclaArq -Force -ErrorAction SilentlyContinue
    $t = $t.Trim().ToUpper()
    if ($t.Length -gt 1) { $t = $t.Substring(0, 1) }
    return [pscustomobject]@{ Key = $t; KeyChar = $t }
}

# ---------- o que a janela pode pedir ----------
$acoes = @{
    'raiox'         = { RaioX-Fps }
    'modojogo'      = { Modo-Jogo }
    'bench'         = { Benchmark-Fps }
    'graficos'      = { Otimizar-Graficos }
    'tela'          = { Menu-Tela }
    'historico'     = { Mostrar-Historico }
    'rede'          = { Menu-Rede }
    'conexao'       = { Testar-ConexaoCompleta }
    'dns'           = { Testar-Dns }
    'latencia'      = { Otimizar-Latencia }
    'tweaks'        = { Aplicar-TweaksJogo }
    'ram'           = { Liberar-RAM @() }
    'centralfps'    = { Menu-FPS }
    'limpeza'       = { Menu-Limpeza }
    'limparapida'   = { Limpar-PC }
    'aparencia'     = { Otimizar-Inteligente }
    'temperatura'   = { Menu-Temperatura }
    'monitor'       = { Monitor-Temperatura }
    'inicializacao' = { Gerenciar-Inicializacao }
    'manutencao'    = { Menu-Manutencao }
    'megazord'      = { Menu-Megazord }
    'desfazer'      = { Desfazer }
    'assistente'    = { Assistente-Completo }
    'scan'          = { Cacar-Ameacas -Modo 'Rapida' }
    'scanfull'      = { Cacar-Ameacas -Modo 'Completa' }
    'blindagem'     = { Menu-Blindagem }
    'seguranca'     = { Menu-Seguranca }
    'quarentena'    = { Menu-Quarentena }
    'recuperar'     = { }
}

if (-not (Test-Admin)) {
    Write-Host 'O SerpyraToolkit precisa ser aberto como Administrador para mexer no Windows.' -ForegroundColor Red
    exit 3
}

# se o PC desligou no meio de um Modo Jogo, desfaz o que ficou pendente
Recuperar-Sessao

if (-not $acoes.ContainsKey($Acao)) {
    Write-Host ('Acao desconhecida: ' + $Acao) -ForegroundColor Red
    exit 2
}

try {
    & $acoes[$Acao]
} catch {
    Write-Host ''
    Write-Host ('Algo deu errado: ' + $_.Exception.Message) -ForegroundColor Red
    [Console]::Error.WriteLine('ERRO|' + $Acao + '|' + $_.Exception.GetType().Name + '|' + $_.Exception.Message + '|' + $_.InvocationInfo.ScriptLineNumber)
    exit 1
}
exit 0
