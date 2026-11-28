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
# argumento extra (nome do jogo, itens a corrigir...) vem por variavel de ambiente para nao ter problema com aspas
$script:Arg = [string]$env:SERPYRA_ARG
$script:Arg2 = [string]$env:SERPYRA_ARG2

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

# dados para as telas da janela (ESC[json] ... ESC[/json])
function Saida-Json($o) {
    $j = ConvertTo-Json -InputObject $o -Depth 6 -Compress
    [Console]::Out.Write($script:ESC + '[json]' + $j + $script:ESC + '[/json]' + "`n")
    [Console]::Out.Flush()
}

function RaioX-Json {
    $lista = @(Get-ChecagensFps)
    $total = 0; $ok = 0
    foreach ($c in $lista) { if ($c.Estado -ne 'INFO') { $total += $c.Peso; if ($c.Estado -eq 'OK') { $ok += $c.Peso } } }
    $nota = 100
    if ($total -gt 0) { $nota = [int][math]::Round(($ok / $total) * 100, 0) }
    $cfg = Load-Config
    $antes = 0; if ($cfg.UltimaNotaFps) { $antes = [int]$cfg.UltimaNotaFps }
    $cfg | Add-Member -NotePropertyName UltimaNotaFps -NotePropertyValue $nota -Force
    Save-Config $cfg
    Write-Log ("Raio-X FPS (janela): nota {0}" -f $nota)
    $itens = @($lista | ForEach-Object { [pscustomobject]@{ Grupo = [string]$_.Grupo; Nome = [string]$_.Nome; Estado = [string]$_.Estado; Detalhe = [string]$_.Detalhe; Corrige = [bool]$_.Fix; Auto = [bool]$_.Auto; Peso = [int]$_.Peso } })
    Saida-Json ([pscustomobject]@{ nota = $nota; antes = $antes; itens = $itens })
}

function RaioX-Corrigir {
    $nomes = @($script:Arg -split '\|' | Where-Object { $_ })
    $lista = @(Get-ChecagensFps)
    $alvos = @($lista | Where-Object { $_.Fix -and ($nomes -contains [string]$_.Nome) })
    if ($alvos.Count -eq 0) { Show-Aviso 'Nada para corrigir.'; return }
    if ($script:Arg2 -eq 'ponto') { Criar-PontoRestauracao 'SerpyraToolkit - antes do Raio-X de FPS' }
    foreach ($c in $alvos) {
        Write-Host ''
        Write-Host ('--> ' + $c.Nome) -ForegroundColor Cyan
        try { & $c.Fix $c.Arg } catch { Show-Aviso ('Falhou: ' + $_.Exception.Message) }
    }
    Write-Host ''
    Show-Ok 'Correcoes aplicadas. Reinicie o PC e rode o Raio-X de novo para ver a nota nova.'
}

function Jogos-Json {
    $l = @(Get-JogosInstalados | ForEach-Object { [pscustomobject]@{ Nome = [string]$_.Nome; Origem = [string]$_.Origem; Pasta = [string]$_.Pasta } })
    Saida-Json ([pscustomobject]@{ jogos = $l })
}

function Temp-Fluxo {
    while ($true) {
        $t = Get-Termica
        $g = Get-GpuNvidia
        $top = @(Get-TopCpu 4)
        $mem = $null
        try { $os = Get-CimInstance Win32_OperatingSystem; $mem = [pscustomobject]@{ Livre = [math]::Round($os.FreePhysicalMemory / 1MB, 1); Total = [math]::Round($os.TotalVisibleMemorySize / 1MB, 1) } } catch { }
        Saida-Json ([pscustomobject]@{ cpu = $t; gpu = $g; top = $top; mem = $mem; em = (Get-Date).ToString('HH:mm:ss') })
        Start-Sleep -Seconds 2
    }
}

function Rede-Json {
    $alvos = @(@{ n = 'Cloudflare'; h = '1.1.1.1' }, @{ n = 'Google'; h = '8.8.8.8' }, @{ n = 'Quad9'; h = '9.9.9.9' })
    $out = @()
    foreach ($a in $alvos) {
        $r = Medir-Ping $a.h 10
        $out += [pscustomobject]@{ Nome = $a.n; Host = $a.h; Media = $(if ($r) { $r.Media } else { -1 }); Jitter = $(if ($r) { $r.Jitter } else { -1 }); Perda = $(if ($r) { $r.Perda } else { 100 }) }
    }
    $dns = @()
    try { $dns = @(Get-DnsClientServerAddress -AddressFamily IPv4 -ErrorAction Stop | Where-Object { $_.ServerAddresses } | Select-Object -First 1 -ExpandProperty ServerAddresses) } catch { }
    Saida-Json ([pscustomobject]@{ alvos = $out; dns = $dns })
}

function Resumo-Json {
    $d = Get-PSDrive C
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $gpu = @(Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name) -join ' + '
    $cfg = Load-Config
    Saida-Json ([pscustomobject]@{ discoLivre = [math]::Round($d.Free / 1GB, 1); discoTotal = [math]::Round(($d.Used + $d.Free) / 1GB, 0); memLivre = [math]::Round($os.FreePhysicalMemory / 1MB, 1); memTotal = [math]::Round($os.TotalVisibleMemorySize / 1MB, 0); cpu = [string]$cpu.Name; gpu = $gpu; windows = [string]$os.Caption; nota = [int]$cfg.UltimaNotaFps })
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
    'raiox-json'    = { RaioX-Json }
    'raiox-corrigir'= { RaioX-Corrigir }
    'jogos-json'    = { Jogos-Json }
    'modojogo-jogo' = { Modo-Jogo -Preselecionado $script:Arg }
    'temp-fluxo'    = { Temp-Fluxo }
    'rede-json'     = { Rede-Json }
    'resumo-json'   = { Resumo-Json }
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
