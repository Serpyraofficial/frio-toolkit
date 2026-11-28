# ======================================================================
# PC-Toolkit  -  versao 4.0
#
# NOVO NA 4.0: CENTRAL DE FPS
#  + RAIO-X DE FPS (nota do PC + correcao com 1 clique)
#  + medidor de FPS real (FPS medio, 1% low, engasgos) e benchmark antes x depois
#  + analise de gargalo CPU x placa de video, monitor no Hz maximo
#  + Modo Jogo com pacote TURBO (timer 0.5 ms, memoria standby, nucleos P/X3D)
#  + rede (jitter/perda, DNS mais rapido, placa de rede), latencia e input
#  + mais jogos nos graficos: Minecraft Bedrock, Fortnite/Valorant, CS2, Apex, LoL, Roblox
#
# Limpeza profunda + FPS + Temperatura/Refrigeracao + Modo Jogo
# (QUALQUER jogo: Steam, Epic, GOG, Ubisoft, EA, Rockstar, Xbox/Game Pass,
#  Minecraft, FiveM, pastas suas ou jogo ja aberto) + Reset de fabrica
#  + CACADOR DE AMEACAS (malware, mineradores, invasores)
#  + APARENCIA E DESEMPENHO INTELIGENTE do Windows.
#
# Uso (pelo PC-Toolkit.bat, que ja contem este script):
#   PC-Toolkit.bat                           -> menu principal
#   PC-Toolkit.bat jogar                     -> direto para o Modo Jogo
#   PC-Toolkit.bat -ModoJogo -Jogo "GTA V"   -> abre direto o jogo cujo nome bate
#   PC-Toolkit.bat monitor                   -> monitor de temperatura ao vivo
#   PC-Toolkit.bat limpar                    -> limpeza padrao e sai
#   PC-Toolkit.bat scan                      -> varredura rapida de ameacas
#   PC-Toolkit.bat scanfull                  -> varredura completa de ameacas
#   PC-Toolkit.bat visual                    -> aparencia/desempenho inteligente
#   PC-Toolkit.bat blindar                   -> blindagem (VirusTotal + reforco Defender/SmartScreen)
#   PC-Toolkit.bat fps                       -> RAIO-X DE FPS
#   PC-Toolkit.bat bench                     -> medir FPS / benchmark
#
# Observacao: este arquivo e ASCII de proposito (sem acentos), para o
# Windows PowerShell 5.1 nao embaralhar os textos.
# ======================================================================
param(
    [switch]$ModoJogo,
    [string]$Jogo,
    [switch]$Monitor,
    [switch]$LimpezaRapida,
    [switch]$Scan,
    [switch]$ScanCompleto,
    [switch]$Aparencia,
    [switch]$Blindagem,
    [switch]$RaioX,
    [switch]$Benchmark
)

$ErrorActionPreference = 'SilentlyContinue'
$script:Versao = '4.0'
try { $Host.UI.RawUI.WindowTitle = ('PC Toolkit ' + $script:Versao) } catch { }

# ----------------------------------------------------------------
# Arquivos de estado (tudo que o toolkit grava fica na sua pasta de usuario)
# ----------------------------------------------------------------
$script:UndoFile       = Join-Path $env:USERPROFILE "FPS-Otimizador-undo.json"
$script:PowerFile      = Join-Path $env:USERPROFILE "FPS-Otimizador-energia.txt"
$script:UltimoJogoFile = Join-Path $env:USERPROFILE "FPS-Otimizador-ultimo-jogo.txt"
$script:EnergiaFile    = Join-Path $env:USERPROFILE "PC-Toolkit-energia.json"
$script:SessaoFile     = Join-Path $env:USERPROFILE "PC-Toolkit-sessao.json"
$script:ArquivosFile   = Join-Path $env:USERPROFILE "PC-Toolkit-arquivos.json"
$script:ConfigFile     = Join-Path $env:USERPROFILE "PC-Toolkit-config.json"
$script:LogDir         = Join-Path $env:USERPROFILE "PC-Toolkit-logs"
$script:FxSettings     = Join-Path $env:APPDATA "CitizenFX\gta5_settings.xml"
$script:RkSettings     = Join-Path ([Environment]::GetFolderPath('MyDocuments')) "Rockstar Games\GTA V\settings.xml"
$script:JogosCache     = $null
$script:Simular        = $false
$script:TotalLimpo     = [int64]0
$script:LocaisLimpos   = 0

# ----------------------------------------------------------------
# Funcoes auxiliares comuns
# ----------------------------------------------------------------
function Test-Admin {
    ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Format-Size($b) {
    $v = [double]$b
    $sinal = ''
    if ($v -lt 0) { $sinal = '-'; $v = -$v }
    if ($v -ge 1GB) { return ("{0}{1:N1} GB" -f $sinal, ($v / 1GB)) }
    elseif ($v -ge 1MB) { return ("{0}{1:N0} MB" -f $sinal, ($v / 1MB)) }
    else { return ("{0}{1:N0} KB" -f $sinal, ($v / 1KB)) }
}

function Ask-YesNo($pergunta) {
    $r = Read-Host ($pergunta + " (S/N)")
    return ($r -match '^[sSyY]')
}

function Pausa { Read-Host "Enter para voltar ao menu" | Out-Null }
function Show-Ok($t)    { Write-Host ("  [ok]  " + $t) -ForegroundColor Green }
function Show-Aviso($t) { Write-Host ("  [!!]  " + $t) -ForegroundColor Yellow }
function Show-Info($t)  { Write-Host ("  " + $t) -ForegroundColor DarkGray }

function Write-Log($msg) {
    try {
        if (-not (Test-Path -LiteralPath $script:LogDir)) { New-Item -ItemType Directory -Path $script:LogDir -Force | Out-Null }
        $arq = Join-Path $script:LogDir ("toolkit-{0}.log" -f (Get-Date -Format 'yyyy-MM-dd'))
        Add-Content -LiteralPath $arq -Value ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg) -Encoding UTF8
    } catch { }
}

function ConvertTo-Indices($sel, $max) {
    if (-not $sel) { return @() }
    return @($sel -split '[,; ]+' | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ } | Where-Object { $_ -ge 1 -and $_ -le $max } | Sort-Object -Unique)
}

function Get-FolderSize($path) {
    $s = (Get-ChildItem -LiteralPath $path -Recurse -Force -File -ErrorAction SilentlyContinue |
          Measure-Object -Property Length -Sum).Sum
    if (-not $s) { return 0 }
    return $s
}

function Get-FolderStats($path) {
    $f = Get-ChildItem -LiteralPath $path -Recurse -Force -File -ErrorAction SilentlyContinue
    $m = $f | Measure-Object -Property Length -Sum
    return [pscustomobject]@{ Arquivos = [int64]$m.Count; Bytes = [int64]$m.Sum }
}

function Get-DiscosFixos {
    return @(Get-CimInstance Win32_LogicalDisk | Where-Object { $_.DriveType -eq 3 })
}

function Get-TipoDisco($letra) {
    $pdisk = Get-Partition -DriveLetter $letra -ErrorAction SilentlyContinue | Get-Disk -ErrorAction SilentlyContinue | Get-PhysicalDisk -ErrorAction SilentlyContinue
    if ($pdisk) { return [string]$pdisk.MediaType }
    return 'desconhecido'
}

# ----------------------------------------------------------------
# Config do usuario (pastas de jogos extras, perfil preferido no Modo Jogo...)
# ----------------------------------------------------------------
function Load-Config {
    $cfg = [pscustomobject]@{ PastasJogos = @(); PerfilJogo = 'Maximo'; LimiteTemp = 92; VtApiKey = ''; PresentMonPath = ''; PresentMonEstilo = ''; UltimaNotaFps = 0; TurboJogo = $true }
    if (Test-Path -LiteralPath $script:ConfigFile) {
        try {
            $j = Get-Content -LiteralPath $script:ConfigFile -Raw | ConvertFrom-Json
            foreach ($pr in $j.PSObject.Properties) { $cfg | Add-Member -NotePropertyName $pr.Name -NotePropertyValue $pr.Value -Force }
            $cfg.PastasJogos = @($cfg.PastasJogos | Where-Object { $_ })
            if (-not $cfg.LimiteTemp) { $cfg.LimiteTemp = 92 }
            if (-not $cfg.PerfilJogo) { $cfg.PerfilJogo = 'Maximo' }
        } catch { }
    }
    return $cfg
}
function Save-Config($cfg) {
    try { ConvertTo-Json -InputObject $cfg -Depth 4 | Set-Content -LiteralPath $script:ConfigFile -Encoding UTF8 } catch { }
}

# ----------------------------------------------------------------
# Tipos nativos (liberar RAM e teste de estresse da CPU)
# ----------------------------------------------------------------
function Initialize-Tipos {
    if ('PCToolkit.Nativo' -as [type]) { return $true }
    $codigo = @'
using System;
using System.Runtime.InteropServices;
using System.Threading;
namespace PCToolkit {
    public static class Nativo {
        [DllImport("psapi.dll")]
        public static extern bool EmptyWorkingSet(IntPtr hProcess);
        public static double Sink;
        private static volatile bool rodando;
        private static Thread[] fios;
        public static void IniciarCarga(int n) {
            rodando = true;
            fios = new Thread[n];
            for (int i = 0; i < n; i++) {
                fios[i] = new Thread(Carga);
                fios[i].IsBackground = true;
                fios[i].Start();
            }
        }
        private static void Carga() {
            double x = 1.0;
            while (rodando) {
                for (int k = 0; k < 200000; k++) { x = Math.Sqrt(x + k) * 1.0000001; }
                Sink = x;
            }
        }
        public static void PararCarga() { rodando = false; }
    }
}
'@
    try { Add-Type -TypeDefinition $codigo -Language CSharp -ErrorAction Stop; return $true } catch { return $false }
}

# ======================================================================
# SECAO 1: LIMPEZA DO PC
# ======================================================================
function Show-TopFolders($root, $count) {
    Write-Host ""
    Write-Host "=== Maiores pastas em $root ===" -ForegroundColor Cyan
    $lista = Get-ChildItem -LiteralPath $root -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host ("  medindo " + $_.FullName + " ...") -ForegroundColor DarkGray
        [pscustomobject]@{ Pasta = $_.FullName; Tamanho = (Get-FolderSize $_.FullName) }
    } | Sort-Object Tamanho -Descending | Select-Object -First $count
    Write-Host ""
    foreach ($i in $lista) { Write-Host ("{0,10}   {1}" -f (Format-Size $i.Tamanho), $i.Pasta) }
}

function Analisar-Disco {
    $desktop = [Environment]::GetFolderPath('Desktop')
    $relatorio = Join-Path $desktop "Relatorio-Disco.txt"
    Start-Transcript -Path $relatorio -Force | Out-Null

    Write-Host "=== Discos ===" -ForegroundColor Cyan
    foreach ($dk in (Get-DiscosFixos)) {
        Write-Host ("  {0}  {1,-16} livre {2} de {3}" -f $dk.DeviceID, $dk.VolumeName, (Format-Size $dk.FreeSpace), (Format-Size $dk.Size))
    }
    $d = Get-PSDrive C
    Write-Host ("Disco C: usado {0} | livre {1}" -f (Format-Size $d.Used), (Format-Size $d.Free)) -ForegroundColor Yellow
    Write-Host "Isso pode levar varios minutos. Aguarde..." -ForegroundColor Yellow

    Write-Host ""
    Write-Host "=== Arquivos grandes na raiz do C: ===" -ForegroundColor Cyan
    Get-ChildItem -LiteralPath C:\ -File -Force -ErrorAction SilentlyContinue |
        Sort-Object Length -Descending | Select-Object -First 5 | ForEach-Object {
            Write-Host ("{0,10}   {1}" -f (Format-Size $_.Length), $_.FullName)
        }

    Show-TopFolders "C:\" 12
    Show-TopFolders $env:USERPROFILE 12
    Show-TopFolders (Join-Path $env:USERPROFILE "AppData\Local") 10
    Show-TopFolders (Join-Path $env:USERPROFILE "AppData\Roaming") 8

    Write-Host ""
    Write-Host "=== 20 maiores arquivos (acima de 300 MB) em C:\Users ===" -ForegroundColor Cyan
    Get-ChildItem -LiteralPath C:\Users -Recurse -Force -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Length -gt 300MB } |
        Sort-Object Length -Descending | Select-Object -First 20 | ForEach-Object {
            Write-Host ("{0,10}   {1}" -f (Format-Size $_.Length), $_.FullName)
        }

    Write-Host ""
    Write-Host "Pronto. Relatorio salvo na sua Area de Trabalho: Relatorio-Disco.txt" -ForegroundColor Green
    Stop-Transcript | Out-Null
}

# ---- motor de limpeza (com modo simulacao) ----
function Get-Rotulo($nome, $caminho) {
    $folha = Split-Path $caminho -Leaf
    if ($folha -eq '*') { $folha = Split-Path (Split-Path $caminho -Parent) -Leaf }
    return ("{0}: {1}" -f $nome, $folha)
}

function Limpar-Pasta($nome, $caminho) {
    if (-not (Test-Path -Path $caminho -ErrorAction SilentlyContinue)) { return 0 }
    $itens = @(Get-ChildItem -Path $caminho -Force -ErrorAction SilentlyContinue)
    if ($itens.Count -eq 0) { return 0 }
    $total = [int64]0
    foreach ($i in $itens) {
        if ($i.PSIsContainer) { $total += (Get-FolderSize $i.FullName) } else { $total += $i.Length }
    }
    if ($script:Simular) {
        Write-Host ("  [sim] {0}: ~{1}" -f $nome, (Format-Size $total)) -ForegroundColor DarkCyan
    } else {
        $itens | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
        Show-Ok ("{0}: ~{1}" -f $nome, (Format-Size $total))
    }
    $script:TotalLimpo += $total
    $script:LocaisLimpos++
    return $total
}

function New-Alvo($nome, $procs, $paths) {
    return [pscustomobject]@{
        Nome  = $nome
        Procs = @($procs | Where-Object { $_ })
        Paths = @($paths | Where-Object { $_ })
    }
}

# Pastas de cache de qualquer navegador baseado em Chromium (todos os perfis)
function Get-ChromiumPaths($userData, [bool]$profundo) {
    $r = @()
    if (-not (Test-Path -LiteralPath $userData)) { return $r }
    $perfis = @(Get-ChildItem -LiteralPath $userData -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'Default' -or $_.Name -like 'Profile *' -or $_.Name -eq 'Guest Profile' })
    foreach ($p in $perfis) {
        $subs = @('Cache', 'Code Cache', 'GPUCache')
        if ($profundo) { $subs += @('Service Worker\CacheStorage', 'Service Worker\ScriptCache', 'DawnCache') }
        foreach ($s in $subs) { $r += (Join-Path $p.FullName $s) }
    }
    if ($profundo) { foreach ($s in 'ShaderCache', 'GrShaderCache', 'GraphiteDawnCache') { $r += (Join-Path $userData $s) } }
    return $r
}

function Get-AlvosLimpeza([bool]$profundo) {
    $LocalApp = $env:LOCALAPPDATA
    $RoamApp  = $env:APPDATA
    $ProgData = $env:ProgramData
    $WinDir   = $env:SystemRoot
    $alvos = @()

    # --- Windows e usuarios ---
    $temps = @($env:TEMP)
    $raizUsers = Split-Path $env:USERPROFILE -Parent
    foreach ($u in @(Get-ChildItem -LiteralPath $raizUsers -Directory -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notin @('Public', 'Default', 'Default User', 'All Users') })) {
        $temps += (Join-Path $u.FullName 'AppData\Local\Temp')
    }
    $temps = @($temps | Where-Object { $_ } | Sort-Object -Unique)
    $alvos += New-Alvo 'Temp do usuario' $null $temps
    $alvos += New-Alvo 'Temp do Windows' $null @("$WinDir\Temp")
    $alvos += New-Alvo 'Dumps e relatorios de erro' $null @("$LocalApp\CrashDumps", "$WinDir\Minidump",
        "$ProgData\Microsoft\Windows\WER\ReportArchive", "$ProgData\Microsoft\Windows\WER\ReportQueue",
        "$ProgData\Microsoft\Windows\WER\Temp", "$LocalApp\Microsoft\Windows\WER")
    $alvos += New-Alvo 'Miniaturas' $null @("$LocalApp\Microsoft\Windows\Explorer\thumbcache_*.db")
    $alvos += New-Alvo 'Cache de internet do Windows' $null @("$LocalApp\Microsoft\Windows\INetCache")
    $alvos += New-Alvo 'Temporarios de apps da Loja' $null @("$LocalApp\Packages\*\TempState")

    # --- FiveM ---
    $fv = "$LocalApp\FiveM\FiveM.app\data"
    $fvPaths = @("$fv\cache", "$fv\server-cache", "$fv\server-cache-priv", "$fv\nui-storage")
    if ($profundo) { $fvPaths += @("$LocalApp\FiveM\FiveM.app\logs", "$LocalApp\FiveM\FiveM.app\crashes") }
    $alvos += New-Alvo 'FiveM' @('FiveM*', 'GTA5*', 'CitizenFX*') $fvPaths

    # --- Navegadores ---
    $alvos += New-Alvo 'Chrome' @('chrome') (Get-ChromiumPaths "$LocalApp\Google\Chrome\User Data" $profundo)
    $alvos += New-Alvo 'Edge' @('msedge') (Get-ChromiumPaths "$LocalApp\Microsoft\Edge\User Data" $profundo)
    $alvos += New-Alvo 'Brave' @('brave') (Get-ChromiumPaths "$LocalApp\BraveSoftware\Brave-Browser\User Data" $profundo)
    $alvos += New-Alvo 'Vivaldi' @('vivaldi') (Get-ChromiumPaths "$LocalApp\Vivaldi\User Data" $profundo)
    $alvos += New-Alvo 'Opera' @('opera') @("$LocalApp\Opera Software\Opera Stable\Cache", "$LocalApp\Opera Software\Opera Stable\Code Cache", "$LocalApp\Opera Software\Opera Stable\GPUCache")
    $alvos += New-Alvo 'Opera GX' @('opera') @("$LocalApp\Opera Software\Opera GX Stable\Cache", "$LocalApp\Opera Software\Opera GX Stable\Code Cache", "$LocalApp\Opera Software\Opera GX Stable\GPUCache")
    $ffPaths = @("$LocalApp\Mozilla\Firefox\Profiles\*\cache2")
    if ($profundo) { $ffPaths += @("$LocalApp\Mozilla\Firefox\Profiles\*\startupCache", "$LocalApp\Mozilla\Firefox\Profiles\*\jumpListCache") }
    $alvos += New-Alvo 'Firefox' @('firefox') $ffPaths

    if ($profundo) {
        # --- Windows (profundo) ---
        $alvos += New-Alvo 'Logs e dumps do Windows' $null @("$WinDir\Logs\CBS\*.log", "$WinDir\Logs\DISM\*.log",
            "$WinDir\Logs\WindowsUpdate", "$ProgData\Microsoft\Diagnosis\ETLLogs", "$WinDir\LiveKernelReports\*", "$WinDir\MEMORY.DMP")


        # --- Launchers e apps de jogo ---
        $steamPaths = @("$LocalApp\Steam\htmlcache")
        foreach ($sp in @(Get-SteamPaths)) { $steamPaths += @("$sp\logs", "$sp\dumps", "$sp\appcache\httpcache") }
        $alvos += New-Alvo 'Steam (cache e logs)' @('steam', 'steamwebhelper') $steamPaths
        $alvos += New-Alvo 'Epic Games Launcher' @('EpicGamesLauncher', 'EpicWebHelper') @("$LocalApp\EpicGamesLauncher\Saved\webcache*",
            "$LocalApp\EpicGamesLauncher\Saved\Logs", "$LocalApp\EpicGamesLauncher\Saved\Crashes", "$ProgData\Epic\EpicGamesLauncher\VaultCache")
        $alvos += New-Alvo 'Ubisoft Connect' @('upc', 'UbisoftConnect') @("$LocalApp\Ubisoft Game Launcher\cache", "$LocalApp\Ubisoft Game Launcher\logs")
        $alvos += New-Alvo 'EA app / Origin' @('EADesktop', 'Origin') @("$LocalApp\Electronic Arts\EA Desktop\Logs", "$RoamApp\Origin\Logs")
        $alvos += New-Alvo 'GOG Galaxy' @('GalaxyClient') @("$ProgData\GOG.com\Galaxy\webcache")
        $alvos += New-Alvo 'Battle.net' @('Battle.net') @("$RoamApp\Battle.net\Cache")
        $alvos += New-Alvo 'Riot / VALORANT (so logs)' @('RiotClientServices', 'VALORANT*', 'LeagueClient*') @("$LocalApp\Riot Games\Riot Client\Logs",
            "$LocalApp\VALORANT\Saved\Logs", "$LocalApp\VALORANT\Saved\Crashes")
        $alvos += New-Alvo 'Minecraft (logs)' @('javaw', 'MinecraftLauncher') @("$RoamApp\.minecraft\logs", "$RoamApp\.minecraft\crash-reports")
        $alvos += New-Alvo 'Roblox (logs)' @('RobloxPlayerBeta') @("$LocalApp\Roblox\logs")

        # --- Apps comuns ---
        $alvos += New-Alvo 'Discord' @('Discord') @("$RoamApp\discord\Cache", "$RoamApp\discord\Code Cache", "$RoamApp\discord\GPUCache", "$RoamApp\discord\DawnCache")
        $alvos += New-Alvo 'Spotify' @('Spotify') @("$LocalApp\Spotify\Storage", "$LocalApp\Spotify\Data")
        $alvos += New-Alvo 'Teams (classico)' @('Teams') @("$RoamApp\Microsoft\Teams\Cache", "$RoamApp\Microsoft\Teams\blob_storage",
            "$RoamApp\Microsoft\Teams\GPUCache", "$RoamApp\Microsoft\Teams\tmp", "$RoamApp\Microsoft\Teams\Application Cache")
        $alvos += New-Alvo 'Slack' @('slack') @("$RoamApp\Slack\Cache", "$RoamApp\Slack\Code Cache", "$RoamApp\Slack\GPUCache")
        $alvos += New-Alvo 'Caches de desenvolvimento (pip, npm, yarn, NuGet)' $null @("$LocalApp\pip\Cache", "$LocalApp\npm-cache", "$LocalApp\Yarn\Cache", "$LocalApp\NuGet\v3-cache")
    }
    return $alvos
}

# Usa o limpador nativo do Windows (cleanmgr) em todas as categorias seguras
function Executar-CleanMgr {
    $base = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\VolumeCaches'
    $excluir = @('DownloadsFolder', 'Previous Installations')
    $chaves = @(Get-ChildItem -Path $base -ErrorAction SilentlyContinue | Where-Object { $excluir -notcontains $_.PSChildName })
    foreach ($k in $chaves) { Set-ItemProperty -Path $k.PSPath -Name 'StateFlags0099' -Value 2 -Type DWord -ErrorAction SilentlyContinue }
    Write-Host "  Abrindo o Limpador de Disco do Windows (pode demorar; a pasta Downloads NAO e tocada)..." -ForegroundColor DarkGray
    Start-Process -FilePath 'cleanmgr.exe' -ArgumentList '/sagerun:99' -Wait
    foreach ($k in $chaves) { Remove-ItemProperty -Path $k.PSPath -Name 'StateFlags0099' -ErrorAction SilentlyContinue }
}

function Limpar-PC {
    param([switch]$Profundo)
    $antes = (Get-PSDrive C).Free
    Write-Host ""
    if ($script:Simular) { Write-Host "=== SIMULACAO DE LIMPEZA PROFUNDA (nada sera apagado) ===" -ForegroundColor DarkCyan }
    elseif ($Profundo) { Write-Host "=== LIMPEZA PROFUNDA ===" -ForegroundColor Magenta }
    else { Write-Host "=== LIMPEZA PADRAO ===" -ForegroundColor Cyan }

    Write-Host "Vou limpar:"
    Write-Host "  - Temporarios de todos os usuarios e do Windows, Lixeira, miniaturas, dumps e relatorios de erro"
    Write-Host "  - Cache de FiveM, Chrome, Edge, Brave, Vivaldi, Opera e Firefox (todos os perfis)"
    Write-Host "  - Downloads do Windows Update, componentes antigos do Windows (DISM) e cache de DNS"
    if ($Profundo) {
        Write-Host "  - Logs e dumps do Windows (cache de shaders so com confirmacao: apagar causa engasgos nos jogos)"
        Write-Host "  - Steam (cache/logs de todas as bibliotecas), Epic (inclui VaultCache), Ubisoft, EA, GOG, Battle.net, Riot, Minecraft, Roblox"
        Write-Host "  - Discord, Spotify, Teams, Slack, caches de pip/npm/yarn/NuGet e Otimizacao de Entrega"
        Write-Host "  - Limpador de Disco nativo do Windows (todas as categorias seguras)"
        Write-Host "  - No final, pergunto sobre itens avancados (Windows.old, hibernacao, logs de eventos, etc.)"
    }
    Write-Host "NAO mexo em: senhas, historico, cookies, favoritos, saves de jogos, documentos e Downloads." -ForegroundColor DarkGray
    Write-Host ""
    if (-not $script:Simular) {
        if (-not (Ask-YesNo "Continuar?")) { Write-Host "Cancelado."; return }
    }

    $script:TotalLimpo = [int64]0
    $script:LocaisLimpos = 0
    $alvos = @(Get-AlvosLimpeza $Profundo.IsPresent)

    # Programas abertos bloqueiam a limpeza: oferece fechar de uma vez
    $nomesProc = @($alvos | ForEach-Object { $_.Procs } | Where-Object { $_ } | Sort-Object -Unique)
    $abertos = @()
    if ($nomesProc.Count -gt 0) { $abertos = @(Get-Process -Name $nomesProc -ErrorAction SilentlyContinue) }
    if ($abertos.Count -gt 0 -and -not $script:Simular) {
        $nomes = @($abertos | Select-Object -ExpandProperty ProcessName | Sort-Object -Unique)
        Write-Host ""
        Write-Host ("Programas abertos que bloqueiam a limpeza: " + ($nomes -join ', ')) -ForegroundColor Yellow
        if (Ask-YesNo "Fechar agora? (salve o que estiver fazendo antes!)") {
            $abertos | Stop-Process -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
        }
    }

    Write-Host ""
    Write-Host "Limpando..." -ForegroundColor Cyan
    foreach ($a in $alvos) {
        if ($a.Procs.Count -gt 0 -and (Get-Process -Name $a.Procs -ErrorAction SilentlyContinue)) {
            Show-Aviso ("{0} esta aberto. Pulei." -f $a.Nome)
            continue
        }
        foreach ($c in $a.Paths) { [void](Limpar-Pasta (Get-Rotulo $a.Nome $c) $c) }
    }

    # Windows Update (para os servicos so se estavam rodando, e religa depois)
    $svcParados = @()
    if (-not $script:Simular) {
        foreach ($s in 'wuauserv', 'bits') {
            $sv = Get-Service -Name $s -ErrorAction SilentlyContinue
            if ($sv -and $sv.Status -eq 'Running') { $svcParados += $s }
        }
        if ($svcParados.Count -gt 0) { Stop-Service -Name $svcParados -Force -ErrorAction SilentlyContinue }
    }
    [void](Limpar-Pasta "Downloads do Windows Update" "$env:SystemRoot\SoftwareDistribution\Download")
    if ($svcParados.Count -gt 0) { Start-Service -Name $svcParados -ErrorAction SilentlyContinue }

    if ($Profundo) {
        $dosRodando = $false
        $dv = Get-Service -Name dosvc -ErrorAction SilentlyContinue
        if ($dv -and $dv.Status -eq 'Running') { $dosRodando = $true }
        if ($dosRodando -and -not $script:Simular) { Stop-Service -Name dosvc -Force -ErrorAction SilentlyContinue }
        [void](Limpar-Pasta "Otimizacao de Entrega" "$env:SystemRoot\ServiceProfiles\NetworkService\AppData\Local\Microsoft\Windows\DeliveryOptimization\Cache")
        if ($dosRodando) { Start-Service -Name dosvc -ErrorAction SilentlyContinue }
    }

    if ($script:Simular) {
        Write-Host ""
        Write-Host "=====================================" -ForegroundColor DarkCyan
        Write-Host ("Estimativa nas pastas de cache: ~{0} em {1} locais" -f (Format-Size $script:TotalLimpo), $script:LocaisLimpos) -ForegroundColor DarkCyan
        Write-Host "Nada foi apagado (Lixeira, DISM, Windows.old etc. nao entram nessa conta)." -ForegroundColor DarkCyan
        Write-Host "=====================================" -ForegroundColor DarkCyan
        return
    }

    Clear-RecycleBin -Force -ErrorAction SilentlyContinue
    Show-Ok "Lixeira esvaziada"
    & ipconfig.exe /flushdns | Out-Null
    Show-Ok "Cache de DNS limpo"

    Write-Host ""
    Write-Host "Limpando componentes antigos do Windows (pode levar alguns minutos)..." -ForegroundColor Cyan
    & Dism.exe /Online /Cleanup-Image /StartComponentCleanup | Out-Null
    Show-Ok "Componentes do Windows"

    if ($Profundo) {
        Write-Host ""
        if (Ask-YesNo "Rodar tambem o Limpador de Disco nativo do Windows (cleanmgr)?") {
            Executar-CleanMgr
            Show-Ok "Limpador de Disco concluido"
        }

        Write-Host ""
        Write-Host "=== ITENS AVANCADOS (cada um pede confirmacao) ===" -ForegroundColor Magenta

        if (Test-Path "C:\Windows.old") {
            $sz = Get-FolderSize "C:\Windows.old"
            Write-Host ""
            Write-Host ("Windows.old: {0} (copia do Windows anterior)." -f (Format-Size $sz)) -ForegroundColor Yellow
            Write-Host "Apagar impede voltar para a versao anterior do Windows. Se ja esta tudo funcionando, e seguro."
            if (Ask-YesNo "Apagar Windows.old?") {
                & takeown.exe /F "C:\Windows.old" /R /A | Out-Null
                & icacls.exe "C:\Windows.old" /grant "*S-1-5-32-544:F" /T /C /Q | Out-Null
                Remove-Item "C:\Windows.old" -Recurse -Force -ErrorAction SilentlyContinue
                if (Test-Path "C:\Windows.old") { Write-Host "  Nao consegui apagar tudo. Use: Configuracoes > Armazenamento > Arquivos temporarios > Instalacoes anteriores do Windows." -ForegroundColor Yellow }
                else { Show-Ok "Windows.old apagado" }
            }
        }

        foreach ($resto in @('C:\$Windows.~BT', 'C:\$WINDOWS.~WS')) {
            if (Test-Path -LiteralPath $resto) {
                $sz = Get-FolderSize $resto
                Write-Host ""
                Write-Host ("Sobras de atualizacao do Windows ({0}): {1}." -f $resto, (Format-Size $sz)) -ForegroundColor Yellow
                if (Ask-YesNo "Apagar?") {
                    & takeown.exe /F $resto /R /A | Out-Null
                    & icacls.exe $resto /grant "*S-1-5-32-544:F" /T /C /Q | Out-Null
                    Remove-Item -LiteralPath $resto -Recurse -Force -ErrorAction SilentlyContinue
                    if (Test-Path -LiteralPath $resto) { Show-Aviso "Nao consegui apagar tudo." } else { Show-Ok "Apagado" }
                }
            }
        }

        $hib = Get-Item "C:\hiberfil.sys" -Force -ErrorAction SilentlyContinue
        if ($hib) {
            Write-Host ""
            Write-Host ("Arquivo de hibernacao (hiberfil.sys): {0}." -f (Format-Size $hib.Length)) -ForegroundColor Yellow
            Write-Host "Desativar a hibernacao libera esse espaco. O PC continua desligando e suspendendo normalmente (inicializacao rapida tambem e desativada)."
            if (Ask-YesNo "Desativar a hibernacao?") {
                & powercfg.exe /hibernate off
                Show-Ok "Hibernacao desativada"
            }
        }

        $shPaths = @("$env:LOCALAPPDATA\D3DSCache", "$env:LOCALAPPDATA\NVIDIA\DXCache", "$env:LOCALAPPDATA\NVIDIA\GLCache", "$env:LOCALAPPDATA\NVIDIA\ComputeCache",
            "$env:ProgramData\NVIDIA Corporation\NV_Cache", "$env:LOCALAPPDATA\Intel\ShaderCache", "$env:LOCALAPPDATA\AMD\DxCache", "$env:LOCALAPPDATA\AMD\GLCache",
            "$env:LOCALAPPDATA\AMD\VkCache", "$env:LOCALAPPDATA\AMD\DxcCache")
        foreach ($lib in @(Get-SteamLibraries)) { $shPaths += "$lib\steamapps\shadercache" }
        $shTot = [int64]0
        foreach ($sp in $shPaths) { if (Test-Path -LiteralPath $sp) { $shTot += Get-FolderSize $sp } }
        if ($shTot -gt 50MB) {
            Write-Host ""
            Write-Host ("Cache de shaders (placa de video e Steam): {0}." -f (Format-Size $shTot)) -ForegroundColor Yellow
            Write-Host "ATENCAO: apagar NAO aumenta o FPS. Os jogos recompilam os shaders e as primeiras partidas ficam com MAIS engasgos."
            Write-Host "So vale a pena se um jogo ficou com graficos bugados/crash depois de atualizar o driver, ou se faltar muito espaco."
            if (Ask-YesNo "Apagar o cache de shaders mesmo assim?") {
                foreach ($sp in $shPaths) { [void](Limpar-Pasta (Get-Rotulo 'Cache de shaders' $sp) $sp) }
            }
        }

        Write-Host ""
        Write-Host "Logs de eventos do Windows (Visualizador de Eventos)." -ForegroundColor Yellow
        Write-Host "Limpar libera espaco e remove o historico de diagnostico (so atrapalha se voce estiver investigando um problema)."
        if (Ask-YesNo "Limpar todos os logs de eventos?") {
            foreach ($ev in @(& wevtutil.exe el)) { & wevtutil.exe cl $ev 2>&1 | Out-Null }
            Show-Ok "Logs de eventos limpos"
        }

        Write-Host ""
        Write-Host "Reduzir o tamanho do Windows Update (DISM /ResetBase)." -ForegroundColor Yellow
        Write-Host "Remove versoes antigas de componentes. Depois, nao da para desinstalar atualizacoes ja instaladas."
        if (Ask-YesNo "Executar? (pode demorar)") {
            & Dism.exe /Online /Cleanup-Image /StartComponentCleanup /ResetBase | Out-Null
            Show-Ok "ResetBase concluido"
        }

        Write-Host ""
        Write-Host "Pontos de restauracao do sistema (copias de sombra)." -ForegroundColor Yellow
        Write-Host "Apagar libera espaco, mas voce perde a chance de restaurar o Windows para uma data anterior."
        if (Ask-YesNo "Apagar todos os pontos de restauracao?") {
            & vssadmin.exe delete shadows /all /quiet | Out-Null
            Show-Ok "Pontos de restauracao apagados"
        }

        $gs = "$env:LOCALAPPDATA\FiveM\FiveM.app\data\game-storage"
        if (Test-Path $gs) {
            $sz = Get-FolderSize $gs
            Write-Host ""
            Write-Host ("FiveM game-storage: {0} (copia dos arquivos do GTA usada pelo FiveM)." -f (Format-Size $sz)) -ForegroundColor Yellow
            Write-Host "Se apagar, o FiveM baixa de novo na proxima vez. So vale a pena se o jogo estiver dando erro de arquivo corrompido."
            if (Get-Process -Name "FiveM*", "GTA5*", "CitizenFX*" -ErrorAction SilentlyContinue) {
                Write-Host "  FiveM aberto, pulei." -ForegroundColor Yellow
            } elseif (Ask-YesNo "Apagar game-storage?") {
                Remove-Item $gs -Recurse -Force -ErrorAction SilentlyContinue
                Show-Ok "game-storage apagado"
            }
        }
    }

    $depois = (Get-PSDrive C).Free
    Write-Log ("Limpeza concluida. Liberado: {0}" -f (Format-Size ($depois - $antes)))
    Write-Host ""
    Write-Host "=====================================" -ForegroundColor Green
    Write-Host ("Espaco livre antes:  {0}" -f (Format-Size $antes)) -ForegroundColor Green
    Write-Host ("Espaco livre agora:  {0}" -f (Format-Size $depois)) -ForegroundColor Green
    Write-Host ("Liberado:            {0}" -f (Format-Size ($depois - $antes))) -ForegroundColor Green
    Write-Host "=====================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Se ainda estiver cheio: veja o tamanho dos jogos (opcao 7), arquivos grandes (5) e programas instalados (9)." -ForegroundColor Yellow
}

# ---- revisao manual de arquivos (vai para a Lixeira, nunca apaga direto) ----
function Selecionar-ParaLixeira($arquivos) {
    Add-Type -AssemblyName Microsoft.VisualBasic
    Write-Host ""
    Write-Host "Digite os NUMEROS dos arquivos que quer mandar para a Lixeira, separados por virgula (ex: 1,3,7)."
    Write-Host "Enter sem digitar nada = nao apagar nada." -ForegroundColor DarkGray
    $sel = Read-Host "Numeros"
    if (-not $sel) { return }

    $idx = @(ConvertTo-Indices $sel $arquivos.Count)
    if ($idx.Count -eq 0) { Write-Host "Nenhum numero valido."; return }

    $soma = 0
    Write-Host ""
    Write-Host "Vao para a Lixeira:" -ForegroundColor Yellow
    foreach ($i in $idx) { $f = $arquivos[$i - 1]; $soma += $f.Length; Write-Host ("  {0}  {1}" -f (Format-Size $f.Length), $f.FullName) }
    Write-Host ("Total: {0}" -f (Format-Size $soma)) -ForegroundColor Yellow
    if (-not (Ask-YesNo "Confirmar?")) { Write-Host "Cancelado."; return }

    foreach ($i in $idx) {
        $f = $arquivos[$i - 1]
        try { [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($f.FullName, 'OnlyErrorDialogs', 'SendToRecycleBin') } catch { Show-Aviso ("Nao consegui mover: " + $f.FullName) }
    }
    Write-Host "Feito. O espaco so e liberado quando a Lixeira for esvaziada." -ForegroundColor Green
    if (Ask-YesNo "Esvaziar a Lixeira agora?") {
        Clear-RecycleBin -Force -ErrorAction SilentlyContinue
        Show-Ok "Lixeira esvaziada"
    }
}

function Mostrar-ListaArquivos($arquivos) {
    $n = 1
    foreach ($a in $arquivos) {
        $aviso = ""
        if ($a.FullName -like "*\AppData\*") { $aviso = "  [AppData - cuidado, pode ser de um programa]" }
        if ($a.FullName -like "*\OneDrive*") { $aviso = "  [OneDrive - apagar tambem apaga da nuvem]" }
        if ($a.FullName -match '\\steamapps\\|\\Epic Games\\|\\XboxGames\\') { $aviso = "  [Jogo instalado - desinstale pelo launcher em vez de apagar]" }
        Write-Host ("{0,3}) {1,9}  {2}  {3}{4}" -f $n, (Format-Size $a.Length), $a.LastWriteTime.ToString('yyyy-MM-dd'), $a.FullName, $aviso)
        $n++
    }
}

function Revisar-Grandes {
    Write-Host ""
    $m = Read-Host "Tamanho minimo em MB (Enter = 500)"
    if ($m -notmatch '^\d+$') { $m = 500 }
    $min = [int64]$m * 1MB

    Write-Host "Onde procurar?  1) Meu perfil de usuario (rapido)   2) Todos os discos (demorado)"
    $onde = Read-Host "Escolha (Enter = 1)"
    $raizes = @()
    if ($onde -eq '2') {
        $excl = @('Windows', 'Program Files', 'Program Files (x86)', 'ProgramData', '$Recycle.Bin', 'System Volume Information', 'Recovery', 'Boot')
        foreach ($dk in (Get-DiscosFixos)) {
            $raiz = $dk.DeviceID + '\'
            $raizes += @(Get-ChildItem -LiteralPath $raiz -Directory -Force -ErrorAction SilentlyContinue | Where-Object { $excl -notcontains $_.Name } | ForEach-Object { $_.FullName })
        }
    } else {
        $raizes = @(Split-Path $env:USERPROFILE -Parent)
    }

    Write-Host "Procurando arquivos grandes (pode demorar)..." -ForegroundColor Yellow
    $arquivos = @()
    foreach ($rz in $raizes) {
        $arquivos += @(Get-ChildItem -LiteralPath $rz -Recurse -Force -File -ErrorAction SilentlyContinue | Where-Object { $_.Length -gt $min })
    }
    $arquivos = @($arquivos | Sort-Object Length -Descending | Select-Object -First 40)

    if ($arquivos.Count -eq 0) { Write-Host "Nenhum arquivo acima de $m MB encontrado." -ForegroundColor Green; return }
    Write-Host ""
    Write-Host "=== Maiores arquivos (ate 40) ===" -ForegroundColor Cyan
    Mostrar-ListaArquivos $arquivos
    Selecionar-ParaLixeira $arquivos
}

function Revisar-Downloads {
    $dl = Join-Path $env:USERPROFILE 'Downloads'
    Write-Host ""
    $d = Read-Host "Mostrar arquivos mais antigos que quantos dias? (Enter = 90)"
    if ($d -notmatch '^\d+$') { $d = 90 }
    $limite = (Get-Date).AddDays(-[int]$d)
    $arquivos = @(Get-ChildItem -LiteralPath $dl -Recurse -Force -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $limite -and $_.Length -gt 1MB } |
        Sort-Object Length -Descending | Select-Object -First 40)
    if ($arquivos.Count -eq 0) { Write-Host "Nada antigo e grande em Downloads." -ForegroundColor Green; return }
    Write-Host ""
    Write-Host ("=== Downloads com mais de {0} dias (maiores primeiro) ===" -f $d) -ForegroundColor Cyan
    Mostrar-ListaArquivos $arquivos
    Selecionar-ParaLixeira $arquivos
}

function Mostrar-TamanhoJogos {
    Write-Host ""
    Write-Host "=== QUANTO CADA JOGO OCUPA ===" -ForegroundColor Cyan
    $jogos = @(Get-JogosInstalados)
    if ($jogos.Count -eq 0) { Write-Host "Nenhum jogo encontrado automaticamente."; return }
    $lista = @()
    $k = 1
    foreach ($j in $jogos) {
        Write-Host ("  medindo {0}/{1}: {2} ..." -f $k, $jogos.Count, $j.Nome) -ForegroundColor DarkGray
        $lista += [pscustomobject]@{ Nome = $j.Nome; Origem = $j.Origem; Tamanho = (Get-FolderSize $j.Pasta); Pasta = $j.Pasta }
        $k++
    }
    $lista = @($lista | Sort-Object Tamanho -Descending)
    Write-Host ""
    foreach ($i in $lista) { Write-Host ("{0,10}   {1,-45} [{2}]" -f (Format-Size $i.Tamanho), $i.Nome, $i.Origem) }
    $soma = ($lista | Measure-Object -Property Tamanho -Sum).Sum
    Write-Host ""
    Write-Host ("Total em jogos: {0}" -f (Format-Size $soma)) -ForegroundColor Yellow
    Write-Host "Para desinstalar: use o launcher (Steam/Epic/Xbox) ou a opcao 9 (programas instalados)." -ForegroundColor DarkGray
}

# ---- CompactOS ----
function Espaco-Extra {
    Write-Host ""
    Write-Host "=== ESPACO EXTRA: COMPACTAR O WINDOWS (CompactOS) ===" -ForegroundColor Cyan
    Write-Host "Comprime os arquivos do proprio Windows e costuma liberar de 2 a 5 GB."
    Write-Host "Estado atual:"
    (& compact.exe /compactOS:query) | ForEach-Object { Write-Host ("  " + $_) }
    $tipo = Get-TipoDisco 'C'
    Write-Host ("Tipo do disco C: {0}" -f $tipo)
    if ($tipo -eq 'HDD') { Write-Host "Em HD comum isso pode deixar o sistema um pouco mais lento. Nao recomendo." -ForegroundColor Yellow }
    Write-Host "Demora alguns minutos. Reversivel: opcao Desfazer do menu de FPS."
    if (Ask-YesNo "Ativar a compactacao?") {
        & compact.exe /compactOS:always | ForEach-Object { Write-Host ("  " + $_) }
        $d = Get-PSDrive C
        Write-Host ("Pronto. Livre agora: {0}" -f (Format-Size $d.Free)) -ForegroundColor Green
    }
}

function Menu-Limpeza {
    while ($true) {
        Clear-Host
        $d = Get-PSDrive C
        Write-Host "=========== LIMPEZA DO PC ===========" -ForegroundColor Cyan
        Write-Host ("Disco C: livre {0} de {1}" -f (Format-Size $d.Free), (Format-Size ($d.Used + $d.Free)))
        Write-Host ""
        Write-Host " 1) Analisar o disco (mostra o que ocupa espaco, nao apaga nada)"
        Write-Host " 2) Limpeza padrao (lixo seguro)"
        Write-Host " 3) Limpeza PROFUNDA (caches de jogos/launchers/shaders + itens avancados com confirmacao)"
        Write-Host " 4) SIMULAR limpeza profunda (mostra quanto liberaria, sem apagar)"
        Write-Host " 5) Revisar arquivos grandes (voce escolhe o que vai para a Lixeira)"
        Write-Host " 6) Revisar Downloads antigos (voce escolhe o que vai para a Lixeira)"
        Write-Host " 7) Quanto cada jogo instalado ocupa"
        Write-Host " 8) Compactar o Windows (CompactOS, libera 2-5 GB)"
        Write-Host " 9) Abrir lista de programas instalados (para desinstalar o que nao usa)"
        Write-Host "10) Voltar ao menu principal"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1'  { Analisar-Disco; Pausa }
            '2'  { Limpar-PC; Pausa }
            '3'  { Limpar-PC -Profundo; Pausa }
            '4'  { $script:Simular = $true; Limpar-PC -Profundo; $script:Simular = $false; Pausa }
            '5'  { Revisar-Grandes; Pausa }
            '6'  { Revisar-Downloads; Pausa }
            '7'  { Mostrar-TamanhoJogos; Pausa }
            '8'  { Espaco-Extra; Pausa }
            '9'  { Start-Process "ms-settings:appsfeatures"; Start-Sleep -Seconds 1 }
            '10' { return }
        }
    }
}

# ======================================================================
# SECAO 2: MEGAZORD - RESET DE FABRICA
# ======================================================================
$script:BackupOk = $false
$script:BackupPath = $null

function Etapa-Checagens {
    Write-Host ""
    Write-Host "=== CHECAGENS ===" -ForegroundColor Cyan

    $lic = Get-CimInstance SoftwareLicensingProduct -Filter "ApplicationId='55c92734-d682-4d71-983e-d6ec3f16059f' AND LicenseStatus=1" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($lic) { Write-Host "  [ok]  Windows ativado (a licenca volta sozinha depois do reset)" -ForegroundColor Green }
    else { Write-Host "  [!!]  Nao consegui confirmar que o Windows esta ativado. Veja em Configuracoes > Sistema > Ativacao." -ForegroundColor Yellow }

    $bl = Get-BitLockerVolume -MountPoint 'C:' -ErrorAction SilentlyContinue
    if ($bl -and $bl.ProtectionStatus -eq 'On') {
        Write-Host "  [!!]  BitLocker/criptografia ativo no C:. Confirme que voce tem a chave de recuperacao na sua conta Microsoft (aka.ms/myrecoverykey)." -ForegroundColor Yellow
    } else { Write-Host "  [ok]  Sem BitLocker ativo no C:" -ForegroundColor Green }

    if (Test-Connection -ComputerName 8.8.8.8 -Count 1 -Quiet) { Write-Host "  [ok]  Internet funcionando (necessaria se escolher reinstalar pela nuvem)" -ForegroundColor Green }
    else { Write-Host "  [!!]  Sem internet agora. A reinstalacao pela nuvem precisa dela." -ForegroundColor Yellow }

    $d = Get-PSDrive C
    Write-Host ("  Disco C: usado {0} | livre {1}" -f (Format-Size $d.Used), (Format-Size $d.Free))
}

function Etapa-Inventario($pasta) {
    New-Item -ItemType Directory -Path $pasta -Force | Out-Null

    $chaves = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    Get-ItemProperty $chaves -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName } |
        Sort-Object DisplayName | Select-Object DisplayName, DisplayVersion, Publisher |
        Format-Table -AutoSize | Out-String -Width 250 |
        Out-File (Join-Path $pasta "programas-instalados.txt") -Encoding UTF8
    Write-Host "  [ok]  Lista de programas instalados salva"

    Write-Host "  Exportando drivers (rede, audio, etc.)..." -ForegroundColor DarkGray
    New-Item -ItemType Directory -Path (Join-Path $pasta "Drivers") -Force | Out-Null
    Export-WindowsDriver -Online -Destination (Join-Path $pasta "Drivers") -ErrorAction SilentlyContinue | Out-Null
    Write-Host "  [ok]  Drivers exportados (ajudam se a rede nao funcionar depois do reset)"

    $todosJogos = @(Get-JogosInstalados)
    if ($todosJogos.Count -gt 0) {
        $txtJogos = ($todosJogos | ForEach-Object { "{0}  [{1}]  {2}" -f $_.Nome, $_.Origem, $_.Pasta }) -join "`r`n"
        $txtJogos | Out-File (Join-Path $pasta "jogos-instalados.txt") -Encoding UTF8
        Write-Host "  [ok]  Lista de jogos instalados (Steam/Epic/GOG/Ubisoft/EA/Xbox/FiveM...) salva"
    }

    $gpus = (@(Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name }) -join ', ')
    $drv = @()
    if ($gpus -match 'NVIDIA') { $drv += 'NVIDIA App / GeForce Experience (nvidia.com/drivers)' }
    if ($gpus -match 'AMD|Radeon') { $drv += 'AMD Adrenalin (amd.com/support)' }
    if ($gpus -match 'Intel') { $drv += 'Intel Driver & Support Assistant' }
    if ($drv.Count -eq 0) { $drv += 'driver da sua placa de video (site do fabricante)' }

    $linhas = @(
        'CHECKLIST POS-RESET',
        '===================',
        ('Video neste PC: ' + $gpus),
        '',
        '1. Configure o Windows e entre na conta Microsoft.',
        '2. Rode o Windows Update ate nao ter mais nada pendente (inclui "Atualizacoes opcionais" > drivers).',
        '3. Se a internet/audio nao funcionar: Gerenciador de Dispositivos > Atualizar driver > Procurar no computador > pasta Drivers deste backup.',
        ('4. Instale o driver grafico: ' + ($drv -join ' | ')),
        '5. Instale o Chrome/Edge e entre na conta para sincronizar favoritos e senhas.',
        '6. Reinstale o que usa, consultando programas-instalados.txt (so o que realmente usa).',
        '7. Jogos: reinstale pela Steam/Epic/GOG/Ubisoft/EA/Xbox, consultando jogos-instalados.txt. GTA V + FiveM: reinstale pelo Steam/Epic/Rockstar e depois o FiveM (fivem.net).',
        '8. Copie de volta so o que precisa do backup (Documentos, Imagens, Saved Games, etc.).',
        '9. Discord, Steam, Spotify e outros: sera preciso entrar de novo (tenha o celular/2FA por perto).',
        '10. Ajustes do FiveM: a pasta CitizenFX do backup tem as configuracoes antigas (opcional).',
        '11. Depois de tudo instalado, rode o PC Toolkit de novo: Assistente (opcao 1) deixa limpeza, FPS e temperatura prontos.',
        '',
        'Dica: deixe a memoria virtual em automatico e instale seus jogos com pelo menos 60 GB livres.'
    )
    ($linhas -join "`r`n") | Out-File (Join-Path $pasta "CHECKLIST-POS-RESET.txt") -Encoding UTF8
    Write-Host "  [ok]  Checklist pos-reset salvo"
}

function Passo-Preparar {
    $script:BackupOk = $false
    Etapa-Checagens

    Write-Host ""
    Write-Host "=== DESTINO DO BACKUP ===" -ForegroundColor Cyan
    Write-Host "O backup precisa ir para OUTRO disco (HD/SSD externo, pen drive grande ou segundo disco). Nunca para o C:." -ForegroundColor Yellow
    $discos = @(Get-CimInstance Win32_LogicalDisk | Where-Object { $_.DeviceID -ne 'C:' -and ($_.DriveType -eq 2 -or $_.DriveType -eq 3) -and $_.FreeSpace -gt 1GB })
    if ($discos.Count -eq 0) {
        Write-Host ""
        Write-Host "Nenhum outro disco encontrado. Conecte um HD/SSD externo e rode o Passo 1 de novo." -ForegroundColor Red
        return
    }
    Write-Host ""
    foreach ($x in $discos) {
        $tipo = "Local"; if ($x.DriveType -eq 2) { $tipo = "Removivel" }
        Write-Host ("  {0}  {1,-18} {2,-10} livre {3} de {4}" -f $x.DeviceID, $x.VolumeName, $tipo, (Format-Size $x.FreeSpace), (Format-Size $x.Size))
    }
    $letra = (Read-Host "Digite a letra do disco de destino (ex: E)").Trim().TrimEnd(':').ToUpper()
    $disco = $discos | Where-Object { $_.DeviceID -eq ($letra + ":") } | Select-Object -First 1
    if (-not $disco) { Write-Host "Letra invalida." -ForegroundColor Red; return }

    $fontes = @(
        @{ N = 'Area de Trabalho'; P = [Environment]::GetFolderPath('Desktop') },
        @{ N = 'Documentos';       P = [Environment]::GetFolderPath('MyDocuments') },
        @{ N = 'Imagens';          P = [Environment]::GetFolderPath('MyPictures') },
        @{ N = 'Videos';           P = [Environment]::GetFolderPath('MyVideos') },
        @{ N = 'Musicas';          P = [Environment]::GetFolderPath('MyMusic') },
        @{ N = 'Downloads';        P = (Join-Path $env:USERPROFILE 'Downloads') },
        @{ N = 'Saved Games (saves de varios jogos)'; P = (Join-Path $env:USERPROFILE 'Saved Games') },
        @{ N = 'CitizenFX (config do FiveM)'; P = (Join-Path $env:APPDATA 'CitizenFX') },
        @{ N = 'Minecraft - saves'; P = (Join-Path $env:APPDATA '.minecraft\saves') }
    )
    foreach ($sp in @(Get-SteamPaths)) {
        $ud = Join-Path $sp 'userdata'
        if (Test-Path -LiteralPath $ud) { $fontes += @{ N = 'Steam - userdata (configs/saves locais)'; P = $ud } }
    }

    Write-Host ""
    Write-Host "Se tiver arquivos importantes em outras pastas (ex: C:\Jogos, C:\Fotos), digite o caminho." -ForegroundColor Yellow
    while ($true) {
        $extra = (Read-Host "Pasta extra para incluir (Enter para continuar)").Trim().Trim('"')
        if (-not $extra) { break }
        if (Test-Path -LiteralPath $extra -PathType Container) { $fontes += @{ N = ("Extra - " + (Split-Path $extra -Leaf)); P = $extra } }
        else { Write-Host "  Pasta nao encontrada." -ForegroundColor Red }
    }

    Write-Host ""
    Write-Host "Medindo o que sera copiado (pode demorar)..." -ForegroundColor Yellow
    $lista = @()
    foreach ($f in $fontes) {
        if ($f.P -and (Test-Path -LiteralPath $f.P)) {
            $st = Get-FolderStats $f.P
            $lista += [pscustomobject]@{ N = $f.N; P = $f.P; Arq = $st.Arquivos; Bytes = $st.Bytes }
        }
    }
    if ($lista.Count -eq 0) { Write-Host "Nada para copiar." -ForegroundColor Red; return }

    Write-Host ""
    $n = 1
    foreach ($i in $lista) { Write-Host ("{0,2}) {1,-38} {2,9}  {3,7} arquivos  {4}" -f $n, $i.N, (Format-Size $i.Bytes), $i.Arq, $i.P); $n++ }

    $ex = Read-Host "Numeros que quer EXCLUIR do backup, separados por virgula (Enter = copiar tudo)"
    if ($ex) {
        $exc = @($ex -split '[,; ]+' | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ })
        $nova = @(); $k = 1
        foreach ($i in $lista) { if ($exc -notcontains $k) { $nova += $i }; $k++ }
        $lista = $nova
    }
    if ($lista.Count -eq 0) { Write-Host "Nada selecionado." -ForegroundColor Red; return }

    $total = ($lista | Measure-Object -Property Bytes -Sum).Sum
    Write-Host ""
    Write-Host ("Total a copiar: {0} | Livre no destino: {1}" -f (Format-Size $total), (Format-Size $disco.FreeSpace)) -ForegroundColor Cyan
    if ($total * 1.05 -gt $disco.FreeSpace) {
        Write-Host "Nao cabe no disco de destino. Exclua pastas da lista ou use um disco maior." -ForegroundColor Red
        return
    }
    if (-not (Ask-YesNo "Iniciar o backup?")) { Write-Host "Cancelado."; return }

    $raiz = Join-Path ($disco.DeviceID + "\") ("Backup-PC-" + (Get-Date).ToString('yyyy-MM-dd'))
    New-Item -ItemType Directory -Path $raiz -Force | Out-Null
    Write-Host ""
    Write-Host "=== INVENTARIO ===" -ForegroundColor Cyan
    Etapa-Inventario (Join-Path $raiz "_Inventario")

    Write-Host ""
    Write-Host "=== BACKUP (pode levar bastante tempo, nao feche a janela) ===" -ForegroundColor Cyan
    $log = Join-Path $raiz "_backup-log.txt"
    $tudoOk = $true
    $idx = 1
    foreach ($i in $lista) {
        $nome = ($i.N -replace '[^\w]', '_')
        $dst = Join-Path $raiz ("{0:D2}_{1}" -f $idx, $nome)
        Write-Host ("  Copiando {0} ({1})..." -f $i.N, (Format-Size $i.Bytes))
        $rcArgs = @($i.P, $dst, '/E', '/COPY:DAT', '/DCOPY:DAT', '/R:1', '/W:1', '/MT:8', '/XJ', '/NP', '/NFL', '/NDL', "/LOG+:$log")
        & robocopy.exe @rcArgs | Out-Null
        $code = $LASTEXITCODE
        $dd = Get-FolderStats $dst
        if ($code -lt 8 -and $dd.Arquivos -eq $i.Arq -and $dd.Bytes -eq $i.Bytes) {
            Write-Host ("  [ok]  {0}: {1} arquivos conferidos" -f $i.N, $dd.Arquivos) -ForegroundColor Green
        } else {
            Write-Host ("  [!!]  {0}: diferenca na conferencia (origem {1} arq / destino {2} arq). Veja o log." -f $i.N, $i.Arq, $dd.Arquivos) -ForegroundColor Yellow
            $tudoOk = $false
        }
        $idx++
    }

    Write-Host ""
    if ($tudoOk) {
        $script:BackupOk = $true
        $script:BackupPath = $raiz
        Write-Host "BACKUP CONFERIDO COM SUCESSO em: $raiz" -ForegroundColor Green
        Write-Host "Abra a pasta e confira se os arquivos abrem normalmente antes de redefinir." -ForegroundColor Yellow
    } else {
        Write-Host "O backup teve diferencas. Veja $log. Nao redefina ate resolver (arquivos em uso costumam ser a causa: feche programas e rode de novo)." -ForegroundColor Red
    }
}

function Passo-Redefinir {
    Write-Host ""
    Write-Host "=== REDEFINIR ESTE PC (restaura o Windows ao estado de fabrica) ===" -ForegroundColor Magenta
    Write-Host ""
    if ($script:BackupOk) { Write-Host "Backup conferido nesta sessao: $($script:BackupPath)" -ForegroundColor Green }
    else { Write-Host "Nenhum backup conferido nesta sessao." -ForegroundColor Red }
    Write-Host ""
    Write-Host "O que vai ser APAGADO do C: (tudo):" -ForegroundColor Yellow
    Write-Host "  - Todos os seus arquivos, programas e jogos"
    Write-Host "  - Todas as contas e configuracoes (precisara entrar de novo em tudo)"
    Write-Host ""
    Write-Host "Mantenha o PC ligado na tomada. Ele vai reiniciar varias vezes e demora de 30 min a 2 h."
    Write-Host ""
    if (-not (Ask-YesNo "Seus arquivos importantes estao salvos em OUTRO lugar (e voce conferiu)?")) { Write-Host "Cancelado. Faca o Passo 1 primeiro."; return }
    $t = Read-Host "Para continuar, digite exatamente REDEFINIR (Enter para cancelar)"
    if ($t -cne 'REDEFINIR') { Write-Host "Cancelado."; return }

    Write-Host ""
    Write-Host "Abrindo a ferramenta oficial do Windows. Na janela que abrir, escolha:" -ForegroundColor Cyan
    Write-Host "  1. 'Remover tudo'"
    Write-Host "  2. 'Baixar da nuvem' (recomendado, instala uma copia limpa) ou 'Reinstalar localmente'"
    Write-Host "  3. 'Limpar dados': deixe em NAO (so use SIM se for vender ou doar o PC; leva horas)"
    Write-Host "  4. Confirme em 'Redefinir'"
    Write-Host ""
    try {
        Start-Process -FilePath "systemreset.exe" -ArgumentList "-factoryreset" -ErrorAction Stop
    } catch {
        Write-Host "Nao consegui abrir direto. Abrindo Configuracoes > Recuperacao. Clique em 'Redefinir o PC'." -ForegroundColor Yellow
        Start-Process "ms-settings:recovery"
    }
}

function Menu-Megazord {
    while ($true) {
        Clear-Host
        Write-Host "=========== MEGAZORD: RESET DE FABRICA ===========" -ForegroundColor Magenta
        Write-Host ""
        Write-Host " 1) PASSO 1 - Preparar: checagens + inventario + BACKUP conferido"
        Write-Host " 2) PASSO 2 - Redefinir o PC (apaga TUDO do C:)"
        Write-Host " 3) Voltar ao menu principal"
        Write-Host ""
        if ($script:BackupOk) { Write-Host (" Backup conferido: " + $script:BackupPath) -ForegroundColor Green }
        $op = Read-Host "Escolha"
        switch ($op) {
            '1' { Passo-Preparar; Pausa }
            '2' { Passo-Redefinir; Pausa }
            '3' { return }
        }
    }
}

# ======================================================================
# SECAO 3: DETECCAO DE JOGOS INSTALADOS (qualquer launcher)
# ======================================================================
function New-Jogo($nome, $pasta, $origem, $exe, $uri) {
    return [pscustomobject]@{ Nome = $nome; Pasta = $pasta; Origem = $origem; ExeConhecido = $exe; LaunchUri = $uri }
}

function Get-SteamPaths {
    $steamPaths = @()
    $regPaths = @(
        'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam',
        'HKLM:\SOFTWARE\Valve\Steam',
        'HKCU:\Software\Valve\Steam'
    )
    foreach ($rp in $regPaths) {
        $v = Get-ItemProperty -Path $rp -ErrorAction SilentlyContinue
        if ($v) {
            if ($v.InstallPath) { $steamPaths += $v.InstallPath }
            if ($v.SteamPath) { $steamPaths += ($v.SteamPath -replace '/', '\') }
        }
    }
    $steamPaths += 'C:\Program Files (x86)\Steam', 'C:\Program Files\Steam'
    return @($steamPaths | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Sort-Object -Unique)
}

# Le o libraryfolders.vdf para achar TODAS as bibliotecas (outros discos/pastas)
function Get-SteamLibraries {
    $libs = @()
    foreach ($sp in @(Get-SteamPaths)) {
        $libs += $sp
        $vdf = Join-Path $sp 'steamapps\libraryfolders.vdf'
        if (Test-Path -LiteralPath $vdf) {
            $conteudo = Get-Content -LiteralPath $vdf -Raw -ErrorAction SilentlyContinue
            if ($conteudo) {
                foreach ($x in [regex]::Matches($conteudo, '"path"\s*"([^"]+)"')) {
                    $p = $x.Groups[1].Value -replace '\\\\', '\'
                    if (Test-Path -LiteralPath $p) { $libs += $p }
                }
            }
        }
    }
    return @($libs | Sort-Object -Unique)
}

function Get-SteamGames {
    $jogos = @()
    foreach ($lib in @(Get-SteamLibraries)) {
        $common = Join-Path $lib 'steamapps\common'
        $pastaManifests = Join-Path $lib 'steamapps'
        $acfs = @(Get-ChildItem -Path $pastaManifests -Filter 'appmanifest_*.acf' -File -ErrorAction SilentlyContinue)
        foreach ($a in $acfs) {
            $txt = Get-Content -LiteralPath $a.FullName -Raw -ErrorAction SilentlyContinue
            if (-not $txt) { continue }
            $nome = [regex]::Match($txt, '"name"\s*"([^"]+)"').Groups[1].Value
            $instdir = [regex]::Match($txt, '"installdir"\s*"([^"]+)"').Groups[1].Value
            $appid = [regex]::Match($txt, '"appid"\s*"(\d+)"').Groups[1].Value
            if (-not $instdir) { continue }
            $pasta = Join-Path $common $instdir
            if (-not (Test-Path -LiteralPath $pasta)) { continue }
            if (-not $nome) { $nome = $instdir }
            if ($nome -match 'Steamworks Common Redistributables|Steam Linux Runtime|^Proton') { continue }
            $uri = $null
            if ($appid) { $uri = ("steam://rungameid/" + $appid) }
            $jogos += (New-Jogo $nome $pasta 'Steam' $null $uri)
        }
    }
    return $jogos
}

function Get-EpicGames {
    $manifestDir = "$env:ProgramData\Epic\EpicGamesLauncher\Data\Manifests"
    $jogos = @()
    if (Test-Path -LiteralPath $manifestDir) {
        $itens = @(Get-ChildItem -Path $manifestDir -Filter '*.item' -File -ErrorAction SilentlyContinue)
        foreach ($it in $itens) {
            try {
                $j = Get-Content -LiteralPath $it.FullName -Raw | ConvertFrom-Json
                if ($j.InstallLocation -and -not $j.bIsIncompleteInstall -and (Test-Path -LiteralPath $j.InstallLocation)) {
                    $exe = $null
                    if ($j.LaunchExecutable) {
                        $candidato = Join-Path $j.InstallLocation $j.LaunchExecutable
                        if (Test-Path -LiteralPath $candidato) { $exe = $candidato }
                    }
                    $jogos += (New-Jogo $j.DisplayName $j.InstallLocation 'Epic' $exe $null)
                }
            } catch { }
        }
    }
    return $jogos
}

# GOG, Ubisoft Connect, EA, Rockstar, Riot, Minecraft, Xbox/Game Pass e pastas extras do usuario
function Get-OutrosLaunchers {
    $jogos = @()

    foreach ($s in @(Get-ChildItem -Path 'HKLM:\SOFTWARE\WOW6432Node\GOG.com\Games' -ErrorAction SilentlyContinue)) {
        $p = Get-ItemProperty -Path $s.PSPath -ErrorAction SilentlyContinue
        if ($p -and $p.path -and (Test-Path -LiteralPath $p.path)) {
            $exe = $null
            if ($p.exe -and (Test-Path -LiteralPath $p.exe)) { $exe = $p.exe }
            $nome = $p.gameName
            if (-not $nome) { $nome = Split-Path $p.path -Leaf }
            $jogos += (New-Jogo $nome $p.path 'GOG' $exe $null)
        }
    }

    foreach ($s in @(Get-ChildItem -Path 'HKLM:\SOFTWARE\WOW6432Node\Ubisoft\Launcher\Installs' -ErrorAction SilentlyContinue)) {
        $p = Get-ItemProperty -Path $s.PSPath -ErrorAction SilentlyContinue
        if ($p -and $p.InstallDir) {
            $dir = ([string]$p.InstallDir -replace '/', '\').TrimEnd('\')
            if (Test-Path -LiteralPath $dir) { $jogos += (New-Jogo (Split-Path $dir -Leaf) $dir 'Ubisoft' $null $null) }
        }
    }

    foreach ($raiz in @('HKLM:\SOFTWARE\WOW6432Node\EA Games', 'HKLM:\SOFTWARE\EA Games')) {
        foreach ($s in @(Get-ChildItem -Path $raiz -ErrorAction SilentlyContinue)) {
            $p = Get-ItemProperty -Path $s.PSPath -ErrorAction SilentlyContinue
            $dir = $null
            if ($p) { $dir = [string]$p.'Install Dir' }
            if ($dir) {
                $dir = $dir.TrimEnd('\')
                if (Test-Path -LiteralPath $dir) { $jogos += (New-Jogo $s.PSChildName $dir 'EA' $null $null) }
            }
        }
    }

    foreach ($s in @(Get-ChildItem -Path 'HKLM:\SOFTWARE\WOW6432Node\Rockstar Games' -ErrorAction SilentlyContinue)) {
        if ($s.PSChildName -match 'Launcher|Social Club') { continue }
        $p = Get-ItemProperty -Path $s.PSPath -ErrorAction SilentlyContinue
        if ($p -and $p.InstallFolder) {
            $dir = ([string]$p.InstallFolder).TrimEnd('\')
            if (Test-Path -LiteralPath $dir) { $jogos += (New-Jogo $s.PSChildName $dir 'Rockstar' $null $null) }
        }
    }

    foreach ($dk in (Get-DiscosFixos)) {
        $rg = $dk.DeviceID + '\Riot Games'
        if (Test-Path -LiteralPath $rg) {
            foreach ($g in @(Get-ChildItem -LiteralPath $rg -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne 'Riot Client' })) {
                $exe = $null
                $le = Join-Path $g.FullName 'LeagueClient.exe'
                if (Test-Path -LiteralPath $le) { $exe = $le }
                $jogos += (New-Jogo $g.Name $g.FullName 'Riot' $exe $null)
            }
        }
        $xg = $dk.DeviceID + '\XboxGames'
        if (Test-Path -LiteralPath $xg) {
            foreach ($g in @(Get-ChildItem -LiteralPath $xg -Directory -ErrorAction SilentlyContinue)) {
                $pasta = Join-Path $g.FullName 'Content'
                if (-not (Test-Path -LiteralPath $pasta)) { $pasta = $g.FullName }
                $jogos += (New-Jogo $g.Name $pasta 'Xbox/Game Pass' $null $null)
            }
        }
    }

    foreach ($mc in @("${env:ProgramFiles(x86)}\Minecraft Launcher\MinecraftLauncher.exe", "$env:ProgramFiles\Minecraft Launcher\MinecraftLauncher.exe")) {
        if (Test-Path -LiteralPath $mc) {
            $jogos += (New-Jogo 'Minecraft (Java)' (Split-Path $mc -Parent) 'Minecraft' $mc $null)
            break
        }
    }

    $cfg = Load-Config
    foreach ($pf in @($cfg.PastasJogos)) {
        if (-not $pf -or -not (Test-Path -LiteralPath $pf)) { continue }
        $subs = @(Get-ChildItem -LiteralPath $pf -Directory -ErrorAction SilentlyContinue)
        if ($subs.Count -gt 0) {
            foreach ($sb in $subs) { $jogos += (New-Jogo $sb.Name $sb.FullName 'Pasta extra' $null $null) }
        } else {
            $jogos += (New-Jogo (Split-Path $pf -Leaf) $pf 'Pasta extra' $null $null)
        }
    }
    return $jogos
}

# Procura o .exe principal (ate 3 niveis de profundidade, ignorando instaladores/anticheats)
function Find-JogoExe($pasta, $nomeJogo) {
    $ignorar = 'vcredist|unitycrashhandler|ue4prereq|ue5prereq|directx|dxsetup|unins|crashreportclient|battleye|easyanticheat|vc_redist|dotnet|redist|setup|installer|helper|service|updater|crashpad|cef_sub|steamservice|steamerrorreporter|rankingsystem|launcher_cef|webhelper|notification'
    $exes = @(Get-ChildItem -LiteralPath $pasta -Recurse -Depth 3 -Filter '*.exe' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch $ignorar })
    if ($exes.Count -eq 0) { return $null }

    $alvoNome = ($nomeJogo -replace '[^a-zA-Z0-9]', '').ToLower()
    $porNome = @($exes | Where-Object { ($_.BaseName -replace '[^a-zA-Z0-9]', '').ToLower() -eq $alvoNome })
    if ($porNome.Count -gt 0) { return $porNome[0].FullName }

    $naRaiz = @($exes | Where-Object { $_.DirectoryName -eq $pasta })
    if ($naRaiz.Count -gt 0) { return ($naRaiz | Sort-Object Length -Descending | Select-Object -First 1).FullName }

    return ($exes | Sort-Object Length -Descending | Select-Object -First 1).FullName
}

function Get-JogosInstalados {
    if ($script:JogosCache) { return $script:JogosCache }
    $todos = @()

    $fivemExe = Join-Path $env:LOCALAPPDATA 'FiveM\FiveM.exe'
    if (Test-Path -LiteralPath $fivemExe) {
        $todos += (New-Jogo 'FiveM' (Split-Path $fivemExe -Parent) 'FiveM' $fivemExe $null)
    }

    Write-Host "  Procurando jogos da Steam (todas as bibliotecas/discos)..." -ForegroundColor DarkGray
    $todos += @(Get-SteamGames)

    Write-Host "  Procurando jogos da Epic Games..." -ForegroundColor DarkGray
    $todos += @(Get-EpicGames)

    Write-Host "  Procurando GOG, Ubisoft, EA, Rockstar, Riot, Xbox/Game Pass, Minecraft e suas pastas extras..." -ForegroundColor DarkGray
    $todos += @(Get-OutrosLaunchers)

    $script:JogosCache = @($todos | Sort-Object Nome -Unique)
    return $script:JogosCache
}

# ======================================================================
# SECAO 4: TEMPERATURA E REFRIGERACAO
# O software NAO esfria o hardware: ele reduz o CALOR GERADO (turbo, FPS,
# processos). A parte fisica (poeira, fluxo de ar, pasta termica) e outra.
# ======================================================================
$script:SubProc  = '54533251-82be-4824-96c1-47b60b740d00'   # SUB_PROCESSOR
$script:SetMax   = 'bc5038f7-23e0-4960-96da-33abaf5935ec'   # PROCTHROTTLEMAX
$script:SetMin   = '893dee8e-2bef-41e0-89c6-b55d0929964c'   # PROCTHROTTLEMIN
$script:SetBoost = 'be337238-0d82-4146-a960-4f3749d470c7'   # PERFBOOSTMODE
$script:SetCool  = '94d3a615-a899-4ac5-ae2b-e4d8f634367f'   # SYSCOOLPOL

function Get-GuidFromText($t) {
    if ([string]$t -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') { return $Matches[1] }
    return $null
}

function Get-EsquemaAtivo {
    return (Get-GuidFromText ((& powercfg.exe /getactivescheme) -join ' '))
}

function Test-EsquemaExiste($guid) {
    if (-not $guid) { return $false }
    return ((((& powercfg.exe /list) -join ' ') -match [regex]::Escape([string]$guid)))
}

function Get-CfgPerfil($perfil, $maxPct) {
    if ($perfil -eq 'Maximo') { return @{ Max = 100; Min = 5; Boost = 2; Cool = 1; Epp = 0; Park = 100; Agressivo = $true; Nome = 'PC-Toolkit Maximo FPS' } }
    if ($perfil -eq 'Frio') { return @{ Max = 99; Min = 5; Boost = 0; Cool = 1; Nome = 'PC-Toolkit Equilibrado Frio' } }
    if ($perfil -eq 'Silencioso') { return @{ Max = 70; Min = 5; Boost = 0; Cool = 1; Nome = 'PC-Toolkit Silencioso' } }
    $m = 100
    if ($maxPct -ge 30 -and $maxPct -le 100) { $m = [int]$maxPct }
    return @{ Max = $m; Min = 5; Boost = 0; Cool = 1; Nome = ('PC-Toolkit Personalizado ' + $m + '%') }
}

function Set-ValoresEsquema($guid, $cfg) {
    foreach ($modo in @('/setacvalueindex', '/setdcvalueindex')) {
        & powercfg.exe $modo $guid $script:SubProc $script:SetMax $cfg.Max 2>&1 | Out-Null
        & powercfg.exe $modo $guid $script:SubProc $script:SetMin $cfg.Min 2>&1 | Out-Null
        & powercfg.exe $modo $guid $script:SubProc $script:SetBoost $cfg.Boost 2>&1 | Out-Null
        & powercfg.exe $modo $guid $script:SubProc $script:SetCool $cfg.Cool 2>&1 | Out-Null
    }
    # so na tomada: preferencia de desempenho, sem estacionar nucleos, PCIe e USB sem economia de energia
    if ($null -ne $cfg.Epp) { & powercfg.exe /setacvalueindex $guid $script:SubProc $script:SetEpp $cfg.Epp 2>&1 | Out-Null }
    if ($null -ne $cfg.Park) { & powercfg.exe /setacvalueindex $guid $script:SubProc $script:SetPark $cfg.Park 2>&1 | Out-Null }
    if ($cfg.Agressivo) {
        & powercfg.exe /setacvalueindex $guid $script:SubPcie $script:SetAspm 0 2>&1 | Out-Null
        & powercfg.exe /setacvalueindex $guid $script:SubUsb $script:SetUsbSusp 0 2>&1 | Out-Null
    }
}

# Cria um plano de energia NOVO (copia do Alto Desempenho) sem mexer nos seus planos existentes.
# Retorna o GUID, ou $null se o Windows nao deixar.
function New-EsquemaPerfil($perfil, $maxPct) {
    $cfg = Get-CfgPerfil $perfil $maxPct
    $guid = $null
    foreach ($base in @('SCHEME_MIN', 'e9a42b02-d5df-448d-aa00-03f14749eb61', 'SCHEME_BALANCED')) {
        $out = (& powercfg.exe /duplicatescheme $base 2>&1) -join ' '
        if ($LASTEXITCODE -eq 0) { $guid = Get-GuidFromText $out }
        if ($guid) { break }
    }
    if (-not $guid) { return $null }
    & powercfg.exe /changename $guid $cfg.Nome 'Criado pelo PC Toolkit' 2>&1 | Out-Null
    Set-ValoresEsquema $guid $cfg
    return $guid
}

function Load-EnergiaEstado {
    if (Test-Path -LiteralPath $script:EnergiaFile) {
        try { return (Get-Content -LiteralPath $script:EnergiaFile -Raw | ConvertFrom-Json) } catch { }
    }
    return $null
}
function Save-EnergiaEstado($o) {
    try { ConvertTo-Json -InputObject $o | Set-Content -LiteralPath $script:EnergiaFile -Encoding UTF8 } catch { }
}

# Aplica o perfil de forma persistente (ate voce restaurar). Retorna $true/$false.
function Aplicar-PerfilTermico($perfil, $maxPct) {
    $estado = Load-EnergiaEstado
    $orig = $null
    if ($estado -and $estado.Original -and (Test-EsquemaExiste $estado.Original)) { $orig = [string]$estado.Original }
    else { $orig = Get-EsquemaAtivo }

    $guid = $null
    if ($estado -and $estado.Criado -and (Test-EsquemaExiste $estado.Criado)) {
        $guid = [string]$estado.Criado
        $cfg = Get-CfgPerfil $perfil $maxPct
        Set-ValoresEsquema $guid $cfg
        & powercfg.exe /changename $guid $cfg.Nome 'Criado pelo PC Toolkit' 2>&1 | Out-Null
    } else {
        $guid = New-EsquemaPerfil $perfil $maxPct
    }
    if (-not $guid) { Show-Aviso "Nao consegui criar o plano de energia (o Windows bloqueou)."; return $false }

    & powercfg.exe /setactive $guid 2>&1 | Out-Null
    Save-EnergiaEstado ([pscustomobject]@{ Original = $orig; Criado = $guid; Perfil = $perfil; MaxPct = [int]$maxPct })
    Write-Log ("Perfil termico aplicado: " + $perfil)
    return $true
}

function Restaurar-EsquemaOriginal {
    $feito = $false
    $estado = Load-EnergiaEstado
    if ($estado) {
        $alvo = [string]$estado.Original
        if (-not (Test-EsquemaExiste $alvo)) { $alvo = 'SCHEME_BALANCED' }
        & powercfg.exe /setactive $alvo 2>&1 | Out-Null
        if ($estado.Criado) { & powercfg.exe /delete ([string]$estado.Criado) 2>&1 | Out-Null }
        Remove-Item -LiteralPath $script:EnergiaFile -Force -ErrorAction SilentlyContinue
        $feito = $true
    }
    # formato antigo (versao 1): arquivo com o GUID do plano original
    if (Test-Path -LiteralPath $script:PowerFile) {
        $g = (Get-Content -LiteralPath $script:PowerFile -Raw).Trim()
        & powercfg.exe /setactive $g 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { & powercfg.exe /setactive SCHEME_BALANCED 2>&1 | Out-Null }
        Remove-Item -LiteralPath $script:PowerFile -Force -ErrorAction SilentlyContinue
        $feito = $true
    }
    return $feito
}

function Escolher-PerfilTermico {
    Write-Host ""
    Write-Host "Escolha o perfil de energia e temperatura:" -ForegroundColor Cyan
    Write-Host " 1) MAXIMO FPS       - turbo agressivo, nucleos sempre acordados, PCIe/USB sem economia. E o mais quente."
    Write-Host " 2) EQUILIBRADO FRIO - turbo desligado (CPU limitada a 99%). Costuma baixar bastante a temperatura"
    Write-Host "                       com pouca perda de FPS em jogos que dependem da placa de video."
    Write-Host " 3) SILENCIOSO       - CPU limitada a 70%. Bem mais fria e silenciosa; o FPS cai em jogos pesados."
    Write-Host " 4) PERSONALIZADO    - voce define o limite da CPU (30 a 100%)."
    Write-Host " 5) Nao mexer"
    $op = Read-Host "Escolha (Enter = 1)"
    switch ($op) {
        '2' { return [pscustomobject]@{ Perfil = 'Frio'; MaxPct = 99 } }
        '3' { return [pscustomobject]@{ Perfil = 'Silencioso'; MaxPct = 70 } }
        '4' {
            $m = Read-Host "Limite maximo da CPU em % (30 a 100)"
            if ($m -notmatch '^\d+$') { return $null }
            return [pscustomobject]@{ Perfil = 'Custom'; MaxPct = [int]$m }
        }
        '5' { return $null }
        default { return [pscustomobject]@{ Perfil = 'Maximo'; MaxPct = 100 } }
    }
}

function Menu-AplicarPerfil {
    $esc = Escolher-PerfilTermico
    if (-not $esc) { Write-Host "Nada alterado."; return }
    if (Aplicar-PerfilTermico $esc.Perfil $esc.MaxPct) {
        Show-Ok ("Perfil aplicado: {0} (CPU maxima {1}%)" -f $esc.Perfil, $esc.MaxPct)
        Write-Host "  Para voltar ao seu plano de energia original use a opcao 4 deste menu." -ForegroundColor DarkGray
        Write-Host "  Dica: abra o Monitor ao vivo (opcao 2) e rode um jogo para comparar as temperaturas." -ForegroundColor DarkGray
    }
}

# Le o valor atual (rede/bateria) de uma configuracao do plano ativo. Independe do idioma.
function Get-ValorEsquema($setGuid) {
    $txt = (& powercfg.exe /query SCHEME_CURRENT $script:SubProc $setGuid) -join "`n"
    $m = [regex]::Matches($txt, '0x[0-9a-fA-F]{8}')
    if ($m.Count -ge 2) {
        return @{ AC = [Convert]::ToInt32($m[$m.Count - 2].Value, 16); DC = [Convert]::ToInt32($m[$m.Count - 1].Value, 16) }
    }
    return $null
}

# ---------------- sensores ----------------
function Get-NvidiaSmiPath {
    foreach ($p in @("$env:SystemRoot\System32\nvidia-smi.exe", "$env:ProgramFiles\NVIDIA Corporation\NVSMI\nvidia-smi.exe")) {
        if (Test-Path -LiteralPath $p) { return $p }
    }
    $c = Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    return $null
}

function Get-GpuNvidia {
    $exe = Get-NvidiaSmiPath
    if (-not $exe) { return $null }
    try {
        $linha = (& $exe --query-gpu=name,temperature.gpu,utilization.gpu,fan.speed,power.draw,clocks.sm --format=csv,noheader,nounits 2>$null | Select-Object -First 1)
        if (-not $linha) { return $null }
        $p = @(([string]$linha) -split ',\s*')
        if ($p.Count -lt 6) { return $null }
        return [pscustomobject]@{ Nome = $p[0]; Temp = $p[1]; Uso = $p[2]; Fan = $p[3]; Watts = $p[4]; Clock = $p[5] }
    } catch { return $null }
}

# Usa classes WMI (nomes iguais em qualquer idioma do Windows, ao contrario dos contadores de desempenho)
function Get-Termica {
    $r = [pscustomobject]@{ TempC = $null; LimitePassivo = $null; LimiteCpu = $null; FreqMHz = $null; UsoCpu = $null; Throttle = $false; Fonte = '' }
    $temps = @()
    $lims = @()
    try {
        foreach ($z in @(Get-CimInstance -ClassName Win32_PerfFormattedData_Counters_ThermalZoneInformation -ErrorAction Stop)) {
            $k = $null
            if ($z.HighPrecisionTemperature -gt 0) { $k = [double]$z.HighPrecisionTemperature / 10 }
            elseif ($z.Temperature -gt 0) { $k = [double]$z.Temperature }
            if ($k) {
                $c = [math]::Round($k - 273.15, 1)
                if ($c -gt 5 -and $c -lt 125) { $temps += $c }
            }
            if ($null -ne $z.PercentPassiveLimit) { $lims += [int]$z.PercentPassiveLimit }
        }
    } catch { }
    if ($temps.Count -gt 0) { $r.Fonte = 'ThermalZone' }
    else {
        try {
            foreach ($z in @(Get-CimInstance -Namespace 'root/wmi' -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction Stop)) {
                $c = [math]::Round(([double]$z.CurrentTemperature / 10) - 273.15, 1)
                if ($c -gt 5 -and $c -lt 125) { $temps += $c }
            }
        } catch { }
        if ($temps.Count -gt 0) { $r.Fonte = 'ACPI' }
    }
    if ($temps.Count -gt 0) { $r.TempC = ($temps | Measure-Object -Maximum).Maximum }
    if ($lims.Count -gt 0) { $r.LimitePassivo = ($lims | Measure-Object -Minimum).Minimum }

    try {
        $proc = Get-CimInstance -ClassName Win32_PerfFormattedData_Counters_ProcessorInformation -Filter "Name='_Total'" -ErrorAction Stop | Select-Object -First 1
        if ($proc) {
            if ($proc.ProcessorFrequency) { $r.FreqMHz = [int]$proc.ProcessorFrequency }
            if ($null -ne $proc.PercentProcessorTime) { $r.UsoCpu = [int]$proc.PercentProcessorTime }
            if ($null -ne $proc.PercentPerformanceLimit) { $r.LimiteCpu = [int]$proc.PercentPerformanceLimit }
        }
    } catch { }
    if (-not $r.FreqMHz) {
        $w = Get-CimInstance Win32_Processor | Select-Object -First 1
        if ($w -and $w.CurrentClockSpeed) { $r.FreqMHz = [int]$w.CurrentClockSpeed }
    }
    # throttling TERMICO = o Windows esta limitando a CPU por calor (passive cooling)
    if ($null -ne $r.LimitePassivo -and $r.LimitePassivo -lt 100) { $r.Throttle = $true }
    return $r
}

function Get-CorTemp($c) {
    if ($null -eq $c) { return 'Gray' }
    if ($c -ge 90) { return 'Red' }
    if ($c -ge 80) { return 'Yellow' }
    return 'Green'
}

function Format-LinhaTermica($t, $gpu) {
    $p = @()
    if ($null -ne $t.TempC) { $p += ("CPU {0:N0} C" -f $t.TempC) } else { $p += "CPU temp n/d" }
    if ($null -ne $t.UsoCpu) { $p += ("uso {0}%" -f $t.UsoCpu) }
    if ($t.FreqMHz) { $p += ("{0} MHz" -f $t.FreqMHz) }
    if ($null -ne $t.LimitePassivo -and $t.LimitePassivo -lt 100) { $p += ("LIMITADA POR CALOR ({0}%)" -f $t.LimitePassivo) }
    if ($gpu) { $p += ("GPU {0} C, {1}% uso, ventoinha {2}%, {3} W" -f $gpu.Temp, $gpu.Uso, $gpu.Fan, $gpu.Watts) }
    return ($p -join '  |  ')
}

function Get-TopCpu($qtd) {
    $nproc = [int]$env:NUMBER_OF_PROCESSORS
    if ($nproc -lt 1) { $nproc = 1 }
    $lista = @(Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne '_Total' -and $_.Name -ne 'Idle' } |
        Sort-Object PercentProcessorTime -Descending | Select-Object -First $qtd)
    $saida = @()
    foreach ($x in $lista) {
        $saida += [pscustomobject]@{ Nome = ($x.Name -replace '#\d+$', ''); Uso = [math]::Round(([double]$x.PercentProcessorTime / $nproc), 1) }
    }
    return $saida
}

# ---------------- diagnostico ----------------
function Diagnostico-Termico {
    Write-Host ""
    Write-Host "=== DIAGNOSTICO TERMICO ===" -ForegroundColor Cyan
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    if ($cpu) { Write-Host ("CPU: {0}" -f $cpu.Name.Trim()) }
    $notebook = [bool](Get-CimInstance Win32_Battery)
    if ($notebook) { Write-Host "Tipo: notebook (bateria detectada)" } else { Write-Host "Tipo: desktop (sem bateria)" }

    Write-Host ""
    Write-Host "Agora:" -ForegroundColor Yellow
    $t = Get-Termica
    $gpu = Get-GpuNvidia
    Write-Host ("  " + (Format-LinhaTermica $t $gpu)) -ForegroundColor (Get-CorTemp $t.TempC)
    if ($null -eq $t.TempC) {
        Write-Host "  Este PC nao informa temperatura ao Windows. Use HWiNFO64 ou Core Temp (gratis) para ver as reais." -ForegroundColor Yellow
    } else {
        Write-Host ("  Fonte do sensor: {0}. Se o numero nunca muda, o sensor da placa-mae nao e confiavel: use HWiNFO64." -f $t.Fonte) -ForegroundColor DarkGray
        Write-Host "  Referencia: ate ~60 C parado e normal | ate ~80 C jogando e bom | 80-90 C quente | 95+ C throttling." -ForegroundColor DarkGray
    }
    if ($t.Throttle) { Show-Aviso "O Windows esta LIMITANDO a CPU por causa do calor agora (throttling). Isso derruba o FPS." }

    Write-Host ""
    Write-Host "Plano de energia ativo:" -ForegroundColor Yellow
    Write-Host ("  " + ((& powercfg.exe /getactivescheme) -join ' '))
    $mx = Get-ValorEsquema $script:SetMax
    $bo = Get-ValorEsquema $script:SetBoost
    $co = Get-ValorEsquema $script:SetCool
    if ($mx) { Write-Host ("  Limite maximo da CPU: {0}% (na tomada) | {1}% (bateria)" -f $mx.AC, $mx.DC) }
    $nomesBoost = @('Desativado', 'Ativado', 'Agressivo', 'Eficiente ativado', 'Eficiente agressivo', 'Agressivo (garantido)', 'Eficiente agressivo (garantido)')
    if ($bo) {
        $nb = 'desconhecido'
        if ($bo.AC -ge 0 -and $bo.AC -lt $nomesBoost.Count) { $nb = $nomesBoost[$bo.AC] }
        Write-Host ("  Modo turbo (na tomada): {0}" -f $nb)
    }
    if ($co) {
        $nc = 'Passiva (reduz a CPU antes de ligar a ventoinha)'
        if ($co.AC -eq 1) { $nc = 'Ativa (liga a ventoinha primeiro)' }
        Write-Host ("  Politica de refrigeracao (na tomada): {0}" -f $nc)
    }

    Write-Host ""
    Write-Host "Quem mais esta usando a CPU agora:" -ForegroundColor Yellow
    foreach ($x in @(Get-TopCpu 6)) { Write-Host ("  {0,5}%  {1}" -f $x.Uso, $x.Nome) }

    $discos = @(Get-PhysicalDisk -ErrorAction SilentlyContinue)
    $linhasDisco = @()
    foreach ($pd in $discos) {
        $ctr = $pd | Get-StorageReliabilityCounter -ErrorAction SilentlyContinue
        if ($ctr -and $ctr.Temperature) { $linhasDisco += ("  {0}: {1} C" -f $pd.FriendlyName, $ctr.Temperature) }
    }
    if ($linhasDisco.Count -gt 0) {
        Write-Host ""
        Write-Host "Temperatura dos discos:" -ForegroundColor Yellow
        foreach ($ld in $linhasDisco) { Write-Host $ld }
    }

    Write-Host ""
    Write-Host "O que fazer:" -ForegroundColor Cyan
    $dicas = 0
    if ($t.Throttle -or ($null -ne $t.TempC -and $t.TempC -ge 85)) {
        Write-Host "  - Esta quente: use o perfil EQUILIBRADO FRIO (opcao 3 deste menu) e limite o FPS dos jogos."; $dicas++
        Write-Host "  - Limpe a poeira das ventoinhas e confira o fluxo de ar (opcao 6 tem o passo a passo)."; $dicas++
    }
    if ($bo -and $bo.AC -ge 2 -and $null -ne $t.TempC -and $t.TempC -ge 75) {
        Write-Host "  - O turbo esta em modo agressivo e a temperatura ja esta alta mesmo sem carga total."; $dicas++
    }
    if ($notebook) { Write-Host "  - Notebook: use sobre superficie dura, eleve a traseira ou use cooler pad; nunca em cama/sofa."; $dicas++ }
    if ($dicas -eq 0) { Write-Host "  - Tudo certo por aqui. Rode o Teste de estresse (opcao 5) para ver como a refrigeracao se comporta sob carga." }
}

# ---------------- monitor ao vivo ----------------
function Monitor-Temperatura {
    Clear-Host
    Write-Host "=== MONITOR DE TEMPERATURA AO VIVO ===" -ForegroundColor Cyan
    Write-Host "Atualiza a cada 2 segundos. Pressione Q para sair." -ForegroundColor DarkGray
    Write-Host "Dica: deixe aberto, inicie um jogo e veja se a CPU passa de 90 C ou aparece 'LIMITADA POR CALOR'." -ForegroundColor DarkGray
    Write-Host ""
    $nv = Get-NvidiaSmiPath
    if ($nv) { Write-Host "GPU NVIDIA detectada (nvidia-smi): mostrando temperatura/uso/ventoinha da placa." -ForegroundColor DarkGray }
    $pico = 0
    $amostras = 0
    $iguais = 0
    $ultima = $null
    $avisou = $false
    while ($true) {
        $t = Get-Termica
        $g = $null
        if ($nv) { $g = Get-GpuNvidia }
        $linhaM = Format-LinhaTermica $t $g
        if (-not $g) {
            $ga = Get-AmostraGpu $null
            if ($null -ne $ga.Uso3D) { $linhaM += ("  |  GPU {0}% uso" -f $ga.Uso3D); if ($ga.VramMB) { $linhaM += (", VRAM {0} MB" -f $ga.VramMB) } }
        }
        $nmx = Get-NucleoMax
        if ($null -ne $nmx) { $linhaM += ("  |  nucleo mais usado {0}%" -f $nmx) }
        Write-Host ((Get-Date -Format 'HH:mm:ss') + "  " + $linhaM) -ForegroundColor (Get-CorTemp $t.TempC)
        if ($null -ne $t.TempC) {
            $amostras++
            if ($t.TempC -gt $pico) { $pico = $t.TempC }
            if ($null -ne $ultima -and $ultima -eq $t.TempC) { $iguais++ } else { $iguais = 0 }
            $ultima = $t.TempC
            if ($iguais -ge 12 -and -not $avisou) {
                $avisou = $true
                Write-Host "  (o valor da CPU nao muda: o sensor desta placa-mae pode ser fixo; para temperatura real use HWiNFO64)" -ForegroundColor Yellow
            }
        }
        $sair = $false
        try {
            if ([Console]::KeyAvailable) {
                $k = [Console]::ReadKey($true)
                if ($k.Key -eq 'Q') { $sair = $true }
            }
        } catch { }
        if ($sair) { break }
        Start-Sleep -Seconds 2
    }
    Write-Host ""
    if ($amostras -gt 0) { Write-Host ("Pico de temperatura da CPU nesta sessao: {0:N0} C" -f $pico) -ForegroundColor Cyan }
}

# ---------------- teste de estresse ----------------
function Teste-Estresse {
    Write-Host ""
    Write-Host "=== TESTE DE ESTRESSE DA CPU ===" -ForegroundColor Cyan
    Write-Host "Coloca TODOS os nucleos da CPU em 100% por alguns segundos e mede se a refrigeracao aguenta."
    Write-Host "Se a temperatura chegar a 97 C o teste para sozinho. Salve seus trabalhos antes."
    Write-Host "Compare rodando antes e depois de limpar a poeira ou trocar o perfil termico." -ForegroundColor DarkGray
    $dur = Read-Host "Duracao em segundos (Enter = 60; minimo 20, maximo 300)"
    if ($dur -notmatch '^\d+$') { $dur = 60 }
    $dur = [int]$dur
    if ($dur -lt 20) { $dur = 20 }
    if ($dur -gt 300) { $dur = 300 }
    if (-not (Ask-YesNo "Iniciar o teste de $dur segundos?")) { Write-Host "Cancelado."; return }
    if (-not (Initialize-Tipos)) { Show-Aviso "Nao consegui preparar o teste neste PC."; return }

    $limite = 97
    $nproc = [int]$env:NUMBER_OF_PROCESSORS
    if ($nproc -lt 1) { $nproc = 2 }
    $antes = Get-Termica
    Write-Host ("Antes: " + (Format-LinhaTermica $antes $null)) -ForegroundColor DarkGray
    Write-Host ""

    $amostras = @()
    $abortou = $false
    [PCToolkit.Nativo]::IniciarCarga($nproc)
    try {
        $fim = (Get-Date).AddSeconds($dur)
        while ((Get-Date) -lt $fim) {
            Start-Sleep -Seconds 2
            $t = Get-Termica
            $amostras += $t
            $resta = [int]($fim - (Get-Date)).TotalSeconds
            Write-Host ("  {0,3}s  {1}" -f $resta, (Format-LinhaTermica $t $null)) -ForegroundColor (Get-CorTemp $t.TempC)
            if ($null -ne $t.TempC -and $t.TempC -ge $limite) { $abortou = $true; break }
        }
    } finally {
        [PCToolkit.Nativo]::PararCarga()
    }
    Start-Sleep -Seconds 1

    $temps = @($amostras | Where-Object { $null -ne $_.TempC } | ForEach-Object { $_.TempC })
    $freqs = @($amostras | Where-Object { $_.FreqMHz } | ForEach-Object { $_.FreqMHz })
    $pico = $null
    if ($temps.Count -gt 0) { $pico = ($temps | Measure-Object -Maximum).Maximum }
    $queda = $null
    if ($freqs.Count -ge 8) {
        $ini = ($freqs | Select-Object -First 3 | Measure-Object -Average).Average
        $fimF = ($freqs | Select-Object -Last 3 | Measure-Object -Average).Average
        if ($ini -gt 0) { $queda = [math]::Round((($ini - $fimF) / $ini) * 100, 0) }
    }
    $limitou = @($amostras | Where-Object { $_.Throttle }).Count

    Write-Host ""
    Write-Host "=== RESULTADO ===" -ForegroundColor Cyan
    if ($null -ne $pico) { Write-Host ("  Pico de temperatura: {0:N0} C" -f $pico) -ForegroundColor (Get-CorTemp $pico) }
    if ($null -ne $queda) { Write-Host ("  Variacao da frequencia (inicio -> fim): {0}%" -f (-$queda)) }
    if ($abortou) {
        Show-Aviso "Teste interrompido por seguranca: a CPU chegou a $limite C. A refrigeracao esta insuficiente para carga total."
        Write-Host "  Faca limpeza fisica (poeira/pasta termica) e use o perfil EQUILIBRADO FRIO ou SILENCIOSO." -ForegroundColor Yellow
    } elseif ($limitou -gt 0 -or ($null -ne $queda -and $queda -ge 15)) {
        Show-Aviso "Houve queda de desempenho durante o teste (throttling ou perda de turbo)."
        Write-Host "  Se voce usa o perfil FRIO/SILENCIOSO, parte dessa queda e esperada (turbo desligado de proposito)." -ForegroundColor DarkGray
        Write-Host "  Se esta no MAXIMO FPS, a refrigeracao nao sustenta o turbo: limpe o PC e limite o FPS dos jogos." -ForegroundColor Yellow
    } else {
        Show-Ok "A CPU manteve o desempenho durante todo o teste. Refrigeracao adequada para esse uso."
    }
    if ($null -eq $pico) { Write-Host "  (Sem leitura de temperatura neste PC: so a frequencia foi analisada. Use HWiNFO64 para temperaturas reais.)" -ForegroundColor DarkGray }
    Write-Log ("Teste de estresse: pico={0} queda={1}" -f $pico, $queda)
}

function Dicas-Refrigeracao {
    Write-Host ""
    Write-Host "=== DICAS DE REFRIGERACAO (o que realmente baixa a temperatura) ===" -ForegroundColor Cyan
    Write-Host "O script reduz o CALOR GERADO (turbo, FPS, processos). A causa fisica so se resolve na parte fisica."
    Write-Host "Em ordem de efeito:" -ForegroundColor Yellow
    Write-Host " 1. LIMPAR A POEIRA: ar comprimido ou pincel nas saidas de ar, ventoinhas e dissipador (a cada 3-6 meses)."
    Write-Host "    Costuma ser o maior ganho. Segure a ventoinha parada ao soprar para nao girar demais."
    Write-Host " 2. NOTEBOOK: superficie dura e plana (nunca cama/sofa), traseira elevada ou cooler pad, entradas de ar livres."
    Write-Host " 3. DESKTOP: fluxo de ar frente/baixo entrando e traseira/cima saindo; cabos organizados; gabinete longe da parede."
    Write-Host " 4. PASTA TERMICA: se o PC tem mais de 2-3 anos e esquenta muito rapido, trocar a pasta pode ajudar bastante."
    Write-Host " 5. LIMITE O FPS: jogar a 60 FPS gasta bem menos que 200 FPS sem limite (menos calor, menos ruido)."
    Write-Host " 6. VIDEO INTEGRADA: CPU e video dividem o mesmo calor. O perfil EQUILIBRADO FRIO ajuda muito nesses PCs."
    Write-Host " 7. AMBIENTE: local ventilado, longe de sol direto e de aquecedores."
    Write-Host " 8. VENTOINHAS: ajuste a curva na BIOS/software do fabricante; em notebooks use o modo 'Desempenho/Turbo' do fabricante."
    Write-Host " 9. AVANCADO: undervolt (ThrottleStop/XTU/Ryzen Controller) se o seu modelo permitir, e atualizar a BIOS."
    Write-Host ""
    Write-Host "Referencia de temperatura da CPU: ate 60 C parado | ate 80 C jogando = bom | 80-90 C = quente | 95+ C = throttling." -ForegroundColor DarkGray
}

function Configurar-LimiteTemp {
    $cfg = Load-Config
    Write-Host ""
    Write-Host ("Limite atual da protecao termica automatica do Modo Jogo: {0} C" -f $cfg.LimiteTemp)
    Write-Host "Se a CPU chegar nesse valor durante o jogo, o Modo Jogo troca para o perfil EQUILIBRADO FRIO sozinho."
    $v = Read-Host "Novo limite em C (70 a 100; Enter mantem)"
    if ($v -match '^\d+$' -and [int]$v -ge 70 -and [int]$v -le 100) {
        $cfg.LimiteTemp = [int]$v
        Save-Config $cfg
        Show-Ok ("Limite salvo: {0} C" -f $v)
    } else { Write-Host "Mantido." }
}

function Menu-Temperatura {
    while ($true) {
        Clear-Host
        Write-Host "=========== TEMPERATURA E REFRIGERACAO ===========" -ForegroundColor Cyan
        $t = Get-Termica
        Write-Host ("Agora: " + (Format-LinhaTermica $t $null)) -ForegroundColor (Get-CorTemp $t.TempC)
        Write-Host ""
        Write-Host " 1) Diagnostico termico (temperatura, throttling, quem esquenta o PC)"
        Write-Host " 2) Monitor ao vivo (temperatura, clock, uso por nucleo, placa de video de qualquer marca)"
        Write-Host " 3) Aplicar perfil de energia/temperatura (Maximo FPS / Equilibrado frio / Silencioso)"
        Write-Host " 4) Voltar ao plano de energia original"
        Write-Host " 5) Teste de estresse da CPU (verifica se a refrigeracao aguenta)"
        Write-Host " 6) Dicas de refrigeracao fisica (o que realmente baixa a temperatura)"
        Write-Host " 7) Ajustar o limite da protecao termica automatica do Modo Jogo"
        Write-Host " 8) Voltar ao menu principal"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1' { Diagnostico-Termico; Pausa }
            '2' { Monitor-Temperatura; Pausa }
            '3' { Menu-AplicarPerfil; Pausa }
            '4' {
                if (Restaurar-EsquemaOriginal) { Show-Ok "Plano de energia original restaurado" } else { Write-Host "Nada para restaurar (nenhum perfil do toolkit ativo)." }
                Pausa
            }
            '5' { Teste-Estresse; Pausa }
            '6' { Dicas-Refrigeracao; Pausa }
            '7' { Configurar-LimiteTemp; Pausa }
            '8' { return }
        }
    }
}

# ======================================================================
# SECAO 5A: OTIMIZACAO DO WINDOWS (tudo reversivel pela opcao Desfazer)
# ======================================================================
function Load-Undo {
    if (Test-Path -LiteralPath $script:UndoFile) {
        try { return @(Get-Content -LiteralPath $script:UndoFile -Raw | ConvertFrom-Json) } catch { }
    }
    return @()
}
function Save-Undo($lista) {
    try { ConvertTo-Json -InputObject @($lista) -Depth 4 | Set-Content -LiteralPath $script:UndoFile -Encoding UTF8 } catch { }
}

# Grava um valor de registro guardando o valor antigo para o Desfazer
function Set-RegBackup($path, $name, $value, $type) {
    $undo = @(Load-Undo)
    $ja = @($undo | Where-Object { $_.Path -eq $path -and $_.Name -eq $name })
    if ($ja.Count -eq 0) {
        $existed = $false
        $old = $null
        $oldType = $type
        if (Test-Path -LiteralPath $path) {
            $prop = Get-ItemProperty -LiteralPath $path -Name $name -ErrorAction SilentlyContinue
            if ($prop -and ($prop.PSObject.Properties.Name -contains $name)) {
                $existed = $true
                $old = $prop.$name
                try { $oldType = (Get-Item -LiteralPath $path).GetValueKind($name).ToString() } catch { }
            }
        }
        $undo += [pscustomobject]@{ Path = $path; Name = $name; Existed = $existed; Old = $old; Type = $oldType }
        Save-Undo $undo
    }
    if (-not (Test-Path -LiteralPath $path)) { New-Item -Path $path -Force | Out-Null }
    Set-ItemProperty -LiteralPath $path -Name $name -Value $value -Type $type -Force -ErrorAction SilentlyContinue
}

function Get-RegString($path, $name) {
    $p = Get-ItemProperty -LiteralPath $path -Name $name -ErrorAction SilentlyContinue
    if ($p -and ($p.PSObject.Properties.Name -contains $name)) { return [string]$p.$name }
    return $null
}

# Junta "Chave=valor;" a uma configuracao do DirectX sem apagar as outras
function Merge-DxSetting($atual, $chave, $valor) {
    $partes = @()
    if ($atual) {
        $partes = @(([string]$atual) -split ';' | Where-Object { $_ -and ($_ -notmatch ('^\s*' + [regex]::Escape($chave) + '\s*=')) })
    }
    $partes += ("{0}={1}" -f $chave, $valor)
    return (($partes -join ';') + ';')
}

function Merge-CompatFlag($atual, $flag) {
    $tok = @()
    if ($atual) { $tok = @(([string]$atual) -split '\s+' | Where-Object { $_ -and $_ -ne '~' }) }
    if ($tok -notcontains $flag) { $tok += $flag }
    return ('~ ' + ($tok -join ' '))
}

function Criar-PontoRestauracao($desc) {
    $k = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SystemRestore'
    $tinha = $false
    $freq = $null
    try {
        Enable-ComputerRestore -Drive ($env:SystemDrive + '\') -ErrorAction Stop
        $p = Get-ItemProperty -LiteralPath $k -Name SystemRestorePointCreationFrequency -ErrorAction SilentlyContinue
        if ($p -and $null -ne $p.SystemRestorePointCreationFrequency) { $tinha = $true; $freq = $p.SystemRestorePointCreationFrequency }
        Set-ItemProperty -LiteralPath $k -Name SystemRestorePointCreationFrequency -Value 0 -Type DWord -Force
        Checkpoint-Computer -Description $desc -RestorePointType MODIFY_SETTINGS -ErrorAction Stop
        Show-Ok "Ponto de restauracao criado"
        Write-Log ("Ponto de restauracao: " + $desc)
    } catch {
        Show-Aviso ("Nao consegui criar o ponto de restauracao: " + $_.Exception.Message)
    } finally {
        if ($tinha) { Set-ItemProperty -LiteralPath $k -Name SystemRestorePointCreationFrequency -Value $freq -Type DWord -Force -ErrorAction SilentlyContinue }
        else { Remove-ItemProperty -LiteralPath $k -Name SystemRestorePointCreationFrequency -ErrorAction SilentlyContinue }
    }
}

function Otimizar-Windows {
    param([switch]$SemPonto)
    Write-Host ""
    Write-Host "=== OTIMIZAR O WINDOWS PARA JOGOS ===" -ForegroundColor Cyan
    Write-Host "Vou aplicar (tudo reversivel na opcao Desfazer):"
    Write-Host "  - Plano de energia/temperatura do jogo (voce escolhe: Maximo FPS, Equilibrado frio ou Silencioso)"
    Write-Host "  - Modo Jogo do Windows ligado; gravacao em segundo plano (Game DVR) e Game Bar desativados"
    Write-Host "  - Prioridade de CPU/GPU para jogos (MMCSS), sem limite de rede para jogos"
    Write-Host "  - Foco de processador no programa em primeiro plano (Win32PrioritySeparation)"
    Write-Host "  - Transparencia e animacoes desligadas, apps em segundo plano desligados"
    Write-Host "  - DirectX: otimizacao para jogos em janela/sem borda (SwapEffectUpgrade) e taxa variavel (VRR)"
    Write-Host "  - Windows Update sem compartilhar sua internet com outros PCs (Otimizacao de Entrega)"
    Write-Host "Perguntas opcionais no final: mouse sem aceleracao e Integridade de memoria (HVCI)."
    Write-Host ""
    if (-not (Ask-YesNo "Continuar?")) { Write-Host "Cancelado."; return }

    if (-not $SemPonto) {
        if (Ask-YesNo "Criar antes um ponto de restauracao do Windows? (recomendado)") {
            Criar-PontoRestauracao 'PC Toolkit - antes de otimizar'
        }
    }

    $esc = Escolher-PerfilTermico
    if ($esc) {
        if (Aplicar-PerfilTermico $esc.Perfil $esc.MaxPct) { Show-Ok ("Plano de energia: {0} (CPU maxima {1}%)" -f $esc.Perfil, $esc.MaxPct) }
    }

    Aplicar-TweaksJogo

    Write-Host ""
    if (Ask-YesNo "Desativar a aceleracao do mouse (melhora a mira em jogos de tiro)?") {
        Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseSpeed' '0' 'String'
        Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseThreshold1' '0' 'String'
        Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseThreshold2' '0' 'String'
        Show-Ok "Aceleracao do mouse desativada (vale depois de sair e entrar na conta do Windows)"
    }

    Write-Host ""
    Write-Host "Integridade de memoria (HVCI) protege o Windows contra malware no nivel do kernel." -ForegroundColor Yellow
    Write-Host "Desativar pode dar alguns FPS a mais em CPUs mais antigas, mas REDUZ a seguranca. Precisa reiniciar."
    if (Ask-YesNo "Desativar a Integridade de memoria?") {
        Set-RegBackup 'HKLM:\SYSTEM\CurrentControlSet\Control\DeviceGuard\Scenarios\HypervisorEnforcedCodeIntegrity' 'Enabled' 0 'DWord'
        Show-Ok "Integridade de memoria sera desativada no proximo reinicio"
    }

    Write-Log "Otimizar-Windows aplicado"
    Write-Host ""
    Write-Host "Pronto. Reinicie o PC para tudo valer. Para reverter: menu FPS > Desfazer." -ForegroundColor Green
}

# ---------------- rede ----------------
function Testar-Latencia {
    Write-Host ""
    Write-Host "Testando latencia (6 pings em cada)..." -ForegroundColor DarkGray
    foreach ($alvo in @(@{ N = 'Cloudflare (1.1.1.1)'; H = '1.1.1.1' }, @{ N = 'Google (8.8.8.8)'; H = '8.8.8.8' })) {
        $r = @(Test-Connection -ComputerName $alvo.H -Count 6 -ErrorAction SilentlyContinue)
        if ($r.Count -gt 0) {
            $tempos = @($r | ForEach-Object { [double]$_.ResponseTime })
            $m = $tempos | Measure-Object -Average -Minimum -Maximum
            $perda = [math]::Round(((6 - $r.Count) / 6) * 100, 0)
            Write-Host ("  {0,-22} media {1:N0} ms | min {2:N0} | max {3:N0} | variacao {4:N0} ms | perda {5}%" -f $alvo.N, $m.Average, $m.Minimum, $m.Maximum, ($m.Maximum - $m.Minimum), $perda)
        } else {
            Write-Host ("  {0,-22} sem resposta" -f $alvo.N) -ForegroundColor Yellow
        }
    }
    Write-Host "  Referencia: ate 30 ms otimo | 30-60 ms bom | 100+ ms perceptivel | variacao alta = lag/engasgos (Wi-Fi ruim?)." -ForegroundColor DarkGray
}

function Otimizar-Rede {
    Write-Host ""
    Write-Host "=== REDE PARA JOGOS ONLINE ===" -ForegroundColor Cyan
    Write-Host "Vou: limpar o cache de DNS, desativar o algoritmo de Nagle nos adaptadores ativos (menos atraso em pacotes pequenos)"
    Write-Host "e impedir o Windows Update de usar sua internet para outros PCs. O efeito e pequeno, mas nao prejudica. Reversivel em 'Desfazer'."
    Write-Host "Para ping/lag de verdade, o que mais pesa e: cabo de rede em vez de Wi-Fi e nada baixando em segundo plano." -ForegroundColor DarkGray
    Testar-Latencia
    Write-Host ""
    if (-not (Ask-YesNo "Aplicar os ajustes?")) { return }

    & ipconfig.exe /flushdns | Out-Null
    Show-Ok "Cache de DNS limpo"
    foreach ($ad in @(Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' })) {
        $chave = 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\' + $ad.InterfaceGuid
        Set-RegBackup $chave 'TcpAckFrequency' 1 'DWord'
        Set-RegBackup $chave 'TCPNoDelay' 1 'DWord'
        Show-Ok ("Nagle desativado em: " + $ad.Name)
    }
    Set-RegBackup 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeliveryOptimization' 'DODownloadMode' 0 'DWord'
    Show-Ok "Otimizacao de Entrega sem compartilhamento"
    Write-Host "Reinicie o PC para os adaptadores assumirem os valores novos." -ForegroundColor Yellow
}

# ---------------- programas de inicializacao ----------------
function Get-ItensInicializacao {
    $itens = @()
    $hk = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved'
    $hl = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved'
    $fontes = @(
        @{ Chave = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'; Apr = ($hk + '\Run'); Rot = 'Usuario' },
        @{ Chave = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run'; Apr = ($hl + '\Run'); Rot = 'Sistema' },
        @{ Chave = 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Run'; Apr = ($hl + '\Run32'); Rot = 'Sistema 32-bit' }
    )
    foreach ($f in $fontes) {
        $prop = Get-ItemProperty -LiteralPath $f.Chave -ErrorAction SilentlyContinue
        if ($prop) {
            foreach ($p in $prop.PSObject.Properties) {
                if ($p.MemberType -ne 'NoteProperty') { continue }
                if (@('PSPath', 'PSParentPath', 'PSChildName', 'PSDrive', 'PSProvider') -contains $p.Name) { continue }
                $itens += [pscustomobject]@{ Nome = $p.Name; Comando = [string]$p.Value; Apr = $f.Apr; Origem = $f.Rot }
            }
        }
    }
    $pastas = @(
        @{ Pasta = (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup'); Apr = ($hk + '\StartupFolder'); Rot = 'Pasta Inicializar (usuario)' },
        @{ Pasta = (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\StartUp'); Apr = ($hl + '\StartupFolder'); Rot = 'Pasta Inicializar (todos)' }
    )
    foreach ($f in $pastas) {
        foreach ($a in @(Get-ChildItem -LiteralPath $f.Pasta -File -Force -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne 'desktop.ini' })) {
            $itens += [pscustomobject]@{ Nome = $a.Name; Comando = $a.FullName; Apr = $f.Apr; Origem = $f.Rot }
        }
    }
    return $itens
}

function Test-InicialAtivo($apr, $nome) {
    $v = Get-ItemProperty -LiteralPath $apr -Name $nome -ErrorAction SilentlyContinue
    if ($v -and ($v.PSObject.Properties.Name -contains $nome)) {
        $bytes = $v.$nome
        if ($bytes -is [byte[]] -and $bytes.Length -gt 0 -and (($bytes[0] -band 1) -eq 1)) { return $false }
    }
    return $true
}

function Set-InicialAtivo($apr, $nome, [bool]$ativo) {
    if (-not (Test-Path -LiteralPath $apr)) { New-Item -Path $apr -Force | Out-Null }
    $b = New-Object byte[] 12
    if ($ativo) { $b[0] = 2 } else { $b[0] = 3 }
    Set-ItemProperty -LiteralPath $apr -Name $nome -Value $b -Type Binary -Force -ErrorAction SilentlyContinue
}

function Gerenciar-Inicializacao {
    Write-Host ""
    Write-Host "=== PROGRAMAS QUE ABREM COM O WINDOWS ===" -ForegroundColor Cyan
    Write-Host "Cada um consome RAM/CPU e atrasa o boot. Desativar NAO desinstala: da para reativar aqui mesmo." -ForegroundColor DarkGray
    $protegidos = 'SecurityHealth|Defender|Windows Security|Antiv|Realtek|HD Audio|Audio|igfx|Intel.*Graphics|Intel.*Driver|NVIDIA.*(Display|Driver|Container)|RtkAudio|Bluetooth|Touchpad|Synaptics|ELAN'
    $itens = @(Get-ItensInicializacao)
    if ($itens.Count -eq 0) { Write-Host "Nenhum item encontrado."; return }
    Write-Host ""
    $n = 1
    foreach ($i in $itens) {
        $estado = 'ATIVO     '
        $cor = 'White'
        if (-not (Test-InicialAtivo $i.Apr $i.Nome)) { $estado = 'desativado'; $cor = 'DarkGray' }
        $aviso = ''
        if ($i.Nome -match $protegidos -or $i.Comando -match $protegidos) { $aviso = '  [sistema/driver - deixe]' }
        Write-Host ("{0,3}) {1}  {2,-30} ({3}){4}" -f $n, $estado, $i.Nome, $i.Origem, $aviso) -ForegroundColor $cor
        $n++
    }
    Write-Host ""
    Write-Host "Digite os numeros para ALTERNAR (ativo <-> desativado), separados por virgula. Enter = sair." 
    $sel = Read-Host "Numeros"
    if (-not $sel) { return }
    foreach ($ix in @(ConvertTo-Indices $sel $itens.Count)) {
        $i = $itens[$ix - 1]
        $ativo = Test-InicialAtivo $i.Apr $i.Nome
        Set-InicialAtivo $i.Apr $i.Nome (-not $ativo)
        if ($ativo) { Show-Ok ("Desativado: " + $i.Nome) } else { Show-Ok ("Reativado: " + $i.Nome) }
    }
    Write-Host "Vale a partir do proximo login." -ForegroundColor Yellow
}

# ---------------- RAM ----------------
function Liberar-RAM($excluirIds) {
    if (-not (Initialize-Tipos)) { Show-Aviso "Nao consegui preparar a liberacao de memoria."; return }
    $os = Get-CimInstance Win32_OperatingSystem
    $antes = [double]$os.FreePhysicalMemory * 1KB
    $skip = @('System', 'Idle', 'Registry', 'MemCompression', 'csrss', 'winlogon', 'dwm', 'lsass', 'services', 'smss', 'wininit', 'audiodg', 'MsMpEng')
    $total = 0
    foreach ($p in @(Get-Process -ErrorAction SilentlyContinue)) {
        if ($p.Id -eq $PID -or $p.Id -le 4 -or $p.SessionId -eq 0) { continue }
        if ($skip -contains $p.ProcessName) { continue }
        if ($excluirIds -and ($excluirIds -contains $p.Id)) { continue }
        try { if ([PCToolkit.Nativo]::EmptyWorkingSet($p.Handle)) { $total++ } } catch { }
    }
    Start-Sleep -Seconds 2
    $os2 = Get-CimInstance Win32_OperatingSystem
    $depois = [double]$os2.FreePhysicalMemory * 1KB
    Show-Ok ("RAM livre: {0} -> {1} ({2} processos enxugados)" -f (Format-Size $antes), (Format-Size $depois), $total)
    if (Limpar-Standby) { Show-Ok "Memoria standby (cache de arquivos) liberada - ajuda contra engasgos em PCs com pouca RAM" }
    Write-Host "  Efeito temporario: o Windows ja gerencia a memoria. Ajuda quando a RAM esta apertada antes de abrir o jogo." -ForegroundColor DarkGray
}

# ---------------- ajustes por jogo ----------------
function Ajustes-PorJogo {
    Write-Host ""
    Write-Host "=== AJUSTES POR JOGO ===" -ForegroundColor Cyan
    Write-Host "Escolha o jogo e eu aplico configuracoes do Windows so para ele (reversivel em 'Desfazer')."
    $sel = Selecionar-Jogo $null $true
    if (-not $sel -or -not $sel.Exe) { Write-Host "Nenhum jogo/executavel selecionado."; return }
    $nomeExe = [IO.Path]::GetFileName([string]$sel.Exe)
    Write-Host ""
    Write-Host ("Jogo: {0}`n  {1}" -f $sel.Nome, $sel.Exe) -ForegroundColor Green

    $gpus = @(Get-CimInstance Win32_VideoController | Where-Object { $_.Name -notmatch 'Basic|Remote|Virtual|Parsec|Meta|Citrix|Mirage|Indirect' })
    Write-Host ""
    if ($gpus.Count -ge 2) {
        Write-Host ("Seu PC tem {0} placas de video: {1}" -f $gpus.Count, (($gpus | ForEach-Object { $_.Name }) -join ' + ')) -ForegroundColor Yellow
        Write-Host "Se o jogo estiver rodando na placa fraca (integrada), forcar a de alto desempenho muda MUITO o FPS."
    } else {
        Write-Host "Seu PC tem so uma placa de video; forcar a de alto desempenho nao muda nada (mas tambem nao atrapalha)."
    }
    $dxk = 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'
    if (Ask-YesNo "Forcar a GPU de alto desempenho para este jogo?") {
        Set-RegBackup $dxk $sel.Exe (Merge-DxSetting (Get-RegString $dxk $sel.Exe) 'GpuPreference' '2') 'String'
        Show-Ok "Preferencia de GPU: alto desempenho"
    }
    Write-Host ""
    Write-Host "Otimizacoes de tela cheia: em alguns jogos (principalmente DX9/DX11 antigos) desativar melhora a estabilidade/FPS; em outros nao muda (teste)." -ForegroundColor DarkGray
    if (Ask-YesNo "Desativar as otimizacoes de tela cheia para este jogo?") {
        $lk = 'HKCU:\Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers'
        $atual = Get-RegString $lk $sel.Exe
        Set-RegBackup $lk $sel.Exe (Merge-CompatFlag $atual 'DISABLEDXMAXIMIZEDWINDOWEDMODE') 'String'
        Show-Ok "Otimizacoes de tela cheia desativadas para este jogo"
    }
    Write-Host ""
    Write-Host "Prioridade ALTA fixa: o Windows abre este jogo SEMPRE com prioridade alta de CPU e de disco, mesmo sem o Modo Jogo." -ForegroundColor DarkGray
    if (Ask-YesNo ("Deixar {0} sempre com prioridade ALTA?" -f $nomeExe)) {
        $ifeo = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Image File Execution Options\' + $nomeExe + '\PerfOptions'
        Set-RegBackup $ifeo 'CpuPriorityClass' 3 'DWord'
        Set-RegBackup $ifeo 'IoPriority' 3 'DWord'
        Show-Ok "Prioridade alta fixa configurada (vale na proxima vez que abrir o jogo)"
    }
    Write-Host ""
    Write-Host "QoS de rede: marca os pacotes do jogo como 'tempo real' (DSCP 46). Ajuda em roteadores com QoS/WMM ligado; nos outros nao muda nada." -ForegroundColor DarkGray
    if (Ask-YesNo "Criar a regra de QoS para este jogo?") {
        Set-RegBackup 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\QoS' 'Do not use NLA' '1' 'String'
        $pol = 'PC-Toolkit ' + $nomeExe
        Remove-NetQosPolicy -Name $pol -Confirm:$false -ErrorAction SilentlyContinue
        try {
            New-NetQosPolicy -Name $pol -AppPathNameMatchCondition $nomeExe -DSCPAction 46 -NetworkProfile All -IPProtocolMatchCondition Both -ErrorAction Stop | Out-Null
            Show-Ok "Regra de QoS criada"
        } catch { Show-Aviso ("Nao consegui criar a regra de QoS: " + $_.Exception.Message) }
    }
    Write-Log ("Ajustes por jogo: " + $sel.Exe)
}

# ======================================================================
# SECAO 5B: GRAFICOS DOS JOGOS, DIAGNOSTICO, MODO JOGO, DESFAZER
# ======================================================================
function Load-Arquivos {
    if (Test-Path -LiteralPath $script:ArquivosFile) {
        try { return @(Get-Content -LiteralPath $script:ArquivosFile -Raw | ConvertFrom-Json) } catch { }
    }
    return @()
}
function Save-Arquivos($lista) {
    try { ConvertTo-Json -InputObject @($lista) | Set-Content -LiteralPath $script:ArquivosFile -Encoding UTF8 } catch { }
}

function Backup-ConfigJogo($arq) {
    $bak = $arq + '.bak-fps'
    if (-not (Test-Path -LiteralPath $bak)) { Copy-Item -LiteralPath $arq -Destination $bak -Force }
    $lista = @(Load-Arquivos)
    if ($lista -notcontains $arq) { $lista += $arq; Save-Arquivos $lista }
}

function Ler-Linhas($arq) {
    $lst = New-Object System.Collections.ArrayList
    foreach ($ln in [System.IO.File]::ReadAllLines($arq)) { [void]$lst.Add($ln) }
    return ,$lst
}
function Gravar-Linhas($arq, $lst) {
    [System.IO.File]::WriteAllLines($arq, [string[]]$lst.ToArray(), (New-Object System.Text.UTF8Encoding($false)))
}

function Set-IniValor($linhas, $chave, $valor) {
    $n = 0
    $pad = '^\s*' + [regex]::Escape($chave) + '\s*='
    for ($i = 0; $i -lt $linhas.Count; $i++) {
        if ($linhas[$i] -match $pad) { $linhas[$i] = $chave + '=' + $valor; $n++ }
    }
    return $n
}

function Set-ChaveMc($linhas, $chave, $tipo, $valor) {
    $n = 0
    $pad = '^' + [regex]::Escape($chave) + ':(.*)$'
    for ($i = 0; $i -lt $linhas.Count; $i++) {
        if ($linhas[$i] -match $pad) {
            $atual = $Matches[1]
            $novo = $valor
            if ($tipo -eq 'bool' -and $atual -match '^\d+$') {
                if ($valor -eq 'true') { $novo = '1' } else { $novo = '0' }
            }
            if ($tipo -eq 'quoted' -and $atual -match '^"') { $novo = '"' + $valor + '"' }
            $linhas[$i] = $chave + ':' + $novo
            $n++
        }
    }
    return $n
}

function Otimizar-ConfigXml($arq, $w, $h, $preset) {
    $x = New-Object System.Xml.XmlDocument
    try { $x.Load($arq) } catch { Show-Aviso "XML invalido ou ilegivel."; return 0 }
    $tex = 0
    if ($preset -eq 'Baixo') { $tex = 1 }
    $v = [ordered]@{
        Tessellation = 0; ShadowQuality = 0; ReflectionQuality = 0; ReflectionMSAA = 0; SSAO = 0; AnisotropicFiltering = 0
        MSAA = 0; MSAAFragments = 0; MSAAQuality = 0; TextureQuality = $tex; ParticleQuality = 0; WaterQuality = 0
        GrassQuality = 0; ShaderQuality = 0; Shadow_SoftShadows = 0; UltraShadows_Enabled = 'false'; Shadow_ParticleShadows = 'false'
        Shadow_LongShadows = 'false'; FXAA_Enabled = 'false'; TXAA_Enabled = 'false'; Lighting_FogVolumes = 'false'
        PostFX = 0; DX_Version = 2; VSync = 0; Windowed = 2
    }
    if ($w -gt 0) { $v['ScreenWidth'] = $w; $v['ScreenHeight'] = $h }
    $n = 0
    foreach ($k in $v.Keys) {
        $no = $x.SelectSingleNode('//' + $k)
        if ($no -and $no.Attributes['value']) { $no.Attributes['value'].Value = [string]$v[$k]; $n++ }
    }
    $ws = New-Object System.Xml.XmlWriterSettings
    $ws.Indent = $true
    $ws.Encoding = New-Object System.Text.UTF8Encoding($false)
    $wr = [System.Xml.XmlWriter]::Create($arq, $ws)
    $x.Save($wr)
    $wr.Close()
    return $n
}

function Otimizar-ConfigMinecraft($arq, $preset, $fps) {
    $linhas = Ler-Linhas $arq
    $baixo = ($preset -eq 'Baixo')
    $rd = '6'; $sd = '5'; $pt = '2'; $bl = '0'; $mm = '0'; $eds = '0.5'
    if ($baixo) { $rd = '8'; $sd = '6'; $pt = '1'; $bl = '1'; $mm = '1'; $eds = '0.75' }
    $mf = '260'
    if ($fps -gt 0) { $mf = [string]$fps }
    $n = 0
    $n += Set-ChaveMc $linhas 'renderDistance' 'num' $rd
    $n += Set-ChaveMc $linhas 'simulationDistance' 'num' $sd
    $n += Set-ChaveMc $linhas 'maxFps' 'num' $mf
    $n += Set-ChaveMc $linhas 'enableVsync' 'bool' 'false'
    $n += Set-ChaveMc $linhas 'particles' 'num' $pt
    $n += Set-ChaveMc $linhas 'entityShadows' 'bool' 'false'
    $n += Set-ChaveMc $linhas 'ao' 'bool' 'false'
    $n += Set-ChaveMc $linhas 'biomeBlendRadius' 'num' $bl
    $n += Set-ChaveMc $linhas 'mipmapLevels' 'num' $mm
    $n += Set-ChaveMc $linhas 'entityDistanceScaling' 'num' $eds
    $n += Set-ChaveMc $linhas 'renderClouds' 'quoted' 'false'
    $n += Set-ChaveMc $linhas 'graphicsMode' 'num' '0'
    $n += Set-ChaveMc $linhas 'fancyGraphics' 'bool' 'false'
    Gravar-Linhas $arq $linhas
    return $n
}

function Otimizar-ConfigUnreal($arq, $w, $h, $preset, $fps) {
    $linhas = Ler-Linhas $arq
    $q = '0'
    $rq = '75.000000'
    if ($preset -eq 'Baixo') { $q = '1'; $rq = '100.000000' }
    $n = 0
    $n += Set-IniValor $linhas 'sg.ResolutionQuality' $rq
    foreach ($g in @('ViewDistanceQuality', 'AntiAliasingQuality', 'ShadowQuality', 'PostProcessQuality', 'TextureQuality',
            'EffectsQuality', 'FoliageQuality', 'ShadingQuality', 'GlobalIlluminationQuality', 'ReflectionQuality')) {
        $n += Set-IniValor $linhas ('sg.' + $g) $q
    }
    $n += Set-IniValor $linhas 'bUseVSync' 'False'
    $limite = '0.000000'
    if ($fps -gt 0) { $limite = ('{0}.000000' -f $fps) }
    $n += Set-IniValor $linhas 'FrameRateLimit' $limite
    if ($w -gt 0) {
        foreach ($c in @('ResolutionSizeX', 'LastUserConfirmedResolutionSizeX', 'DesiredScreenWidth', 'LastUserConfirmedDesiredScreenWidth')) { $n += Set-IniValor $linhas $c ([string]$w) }
        foreach ($c in @('ResolutionSizeY', 'LastUserConfirmedResolutionSizeY', 'DesiredScreenHeight', 'LastUserConfirmedDesiredScreenHeight')) { $n += Set-IniValor $linhas $c ([string]$h) }
    }
    Gravar-Linhas $arq $linhas
    return $n
}

function Set-KvValor($linhas, $chave, $valor) {
    $n = 0
    $pad = '^(\s*)"' + [regex]::Escape($chave) + '"(\s+)"[^"]*"'
    for ($i = 0; $i -lt $linhas.Count; $i++) {
        $m = [regex]::Match([string]$linhas[$i], $pad)
        if ($m.Success) { $linhas[$i] = $m.Groups[1].Value + '"' + $chave + '"' + $m.Groups[2].Value + '"' + $valor + '"'; $n++ }
    }
    return $n
}

# CS2 (cs2_video.txt) e Apex (videoconfig.txt): formato "chave" "valor" da Valve
function Otimizar-ConfigKv($arq, $w, $h, $preset, $jogo) {
    $linhas = Ler-Linhas $arq
    $baixo = ($preset -eq 'Baixo')
    $v = [ordered]@{}
    if ($jogo -eq 'cs2') {
        $msaa = '0'; $tex = '0'; $filt = '0'
        if ($baixo) { $msaa = '2'; $tex = '1'; $filt = '1' }
        $v['setting.mat_vsync'] = '0'
        $v['setting.msaa_samples'] = $msaa
        $v['setting.r_csgo_cmaa_enable'] = '0'
        $v['setting.videocfg_shadow_quality'] = '0'
        $v['setting.videocfg_texture_detail'] = $tex
        $v['setting.r_texturefilteringquality'] = $filt
        $v['setting.shaderquality'] = '0'
        $v['setting.videocfg_particle_detail'] = '0'
        $v['setting.videocfg_ao_detail'] = '0'
    } else {
        $lod = '0.600000'
        if ($baixo) { $lod = '0.800000' }
        $v['setting.mat_vsync_mode'] = '0'
        $v['setting.csm_enabled'] = '0'
        $v['setting.shadow_enable'] = '0'
        $v['setting.ssao_enabled'] = '0'
        $v['setting.mat_depthfeather_enable'] = '0'
        $v['setting.r_createmodeldecals'] = '0'
        $v['setting.r_decals'] = '0'
        $v['setting.r_lod_switch_scale'] = $lod
        $v['setting.particle_cpu_level'] = '0'
        $v['setting.cl_ragdoll_maxcount'] = '0'
        if (-not $baixo) { $v['setting.mat_antialias_mode'] = '0' }
    }
    if ($w -gt 0) { $v['setting.defaultres'] = [string]$w; $v['setting.defaultresheight'] = [string]$h }
    $n = 0
    foreach ($k in $v.Keys) { $n += Set-KvValor $linhas $k $v[$k] }
    Gravar-Linhas $arq $linhas
    return $n
}

function Otimizar-ConfigLol($arq, $preset) {
    $linhas = Ler-Linhas $arq
    $q = '0'
    if ($preset -eq 'Baixo') { $q = '1' }
    $n = 0
    foreach ($k in @('ShadowQuality', 'CharacterQuality', 'EnvironmentQuality', 'EffectsQuality')) { $n += Set-IniValor $linhas $k $q }
    foreach ($k in @('ShadowsEnabled', 'CharacterInking', 'EnableFXAA', 'EnableGrassSwaying', 'WaitForVerticalSync', 'EnableHUDAnimations', 'AutoPerformanceSettings')) { $n += Set-IniValor $linhas $k '0' }
    Gravar-Linhas $arq $linhas
    return $n
}

function Otimizar-ConfigRoblox($arq, $preset) {
    $txt = [IO.File]::ReadAllText($arq)
    $q = '1'
    if ($preset -eq 'Baixo') { $q = '3' }
    $n = 0
    foreach ($nome in @('SavedQualityLevel', 'GraphicsQualityLevel', 'QualityLevel')) {
        $rx = '(<(?:token|int) name="' + $nome + '">)\d+(</(?:token|int)>)'
        $n += [regex]::Matches($txt, $rx).Count
        $txt = [regex]::Replace($txt, $rx, ('${1}' + $q + '${2}'))
    }
    [IO.File]::WriteAllText($arq, $txt, (New-Object System.Text.UTF8Encoding($false)))
    return $n
}

function Otimizar-ConfigBedrock($arq, $preset, $fps) {
    $linhas = Ler-Linhas $arq
    $vd = '96'
    if ($preset -eq 'Baixo') { $vd = '160' }
    $mf = '0'
    if ($fps -gt 0) { $mf = [string]$fps }
    $n = 0
    $n += Set-ChaveMc $linhas 'gfx_viewdistance' 'num' $vd
    foreach ($k in @('gfx_fancygraphics', 'gfx_smoothlighting', 'gfx_fancyskies', 'gfx_transparentleaves', 'gfx_vsync', 'gfx_fancybubbles', 'gfx_renderclouds')) { $n += Set-ChaveMc $linhas $k 'num' '0' }
    $n += Set-ChaveMc $linhas 'gfx_max_framerate' 'num' $mf
    Gravar-Linhas $arq $linhas
    return $n
}

function New-CfgGrafica($nome, $tipo, $arq, $procs) {
    return [pscustomobject]@{ Nome = $nome; Tipo = $tipo; Arquivo = $arq; Procs = @($procs | Where-Object { $_ }) }
}

function Get-ConfigsGraficas {
    $r = @()
    $vistos = @{}
    if (Test-Path -LiteralPath $script:FxSettings) { $r += New-CfgGrafica 'FiveM (CitizenFX\gta5_settings.xml)' 'xml' $script:FxSettings @('FiveM*', 'GTA5*', 'CitizenFX*') }
    if (Test-Path -LiteralPath $script:RkSettings) { $r += New-CfgGrafica 'GTA V (Rockstar settings.xml)' 'xml' $script:RkSettings @('GTA5*', 'PlayGTAV*') }
    $mc = Join-Path $env:APPDATA '.minecraft\options.txt'
    if (Test-Path -LiteralPath $mc) { $r += New-CfgGrafica 'Minecraft Java (options.txt)' 'mc' $mc @('javaw', 'Minecraft*') }

    $bed = @()
    $uwp = Join-Path $env:LOCALAPPDATA 'Packages\Microsoft.MinecraftUWP_8wekyb3d8bbwe\LocalState\games\com.mojang\minecraftpe\options.txt'
    if (Test-Path -LiteralPath $uwp) { $bed += $uwp }
    $gdk = Join-Path $env:APPDATA 'Minecraft Bedrock\Users'
    if (Test-Path -LiteralPath $gdk) { $bed += @(Get-ChildItem -LiteralPath $gdk -Recurse -Depth 5 -Filter 'options.txt' -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -like '*\minecraftpe\*' } | ForEach-Object { $_.FullName }) }
    foreach ($b in $bed) { $r += New-CfgGrafica 'Minecraft Bedrock (options.txt)' 'mcb' $b @('Minecraft.Windows') }

    # Unreal Engine: <pasta>\Saved\Config\...\GameUserSettings.ini (Fortnite, Valorant, PUBG, Palworld e muitos outros)
    foreach ($bs in @($env:LOCALAPPDATA, (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'My Games'))) {
        if (-not $bs -or -not (Test-Path -LiteralPath $bs)) { continue }
        foreach ($d in @(Get-ChildItem -LiteralPath $bs -Directory -Force -ErrorAction SilentlyContinue)) {
            $sc = Join-Path $d.FullName 'Saved\Config'
            if (-not (Test-Path -LiteralPath $sc)) { continue }
            foreach ($f in @(Get-ChildItem -LiteralPath $sc -Recurse -Depth 3 -Filter 'GameUserSettings.ini' -File -ErrorAction SilentlyContinue)) {
                if ($vistos.ContainsKey($f.FullName)) { continue }
                $vistos[$f.FullName] = $true
                $r += New-CfgGrafica ("Unreal Engine: {0} ({1})" -f $d.Name, $f.Directory.Name) 'ue' $f.FullName @()
            }
        }
    }

    foreach ($sp in @(Get-SteamPaths)) {
        $ud = Join-Path $sp 'userdata'
        if (-not (Test-Path -LiteralPath $ud)) { continue }
        foreach ($u in @(Get-ChildItem -LiteralPath $ud -Directory -ErrorAction SilentlyContinue)) {
            $f = Join-Path $u.FullName '730\local\cfg\cs2_video.txt'
            if ((Test-Path -LiteralPath $f) -and -not $vistos.ContainsKey($f.ToLower())) {
                $vistos[$f.ToLower()] = $true
                $r += New-CfgGrafica ("Counter-Strike 2 (conta Steam {0})" -f $u.Name) 'cs2' $f @('cs2')
            }
        }
    }
    $apex = Join-Path $env:USERPROFILE 'Saved Games\Respawn\Apex\local\videoconfig.txt'
    if (Test-Path -LiteralPath $apex) { $r += New-CfgGrafica 'Apex Legends (videoconfig.txt)' 'apex' $apex @('r5apex*') }
    foreach ($dk in (Get-DiscosFixos)) {
        foreach ($cand in @(($dk.DeviceID + '\Riot Games\League of Legends\Config\game.cfg'), ($dk.DeviceID + '\Program Files\Riot Games\League of Legends\Config\game.cfg'))) {
            if (Test-Path -LiteralPath $cand) { $r += New-CfgGrafica 'League of Legends (game.cfg)' 'lol' $cand @('League of Legends', 'LeagueClient*') }
        }
    }
    foreach ($f in @(Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Roblox\GlobalBasicSettings_*.xml') -File -ErrorAction SilentlyContinue)) {
        $r += New-CfgGrafica ('Roblox (' + $f.Name + ')') 'rbx' $f.FullName @('RobloxPlayerBeta')
    }
    return $r
}

function Otimizar-Graficos {
    Write-Host ""
    Write-Host "=== OTIMIZAR GRAFICOS DOS JOGOS (arquivo de configuracao) ===" -ForegroundColor Cyan
    Write-Host "Reduz a qualidade grafica nos arquivos do proprio jogo para ganhar FPS (e esquentar menos)."
    Write-Host "Suporta: GTA V / FiveM, Minecraft Java e Bedrock, Unreal Engine (Fortnite, Valorant, PUBG...), CS2, Apex, LoL, Roblox." -ForegroundColor DarkGray
    Write-Host "So altero chaves que JA existem no arquivo, e guardo uma copia (.bak-fps) de cada um." -ForegroundColor DarkGray
    Write-Host "Procurando arquivos de configuracao..." -ForegroundColor DarkGray
    $cands = @(Get-ConfigsGraficas)
    if ($cands.Count -eq 0) {
        Write-Host ""
        Write-Host "Nao encontrei arquivos de configuracao de jogos compativeis." -ForegroundColor Yellow
        Write-Host "Abra o jogo uma vez (para ele criar o arquivo), feche e rode de novo. Para FiveM/GTA V: entre ate o menu e saia."
        Write-Host "Para outros jogos, baixe a qualidade no menu do jogo; o 'Modo Jogo' e os 'Ajustes por jogo' funcionam com qualquer um." -ForegroundColor DarkGray
        return
    }
    Write-Host ""
    $n = 1
    foreach ($c in $cands) { Write-Host ("{0,3}) {1}" -f $n, $c.Nome); $n++ }
    $sel = Read-Host "Quais otimizar? Numeros (ex: 1,3) ou T = todos (Enter = cancelar)"
    if (-not $sel) { Write-Host "Cancelado."; return }
    $escolhidos = @()
    if ($sel -match '^[tT]') { $escolhidos = $cands }
    else { foreach ($ix in @(ConvertTo-Indices $sel $cands.Count)) { $escolhidos += $cands[$ix - 1] } }
    if ($escolhidos.Count -eq 0) { Write-Host "Nenhum valido."; return }

    Write-Host ""
    Write-Host "Preset: 1) MINIMO (maximo FPS, visual bem simples)   2) BAIXO (equilibrado)"
    $pr = Read-Host "Escolha (Enter = 1)"
    $preset = 'Minimo'
    if ($pr -eq '2') { $preset = 'Baixo' }

    $w = 0; $h = 0
    $temResolucao = @($escolhidos | Where-Object { $_.Tipo -match '^(xml|ue|cs2|apex)$' }).Count -gt 0
    if ($temResolucao) {
        $res = Read-Host "Resolucao (Enter = MANTER a atual | ou digite ex: 1280x720, 1600x900)"
        if ($res -match '^(\d{3,4})\s*[xX]\s*(\d{3,4})$') { $w = [int]$Matches[1]; $h = [int]$Matches[2] }
    }
    $fps = 0
    $temFps = @($escolhidos | Where-Object { $_.Tipo -match '^(mc|mcb|ue)$' }).Count -gt 0
    if ($temFps) {
        $f = Read-Host "Limite de FPS para Minecraft/Unreal (0 = sem limite; ideal: Hz do monitor - 3). Enter = 0"
        if ($f -match '^\d+$') { $fps = [int]$f }
    }

    Write-Host ""
    foreach ($c in $escolhidos) {
        if ($c.Procs.Count -gt 0 -and (Get-Process -Name $c.Procs -ErrorAction SilentlyContinue)) {
            Show-Aviso ("{0}: o jogo esta aberto (ele sobrescreveria as mudancas). Feche e rode de novo. Pulei." -f $c.Nome)
            continue
        }
        Backup-ConfigJogo $c.Arquivo
        $feitos = 0
        try {
            switch ($c.Tipo) {
                'xml'  { $feitos = Otimizar-ConfigXml $c.Arquivo $w $h $preset }
                'mc'   { $feitos = Otimizar-ConfigMinecraft $c.Arquivo $preset $fps }
                'mcb'  { $feitos = Otimizar-ConfigBedrock $c.Arquivo $preset $fps }
                'ue'   { $feitos = Otimizar-ConfigUnreal $c.Arquivo $w $h $preset $fps }
                'cs2'  { $feitos = Otimizar-ConfigKv $c.Arquivo $w $h $preset 'cs2' }
                'apex' { $feitos = Otimizar-ConfigKv $c.Arquivo $w $h $preset 'apex' }
                'lol'  { $feitos = Otimizar-ConfigLol $c.Arquivo $preset }
                'rbx'  { $feitos = Otimizar-ConfigRoblox $c.Arquivo $preset }
            }
        } catch { Show-Aviso ("{0}: erro ao gravar ({1})" -f $c.Nome, $_.Exception.Message); continue }
        if ($feitos -gt 0) { Show-Ok ("{0}: {1} ajustes aplicados (backup .bak-fps ao lado do arquivo)" -f $c.Nome, $feitos) }
        else { Show-Aviso ("{0}: nenhuma chave conhecida encontrada (o jogo pode ter outro formato)." -f $c.Nome) }
        if ($c.Tipo -eq 'lol') { Show-Info "  LoL: se o cliente voltar as configuracoes da nuvem, ajuste uma vez dentro do jogo." }
        if ($c.Tipo -eq 'cs2') { Show-Info "  CS2: sombras dinamicas foram mantidas de proposito (mostram inimigos atras de cantos)." }
        if ($c.Tipo -eq 'ue') { Show-Info "  Unreal: se o jogo estava aberto, feche e rode de novo (ele reescreve o arquivo ao sair)." }
    }
    Write-Log "Otimizar-Graficos executado"
    Write-Host ""
    Write-Host "Se o jogo sobrescrever (alguns reescrevem ao sair), ajuste dentro do jogo. Para reverter: menu FPS > Desfazer." -ForegroundColor Yellow
}

# ---------------- diagnostico geral ----------------
function Diagnostico {
    Write-Host ""
    Write-Host "=== DIAGNOSTICO DO PC ===" -ForegroundColor Cyan
    $recs = @()
    $os = Get-CimInstance Win32_OperatingSystem
    Write-Host ("Windows: {0} (build {1})" -f $os.Caption, $os.BuildNumber)
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    if ($cpu) { Write-Host ("CPU: {0} ({1} nucleos / {2} threads)" -f $cpu.Name.Trim(), $cpu.NumberOfCores, $cpu.NumberOfLogicalProcessors) }

    $mods = @(Get-CimInstance Win32_PhysicalMemory)
    $totalRam = ($mods | Measure-Object -Property Capacity -Sum).Sum
    $vel = ($mods | Select-Object -First 1).Speed
    Write-Host ("RAM: {0} em {1} pente(s), {2} MHz" -f (Format-Size $totalRam), $mods.Count, $vel)
    if ($mods.Count -eq 1) { $recs += "RAM em canal unico (1 pente): um 2o pente igual ativa dual channel e aumenta bastante o FPS, principalmente com video integrada." }
    if ($totalRam -and ($totalRam / 1GB) -lt 8) { $recs += "Menos de 8 GB de RAM: feche programas antes de jogar (Modo Jogo) e considere mais memoria." }

    Write-Host "Video:"
    foreach ($v in @(Get-CimInstance Win32_VideoController)) {
        $dt = ''
        if ($v.DriverDate) { $dt = $v.DriverDate.ToString('yyyy-MM-dd') }
        Write-Host ("  {0} | driver {1} ({2})" -f $v.Name, $v.DriverVersion, $dt)
        if ($v.DriverDate -and $v.DriverDate -lt (Get-Date).AddYears(-1) -and $v.Name -notmatch 'Basic|Remote|Virtual') {
            $recs += ("Driver de video de {0} tem mais de 1 ano: atualize no site do fabricante (NVIDIA/AMD/Intel)." -f $v.Name)
        }
        if ($v.Name -match 'Basic') { $recs += "Driver de video generico (Microsoft Basic): instale o driver do fabricante, o ganho de FPS e enorme." }
    }

    Write-Host "Discos:"
    foreach ($pdk in @(Get-PhysicalDisk -ErrorAction SilentlyContinue)) {
        Write-Host ("  {0} | {1} | {2} | saude: {3}" -f $pdk.FriendlyName, $pdk.MediaType, (Format-Size $pdk.Size), $pdk.HealthStatus)
        if ($pdk.HealthStatus -and $pdk.HealthStatus -ne 'Healthy') { $recs += ("Disco {0} reporta saude '{1}': faca backup AGORA." -f $pdk.FriendlyName, $pdk.HealthStatus) }
        if ($pdk.MediaType -eq 'HDD') { $recs += ("{0} e HD comum: jogos instalados em SSD carregam muito mais rapido." -f $pdk.FriendlyName) }
    }
    foreach ($dk in (Get-DiscosFixos)) {
        $pct = 0
        if ($dk.Size) { $pct = [math]::Round(($dk.FreeSpace / $dk.Size) * 100, 0) }
        Write-Host ("  {0} livre {1} de {2} ({3}%)" -f $dk.DeviceID, (Format-Size $dk.FreeSpace), (Format-Size $dk.Size), $pct)
        if ($pct -lt 15) { $recs += ("Disco {0} com so {1}% livre: rode a Limpeza profunda e veja o tamanho dos jogos." -f $dk.DeviceID, $pct) }
    }

    Write-Host "Configuracoes de jogo:"
    $gm = (Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\GameBar' -ErrorAction SilentlyContinue).AutoGameModeEnabled
    $dvr = (Get-ItemProperty -LiteralPath 'HKCU:\System\GameConfigStore' -ErrorAction SilentlyContinue).GameDVR_Enabled
    $hags = (Get-ItemProperty -LiteralPath 'HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers' -ErrorAction SilentlyContinue).HwSchMode
    $hvci = (Get-ItemProperty -LiteralPath 'HKLM:\SYSTEM\CurrentControlSet\Control\DeviceGuard\Scenarios\HypervisorEnforcedCodeIntegrity' -ErrorAction SilentlyContinue).Enabled
    if ($gm -eq 0) { Write-Host "  Modo Jogo do Windows: DESLIGADO" } else { Write-Host "  Modo Jogo do Windows: ligado/padrao" }
    if ($dvr -eq 0) { Write-Host "  Gravacao em segundo plano (Game DVR): desligada" } else { Write-Host "  Gravacao em segundo plano (Game DVR): LIGADA (consome FPS)"; $recs += "Game DVR ligado: rode 'Otimizar o Windows para jogos'." }
    if ($hags -eq 2) { Write-Host "  Agendamento de GPU por hardware (HAGS): ligado" } elseif ($hags -eq 1) { Write-Host "  Agendamento de GPU por hardware (HAGS): desligado" } else { Write-Host "  HAGS: nao suportado/padrao" }
    Write-Host "    (HAGS: o efeito varia por jogo e driver. Teste ligando/desligando em Configuracoes > Sistema > Video > Graficos.)" -ForegroundColor DarkGray
    if ($hvci -eq 1) { Write-Host "  Integridade de memoria (HVCI): LIGADA (mais segura, pode custar FPS em CPU antiga)" }
    Write-Host ("  Plano de energia: " + ((& powercfg.exe /getactivescheme) -join ' '))

    $ini = @(Get-ItensInicializacao)
    $ativos = 0
    foreach ($i in $ini) { if (Test-InicialAtivo $i.Apr $i.Nome) { $ativos++ } }
    Write-Host ("  Programas na inicializacao: {0} ativos" -f $ativos)
    if ($ativos -gt 10) { $recs += ("{0} programas abrem com o Windows: desative os que nao usa (menu FPS > Inicializacao)." -f $ativos) }

    Write-Host "Temperatura:"
    $t = Get-Termica
    Write-Host ("  " + (Format-LinhaTermica $t (Get-GpuNvidia))) -ForegroundColor (Get-CorTemp $t.TempC)
    if ($t.Throttle) { $recs += "A CPU esta sendo limitada por calor agora: veja o menu Temperatura e refrigeracao." }

    Write-Host "Monitores:"
    foreach ($tl in @(Get-Telas)) {
        Write-Host ("  {0} {1}x{2} a {3} Hz (maximo nesta resolucao: {4} Hz)" -f $tl.Monitor, $tl.W, $tl.H, $tl.Hz, $tl.MaxHz)
        if ($tl.MaxHz -gt ($tl.Hz + 1)) { $recs += ("Monitor {0} esta em {1} Hz mas aceita {2} Hz: FPS > Monitor (ou o Raio-X) corrige na hora." -f $tl.Monitor, $tl.Hz, $tl.MaxHz) }
    }
    $mem1 = Get-CimInstance Win32_PhysicalMemory | Select-Object -First 1
    if ($mem1 -and $mem1.ConfiguredClockSpeed) { Write-Host ("RAM rodando a {0} MHz (configurada)" -f $mem1.ConfiguredClockSpeed) }

    Write-Host "Maiores consumidores de RAM agora:"
    Get-Process -ErrorAction SilentlyContinue | Sort-Object WorkingSet64 -Descending | Select-Object -First 6 | ForEach-Object {
        Write-Host ("  {0,9}  {1}" -f (Format-Size $_.WorkingSet64), $_.ProcessName)
    }

    Write-Host ""
    if ($recs.Count -gt 0) {
        Write-Host "Recomendacoes:" -ForegroundColor Yellow
        foreach ($r in $recs) { Write-Host ("  - " + $r) -ForegroundColor Yellow }
    } else {
        Write-Host "Nada critico encontrado." -ForegroundColor Green
    }
    Write-Host ""
    Write-Host "Para uma NOTA de FPS e correcao automatica: FPS > Raio-X de FPS." -ForegroundColor Cyan
}

# ---------------- escolher o jogo ----------------
function Selecionar-ProcessoAberto {
    $ign = 'explorer|ApplicationFrameHost|TextInputHost|SystemSettings|powershell|WindowsTerminal|conhost|Taskmgr|cmd|SearchHost|StartMenuExperienceHost|ShellExperienceHost|Widgets|Claude|PresentMon'
    $lista = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -and $_.ProcessName -notmatch $ign -and $_.Path } | Sort-Object WorkingSet64 -Descending)
    if ($lista.Count -eq 0) { Write-Host "Nenhum programa com janela encontrado."; return $null }
    Write-Host ""
    Write-Host "Programas com janela aberta (o jogo costuma ser um dos que mais usam RAM):"
    $n = 1
    foreach ($p in $lista) { Write-Host ("{0,3}) {1,-24} {2,9}  {3}" -f $n, $p.ProcessName, (Format-Size $p.WorkingSet64), $p.MainWindowTitle); $n++ }
    $esc = Read-Host "Numero do jogo (Enter = cancelar)"
    if ($esc -notmatch '^\d+$' -or [int]$esc -lt 1 -or [int]$esc -gt $lista.Count) { return $null }
    return $lista[[int]$esc - 1]
}

# Retorna Nome, Exe, Pasta, Uri, JaAberto, ProcBase (ou $null se cancelar)
function Selecionar-Jogo($pre, [bool]$resolverExe) {
    $cand = $null
    $jogos = @()
    $nome = $null; $exe = $null; $pasta = $null; $uri = $null; $aberto = $false; $procBase = $null

    if ($pre) {
        $jogos = @(Get-JogosInstalados)
        $achados = @($jogos | Where-Object { $_.Nome -like ("*" + $pre + "*") })
        if ($achados.Count -ge 1) { $cand = $achados[0] }
        else { Write-Host ("Nao achei jogo com '{0}'. Vou mostrar a lista." -f $pre) -ForegroundColor Yellow }
    } elseif (Test-Path -LiteralPath $script:UltimoJogoFile) {
        $ult = (Get-Content -LiteralPath $script:UltimoJogoFile -Raw).Trim()
        if ($ult -and (Test-Path -LiteralPath $ult)) {
            if (Ask-YesNo ("Usar o ultimo jogo ({0})?" -f [IO.Path]::GetFileNameWithoutExtension($ult))) {
                $nome = [IO.Path]::GetFileNameWithoutExtension($ult); $exe = $ult; $pasta = Split-Path $ult -Parent
            }
        }
    }

    if (-not $cand -and -not $exe) {
        if ($jogos.Count -eq 0) { $jogos = @(Get-JogosInstalados) }
        while ($true) {
            $cnt = $jogos.Count
            Write-Host ""
            if ($cnt -gt 0) { Write-Host "Jogos encontrados:" } else { Write-Host "Nenhum jogo encontrado automaticamente." -ForegroundColor Yellow }
            $n = 1
            foreach ($j in $jogos) { Write-Host ("{0,3}) {1}  [{2}]" -f $n, $j.Nome, $j.Origem); $n++ }
            Write-Host ("{0,3}) Digitar o caminho de um .exe" -f ($cnt + 1))
            Write-Host ("{0,3}) Escolher um jogo que JA ESTA ABERTO (qualquer jogo/launcher)" -f ($cnt + 2))
            Write-Host ("{0,3}) Adicionar uma pasta onde ficam seus jogos (ex: D:\Jogos)" -f ($cnt + 3))
            $esc = Read-Host "Numero (Enter = cancelar)"
            if (-not $esc) { return $null }
            if ($esc -notmatch '^\d+$') { continue }
            $num = [int]$esc
            if ($num -ge 1 -and $num -le $cnt) { $cand = $jogos[$num - 1]; break }
            elseif ($num -eq ($cnt + 1)) {
                $caminho = (Read-Host "Caminho completo do .exe do jogo").Trim().Trim('"')
                if ($caminho -and (Test-Path -LiteralPath $caminho)) {
                    $nome = [IO.Path]::GetFileNameWithoutExtension($caminho); $exe = $caminho; $pasta = Split-Path $caminho -Parent
                    break
                }
                Write-Host "Arquivo nao encontrado." -ForegroundColor Red
            }
            elseif ($num -eq ($cnt + 2)) {
                $proc = Selecionar-ProcessoAberto
                if ($proc) {
                    $nome = $proc.ProcessName; $exe = $proc.Path; $pasta = Split-Path $proc.Path -Parent
                    $aberto = $true; $procBase = $proc.ProcessName
                    break
                }
            }
            elseif ($num -eq ($cnt + 3)) {
                $pf = (Read-Host "Pasta (ex: D:\Jogos). Cada subpasta vira um jogo").Trim().Trim('"')
                if ($pf -and (Test-Path -LiteralPath $pf -PathType Container)) {
                    $cfg = Load-Config
                    $cfg.PastasJogos = @(@($cfg.PastasJogos) + $pf | Sort-Object -Unique)
                    Save-Config $cfg
                    $script:JogosCache = $null
                    $jogos = @(Get-JogosInstalados)
                    Show-Ok "Pasta salva. Os jogos dela aparecem na lista."
                } else { Write-Host "Pasta nao encontrada." -ForegroundColor Red }
            }
        }
    }

    if ($cand) {
        $nome = $cand.Nome; $pasta = $cand.Pasta; $uri = $cand.LaunchUri; $exe = $cand.ExeConhecido
        if ($exe -and -not (Test-Path -LiteralPath $exe)) { $exe = $null }
        if (-not $exe -and (-not $uri -or $resolverExe)) {
            Write-Host "Procurando o executavel principal dentro da pasta do jogo..." -ForegroundColor DarkGray
            $exe = Find-JogoExe $pasta $nome
        }
        if (-not $exe -and -not $uri) {
            $caminho = (Read-Host "Nao achei o .exe sozinho. Caminho completo do .exe do jogo").Trim().Trim('"')
            if ($caminho -and (Test-Path -LiteralPath $caminho)) { $exe = $caminho } else { return $null }
        }
    }
    return [pscustomobject]@{ Nome = $nome; Exe = $exe; Pasta = $pasta; Uri = $uri; JaAberto = $aberto; ProcBase = $procBase }
}

# ---------------- Modo Jogo ----------------
function Fechar-AppsPesados {
    $lista = @('chrome', 'msedge', 'firefox', 'brave', 'opera', 'vivaldi', 'OneDrive', 'Teams', 'ms-teams', 'Spotify', 'PhoneExperienceHost',
        'YourPhone', 'GameBar', 'Widgets', 'WidgetService', 'Dropbox', 'GoogleDriveFS', 'Skype', 'Zoom', 'Slack', 'wallpaper32', 'wallpaper64',
        'livelywpf', 'Overwolf', 'OverwolfBrowser')
    $abertos = @(Get-Process -Name $lista -ErrorAction SilentlyContinue)
    if ($abertos.Count -eq 0) { Write-Host "  Nenhum programa pesado aberto." -ForegroundColor DarkGray; return }
    $grupos = @($abertos | Group-Object ProcessName | ForEach-Object {
        [pscustomobject]@{ Nome = $_.Name; Qtd = $_.Count; MB = [math]::Round((($_.Group | Measure-Object -Property WorkingSet64 -Sum).Sum) / 1MB, 0) }
    } | Sort-Object MB -Descending)
    Write-Host ""
    Write-Host "Programas pesados abertos (salve seu trabalho antes de fechar):" -ForegroundColor Yellow
    $n = 1
    foreach ($g in $grupos) { Write-Host ("{0,3}) {1,-22} {2,3} processo(s)  {3,6} MB" -f $n, $g.Nome, $g.Qtd, $g.MB); $n++ }
    $sel = Read-Host "Fechar quais? Numeros (ex: 1,3) | T = todos | Enter = nenhum"
    if (-not $sel) { return }
    $alvos = @()
    if ($sel -match '^[tT]') { $alvos = $grupos } else { foreach ($ix in @(ConvertTo-Indices $sel $grupos.Count)) { $alvos += $grupos[$ix - 1] } }
    foreach ($g in $alvos) {
        Get-Process -Name $g.Nome -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Show-Ok ("Fechado: " + $g.Nome)
    }
}

function Pausar-Servicos {
    $nomes = @('SysMain', 'WSearch', 'DiagTrack', 'wuauserv', 'UsoSvc', 'DoSvc', 'WerSvc', 'MapsBroker')
    $atualizando = [bool](Get-Process -Name TiWorker -ErrorAction SilentlyContinue)
    $parados = @()
    foreach ($s in $nomes) {
        if ($atualizando -and ($s -eq 'wuauserv' -or $s -eq 'UsoSvc')) { continue }
        $sv = Get-Service -Name $s -ErrorAction SilentlyContinue
        if ($sv -and $sv.Status -eq 'Running') {
            Stop-Service -Name $s -Force -ErrorAction SilentlyContinue
            $parados += $s
        }
    }
    return $parados
}

function Save-Sessao($antes, $temp, $servicos) {
    $o = [pscustomobject]@{ Pid = $PID; Antes = $antes; Temp = $temp; Servicos = @($servicos) }
    try { ConvertTo-Json -InputObject $o | Set-Content -LiteralPath $script:SessaoFile -Encoding UTF8 } catch { }
}

# Se a janela foi fechada no meio de uma sessao, restaura plano de energia e servicos
function Recuperar-Sessao {
    if (-not (Test-Path -LiteralPath $script:SessaoFile)) { return }
    $s = $null
    try { $s = Get-Content -LiteralPath $script:SessaoFile -Raw | ConvertFrom-Json } catch { }
    if ($s -and $s.Pid -and (Get-Process -Id $s.Pid -ErrorAction SilentlyContinue)) { return }
    Write-Host "Encontrei uma sessao do Modo Jogo que nao terminou direito. Restaurando o estado normal..." -ForegroundColor Yellow
    if ($s) {
        if ($s.Antes) {
            & powercfg.exe /setactive ([string]$s.Antes) 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) { & powercfg.exe /setactive SCHEME_BALANCED 2>&1 | Out-Null }
        }
        if ($s.Temp) { & powercfg.exe /delete ([string]$s.Temp) 2>&1 | Out-Null }
        foreach ($sv in @($s.Servicos)) { if ($sv) { Start-Service -Name ([string]$sv) -ErrorAction SilentlyContinue } }
    }
    Remove-Item -LiteralPath $script:SessaoFile -Force -ErrorAction SilentlyContinue
    Show-Ok "Estado restaurado"
    Start-Sleep -Seconds 1
}

# Abre o jogo pelo Explorer: ele roda SEM privilegio de administrador (melhor compatibilidade
# com Steam/Discord/overlays), enquanto o toolkit continua elevado para ajustar prioridades.
function Start-JogoNaoElevado($alvo) {
    try { Start-Process -FilePath 'explorer.exe' -ArgumentList ('"{0}"' -f $alvo) -ErrorAction Stop }
    catch { Start-Process -FilePath $alvo -ErrorAction SilentlyContinue }
}

function Get-ProcessosJogo($sel, $idsAntes) {
    $res = @()
    $ignorar = 'crash|helper|webhelper|launcher_cef|steamwebhelper'
    if ($sel.Pasta -and ([string]$sel.Pasta) -notlike ($env:SystemRoot + '*')) {
        $prefixo = ([string]$sel.Pasta).TrimEnd('\') + '\'
        $res = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
            $_.Path -and $_.Path.StartsWith($prefixo, [StringComparison]::OrdinalIgnoreCase) -and $_.ProcessName -notmatch $ignorar })
    }
    if ($res.Count -eq 0 -and $sel.ProcBase) { $res = @(Get-Process -Name $sel.ProcBase -ErrorAction SilentlyContinue) }
    if ($res.Count -eq 0 -and $sel.Exe) {
        $base = [IO.Path]::GetFileNameWithoutExtension($sel.Exe)
        $res = @(Get-Process -Name $base -ErrorAction SilentlyContinue)
    }
    if ($res.Count -eq 0 -and $idsAntes -and -not $sel.JaAberto) {
        $ign2 = 'steam|explorer|epic|ubisoft|upc|origin|ea|galaxy|battle|rockstar|launcher|helper|powershell|conhost|ApplicationFrameHost|Claude'
        $res = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
            $_.MainWindowHandle -ne 0 -and ($idsAntes -notcontains $_.Id) -and $_.ProcessName -notmatch $ign2 })
    }
    return $res
}

function Modo-Jogo {
    param([string]$Preselecionado)

    Write-Host ""
    Write-Host "=== MODO JOGO ===" -ForegroundColor Cyan
    Write-Host "Prepara o PC, abre o jogo (ou usa um que ja esta aberto), da prioridade a ele, aplica o pacote TURBO,"
    Write-Host "mede FPS e gargalo, vigia a temperatura e, quando voce fechar o jogo, devolve tudo ao normal."
    Write-Host "Nao injeta nada no jogo: so mexe no Windows." -ForegroundColor DarkGray

    $sel = Selecionar-Jogo $Preselecionado $false
    if (-not $sel) { Write-Host "Cancelado."; return }
    $cfg = Load-Config
    Write-Host ""
    Write-Host ("Jogo: {0}" -f $sel.Nome) -ForegroundColor Green

    Write-Host ""
    Write-Host "Perfil de energia/temperatura para esta sessao:" -ForegroundColor Cyan
    Write-Host " 1) MAXIMO FPS       (turbo agressivo; mais quente)"
    Write-Host " 2) EQUILIBRADO FRIO (turbo desligado; bem mais frio, perde um pouco de FPS)"
    Write-Host " 3) SILENCIOSO       (CPU limitada a 70%)"
    Write-Host " 4) Manter o plano de energia atual"
    $padrao = '1'
    if ($cfg.PerfilJogo -eq 'Frio') { $padrao = '2' }
    elseif ($cfg.PerfilJogo -eq 'Silencioso') { $padrao = '3' }
    elseif ($cfg.PerfilJogo -eq 'Manter') { $padrao = '4' }
    $op = Read-Host ("Escolha (Enter = {0})" -f $padrao)
    if (-not $op) { $op = $padrao }
    $perfil = 'Maximo'
    $maxPct = 100
    if ($op -eq '2') { $perfil = 'Frio'; $maxPct = 99 }
    elseif ($op -eq '3') { $perfil = 'Silencioso'; $maxPct = 70 }
    elseif ($op -eq '4') { $perfil = 'Manter' }
    $cfg.PerfilJogo = $perfil
    Save-Config $cfg

    $protecao = $false
    if ($perfil -eq 'Maximo') {
        $tt = Get-Termica
        if ($null -ne $tt.TempC) {
            $protecao = Ask-YesNo ("Ativar protecao termica automatica? (se a CPU chegar a {0} C, troco sozinho para o perfil Equilibrado frio)" -f $cfg.LimiteTemp)
        }
    }

    Fechar-AppsPesados
    Write-Host ""
    $pausar = Ask-YesNo "Pausar servicos pesados em segundo plano durante o jogo (Windows Update, indexacao, telemetria...)? Voltam sozinhos ao terminar"
    $ram = Ask-YesNo "Liberar RAM de programas em segundo plano antes de abrir?"

    Initialize-Fps | Out-Null
    $topo = Get-TopologiaCpu
    Write-Host ""
    Write-Host "PACOTE TURBO (so durante o jogo; tudo volta ao normal quando voce fechar):" -ForegroundColor Cyan
    Write-Host "  - Timer do Windows em 0.5 ms (quadros mais uniformes, principalmente com limite de FPS)"
    Write-Host "  - Limpeza automatica da memoria standby quando a RAM livre ficar baixa (menos engasgos)"
    Write-Host "  - Navegador, launchers e apps de nuvem com prioridade BAIXA enquanto voce joga (Discord fica normal)"
    if ($topo.Descricao) { Write-Host ("  - Opcional: " + $topo.Descricao + " -> prender o jogo nos nucleos mais rapidos") }
    $turbo = Ask-YesNo "Ativar o pacote TURBO?"
    $prenderNucleos = $false
    if ($turbo -and $topo.Mascara -gt 0) {
        $prenderNucleos = Ask-YesNo "  Prender o jogo nos nucleos mais rapidos? (alguns anti-cheats recusam; se o jogo ficar estranho, responda N da proxima vez)"
    }
    $pmPath = Get-PresentMonPath
    $medir = $false
    if ($pmPath) { $medir = Ask-YesNo "Medir o FPS durante a sessao (FPS medio, 1% low, engasgos e gargalo no final)?" }
    else { Show-Info "Dica: instale o medidor de FPS em FPS > Medir FPS para ver FPS medio e 1% low no fim de cada sessao." }
    $cfg | Add-Member -NotePropertyName TurboJogo -NotePropertyValue $turbo -Force
    Save-Config $cfg

    $gpus = @(Get-CimInstance Win32_VideoController | Where-Object { $_.Name -notmatch 'Basic|Remote|Virtual|Parsec|Meta|Citrix|Mirage' })
    if ($gpus.Count -ge 2 -and -not $sel.JaAberto) {
        Write-Host ""
        Write-Host ("Seu PC tem {0} placas de video: {1}" -f $gpus.Count, (($gpus | ForEach-Object { $_.Name }) -join ' + ')) -ForegroundColor Yellow
        if (Ask-YesNo "Forcar a GPU de alto desempenho para este jogo?") {
            $exeGpu = $sel.Exe
            if (-not $exeGpu) { $exeGpu = Find-JogoExe $sel.Pasta $sel.Nome }
            if ($exeGpu) {
                Set-RegBackup 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences' $exeGpu (Merge-DxSetting (Get-RegString 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences' $exeGpu) 'GpuPreference' '2') 'String'
                Show-Ok "Preferencia de GPU: alto desempenho"
            }
        }
    }

    $esquemaAntes = $null
    $esquemaTemp = $null
    $parados = @()
    $inicio = Get-Date
    $pico = 0
    $throttle = 0
    $leituras = 0
    $protegido = $false
    $quentes = 0
    $timerAtivo = $false
    $prioOrig = @{}
    $rebaixou = $false
    $pmProc = $null
    $pmCsv = $null
    $amGpu = New-Object System.Collections.ArrayList
    $amCore = New-Object System.Collections.ArrayList
    $ultimaPurga = [datetime]::MinValue
    $purgas = 0
    $pidPrincipal = $null

    try {
        Write-Host ""
        if ($pausar) {
            $parados = @(Pausar-Servicos)
            Show-Ok ("Servicos pausados: {0}" -f $parados.Count)
        }
        if ($perfil -ne 'Manter') {
            $esquemaAntes = Get-EsquemaAtivo
            $esquemaTemp = New-EsquemaPerfil $perfil $maxPct
            if ($esquemaTemp) {
                & powercfg.exe /setactive $esquemaTemp 2>&1 | Out-Null
                Show-Ok ("Plano de energia da sessao ativo: {0}" -f $perfil)
            } else { Show-Aviso "Nao consegui criar o plano de energia; segui com o atual." }
        }
        Save-Sessao $esquemaAntes $esquemaTemp $parados
        if ($ram) { Liberar-RAM $null }
        & ipconfig.exe /flushdns | Out-Null
        if ($turbo) {
            if (Set-TimerResolucao $true) { $timerAtivo = $true; Show-Ok "Timer do Windows em alta resolucao (0.5 ms)" }
            if (Limpar-Standby) { Show-Ok "Memoria standby limpa antes de abrir o jogo" }
        }

        $idsAntes = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | ForEach-Object { $_.Id })
        if ($sel.JaAberto) {
            Write-Host ("Usando o jogo que ja esta aberto: {0}" -f $sel.Nome) -ForegroundColor Green
        } else {
            $alvo = $sel.Uri
            if (-not $alvo) { $alvo = $sel.Exe }
            Write-Host ("Abrindo {0} ..." -f $sel.Nome) -ForegroundColor Green
            Start-JogoNaoElevado $alvo
            if ($sel.Exe) { Set-Content -LiteralPath $script:UltimoJogoFile -Value $sel.Exe -ErrorAction SilentlyContinue }
            Start-Sleep -Seconds 15
        }

        Write-Host "Vigiando o jogo (prioridade alta + temperatura). Feche o jogo normalmente quando terminar." -ForegroundColor Cyan
        $ajustados = @{}
        $semProc = $null
        $ultimaTemp = [datetime]::MinValue
        while ($true) {
            $procs = @(Get-ProcessosJogo $sel $idsAntes)
            if ($procs.Count -eq 0) {
                if (-not $semProc) { $semProc = Get-Date }
                $limite = 120
                if ($ajustados.Count -gt 0) { $limite = 20 }
                if (((Get-Date) - $semProc).TotalSeconds -gt $limite) { break }
            } else {
                $semProc = $null
                foreach ($p in $procs) {
                    if (-not $ajustados.ContainsKey($p.Id)) {
                        $ajustados[$p.Id] = $p.ProcessName
                        try {
                            $p.PriorityClass = 'High'
                            Show-Ok ("Prioridade ALTA: {0} (PID {1})" -f $p.ProcessName, $p.Id)
                        } catch { Show-Info ("  {0}: prioridade protegida pelo jogo/anti-cheat (normal)" -f $p.ProcessName) }
                        if ($prenderNucleos -and $topo.Mascara -gt 0) {
                            try { $p.ProcessorAffinity = [IntPtr]$topo.Mascara; Show-Ok ("{0} preso nos {1} nucleos rapidos" -f $p.ProcessName, $topo.NP) } catch { }
                        }
                    }
                }
                $principal = $procs | Sort-Object WorkingSet64 -Descending | Select-Object -First 1
                if ($principal) { $pidPrincipal = $principal.Id }
                if ($turbo -and -not $rebaixou) {
                    $rebaixou = $true
                    $prioOrig = Rebaixar-SegundoPlano @($procs | ForEach-Object { $_.Id })
                    if ($prioOrig.Count -gt 0) { Show-Ok ("{0} processos de fundo (navegador, launchers, nuvem) com prioridade baixa" -f $prioOrig.Count) }
                }
                if ($medir -and -not $pmProc -and $principal -and ((Get-Date) - $inicio).TotalSeconds -ge 25) {
                    $pmCsv = Join-Path $env:TEMP ("pctk-sessao-" + (Get-Date -Format 'yyyyMMddHHmmss') + '.csv')
                    $pmProc = Iniciar-PresentMon $pmPath ($principal.ProcessName + '.exe') $pmCsv 0 (@(Get-EstilosPresentMon $pmPath)[0])
                    if ($pmProc) { Show-Ok ("Medindo FPS de {0}.exe (resultado quando o jogo fechar)" -f $principal.ProcessName) }
                }
            }

            if (((Get-Date) - $ultimaTemp).TotalSeconds -ge 20) {
                $ultimaTemp = Get-Date
                $t = Get-Termica
                $ga = Get-AmostraGpu $pidPrincipal
                $nmx = Get-NucleoMax
                $extra = ''
                if ($null -ne $ga.Uso3D) { $extra += ("  |  GPU {0}%" -f $ga.Uso3D) }
                if ($ga.VramMB) { $extra += (", VRAM {0} MB" -f $ga.VramMB) }
                if ($null -ne $nmx) { $extra += ("  |  nucleo mais usado {0}%" -f $nmx) }
                if ($ajustados.Count -gt 0) {
                    if ($null -ne $ga.Uso3D) { [void]$amGpu.Add($ga.Uso3D) }
                    if ($null -ne $nmx) { [void]$amCore.Add($nmx) }
                }
                $leituras++
                if ($t.Throttle) { $throttle++ }
                if ($null -eq $t.TempC -and $extra) { Write-Host ("  [{0}] CPU temp n/d{1}" -f (Get-Date -Format 'HH:mm:ss'), $extra) -ForegroundColor Gray }
                if ($null -ne $t.TempC) {
                    if ($t.TempC -gt $pico) { $pico = $t.TempC }
                    Write-Host ("  [{0}] {1}{2}" -f (Get-Date -Format 'HH:mm:ss'), (Format-LinhaTermica $t $null), $extra) -ForegroundColor (Get-CorTemp $t.TempC)
                    if ($protecao -and -not $protegido -and $t.TempC -ge $cfg.LimiteTemp) {
                        $quentes++
                        if ($quentes -ge 2 -and $esquemaTemp) {
                            Set-ValoresEsquema $esquemaTemp (Get-CfgPerfil 'Frio' 99)
                            & powercfg.exe /setactive $esquemaTemp 2>&1 | Out-Null
                            $protegido = $true
                            Show-Aviso ("Protecao termica: CPU em {0:N0} C. Troquei para EQUILIBRADO FRIO (turbo desligado)." -f $t.TempC)
                        }
                    } else { $quentes = 0 }
                }
            }
            if ($turbo -and ((Get-Date) - $ultimaPurga).TotalSeconds -ge 60) {
                $osM = Get-CimInstance Win32_OperatingSystem
                if ($osM) {
                    $livreMB = [double]$osM.FreePhysicalMemory / 1024
                    $limMB = [math]::Max(1024, ([double]$osM.TotalVisibleMemorySize / 1024) * 0.10)
                    if ($livreMB -lt $limMB) { if (Limpar-Standby) { $purgas++ }; $ultimaPurga = Get-Date }
                }
            }
            Start-Sleep -Seconds 5
        }

        Write-Host ""
        if ($ajustados.Count -eq 0) { Show-Aviso "Nao consegui detectar o processo do jogo (ele pode ter demorado ou aberto de outro jeito)." }
        else { Show-Ok "Jogo encerrado." }
        $dur = (Get-Date) - $inicio
        Write-Host ("Sessao: {0:N0} min" -f $dur.TotalMinutes)
        if ($leituras -gt 0 -and $pico -gt 0) { Write-Host ("Pico de temperatura da CPU: {0:N0} C" -f $pico) -ForegroundColor (Get-CorTemp $pico) }
        if ($throttle -gt 0) { Show-Aviso ("A CPU foi limitada por calor em {0} leitura(s). Use o perfil Equilibrado frio ou limite o FPS do jogo." -f $throttle) }
        elseif ($pico -ge 90) { Show-Aviso "A CPU passou de 90 C. Considere o perfil Equilibrado frio e limpar a poeira (menu Temperatura)." }
        $fpsRes = $null
        if ($pmProc) {
            Parar-PresentMon $pmProc
            $pmProc = $null
            Start-Sleep -Seconds 1
            if ($pmCsv -and (Test-Path -LiteralPath $pmCsv)) { $fpsRes = Ler-CsvPresentMon $pmCsv; Remove-Item -LiteralPath $pmCsv -Force -ErrorAction SilentlyContinue }
            if ($fpsRes) { Mostrar-ResultadoFps $fpsRes } else { Show-Info "Nao consegui ler o FPS desta sessao (o jogo pode ter bloqueado a leitura ou fechado cedo demais)." }
        }
        $garg = Get-Gargalo $amGpu $amCore $fpsRes
        if ($garg) { Mostrar-Gargalo $garg }
        if ($amGpu.Count -gt 0) {
            $mgT = [math]::Round((($amGpu | Measure-Object -Average).Average), 0)
            $mcT = 0
            if ($amCore.Count -gt 0) { $mcT = [math]::Round((($amCore | Measure-Object -Average).Average), 0) }
            Show-Info ("Uso medio da placa de video: {0}%  |  nucleo de CPU mais usado (media): {1}%" -f $mgT, $mcT)
        }
        if ($purgas -gt 0) { Show-Info ("Memoria standby limpa {0} vez(es) durante o jogo para evitar engasgos." -f $purgas) }
        $hs = [pscustomobject]@{ Data = (Get-Date -Format 'yyyy-MM-dd HH:mm'); Jogo = [string]$sel.Nome; Min = [int][math]::Round($dur.TotalMinutes, 0); Perfil = $perfil; PicoCpu = [int]$pico; Fps = $null; Low1 = $null; Gargalo = $garg; Turbo = $turbo }
        if ($fpsRes) { $hs.Fps = $fpsRes.Fps; $hs.Low1 = $fpsRes.Low1 }
        Registrar-SessaoHistorico $hs
        Write-Log ("Modo Jogo: {0} | {1:N0} min | pico {2}" -f $sel.Nome, $dur.TotalMinutes, $pico)
    } finally {
        if ($pmProc) { Parar-PresentMon $pmProc }
        if ($timerAtivo) { [void](Set-TimerResolucao $false) }
        if ($prioOrig -and $prioOrig.Count -gt 0) { Restaurar-Prioridades $prioOrig; Show-Ok "Prioridade dos apps de fundo restaurada" }
        if ($esquemaTemp) {
            $volta = $esquemaAntes
            if (-not (Test-EsquemaExiste $volta)) { $volta = 'SCHEME_BALANCED' }
            & powercfg.exe /setactive $volta 2>&1 | Out-Null
            & powercfg.exe /delete $esquemaTemp 2>&1 | Out-Null
            Show-Ok "Plano de energia anterior restaurado"
        }
        foreach ($sv in $parados) { Start-Service -Name $sv -ErrorAction SilentlyContinue }
        if ($parados.Count -gt 0) { Show-Ok "Servicos restaurados" }
        Remove-Item -LiteralPath $script:SessaoFile -Force -ErrorAction SilentlyContinue
    }
}

# ---------------- desfazer ----------------
function Desfazer {
    Write-Host ""
    Write-Host "=== DESFAZER MUDANCAS DO TOOLKIT ===" -ForegroundColor Cyan
    $feito = $false

    $undo = @(Load-Undo)
    if ($undo.Count -gt 0) {
        foreach ($e in $undo) {
            if (-not $e.Path -or -not $e.Name) { continue }
            if ($e.Existed) {
                $val = $e.Old
                if ($e.Type -eq 'DWord') { $val = [int]$e.Old }
                Set-ItemProperty -LiteralPath $e.Path -Name $e.Name -Value $val -Type $e.Type -Force -ErrorAction SilentlyContinue
            } else {
                Remove-ItemProperty -LiteralPath $e.Path -Name $e.Name -Force -ErrorAction SilentlyContinue
            }
        }
        Remove-Item -LiteralPath $script:UndoFile -Force -ErrorAction SilentlyContinue
        Show-Ok ("{0} configuracoes do Windows revertidas" -f $undo.Count)
        $feito = $true
    }

    if (Restaurar-EsquemaOriginal) { Show-Ok "Plano de energia original restaurado"; $feito = $true }
    if (Restaurar-Visual) { Show-Ok "Efeitos visuais do Windows restaurados"; $feito = $true }
    if (Restaurar-Rede) { Show-Ok "Placa de rede, DNS e regras de QoS restaurados"; $feito = $true }

    $arqs = @(Load-Arquivos) + @($script:FxSettings, $script:RkSettings)
    foreach ($a in @($arqs | Where-Object { $_ } | Sort-Object -Unique)) {
        $bak = $a + '.bak-fps'
        $criado = $a + '.created-fps'
        if (Test-Path -LiteralPath $bak) {
            Copy-Item -LiteralPath $bak -Destination $a -Force
            Remove-Item -LiteralPath $bak -Force -ErrorAction SilentlyContinue
            Show-Ok ("Configuracao restaurada: " + $a)
            $feito = $true
        } elseif (Test-Path -LiteralPath $criado) {
            Remove-Item -LiteralPath $a -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $criado -Force -ErrorAction SilentlyContinue
            Show-Ok ("Arquivo criado pelo toolkit removido: " + $a)
            $feito = $true
        }
    }
    Remove-Item -LiteralPath $script:ArquivosFile -Force -ErrorAction SilentlyContinue

    Write-Host ""
    if (Ask-YesNo "Voce compactou o Windows (CompactOS) pela Limpeza e quer reverter?") {
        & compact.exe /compactOS:never | ForEach-Object { Write-Host ("  " + $_) }
        $feito = $true
    }

    if (-not $feito) { Write-Host "Nao havia nada para desfazer." }
    else { Write-Host "Pronto. Reinicie o PC para tudo voltar ao normal." -ForegroundColor Green }
}

function Menu-FPS {
    while ($true) {
        Clear-Host
        Write-Host "=========== CENTRAL DE FPS / JOGOS ===========" -ForegroundColor Green
        $cfgM = Load-Config
        if ($cfgM.UltimaNotaFps) { Write-Host ("Ultima nota do Raio-X de FPS: {0}/100" -f $cfgM.UltimaNotaFps) -ForegroundColor DarkGray }
        Write-Host ""
        Write-Host " 1) RAIO-X DE FPS: nota do PC + correcao com 1 clique (comece por aqui)" -ForegroundColor Green
        Write-Host " 2) MODO JOGO: prepara, abre o jogo, pacote TURBO, mede FPS e restaura tudo" -ForegroundColor Green
        Write-Host " 3) Medir FPS / benchmark ANTES x DEPOIS (FPS medio, 1% low, engasgos, gargalo)"
        Write-Host " 4) Otimizar o Windows para jogos (energia, Game DVR, MMCSS, animacoes, DirectX)"
        Write-Host " 5) Otimizar graficos dos jogos (GTA V/FiveM, Minecraft, Fortnite/Valorant/Unreal, CS2, Apex, LoL, Roblox)"
        Write-Host " 6) Ajustes por jogo (placa dedicada, tela cheia, prioridade fixa, QoS) - qualquer jogo"
        Write-Host " 7) Monitor: Hz maximo e limite de FPS ideal (G-Sync/FreeSync)"
        Write-Host " 8) Rede para jogos online (jitter, perda, DNS mais rapido, placa de rede)"
        Write-Host " 9) Latencia e input (timer, mouse, MPO, Power Throttling)"
        Write-Host "10) Diagnostico completo do PC (relatorio)"
        Write-Host "11) Programas de inicializacao (o que abre com o Windows)"
        Write-Host "12) Liberar RAM agora (+ memoria standby)"
        Write-Host "13) Historico de sessoes e medicoes de FPS"
        Write-Host "14) Desfazer todas as mudancas"
        Write-Host "15) Voltar ao menu principal"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1'  { RaioX-Fps; Pausa }
            '2'  { Modo-Jogo; Pausa }
            '3'  { Benchmark-Fps; Pausa }
            '4'  { Otimizar-Windows; Pausa }
            '5'  { Otimizar-Graficos; Pausa }
            '6'  { Ajustes-PorJogo; Pausa }
            '7'  { Menu-Tela; Pausa }
            '8'  { Menu-Rede }
            '9'  { Otimizar-Latencia }
            '10' { Diagnostico; Pausa }
            '11' { Gerenciar-Inicializacao; Pausa }
            '12' { Liberar-RAM $null; Pausa }
            '13' { Mostrar-Historico; Pausa }
            '14' { Desfazer; Pausa }
            '15' { return }
        }
    }
}

# ======================================================================
# SECAO 7: CACADOR DE AMEACAS (malware, mineradores, acesso remoto, invasores)
#
# E um detector HEURISTICO: ele aponta o que e SUSPEITO e explica o motivo.
# - Nunca apaga nada sem perguntar.
# - Tudo que for removido vai para a QUARENTENA e pode ser restaurado.
# - Nao substitui antivirus: complementa o Windows Defender (que tambem e
#   acionado por este menu).
# - Rootkits profundos podem se esconder de qualquer ferramenta que rode
#   dentro do Windows; para esses casos use a verificacao OFFLINE do Defender.
# ======================================================================
$script:QuarDir   = Join-Path $env:ProgramData 'PC-Toolkit-Quarentena'
$script:QuarIndex = Join-Path $script:QuarDir 'indice.json'
$script:RelFile   = Join-Path $script:LogDir 'ultimo-relatorio-seguranca.txt'
$script:SigCache  = @{}
$script:Achados   = New-Object System.Collections.ArrayList
$script:ChavesAch = @{}
$script:ProcMap   = @{}

# --- assinaturas de comportamento (expressoes regulares) ---
$script:RxMinerNome = '(xmrig|xmr-stak|xmrstak|nbminer|phoenixminer|lolminer|gminer|ethminer|cgminer|bfgminer|sgminer|minerd|cpuminer|nanominer|teamredminer|ccminer|srbminer|kryptex|nicehash|minergate|wildrig|bzminer|nheqminer|easyminer|multiminer|coinminer|coinhive|t-rex)'
$script:RxMinerCmd  = 'stratum\+?(tcp|ssl|udp)?://|stratum2?\+|--donate-level|--coin[= ]|--algo[= ]|--cuda|--opencl|cryptonight|rx/0|randomx|kawpow|ethash|etchash|autolykos|minexmr|supportxmr|moneroocean|nanopool\.org|2miners\.com|ethermine\.org|f2pool\.com|hashvault\.pro|c3pool|unmineable|herominers|flypool|miningpoolhub|nicehash\.com|minergate'
$script:RxCmdSusp   = '(-e(nc|ncodedcommand)?\s+[A-Za-z0-9+/=]{60,})|FromBase64String|(iex|invoke-expression)\s*[\(\$]|DownloadString|DownloadFile|DownloadData|mshta(\.exe)?\s+(https?|vbscript|javascript)|certutil.*-urlcache|bitsadmin.*/transfer|regsvr32.*/i:https?|rundll32.*javascript|-w(indowstyle)?\s+hidden.*(http|iex|-e\s)'
$script:RxHackAlto  = '^(mimikatz|lazagne|rubeus|sharphound|bloodhound|meterpreter|cobaltstrike|beacon|crackmapexec|chisel|frpc|frps|havoc|sliver|pwdump|fgdump|wce|nanodump|mimilib|covenant)$'
$script:RxHackMedio = '^(nc|nc64|ncat|netcat|psexesvc|procdump|procdump64|masscan|hydra|hashcat)$'
$script:RxRemoto    = '(anydesk|teamviewer|rustdesk|ultraviewer|ammyy|supremo|radmin|screenconnect|connectwise|splashtop|netsupport|dwservice|ngrok|winvnc|tightvnc|ultravnc|vncserver|logmein|remotepc|meshagent|aeroadmin|hoptodesk|zohoassist|tacticalrmm)'
$script:RxLookalike = '^(scvhost|svhost|svch0st|svchots|svchost32|svcshost|lsasss|lssas|isass|csrrs|csrs|explorrer|expl0rer|explorer32|winlog0n|servcies|taskhost32|runtimebroker32)\.exe$'
$script:RxPoolDns   = '(minexmr|supportxmr|moneroocean|nanopool|2miners|ethermine|f2pool|hashvault|c3pool|unmineable|herominers|flypool|miningpoolhub|nicehash|minergate|stratum|xmrpool|monerohash|antpool|viabtc|kryptex|zpool|prohashing|woolypooly|crazypool|hiveon)'
$script:PortasMiner = @(3333, 3334, 4444, 5555, 7777, 14433, 14444, 45560, 45700, 19999, 10128, 10300, 10343, 3357)

# processos do Windows que so podem rodar de pastas especificas
$script:SysNomes = @{}
$swin = $env:windir.ToLower()
foreach ($sn in @('svchost', 'csrss', 'lsass', 'services', 'smss', 'wininit', 'winlogon', 'taskhostw', 'dllhost', 'conhost', 'runtimebroker', 'spoolsv', 'searchindexer', 'fontdrvhost', 'sihost', 'ctfmon')) {
    $script:SysNomes[$sn + '.exe'] = @(($swin + '\system32\'), ($swin + '\syswow64\'))
}
$script:SysNomes['explorer.exe'] = @(($swin + '\explorer.exe'))

# ----------------------------------------------------------------
# Auxiliares
# ----------------------------------------------------------------
function Test-CaminhoSuspeito($p) {
    if (-not $p) { return $false }
    $lista = @('*\AppData\Local\Temp\*', '*\Windows\Temp\*', '*\Users\Public\*', '*\ProgramData\*', '*\AppData\Roaming\*', '*\AppData\Local\*', '*\Downloads\*', '*\$Recycle.Bin\*', '*\Windows\Tasks\*', '*\Windows\Debug\*', '*\PerfLogs\*', '*\Windows\Fonts\*')
    foreach ($x in $lista) { if ($p -like $x) { return $true } }
    return $false
}

# Pastas onde programas legitimos quase nunca rodam (diferente de AppData\Local, comum em apps normais)
function Test-CaminhoForte($p) {
    if (-not $p) { return $false }
    foreach ($x in @('*\AppData\Local\Temp\*', '*\Windows\Temp\*', '*\Users\Public\*', '*\$Recycle.Bin\*', '*\Windows\Tasks\*', '*\Windows\Debug\*', '*\PerfLogs\*', '*\Windows\Fonts\*')) {
        if ($p -like $x) { return $true }
    }
    return $false
}

function Test-CaminhoProtegido($p) {
    if (-not $p) { return $true }
    $pl = ([string]$p).ToLower()
    $w = $env:windir.ToLower()
    foreach ($pre in @(($w + '\system32\'), ($w + '\syswow64\'), ($w + '\winsxs\'), ($w + '\explorer.exe'))) {
        if ($pl.StartsWith($pre)) { return $true }
    }
    return $false
}

function Test-IpPrivado($ip) {
    if (-not $ip) { return $true }
    $ip = [string]$ip
    if ($ip -eq '::1' -or $ip -eq '127.0.0.1' -or $ip -eq '0.0.0.0' -or $ip -eq '::' -or $ip -eq '-') { return $true }
    if ($ip -match '^(10\.|127\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)') { return $true }
    if ($ip -match '^(fe80:|f[cd][0-9a-fA-F]{2}:)') { return $true }
    if ($ip -match '^::ffff:(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)') { return $true }
    return $false
}

function Get-Assinatura($path) {
    if (-not $path) { return 'SemCaminho' }
    if ($script:SigCache.ContainsKey($path)) { return $script:SigCache[$path] }
    $r = 'Desconhecida'
    try {
        $s = Get-AuthenticodeSignature -LiteralPath $path -ErrorAction Stop
        if ($s.Status -eq 'Valid') {
            $cn = [regex]::Match([string]$s.SignerCertificate.Subject, 'CN="?([^,"]+)')
            $nomeCert = 'assinante'
            if ($cn.Success) { $nomeCert = $cn.Groups[1].Value }
            $r = 'Valida: ' + $nomeCert
        }
        elseif ($s.Status -eq 'NotSigned') { $r = 'NaoAssinado' }
        else { $r = 'Invalida (' + $s.Status + ')' }
    } catch { }
    $script:SigCache[$path] = $r
    return $r
}

function Get-ExeDoComando($cmd) {
    if (-not $cmd) { return $null }
    $c = [Environment]::ExpandEnvironmentVariables(([string]$cmd).Trim())
    if ($c.StartsWith('"')) {
        $fim = $c.IndexOf('"', 1)
        if ($fim -gt 1) { return $c.Substring(1, $fim - 1) }
        return $c.Trim('"')
    }
    $m = [regex]::Match($c, '^(.+?\.(exe|bat|cmd|ps1|vbs|vbe|js|jse|wsf|scr|com|hta|msi))(\s|$|,)', 'IgnoreCase')
    if ($m.Success) { return $m.Groups[1].Value }
    return ($c -split '\s+')[0]
}

function Get-CaminhosNoComando($txt) {
    $res = New-Object System.Collections.ArrayList
    $ms = [regex]::Matches([string]$txt, '[A-Za-z]:\\[^"<>|*?\r\n]+?\.(exe|dll|bat|cmd|ps1|vbs|vbe|js|jse|wsf|scr|hta|jar|com)(?=[\s",;]|$)', 'IgnoreCase')
    foreach ($m in $ms) {
        $v = $m.Value
        if (-not $res.Contains($v)) { [void]$res.Add($v) }
    }
    return @($res)
}

# Avalia um comando (inicializacao, tarefa, servico): devolve Sev/Motivos/Caminho ou $null
function Avaliar-Comando($cmd, $nomeItem) {
    if (-not $cmd) { return $null }
    $txt = [Environment]::ExpandEnvironmentVariables([string]$cmd)
    $sev = 0
    $mot = New-Object System.Collections.ArrayList
    $cam = $null
    if (($txt -match $script:RxMinerCmd) -or ($txt -match $script:RxMinerNome) -or ($nomeItem -and ([string]$nomeItem -match $script:RxMinerNome))) {
        $sev = 3
        [void]$mot.Add('parametros ou nome de programa de mineracao de criptomoeda')
    }
    if ($txt -match $script:RxCmdSusp) {
        $sev = 3
        [void]$mot.Add('comando com codigo ofuscado ou download/execucao oculta')
    }
    $todos = @(Get-CaminhosNoComando $txt)
    $exe = Get-ExeDoComando $txt
    if ($exe -and ($exe -match '^[A-Za-z]:\\') -and ($todos -notcontains $exe)) { $todos += $exe }
    foreach ($p in $todos) {
        if (-not (Test-CaminhoSuspeito $p)) { continue }
        if (-not $cam) { $cam = $p }
        if (Test-Path -LiteralPath $p -PathType Leaf) {
            $sig = Get-Assinatura $p
            if ($sig -notlike 'Valida*') {
                if ($sev -lt 2) { $sev = 2 }
                [void]$mot.Add(("executa arquivo de pasta de usuario/temporaria sem assinatura confiavel [{0}]: {1}" -f $sig, $p))
            }
        }
    }
    if ($sev -eq 0) { return $null }
    if (-not $cam -and $todos.Count -gt 0) { $cam = $todos[0] }
    return [pscustomobject]@{ Sev = $sev; Motivos = @($mot); Caminho = $cam }
}

function Add-Achado($sev, $cat, $titulo, $detalhe, $acao) {
    $chave = ([string]$cat + '|' + [string]$titulo + '|' + [string]$detalhe)
    if ($script:ChavesAch.ContainsKey($chave)) { return }
    $script:ChavesAch[$chave] = $true
    [void]$script:Achados.Add([pscustomobject]@{ Sev = [int]$sev; Cat = $cat; Titulo = $titulo; Detalhe = $detalhe; Acao = $acao })
}

function Get-SevNome($s) {
    if ([int]$s -ge 3) { return 'ALTO' }
    if ([int]$s -eq 2) { return 'MEDIO' }
    if ([int]$s -eq 1) { return 'BAIXO' }
    return 'INFO'
}

function Get-SevCor($s) {
    if ([int]$s -ge 3) { return 'Red' }
    if ([int]$s -eq 2) { return 'Yellow' }
    if ([int]$s -eq 1) { return 'Cyan' }
    return 'DarkGray'
}

# ----------------------------------------------------------------
# Quarentena
# ----------------------------------------------------------------
function Load-QuarIndex {
    if (Test-Path -LiteralPath $script:QuarIndex) {
        try { return @(Get-Content -LiteralPath $script:QuarIndex -Raw | ConvertFrom-Json) } catch { }
    }
    return @()
}
function Save-QuarIndex($lista) {
    try {
        if (-not (Test-Path -LiteralPath $script:QuarDir)) { New-Item -ItemType Directory -Path $script:QuarDir -Force | Out-Null }
        ConvertTo-Json -InputObject @($lista) -Depth 4 | Set-Content -LiteralPath $script:QuarIndex -Encoding UTF8
    } catch { }
}

function Mover-Quarentena($arq, $motivo) {
    if (-not $arq) { return $false }
    if (-not (Test-Path -LiteralPath $arq -PathType Leaf)) { Show-Info ("Arquivo ja nao existe: " + $arq); return $false }
    if (Test-CaminhoProtegido $arq) { Show-Aviso ("Arquivo dentro do sistema do Windows, nao vou mexer: " + $arq); return $false }
    if (-not (Test-Path -LiteralPath $script:QuarDir)) { New-Item -ItemType Directory -Path $script:QuarDir -Force | Out-Null }
    $hash = ''
    try { $hash = (Get-FileHash -LiteralPath $arq -Algorithm SHA256 -ErrorAction Stop).Hash } catch { }
    $sufixo = 'sem-hash'
    if ($hash) { $sufixo = $hash.Substring(0, 8) }
    $id = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $sufixo
    $dest = Join-Path $script:QuarDir ($id + '.quar')
    try { Move-Item -LiteralPath $arq -Destination $dest -Force -ErrorAction Stop }
    catch { Show-Aviso ("Nao consegui mover (em uso?): " + $arq); return $false }
    $idx = @(Load-QuarIndex)
    $idx += [pscustomobject]@{ Tipo = 'Arquivo'; Id = $id; Original = $arq; Sha256 = $hash; Data = (Get-Date).ToString('s'); Motivo = [string]$motivo }
    Save-QuarIndex $idx
    Show-Ok ("Movido para a quarentena: " + $arq)
    if ($hash) { Show-Info ("SHA256: " + $hash + "  (voce pode pesquisar esse codigo em virustotal.com)") }
    Write-Log ("Quarentena: " + $arq)
    return $true
}

function Guardar-RegistroQuar($path, $name, $valor, $motivo) {
    $idx = @(Load-QuarIndex)
    $idx += [pscustomobject]@{ Tipo = 'Registro'; Id = ('reg-' + (Get-Date -Format 'yyyyMMdd-HHmmss')); Original = ($path + '  ::  ' + $name); Path = $path; Name = $name; Valor = [string]$valor; Data = (Get-Date).ToString('s'); Motivo = [string]$motivo }
    Save-QuarIndex $idx
}

function Menu-Quarentena {
    while ($true) {
        Clear-Host
        Write-Host "=========== QUARENTENA ===========" -ForegroundColor Yellow
        $idx = @(Load-QuarIndex)
        if ($idx.Count -eq 0) { Write-Host "Quarentena vazia." -ForegroundColor DarkGray }
        $n = 1
        foreach ($i in $idx) {
            Write-Host ("{0,3}) [{1}] {2}   ({3})" -f $n, $i.Tipo, $i.Original, $i.Data)
            if ($i.Motivo) { Write-Host ("       motivo: " + $i.Motivo) -ForegroundColor DarkGray }
            $n++
        }
        Write-Host ""
        Write-Host "R) Restaurar itens   E) Esvaziar (apaga os arquivos de vez)   V) Voltar"
        $op = Read-Host "Escolha"
        if ($op -match '^[vV]') { return }
        if ($op -match '^[rR]') {
            $sel = Read-Host "Numeros para restaurar (ex: 1,2)"
            $origIdx = @($idx)
            foreach ($ix in @(ConvertTo-Indices $sel $origIdx.Count)) {
                $i = $origIdx[$ix - 1]
                if ($i.Tipo -eq 'Arquivo') {
                    $q = Join-Path $script:QuarDir ($i.Id + '.quar')
                    if (Test-Path -LiteralPath $q) {
                        $pasta = Split-Path -Parent $i.Original
                        if (-not (Test-Path -LiteralPath $pasta)) { New-Item -ItemType Directory -Path $pasta -Force | Out-Null }
                        Move-Item -LiteralPath $q -Destination $i.Original -Force
                        Show-Ok ("Restaurado: " + $i.Original)
                    }
                } elseif ($i.Tipo -eq 'Registro') {
                    if (-not (Test-Path -LiteralPath $i.Path)) { New-Item -Path $i.Path -Force | Out-Null }
                    Set-ItemProperty -LiteralPath $i.Path -Name $i.Name -Value $i.Valor -Type String -Force
                    Show-Ok ("Entrada de registro restaurada: " + $i.Name)
                }
                $idx = @($idx | Where-Object { $_.Id -ne $i.Id })
            }
            Save-QuarIndex $idx
            Pausa
        }
        if ($op -match '^[eE]') {
            if (Ask-YesNo "Apagar DEFINITIVAMENTE todos os arquivos da quarentena?") {
                Get-ChildItem -LiteralPath $script:QuarDir -Filter '*.quar' -Force | Remove-Item -Force
                Save-QuarIndex @()
                Show-Ok "Quarentena esvaziada"
            }
            Pausa
        }
    }
}

# ----------------------------------------------------------------
# 1) Processos
# ----------------------------------------------------------------
function Scan-Processos {
    Show-Info "[1] Analisando processos em execucao..."
    $procs = @(Get-CimInstance Win32_Process)
    foreach ($p in $procs) { $script:ProcMap[[int]$p.ProcessId] = $p }
    foreach ($p in $procs) {
        $id = [int]$p.ProcessId
        if ($id -le 4 -or $id -eq $PID) { continue }
        $nome = [string]$p.Name
        $path = [string]$p.ExecutablePath
        $cmd = [string]$p.CommandLine
        $base = [System.IO.Path]::GetFileNameWithoutExtension($nome).ToLower()
        $sev = 0
        $mot = New-Object System.Collections.ArrayList

        if ($base -match $script:RxMinerNome) { $sev = 3; [void]$mot.Add('nome de programa de mineracao de criptomoeda') }
        if ($cmd -and ($cmd -match $script:RxMinerCmd)) { $sev = 3; [void]$mot.Add('linha de comando com pool/parametros de mineracao') }
        if ($cmd -and ($cmd -match $script:RxCmdSusp)) { $sev = 3; [void]$mot.Add('linha de comando com codigo ofuscado ou download oculto') }
        if ($base -match $script:RxHackAlto) { $sev = 3; [void]$mot.Add('ferramenta de ataque / roubo de senhas') }
        elseif ($base -match $script:RxHackMedio) {
            if ($sev -lt 2) { $sev = 2 }
            [void]$mot.Add('ferramenta de rede/pos-exploracao (pode ser legitima, confirme)')
        }
        if ($base -match $script:RxRemoto) {
            if ($sev -lt 2) { $sev = 2 }
            [void]$mot.Add('acesso remoto em execucao: alguem pode controlar este PC (confirme que foi voce que instalou)')
        }

        $permitidos = $script:SysNomes[$nome.ToLower()]
        if ($permitidos -and $path) {
            $ok = $false
            $pl = $path.ToLower()
            foreach ($pre in $permitidos) { if ($pl.StartsWith($pre)) { $ok = $true } }
            if (-not $ok) { $sev = 3; [void]$mot.Add('finge ser um processo do Windows mas roda de outra pasta') }
        }
        if ($nome.ToLower() -match $script:RxLookalike) { $sev = 3; [void]$mot.Add('nome parecido com processo do Windows (disfarce)') }

        if ($path -and (Test-CaminhoSuspeito $path) -and ($script:SysNomes.ContainsKey($nome.ToLower()) -eq $false)) {
            $sig = Get-Assinatura $path
            if ($sig -notlike 'Valida*') {
                if ($sev -lt 2) { $sev = 2 }
                [void]$mot.Add(("roda de pasta de usuario/temporaria e a assinatura digital e: " + $sig))
            }
        }

        if ($sev -gt 0) {
            $det = ("Processo: {0} (PID {1})`nArquivo: {2}`nMotivo: {3}" -f $nome, $id, $path, ($mot -join '; '))
            if ($cmd) {
                $c = $cmd
                if ($c.Length -gt 220) { $c = $c.Substring(0, 220) + '...' }
                $det += ("`nComando: " + $c)
            }
            Add-Achado $sev 'Processo' ('Processo suspeito: ' + $nome) $det @{ Tipo = 'Processo'; Pid = $id; Path = $path; Nome = $nome }
        }
    }
}

# ----------------------------------------------------------------
# 2) Consumo de CPU e GPU (assinatura classica de minerador)
# ----------------------------------------------------------------
function Scan-Consumo {
    Show-Info "[2] Medindo consumo de CPU por 8 segundos (evite usar o PC agora)..."
    $nc = [int]$env:NUMBER_OF_PROCESSORS
    if ($nc -lt 1) { $nc = 1 }
    $antes = @{}
    foreach ($p in @(Get-Process)) { try { $antes[$p.Id] = $p.TotalProcessorTime.TotalSeconds } catch { } }
    Start-Sleep -Seconds 8
    foreach ($p in @(Get-Process)) {
        if (-not $antes.ContainsKey($p.Id) -or $p.Id -eq $PID -or $p.Id -le 4) { continue }
        $pct = 0
        try { $pct = (($p.TotalProcessorTime.TotalSeconds - $antes[$p.Id]) / 8) / $nc * 100 } catch { continue }
        if ($pct -lt 20) { continue }
        $nome = [string]$p.ProcessName
        if ($nome -match '^(MsMpEng|MpDefenderCoreService|System|Idle|TiWorker|TrustedInstaller|MicrosoftEdgeUpdate|vmmem)$') { continue }
        $path = [string]$p.Path
        $sev = 0
        $info = ''
        if ($path -and (Test-CaminhoSuspeito $path)) {
            $sig = Get-Assinatura $path
            if ($sig -notlike 'Valida*') {
                $sev = 2
                if (Test-CaminhoForte $path) { $sev = 3 }
                $info = 'programa sem assinatura em pasta de usuario/temporaria usando CPU de forma constante (comportamento tipico de minerador; se for um jogo/app que voce conhece, ignore)'
            }
        }
        if ($sev -eq 0 -and $script:SysNomes.ContainsKey(($nome + '.exe').ToLower()) -and $pct -ge 40) {
            $sev = 2
            $info = 'processo do Windows com CPU muito alta e constante (mineradores costumam se injetar neles). Se persistir com o PC parado, investigue'
        }
        $titulo = ("Consumo alto de CPU: {0} ({1:N0}%)" -f $nome, $pct)
        $det = ("Processo: {0} (PID {1})`nArquivo: {2}" -f $nome, $p.Id, $path)
        if ($info) { $det += ("`nMotivo: " + $info) }
        $acao = $null
        if ($sev -ge 2 -and $path) { $acao = @{ Tipo = 'Processo'; Pid = [int]$p.Id; Path = $path; Nome = $nome } }
        Add-Achado $sev 'CPU' $titulo $det $acao
    }
}

function Scan-GpuUso {
    Show-Info "[3] Medindo uso da placa de video (computacao/CUDA)..."
    $tot = @{}
    $amostras = 0
    $wmiOk = $true
    # 1o: WMI (mesmo nome em qualquer idioma do Windows). A primeira leitura e descartada (vem zerada).
    for ($k = 0; $k -lt 5; $k++) {
        $eng = $null
        try { $eng = @(Get-CimInstance -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction Stop) } catch { $wmiOk = $false; break }
        if ($k -gt 0) {
            $amostras++
            foreach ($s in $eng) {
                $mm = [regex]::Match([string]$s.Name, 'pid_(\d+)_.*engtype_(.+)$')
                if (-not $mm.Success) { continue }
                $procId = [int]$mm.Groups[1].Value
                $tp = $mm.Groups[2].Value
                if (-not $tot.ContainsKey($procId)) { $tot[$procId] = @{} }
                if (-not $tot[$procId].ContainsKey($tp)) { $tot[$procId][$tp] = 0.0 }
                $tot[$procId][$tp] += [double]$s.UtilizationPercentage
            }
        }
        Start-Sleep -Seconds 1
    }
    # 2o: contadores de desempenho (so funcionam com nomes em ingles)
    if (-not $wmiOk) {
        $c = $null
        try { $c = @(Get-Counter -Counter '\GPU Engine(*)\Utilization Percentage' -SampleInterval 1 -MaxSamples 4 -ErrorAction Stop) } catch { return }
        if (-not $c -or $c.Count -eq 0) { return }
        foreach ($amostra in $c) {
            foreach ($s in $amostra.CounterSamples) {
                $mm = [regex]::Match([string]$s.InstanceName, 'pid_(\d+)_.*engtype_(.+)$')
                if (-not $mm.Success) { continue }
                $procId = [int]$mm.Groups[1].Value
                $tp = $mm.Groups[2].Value
                if (-not $tot.ContainsKey($procId)) { $tot[$procId] = @{} }
                if (-not $tot[$procId].ContainsKey($tp)) { $tot[$procId][$tp] = 0.0 }
                $tot[$procId][$tp] += [double]$s.CookedValue
            }
        }
        $amostras = $c.Count
    }
    if ($amostras -eq 0) { return }
    foreach ($procId in $tot.Keys) {
        foreach ($tp in $tot[$procId].Keys) {
            $media = $tot[$procId][$tp] / $amostras
            if ($media -lt 20) { continue }
            if ($tp -notmatch 'Compute|Cuda') { continue }
            $pr = $script:ProcMap[[int]$procId]
            $nome = '(desconhecido)'
            $path = ''
            if ($pr) { $nome = [string]$pr.Name; $path = [string]$pr.ExecutablePath }
            $sev = 2
            $mot = 'a GPU esta fazendo calculo pesado (CUDA/Compute), padrao de mineracao. Pode ser tambem edicao de video/IA/render: confirme se voce esta usando algo assim'
            if ($path -and (Test-CaminhoSuspeito $path) -and ((Get-Assinatura $path) -notlike 'Valida*')) { $sev = 3; $mot = $mot + '. Alem disso o programa nao tem assinatura e roda de pasta de usuario/temporaria' }
            $det = ("Processo: {0} (PID {1})`nArquivo: {2}`nUso medio da GPU ({3}): {4:N0}%`nMotivo: {5}" -f $nome, $procId, $path, $tp, $media, $mot)
            $acao = $null
            if ($pr) { $acao = @{ Tipo = 'Processo'; Pid = [int]$procId; Path = $path; Nome = $nome } }
            Add-Achado $sev 'GPU' ('Uso intenso de computacao na GPU: ' + $nome) $det $acao
        }
    }
}

# ----------------------------------------------------------------
# 3) Rede (pools de mineracao, backdoors, DNS)
# ----------------------------------------------------------------
function Scan-Rede {
    Show-Info "[4] Analisando conexoes de rede e cache de DNS..."
    $porPid = @{}
    foreach ($c in @(Get-NetTCPConnection -State Established)) {
        if (Test-IpPrivado $c.RemoteAddress) { continue }
        $id = [int]$c.OwningProcess
        if ($id -le 4 -or $id -eq $PID) { continue }
        if (-not $porPid.ContainsKey($id)) { $porPid[$id] = New-Object System.Collections.ArrayList }
        [void]$porPid[$id].Add(@{ Ip = [string]$c.RemoteAddress; Porta = [int]$c.RemotePort })
    }
    foreach ($id in $porPid.Keys) {
        $pr = $script:ProcMap[[int]$id]
        if (-not $pr) { continue }
        $nome = [string]$pr.Name
        $path = [string]$pr.ExecutablePath
        $base = [System.IO.Path]::GetFileNameWithoutExtension($nome).ToLower()
        if ($base -match '^(chrome|msedge|firefox|brave|opera|vivaldi|steam|steamwebhelper|discord)$') { continue }
        $portasMiner = @()
        $alvos = @()
        foreach ($r in $porPid[$id]) {
            if ($script:PortasMiner -contains $r.Porta) { $portasMiner += ("{0}:{1}" -f $r.Ip, $r.Porta) }
            if ($alvos.Count -lt 3) { $alvos += ("{0}:{1}" -f $r.Ip, $r.Porta) }
        }
        $sev = 0
        $mot = ''
        if ($portasMiner.Count -gt 0) {
            $sev = 2
            $mot = 'conectado em porta tipica de pool de mineracao (' + (($portasMiner | Select-Object -First 3) -join ', ') + ')'
        }
        if ($path -and (Test-CaminhoSuspeito $path) -and ((Get-Assinatura $path) -notlike 'Valida*')) {
            if ($sev -lt 2) { $sev = 2 }
            if ($mot) { $mot += '; ' }
            $mot += 'programa sem assinatura em pasta de usuario/temporaria esta conectado a internet'
            if ($portasMiner.Count -gt 0) { $sev = 3 }
        }
        if ($sev -gt 0) {
            $det = ("Processo: {0} (PID {1})`nArquivo: {2}`nConexoes: {3}`nMotivo: {4}" -f $nome, $id, $path, ($alvos -join ', '), $mot)
            Add-Achado $sev 'Rede' ('Conexao suspeita: ' + $nome) $det @{ Tipo = 'Processo'; Pid = [int]$id; Path = $path; Nome = $nome }
        }
    }

    # portas abertas para a internet por programas suspeitos (backdoor)
    foreach ($c in @(Get-NetTCPConnection -State Listen)) {
        if ($c.LocalAddress -ne '0.0.0.0' -and $c.LocalAddress -ne '::') { continue }
        $id = [int]$c.OwningProcess
        $pr = $script:ProcMap[$id]
        if (-not $pr) { continue }
        $path = [string]$pr.ExecutablePath
        if (-not $path -or -not (Test-CaminhoSuspeito $path)) { continue }
        if ((Get-Assinatura $path) -like 'Valida*') { continue }
        $det = ("Processo: {0} (PID {1})`nArquivo: {2}`nPorta aberta para a rede: {3}`nMotivo: programa sem assinatura aceitando conexoes externas (possivel backdoor)" -f $pr.Name, $id, $path, $c.LocalPort)
        Add-Achado 2 'Rede' ('Porta aberta por programa suspeito: ' + $pr.Name) $det @{ Tipo = 'Processo'; Pid = $id; Path = $path; Nome = [string]$pr.Name }
    }

    # cache de DNS: nomes de pools de mineracao que este PC consultou
    $dns = @(Get-DnsClientCache | Where-Object { $_.Entry -match $script:RxPoolDns } | Select-Object -ExpandProperty Entry -Unique)
    if ($dns.Count -gt 0) {
        $lista = ($dns | Select-Object -First 6) -join ', '
        Add-Achado 2 'Rede' 'Este PC consultou enderecos de pools de mineracao' ("Enderecos: {0}`nMotivo: so pesquisar o site nao e problema, mas se voce nunca acessou isso, ha um minerador no PC." -f $lista) $null
    }
}

# ----------------------------------------------------------------
# 4) Persistencia: o que reabre sozinho (a chave para erradicar de vez)
# ----------------------------------------------------------------
function Scan-Persistencia {
    Show-Info "[5] Verificando o que inicia sozinho (registro, pasta Inicializar, tarefas, servicos, WMI)..."

    $chaves = @(
        @{ P = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run'; E = 'HKLM Run' },
        @{ P = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\RunOnce'; E = 'HKLM RunOnce' },
        @{ P = 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Run'; E = 'HKLM Run (32 bits)' },
        @{ P = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'; E = 'HKCU Run' },
        @{ P = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\RunOnce'; E = 'HKCU RunOnce' }
    )
    foreach ($k in $chaves) {
        if (-not (Test-Path -LiteralPath $k.P)) { continue }
        $item = Get-Item -LiteralPath $k.P
        foreach ($nome in $item.GetValueNames()) {
            if (-not $nome) { continue }
            $val = [string]$item.GetValue($nome, $null, 'DoNotExpandEnvironmentNames')
            $av = Avaliar-Comando $val $nome
            if ($av -and $av.Sev -ge 2) {
                $det = ("Local: {0}`nNome: {1}`nComando: {2}`nMotivo: {3}" -f $k.E, $nome, $val, ($av.Motivos -join '; '))
                Add-Achado $av.Sev 'Inicializacao' ('Inicializacao suspeita: ' + $nome) $det @{ Tipo = 'RegValor'; Path = $k.P; Name = $nome; Valor = $val; Caminho = $av.Caminho }
            }
        }
    }

    # pastas Inicializar
    $pastas = @((Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup'), (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\Startup'))
    foreach ($d in $pastas) {
        if (-not (Test-Path -LiteralPath $d)) { continue }
        foreach ($f in @(Get-ChildItem -LiteralPath $d -Force -File)) {
            if ($f.Name -ieq 'desktop.ini') { continue }
            $alvo = $f.FullName
            if ($f.Extension -ieq '.lnk') {
                try {
                    $sh = New-Object -ComObject WScript.Shell
                    $l = $sh.CreateShortcut($f.FullName)
                    $alvo = ('"' + $l.TargetPath + '" ' + $l.Arguments)
                } catch { }
            }
            $av = Avaliar-Comando $alvo $f.Name
            $sev = 0
            $mot = ''
            if ($av) { $sev = $av.Sev; $mot = ($av.Motivos -join '; ') }
            if ($f.Extension -match '^\.(vbs|vbe|js|jse|hta|ps1|wsf)$') {
                if ($sev -lt 2) { $sev = 2 }
                if ($mot) { $mot += '; ' }
                $mot += 'script na pasta Inicializar'
            }
            if ($sev -ge 2) {
                $cam = $f.FullName
                if ($av -and $av.Caminho -and $f.Extension -ieq '.lnk') { $cam = $av.Caminho }
                Add-Achado $sev 'Inicializacao' ('Item suspeito na pasta Inicializar: ' + $f.Name) ("Arquivo: {0}`nAlvo: {1}`nMotivo: {2}" -f $f.FullName, $alvo, $mot) @{ Tipo = 'Arquivo'; Path = $f.FullName; Caminho = $cam }
            }
        }
    }

    # Winlogon (Shell / Userinit)
    $wl = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon'
    $shell = Get-RegString $wl 'Shell'
    if ($shell -and $shell.Trim().ToLower() -ne 'explorer.exe') {
        Add-Achado 3 'Inicializacao' 'Winlogon: Shell alterado' ("Valor atual: {0}`nMotivo: o padrao e 'explorer.exe'. Malware altera isso para ser executado em todo login." -f $shell) @{ Tipo = 'RegRestaurar'; Path = $wl; Name = 'Shell'; Valor = $shell; Padrao = 'explorer.exe' }
    }
    $ui = Get-RegString $wl 'Userinit'
    $uiPadrao = (Join-Path $env:windir 'system32\userinit.exe') + ','
    if ($ui -and ($ui.Trim().TrimEnd(',').ToLower() -ne $uiPadrao.TrimEnd(',').ToLower())) {
        Add-Achado 3 'Inicializacao' 'Winlogon: Userinit alterado' ("Valor atual: {0}`nPadrao: {1}`nMotivo: malware acrescenta programas aqui para rodar em todo login." -f $ui, $uiPadrao) @{ Tipo = 'RegRestaurar'; Path = $wl; Name = 'Userinit'; Valor = $ui; Padrao = $uiPadrao }
    }

    # Image File Execution Options (Debugger sequestrando programas)
    $ifeo = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Image File Execution Options'
    foreach ($sk in @(Get-ChildItem -LiteralPath $ifeo)) {
        $dbg = Get-RegString $sk.PSPath 'Debugger'
        if (-not $dbg) { continue }
        $sev = 2
        if ($sk.PSChildName -match '^(MsMpEng|MpCmdRun|msseces|avp|avgui|mbam|MBAMService|SecurityHealth\w*|taskmgr|procexp\w*|regedit|cmd|powershell)\.exe$') { $sev = 3 }
        Add-Achado $sev 'Inicializacao' ('Redirecionamento de programa (IFEO): ' + $sk.PSChildName) ("Programa: {0}`nDebugger: {1}`nMotivo: ao abrir esse programa, o Windows executa o 'debugger' no lugar. Tecnica usada por malware para desativar antivirus ou persistir." -f $sk.PSChildName, $dbg) @{ Tipo = 'RegRemove'; Path = $sk.PSPath; Name = 'Debugger'; Valor = $dbg }
    }

    # AppInit_DLLs
    foreach ($kw in @('HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Windows', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows NT\CurrentVersion\Windows')) {
        $ai = Get-RegString $kw 'AppInit_DLLs'
        if ($ai -and $ai.Trim()) {
            Add-Achado 2 'Inicializacao' 'AppInit_DLLs em uso' ("Valor: {0}`nMotivo: DLLs injetadas em todo programa que abre. Raro em PCs normais." -f $ai) @{ Tipo = 'RegRemove'; Path = $kw; Name = 'AppInit_DLLs'; Valor = $ai }
        }
    }

    # tarefas agendadas
    foreach ($t in @(Get-ScheduledTask)) {
        $ms = ([string]$t.TaskPath -like '\Microsoft\*')
        foreach ($a in @($t.Actions)) {
            $cmd = (([string]$a.Execute + ' ' + [string]$a.Arguments).Trim())
            if (-not $cmd) { continue }
            $av = Avaliar-Comando $cmd $t.TaskName
            if (-not $av) { continue }
            if ($ms -and $av.Sev -lt 3) { continue }
            if ($av.Sev -ge 2) {
                $det = ("Tarefa: {0}{1}`nComando: {2}`nMotivo: {3}" -f $t.TaskPath, $t.TaskName, $cmd, ($av.Motivos -join '; '))
                Add-Achado $av.Sev 'Tarefa agendada' ('Tarefa agendada suspeita: ' + $t.TaskName) $det @{ Tipo = 'Tarefa'; Nome = [string]$t.TaskName; Pasta = [string]$t.TaskPath; Caminho = $av.Caminho }
            }
        }
    }

    # servicos fora da pasta do Windows
    $wdir = ($env:windir.ToLower() + '\')
    foreach ($s in @(Get-CimInstance Win32_Service)) {
        $pn = [string]$s.PathName
        if (-not $pn) { continue }
        $exp = ([Environment]::ExpandEnvironmentVariables($pn)).Trim('"').ToLower()
        if ($exp.StartsWith($wdir)) { continue }
        $av = Avaliar-Comando $pn $s.Name
        if ($av -and $av.Sev -ge 2) {
            $det = ("Servico: {0} ({1})`nComando: {2}`nSituacao: {3} / inicio {4}`nMotivo: {5}" -f $s.Name, $s.DisplayName, $pn, $s.State, $s.StartMode, ($av.Motivos -join '; '))
            Add-Achado $av.Sev 'Servico' ('Servico suspeito: ' + $s.Name) $det @{ Tipo = 'Servico'; Nome = [string]$s.Name; Caminho = $av.Caminho }
        }
    }

    # WMI (persistencia sem arquivo, muito usada por mineradores e RATs)
    foreach ($c in @(Get-CimInstance -Namespace 'root\subscription' -ClassName '__EventConsumer')) {
        $cls = [string]$c.CimClass.CimClassName
        if ($cls -ne 'CommandLineEventConsumer' -and $cls -ne 'ActiveScriptEventConsumer') { continue }
        $conteudo = ''
        if ($cls -eq 'CommandLineEventConsumer') { $conteudo = [string]$c.CommandLineTemplate } else { $conteudo = [string]$c.ScriptText }
        if ($conteudo.Length -gt 200) { $conteudo = $conteudo.Substring(0, 200) + '...' }
        Add-Achado 3 'WMI' ('Assinatura WMI executando codigo: ' + $c.Name) ("Tipo: {0}`nConteudo: {1}`nMotivo: programas legitimos quase nunca usam isso; e uma tecnica classica de persistencia invisivel." -f $cls, $conteudo) @{ Tipo = 'Wmi'; Nome = [string]$c.Name; Classe = $cls }
    }
}

# ----------------------------------------------------------------
# 5) Configuracoes de seguranca adulteradas
# ----------------------------------------------------------------
function Scan-Configuracao {
    Show-Info "[6] Conferindo antivirus, exclusoes, firewall, hosts, proxy e acesso remoto..."

    $outrosAv = @()
    foreach ($av in @(Get-CimInstance -Namespace 'root/SecurityCenter2' -ClassName AntiVirusProduct)) {
        if ([string]$av.displayName -notmatch 'Defender') { $outrosAv += [string]$av.displayName }
    }
    $mps = Get-MpComputerStatus
    if ($mps) {
        if ($outrosAv.Count -eq 0 -and -not $mps.RealTimeProtectionEnabled) {
            Add-Achado 3 'Antivirus' 'Protecao em tempo real do Windows Defender DESLIGADA' "Motivo: nenhum outro antivirus foi encontrado. Malware costuma desligar o Defender. Ligue em Seguranca do Windows > Protecao contra virus." $null
        }
        if ($mps.AntivirusSignatureAge -gt 7) {
            Add-Achado 1 'Antivirus' ("Definicoes do Defender com " + $mps.AntivirusSignatureAge + " dias") "Use o menu Antivirus do Windows > Atualizar assinaturas." $null
        }
    }
    if ($outrosAv.Count -gt 0) { Add-Achado 0 'Antivirus' ('Antivirus de terceiros ativo: ' + (($outrosAv | Select-Object -Unique) -join ', ')) '' $null }

    foreach ($par in @(@{ P = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows Defender'; N = 'DisableAntiSpyware' }, @{ P = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows Defender\Real-Time Protection'; N = 'DisableRealtimeMonitoring' })) {
        $v = (Get-ItemProperty -LiteralPath $par.P -Name $par.N -ErrorAction SilentlyContinue).($par.N)
        if ($v -eq 1 -and $outrosAv.Count -eq 0) {
            Add-Achado 3 'Antivirus' ('Defender desativado por politica (' + $par.N + ')') ("Chave: {0}`nMotivo: malware usa essa chave para desligar o antivirus." -f $par.P) @{ Tipo = 'RegRemove'; Path = $par.P; Name = $par.N; Valor = [string]$v }
        }
    }

    # exclusoes do Defender
    $mp = Get-MpPreference
    if ($mp) {
        foreach ($e in @($mp.ExclusionPath | Where-Object { $_ })) {
            $sev = 2
            if ($e -match '^[A-Za-z]:\\?$') { $sev = 3 }
            Add-Achado $sev 'Antivirus' ('Exclusao do Defender (pasta): ' + $e) "Motivo: o Defender NAO verifica essa pasta. Mineradores e trojans adicionam exclusoes para se esconder. Se nao foi voce (ou um jogo/crack que voce instalou), remova." @{ Tipo = 'Exclusao'; Alvo = [string]$e; Kind = 'Path' }
        }
        foreach ($e in @($mp.ExclusionProcess | Where-Object { $_ })) {
            Add-Achado 2 'Antivirus' ('Exclusao do Defender (processo): ' + $e) "Motivo: o Defender ignora esse programa. Confirme que foi voce." @{ Tipo = 'Exclusao'; Alvo = [string]$e; Kind = 'Process' }
        }
        foreach ($e in @($mp.ExclusionExtension | Where-Object { $_ })) {
            $sev = 2
            if ($e -match '^\.?(exe|dll|bat|cmd|ps1|vbs|js|scr)$') { $sev = 3 }
            Add-Achado $sev 'Antivirus' ('Exclusao do Defender (extensao): ' + $e) "Motivo: o Defender ignora todos os arquivos dessa extensao." @{ Tipo = 'Exclusao'; Alvo = [string]$e; Kind = 'Extension' }
        }
    }

    # firewall
    foreach ($fp in @(Get-NetFirewallProfile)) {
        if (-not $fp.Enabled -or [string]$fp.Enabled -eq 'False') {
            Add-Achado 2 'Rede' ('Firewall DESLIGADO no perfil ' + $fp.Name) "Motivo: sem firewall, portas ficam expostas. Se voce usa firewall de terceiros, ignore." @{ Tipo = 'Firewall' }
        }
    }

    # UAC
    $sys = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
    $lua = (Get-ItemProperty -LiteralPath $sys -Name EnableLUA -ErrorAction SilentlyContinue).EnableLUA
    if ($lua -eq 0) { Add-Achado 2 'Sistema' 'Controle de Conta de Usuario (UAC) desligado' "Motivo: programas ganham privilegio de administrador sem avisar." @{ Tipo = 'RegBackup'; Path = $sys; Name = 'EnableLUA'; Valor = 1; TipoReg = 'DWord' } }

    # Area de Trabalho Remota
    $ts = 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server'
    $rdp = (Get-ItemProperty -LiteralPath $ts -Name fDenyTSConnections -ErrorAction SilentlyContinue).fDenyTSConnections
    if ($rdp -eq 0) {
        Add-Achado 2 'Rede' 'Area de Trabalho Remota (RDP) esta LIGADA' "Motivo: e a porta de entrada mais atacada por invasores. Se voce nao usa, desligue." @{ Tipo = 'RegBackup'; Path = $ts; Name = 'fDenyTSConnections'; Valor = 1; TipoReg = 'DWord' }
    }

    # hosts
    $hostsArq = Join-Path $env:windir 'System32\drivers\etc\hosts'
    $linhasHosts = @(Get-Content -LiteralPath $hostsArq | Where-Object { $_ -and $_.Trim() -and -not $_.Trim().StartsWith('#') })
    $ruins = @()
    foreach ($ln in $linhasHosts) {
        $partes = @($ln.Trim() -split '\s+')
        if ($partes.Count -lt 2) { continue }
        $ipH = $partes[0]
        $nomeH = $partes[1]
        if ($nomeH -match '^(localhost|localhost\.localdomain|broadcasthost|ip6-localhost|ip6-loopback)$') { continue }
        $sev = 1
        $mot = ''
        if ($nomeH -match 'kaspersky|avast|avg\.|malwarebytes|eset|bitdefender|norton|mcafee|symantec|sophos|trendmicro|virustotal|avira|f-secure|comodo|defender|windowsupdate|update\.microsoft') { $sev = 3; $mot = 'bloqueia site de antivirus/atualizacao' }
        elseif ($ipH -ne '127.0.0.1' -and $ipH -ne '0.0.0.0' -and $ipH -ne '::1') { $sev = 2; $mot = 'redireciona um site para outro IP' }
        if ($sev -ge 2) { $ruins += $ln.Trim(); Add-Achado $sev 'Rede' ('Arquivo hosts alterado: ' + $nomeH) ("Linha: {0}`nMotivo: {1}" -f $ln.Trim(), $mot) @{ Tipo = 'Hosts'; Linha = $ln.Trim() } }
    }

    # proxy
    $is = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $pe = (Get-ItemProperty -LiteralPath $is -Name ProxyEnable -ErrorAction SilentlyContinue).ProxyEnable
    $ps = Get-RegString $is 'ProxyServer'
    $pac = Get-RegString $is 'AutoConfigURL'
    if ($pe -eq 1 -and $ps) { Add-Achado 2 'Rede' ('Proxy ligado no Windows: ' + $ps) "Motivo: malware usa proxy para interceptar seu trafego. Se voce nao configurou, remova." @{ Tipo = 'Proxy' } }
    if ($pac) { Add-Achado 2 'Rede' ('Script de proxy automatico: ' + $pac) "Motivo: pode redirecionar todo o seu trafego. Se voce nao configurou, remova." @{ Tipo = 'Proxy' } }
}

# ----------------------------------------------------------------
# 6) Contas de usuario
# ----------------------------------------------------------------
function Scan-Contas {
    Show-Info "[7] Conferindo contas de usuario e administradores..."
    foreach ($u in @(Get-LocalUser)) {
        if ($u.Enabled -and $u.Name.EndsWith('$')) {
            Add-Achado 3 'Contas' ('Conta oculta ativa: ' + $u.Name) "Motivo: nomes terminados em $ ficam escondidos da tela de login. Invasores criam contas assim." $null
        }
        if ($u.Enabled -and $u.SID.Value -match '-501$') {
            Add-Achado 2 'Contas' 'Conta Convidado (Guest) esta ATIVA' "Motivo: normalmente fica desativada. Desative em Gerenciamento do Computador." $null
        }
    }
    $adm = @()
    foreach ($m in @(Get-LocalGroupMember -SID 'S-1-5-32-544')) { $adm += [string]$m.Name }
    if ($adm.Count -gt 0) { Add-Achado 0 'Contas' ('Administradores deste PC: ' + ($adm -join ', ')) "Se aparecer um nome que voce nao reconhece, trate como invasao." $null }
}

# ----------------------------------------------------------------
# 7) Logs de seguranca (tentativas e acessos de invasores)
# ----------------------------------------------------------------
function Scan-Eventos {
    Show-Info "[8] Lendo o log de seguranca do Windows (tentativas de invasao)..."
    $ini = (Get-Date).AddHours(-24)
    $falhas = @(Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 4625; StartTime = $ini } -MaxEvents 5000 -ErrorAction SilentlyContinue)
    $ips = @{}
    $ext = 0
    foreach ($e in $falhas) {
        $ip = [string]$e.Properties[19].Value
        if ($ip -and -not (Test-IpPrivado $ip)) {
            $ext++
            if ($ips.ContainsKey($ip)) { $ips[$ip] = $ips[$ip] + 1 } else { $ips[$ip] = 1 }
        }
    }
    if ($ext -ge 10) {
        $sev = 2
        if ($ext -ge 50) { $sev = 3 }
        $top = @($ips.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 5)
        $txt = @()
        $lista = @()
        foreach ($t in $top) { $txt += ("{0} ({1} tentativas)" -f $t.Key, $t.Value); $lista += $t.Key }
        Add-Achado $sev 'Invasao' ("{0} tentativas de login FALHAS vindas da internet nas ultimas 24h" -f $ext) ("Origens principais: {0}`nMotivo: ataque de forca bruta (adivinhando senha, normalmente via Area de Trabalho Remota ou SMB). Desligue o RDP e bloqueie esses IPs." -f ($txt -join ', ')) @{ Tipo = 'BloquearIps'; Ips = @($lista) }
    }

    $okEv = @(Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 4624; StartTime = (Get-Date).AddDays(-14) } -MaxEvents 4000 -ErrorAction SilentlyContinue)
    $vistos = @{}
    foreach ($e in $okEv) {
        $tipo = [int]$e.Properties[8].Value
        $ip = [string]$e.Properties[18].Value
        if (($tipo -eq 10 -or $tipo -eq 3) -and $ip -and -not (Test-IpPrivado $ip)) {
            $chave = ("{0}|{1}|{2}" -f $e.Properties[5].Value, $ip, $tipo)
            if ($vistos.ContainsKey($chave)) { continue }
            $vistos[$chave] = $true
            $quem = [string]$e.Properties[5].Value
            $forma = 'rede (SMB)'
            if ($tipo -eq 10) { $forma = 'Area de Trabalho Remota (RDP)' }
            Add-Achado 3 'Invasao' ("Login REMOTO bem-sucedido vindo da internet: {0} de {1}" -f $quem, $ip) ("Quando: {0}`nForma: {1}`nMotivo: se voce nao fez esse acesso, alguem entrou no seu PC. Troque as senhas de OUTRO aparelho e desligue o acesso remoto." -f $e.TimeCreated, $forma) @{ Tipo = 'BloquearIps'; Ips = @($ip) }
        }
    }

    foreach ($e in @(Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 1102; StartTime = (Get-Date).AddDays(-30) } -MaxEvents 5 -ErrorAction SilentlyContinue)) {
        Add-Achado 3 'Invasao' 'O log de seguranca do Windows foi APAGADO' ("Quando: {0}`nMotivo: invasores apagam logs para esconder rastros. Se voce nao fez isso, e um forte indicio de comprometimento." -f $e.TimeCreated) $null
    }
    foreach ($e in @(Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 4720; StartTime = (Get-Date).AddDays(-30) } -MaxEvents 10 -ErrorAction SilentlyContinue)) {
        Add-Achado 2 'Contas' ("Conta de usuario criada: {0}" -f $e.Properties[0].Value) ("Quando: {0}`nCriada por: {1}`nSe nao foi voce, e invasao." -f $e.TimeCreated, $e.Properties[4].Value) $null
    }
    foreach ($e in @(Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 4732; StartTime = (Get-Date).AddDays(-30) } -MaxEvents 10 -ErrorAction SilentlyContinue)) {
        $grp = [string]$e.Properties[2].Value
        if ($grp -match 'Administrators|Administradores') {
            Add-Achado 2 'Contas' ("Usuario adicionado ao grupo {0}: {1}" -f $grp, $e.Properties[0].Value) ("Quando: {0}`nPor: {1}`nSe nao foi voce, e invasao." -f $e.TimeCreated, $e.Properties[6].Value) $null
        }
    }
}

# ----------------------------------------------------------------
# 8) Arquivos suspeitos em pastas de usuario/temporarias
# ----------------------------------------------------------------
function Scan-Arquivos {
    param([bool]$SoMineradores = $false)
    Show-Info "[9] Procurando arquivos suspeitos em Temp, AppData, ProgramData, Public e Downloads (pode demorar)..."
    $raizes = @($env:TEMP, (Join-Path $env:windir 'Temp'), $env:PUBLIC, $env:ProgramData, $env:APPDATA, $env:LOCALAPPDATA, (Join-Path $env:USERPROFILE 'Downloads')) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique
    $extExe = @('.exe', '.scr', '.com', '.pif')
    $extScript = @('.bat', '.cmd', '.vbs', '.vbe', '.js', '.jse', '.hta', '.ps1', '.json', '.conf', '.cfg', '.txt')
    $exes = New-Object System.Collections.ArrayList
    $scripts = New-Object System.Collections.ArrayList
    foreach ($r in $raizes) {
        foreach ($f in @(Get-ChildItem -LiteralPath $r -Recurse -Depth 4 -Force -File -ErrorAction SilentlyContinue)) {
            $x = $f.Extension.ToLower()
            if ($extExe -contains $x) { [void]$exes.Add($f) }
            elseif (($extScript -contains $x) -and $f.Length -gt 0 -and $f.Length -lt 262144) {
                if ($f.FullName -like '*\Cache\*' -or $f.FullName -like '*\Code Cache\*' -or $f.FullName -like '*\IndexedDB\*' -or $f.FullName -like '*\Service Worker\*' -or $f.FullName -like '*\node_modules\*' -or $f.FullName -like '*\GPUCache\*') { continue }
                [void]$scripts.Add($f)
            }
        }
    }

    # executaveis
    $baixos = New-Object System.Collections.ArrayList
    $recentes = @($exes | Sort-Object LastWriteTime -Descending | Select-Object -First 500)
    foreach ($f in $recentes) {
        $nomeBase = [System.IO.Path]::GetFileNameWithoutExtension($f.Name).ToLower()
        if ($nomeBase -match $script:RxMinerNome) {
            Add-Achado 3 'Arquivo' ('Arquivo com nome de minerador: ' + $f.Name) ("Arquivo: {0}`nTamanho: {1}`nModificado: {2}`nMotivo: nome de programa de mineracao de criptomoeda" -f $f.FullName, (Format-Size $f.Length), $f.LastWriteTime) @{ Tipo = 'Arquivo'; Path = $f.FullName }
            continue
        }
        if ($SoMineradores) { continue }
        $sig = Get-Assinatura $f.FullName
        if ($sig -like 'Valida*') { continue }
        $oculto = (($f.Attributes -band [IO.FileAttributes]::Hidden) -ne 0)
        $aleatorio = ($f.Name -match '^(?=.*\d)(?=.*[a-zA-Z])[a-zA-Z0-9]{8,}\.(exe|scr)$')
        if ($oculto -or $aleatorio) {
            $mot = @()
            if ($oculto) { $mot += 'arquivo oculto' }
            if ($aleatorio) { $mot += 'nome aleatorio' }
            Add-Achado 2 'Arquivo' ('Executavel suspeito: ' + $f.Name) ("Arquivo: {0}`nAssinatura: {1}`nModificado: {2}`nMotivo: {3} e sem assinatura digital valida" -f $f.FullName, $sig, $f.LastWriteTime, ($mot -join ' + ')) @{ Tipo = 'Arquivo'; Path = $f.FullName }
        } elseif ($baixos.Count -lt 15 -and $f.LastWriteTime -gt (Get-Date).AddDays(-30)) {
            [void]$baixos.Add(("{0}  [{1}]" -f $f.FullName, $sig))
        }
    }
    if ($baixos.Count -gt 0) {
        Add-Achado 1 'Arquivo' ("{0}+ executaveis recentes sem assinatura em pastas de usuario" -f $baixos.Count) (("Exemplos (muitos sao programas portateis/instaladores normais):`n" + ($baixos -join "`n")) -replace '\r', '') $null
    }

    # conteudo de scripts/configs
    $lidos = 0
    foreach ($f in $scripts) {
        if ($lidos -ge 2500) { break }
        $lidos++
        $txt = $null
        try { $txt = [IO.File]::ReadAllText($f.FullName) } catch { continue }
        if (-not $txt) { continue }
        $ehMiner = ($txt -match $script:RxMinerCmd) -or ($txt -match '"pools"\s*:') -or ($txt -match '"donate-level"')
        $ehSusp = $false
        if (-not $SoMineradores -and ($f.Extension -match '^\.(bat|cmd|ps1|vbs|vbe|js|jse|hta)$')) { $ehSusp = ($txt -match $script:RxCmdSusp) }
        if ($ehMiner) {
            Add-Achado 3 'Arquivo' ('Arquivo de configuracao/script de minerador: ' + $f.Name) ("Arquivo: {0}`nModificado: {1}`nMotivo: contem enderecos de pool ou parametros de mineracao" -f $f.FullName, $f.LastWriteTime) @{ Tipo = 'Arquivo'; Path = $f.FullName }
        } elseif ($ehSusp) {
            Add-Achado 2 'Arquivo' ('Script com codigo ofuscado/download oculto: ' + $f.Name) ("Arquivo: {0}`nModificado: {1}`nMotivo: o conteudo tem comandos tipicos de loader/malware (codigo em base64, download e execucao)" -f $f.FullName, $f.LastWriteTime) @{ Tipo = 'Arquivo'; Path = $f.FullName }
        }
    }
}

# ----------------------------------------------------------------
# Acoes sobre os achados
# ----------------------------------------------------------------
function Tratar-Achado($a) {
    $ac = $a.Acao
    if (-not $ac) { Show-Info "  Este item e informativo: nao ha acao automatica. Siga a orientacao do motivo."; return }
    switch ($ac.Tipo) {
        'Processo' {
            if (Ask-YesNo ("  Encerrar {0} (PID {1}) e mover o arquivo para a quarentena?" -f $ac.Nome, $ac.Pid)) {
                & taskkill.exe /PID ([string]$ac.Pid) /T /F 2>&1 | Out-Null
                Start-Sleep -Milliseconds 800
                Show-Ok ("Processo encerrado: " + $ac.Nome)
                if ($ac.Path) { [void](Mover-Quarentena $ac.Path $a.Titulo) }
            }
        }
        'Arquivo' {
            $alvo = $ac.Path
            if (Ask-YesNo ("  Mover para a quarentena: " + $alvo + " ?")) {
                $nomeArq = [System.IO.Path]::GetFileName($alvo)
                foreach ($pr in @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $alvo })) { & taskkill.exe /PID ([string]$pr.Id) /T /F 2>&1 | Out-Null }
                Start-Sleep -Milliseconds 500
                [void](Mover-Quarentena $alvo $a.Titulo)
            }
        }
        'RegValor' {
            if (Ask-YesNo "  Remover essa entrada de inicializacao? (fica guardada na quarentena para restaurar)") {
                Guardar-RegistroQuar $ac.Path $ac.Name $ac.Valor $a.Titulo
                Remove-ItemProperty -LiteralPath $ac.Path -Name $ac.Name -Force -ErrorAction SilentlyContinue
                Show-Ok "Entrada removida"
                if ($ac.Caminho -and (Test-Path -LiteralPath $ac.Caminho -PathType Leaf)) {
                    if (Ask-YesNo ("  Mover tambem o programa para a quarentena? " + $ac.Caminho)) {
                        foreach ($pr in @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $ac.Caminho })) { & taskkill.exe /PID ([string]$pr.Id) /T /F 2>&1 | Out-Null }
                        Start-Sleep -Milliseconds 500
                        [void](Mover-Quarentena $ac.Caminho $a.Titulo)
                    }
                }
            }
        }
        'RegRemove' {
            if (Ask-YesNo "  Remover esse valor do registro? (fica guardado na quarentena)") {
                Guardar-RegistroQuar $ac.Path $ac.Name $ac.Valor $a.Titulo
                Remove-ItemProperty -LiteralPath $ac.Path -Name $ac.Name -Force -ErrorAction SilentlyContinue
                Show-Ok "Valor removido"
            }
        }
        'RegRestaurar' {
            if (Ask-YesNo ("  Restaurar o valor padrao do Windows: " + $ac.Padrao + " ?")) {
                Guardar-RegistroQuar $ac.Path $ac.Name $ac.Valor $a.Titulo
                Set-ItemProperty -LiteralPath $ac.Path -Name $ac.Name -Value $ac.Padrao -Type String -Force
                Show-Ok "Valor padrao restaurado"
            }
        }
        'RegBackup' {
            if (Ask-YesNo "  Corrigir essa configuracao? (reversivel em FPS > Desfazer)") {
                Set-RegBackup $ac.Path $ac.Name $ac.Valor $ac.TipoReg
                Show-Ok "Configuracao corrigida"
            }
        }
        'Tarefa' {
            if (Ask-YesNo ("  Desativar a tarefa agendada " + $ac.Nome + " ?")) {
                Disable-ScheduledTask -TaskName $ac.Nome -TaskPath $ac.Pasta -ErrorAction SilentlyContinue | Out-Null
                Show-Ok "Tarefa desativada"
                if ($ac.Caminho -and (Test-Path -LiteralPath $ac.Caminho -PathType Leaf)) {
                    if (Ask-YesNo ("  Mover tambem o programa dela para a quarentena? " + $ac.Caminho)) {
                        foreach ($pr in @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $ac.Caminho })) { & taskkill.exe /PID ([string]$pr.Id) /T /F 2>&1 | Out-Null }
                        Start-Sleep -Milliseconds 500
                        [void](Mover-Quarentena $ac.Caminho $a.Titulo)
                    }
                }
            }
        }
        'Servico' {
            if (Ask-YesNo ("  Parar e desativar o servico " + $ac.Nome + " ?")) {
                Stop-Service -Name $ac.Nome -Force -ErrorAction SilentlyContinue
                Set-Service -Name $ac.Nome -StartupType Disabled -ErrorAction SilentlyContinue
                Show-Ok "Servico parado e desativado"
                if ($ac.Caminho -and (Test-Path -LiteralPath $ac.Caminho -PathType Leaf)) {
                    if (Ask-YesNo ("  Mover tambem o programa dele para a quarentena? " + $ac.Caminho)) { [void](Mover-Quarentena $ac.Caminho $a.Titulo) }
                }
            }
        }
        'Wmi' {
            if (Ask-YesNo ("  Remover a assinatura WMI " + $ac.Nome + " ?")) {
                $nomeW = $ac.Nome
                foreach ($b in @(Get-CimInstance -Namespace 'root\subscription' -ClassName '__FilterToConsumerBinding')) {
                    if ([string]$b.Consumer.Name -eq $nomeW) { Remove-CimInstance -InputObject $b -ErrorAction SilentlyContinue }
                }
                foreach ($c in @(Get-CimInstance -Namespace 'root\subscription' -ClassName '__EventConsumer')) {
                    if ([string]$c.Name -eq $nomeW) { Remove-CimInstance -InputObject $c -ErrorAction SilentlyContinue }
                }
                Show-Ok "Assinatura WMI removida"
            }
        }
        'Exclusao' {
            if (Ask-YesNo ("  Remover a exclusao do Defender: " + $ac.Alvo + " ?")) {
                if ($ac.Kind -eq 'Path') { Remove-MpPreference -ExclusionPath $ac.Alvo -ErrorAction SilentlyContinue }
                elseif ($ac.Kind -eq 'Process') { Remove-MpPreference -ExclusionProcess $ac.Alvo -ErrorAction SilentlyContinue }
                else { Remove-MpPreference -ExclusionExtension $ac.Alvo -ErrorAction SilentlyContinue }
                Show-Ok "Exclusao removida (agora o Defender volta a verificar esse local)"
            }
        }
        'Firewall' {
            if (Ask-YesNo "  Ligar o Firewall do Windows em todos os perfis?") {
                Set-NetFirewallProfile -Profile Domain, Private, Public -Enabled True -ErrorAction SilentlyContinue
                Show-Ok "Firewall ligado"
            }
        }
        'Hosts' {
            if (Ask-YesNo ("  Remover esta linha do arquivo hosts? " + $ac.Linha)) {
                $hostsArq = Join-Path $env:windir 'System32\drivers\etc\hosts'
                Copy-Item -LiteralPath $hostsArq -Destination ($hostsArq + '.bak-toolkit') -Force
                $novas = @(Get-Content -LiteralPath $hostsArq | Where-Object { $_.Trim() -ne $ac.Linha })
                Set-Content -LiteralPath $hostsArq -Value $novas -Encoding ASCII
                Show-Ok "Linha removida (copia de seguranca: hosts.bak-toolkit)"
            }
        }
        'Proxy' {
            if (Ask-YesNo "  Desligar o proxy do Windows e remover o script de proxy automatico?") {
                $is = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
                Set-RegBackup $is 'ProxyEnable' 0 'DWord'
                $pac = Get-RegString $is 'AutoConfigURL'
                if ($pac) { Guardar-RegistroQuar $is 'AutoConfigURL' $pac $a.Titulo; Remove-ItemProperty -LiteralPath $is -Name 'AutoConfigURL' -ErrorAction SilentlyContinue }
                Show-Ok "Proxy desligado"
            }
        }
        'BloquearIps' {
            $lista = @($ac.Ips)
            if (Ask-YesNo ("  Criar regra no Firewall bloqueando a entrada destes IPs: " + ($lista -join ', ') + " ?")) {
                New-NetFirewallRule -DisplayName ('PC-Toolkit bloqueio ' + (Get-Date -Format 'yyyyMMdd-HHmmss')) -Direction Inbound -Action Block -RemoteAddress $lista -ErrorAction SilentlyContinue | Out-Null
                Show-Ok "IPs bloqueados"
            }
        }
    }
}

# ----------------------------------------------------------------
# Relatorio e fluxo principal
# ----------------------------------------------------------------
function Mostrar-Achados($lista) {
    $n = 1
    foreach ($a in $lista) {
        Write-Host ("{0,3}) [{1}] {2}" -f $n, (Get-SevNome $a.Sev), $a.Titulo) -ForegroundColor (Get-SevCor $a.Sev)
        if ($a.Detalhe) { foreach ($ln in ([string]$a.Detalhe -split "`n")) { Write-Host ("       " + $ln) -ForegroundColor DarkGray } }
        $n++
    }
}

function Salvar-Relatorio($lista, $modo) {
    try {
        if (-not (Test-Path -LiteralPath $script:LogDir)) { New-Item -ItemType Directory -Path $script:LogDir -Force | Out-Null }
        $linhas = @()
        $linhas += ("PC Toolkit - relatorio de seguranca ({0}) - {1}" -f $modo, (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
        $linhas += ("Computador: {0}   Usuario: {1}" -f $env:COMPUTERNAME, $env:USERNAME)
        $linhas += ''
        $n = 1
        foreach ($a in $lista) {
            $linhas += ("{0}) [{1}] {2}" -f $n, (Get-SevNome $a.Sev), $a.Titulo)
            if ($a.Detalhe) { foreach ($ln in ([string]$a.Detalhe -split "`n")) { $linhas += ("      " + $ln) } }
            $n++
        }
        Set-Content -LiteralPath $script:RelFile -Value $linhas -Encoding UTF8
        Show-Info ("Relatorio salvo em: " + $script:RelFile)
    } catch { }
}

function Cacar-Ameacas {
    param([string]$Modo = 'Rapida')
    $script:Achados = New-Object System.Collections.ArrayList
    $script:ChavesAch = @{}
    $script:ProcMap = @{}
    $script:SigCache = @{}

    Write-Host ""
    Write-Host ("=== CACADOR DE AMEACAS - varredura {0} ===" -f $Modo) -ForegroundColor Cyan
    Write-Host "Procura: mineradores de criptomoeda, trojans/RATs, acesso remoto indevido, persistencia escondida,"
    Write-Host "configuracoes de seguranca adulteradas e sinais de invasao nos logs do Windows."
    Write-Host "Lembrete: e uma analise heuristica. Itens marcados podem ser falsos positivos (programas legitimos sem"
    Write-Host "assinatura, cracks, mods...). Nada e apagado sem a sua confirmacao e tudo vai para a quarentena." -ForegroundColor Yellow
    if (-not (Ask-YesNo "Iniciar a varredura?")) { return }
    Write-Host ""

    Scan-Processos
    Scan-Consumo
    Scan-GpuUso
    Scan-Rede
    Scan-Persistencia
    if ($Modo -ne 'Minerador') {
        Scan-Configuracao
        Scan-Contas
    }
    if ($Modo -eq 'Completa') {
        Scan-Eventos
        Scan-Arquivos -SoMineradores $false
    } elseif ($Modo -eq 'Minerador') {
        Scan-Arquivos -SoMineradores $true
    }

    $lista = @($script:Achados | Sort-Object -Property @{ Expression = 'Sev'; Descending = $true }, Cat)
    $altos = @($lista | Where-Object { $_.Sev -ge 3 }).Count
    $medios = @($lista | Where-Object { $_.Sev -eq 2 }).Count
    $baixos = @($lista | Where-Object { $_.Sev -eq 1 }).Count

    Write-Host ""
    Write-Host "================ RESULTADO ================" -ForegroundColor Cyan
    if ($lista.Count -eq 0) {
        Show-Ok "Nenhum sinal de ameaca encontrado por esta varredura."
    } else {
        Mostrar-Achados $lista
    }
    Write-Host ""
    $sevResumo = 0
    if ($altos -gt 0) { $sevResumo = 3 } elseif ($medios -gt 0) { $sevResumo = 2 } elseif ($baixos -gt 0) { $sevResumo = 1 }
    Write-Host ("Resumo: {0} ALTO | {1} MEDIO | {2} BAIXO" -f $altos, $medios, $baixos) -ForegroundColor (Get-SevCor $sevResumo)
    Salvar-Relatorio $lista $Modo
    Write-Log ("Cacar-Ameacas ({0}): {1} alto, {2} medio, {3} baixo" -f $Modo, $altos, $medios, $baixos)

    $acionaveis = @($lista | Where-Object { $_.Acao })
    if ($acionaveis.Count -gt 0) {
        Write-Host ""
        Write-Host "Quer tratar algum item? Digite os numeros (ex: 1,3,4)  |  A = percorrer todos os ALTO e MEDIO  |  Enter = nao mexer" -ForegroundColor Yellow
        $sel = Read-Host "Escolha"
        $alvos = @()
        if ($sel -match '^[aA]') { $alvos = @($lista | Where-Object { $_.Acao -and $_.Sev -ge 2 }) }
        elseif ($sel) { foreach ($ix in @(ConvertTo-Indices $sel $lista.Count)) { $alvos += $lista[$ix - 1] } }
        if ($alvos.Count -gt 0) {
            if (Ask-YesNo "Criar antes um ponto de restauracao do Windows? (recomendado)") { Criar-PontoRestauracao 'PC Toolkit - antes de tratar ameacas' }
            foreach ($a in $alvos) {
                Write-Host ""
                Write-Host ("--- [{0}] {1}" -f (Get-SevNome $a.Sev), $a.Titulo) -ForegroundColor (Get-SevCor $a.Sev)
                Tratar-Achado $a
            }
            Write-Host ""
            Show-Ok "Tratamento concluido. Rode a varredura de novo (de preferencia depois de reiniciar) para confirmar que nada voltou."
        }
    }

    if ($altos -gt 0) {
        Write-Host ""
        Write-Host "SE HOUVE ACHADOS ALTO (principalmente invasao/acesso remoto):" -ForegroundColor Red
        Write-Host "  1) Troque suas senhas (e-mail, bancos, Steam, Discord...) a partir de OUTRO aparelho limpo e ative a verificacao em 2 etapas."
        Write-Host "  2) Rode a Verificacao OFFLINE do Defender (menu Seguranca > Antivirus do Windows)."
        Write-Host "  3) Se o problema voltar mesmo assim, o caminho mais seguro e fazer backup dos documentos e reinstalar o Windows (menu Megazord)."
    }
    Write-Host ""
    if (Ask-YesNo "Abrir agora o menu do Antivirus do Windows (atualizar e verificar)?") { Menu-Defender }
}

function Menu-Defender {
    while ($true) {
        Clear-Host
        Write-Host "=========== ANTIVIRUS DO WINDOWS (Defender) ===========" -ForegroundColor Yellow
        Write-Host ""
        Write-Host " 1) Atualizar definicoes de virus"
        Write-Host " 2) Verificacao RAPIDA"
        Write-Host " 3) Verificacao COMPLETA (demora bastante)"
        Write-Host " 4) Verificacao OFFLINE (reinicia o PC e pega rootkits que se escondem do Windows)"
        Write-Host " 5) Ver ameacas detectadas"
        Write-Host " 6) Voltar"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1' { Write-Host "Atualizando..."; Update-MpSignature -ErrorAction SilentlyContinue; Show-Ok "Concluido (ou ja estava atualizado)"; Pausa }
            '2' { Write-Host "Verificando (rapida)..."; Start-MpScan -ScanType QuickScan -ErrorAction SilentlyContinue; Show-Ok "Verificacao rapida terminada. Veja a opcao 5."; Pausa }
            '3' { Write-Host "Verificando (completa). Pode levar de 30 min a varias horas..."; Start-MpScan -ScanType FullScan -ErrorAction SilentlyContinue; Show-Ok "Verificacao completa terminada. Veja a opcao 5."; Pausa }
            '4' {
                Write-Host "O PC vai REINICIAR e fazer a verificacao antes do Windows carregar." -ForegroundColor Yellow
                if (Ask-YesNo "Salvou tudo e quer continuar?") { Start-MpWDOScan }
            }
            '5' {
                Write-Host ""
                $th = @(Get-MpThreat)
                if ($th.Count -eq 0) { Show-Ok "Nenhuma ameaca registrada pelo Defender." }
                else {
                    foreach ($t in $th) { Write-Host ("  {0}  (ativa: {1})" -f $t.ThreatName, $t.IsActive) -ForegroundColor Yellow }
                }
                Pausa
            }
            '6' { return }
        }
    }
}

# ======================================================================
# SECAO 7B: BLINDAGEM PREVENTIVA (VirusTotal + reforco do Windows)
#
# O Cacador de Ameacas (acima) procura infeccao DEPOIS que ela ja esta no
# PC. Esta secao e o contrario: verifica ANTES de voce abrir um arquivo
# suspeito (ex.: mod menu/cheat/crack baixado do Discord ou de site
# desconhecido), e reforca as protecoes do Windows para reduzir a chance
# de um trojan/stealer disfarcado (familias tipo Wacatac, Worgtop, etc.)
# conseguir rodar.
#
# Usa a API publica e GRATUITA da VirusTotal (~70 antivirus de uma vez):
#   1. Crie uma conta em virustotal.com
#   2. Icone de perfil (canto superior direito) -> API Key
#   3. Cole aqui quando o menu pedir (fica salva em PC-Toolkit-config.json)
# A conta gratuita permite ~4 consultas por minuto. Nada e enviado para a
# VirusTotal sem voce pedir explicitamente (verificar um arquivo ou
# confirmar o envio de um arquivo desconhecido).
# ======================================================================
$script:VtBase = 'https://www.virustotal.com/api/v3'

function Get-VtApiKey { return [string](Load-Config).VtApiKey }

function Set-VtApiKey {
    Write-Host ""
    Write-Host "Pegue sua API key GRATUITA em virustotal.com -> crie uma conta -> icone de perfil -> API Key." -ForegroundColor DarkGray
    $k = Read-Host "Cole a API key da VirusTotal (Enter para cancelar)"
    if (-not $k) { return $false }
    $cfg = Load-Config
    $cfg.VtApiKey = $k.Trim()
    Save-Config $cfg
    Show-Ok "Chave salva. Nao precisa colar de novo."
    return $true
}

function Invoke-VtHashLookup($hash) {
    $key = Get-VtApiKey
    if (-not $key) { return $null }
    try {
        return Invoke-RestMethod -Uri ($script:VtBase + '/files/' + $hash) -Headers @{ 'x-apikey' = $key } -Method Get -ErrorAction Stop
    } catch {
        $resp = $_.Exception.Response
        if ($resp -and [int]$resp.StatusCode -eq 404) { return $null } # hash desconhecido pela VT
        if ($resp -and [int]$resp.StatusCode -eq 401) { throw "API key invalida. Configure de novo (opcao 4)." }
        if ($resp -and [int]$resp.StatusCode -eq 429) { throw "Limite de consultas da conta gratuita excedido. Espere 1 minuto e tente de novo." }
        throw $_
    }
}

# Mostra o veredito e devolve: 0 = limpo, 1 = poucas deteccoes (possivel falso positivo), 2 = risco real
function Format-VtVeredito($r, $nomeArq) {
    $stats = $r.data.attributes.last_analysis_stats
    $mal = [int]$stats.malicious
    $susp = [int]$stats.suspicious
    $tot = $mal + $susp + [int]$stats.undetected + [int]$stats.harmless
    Write-Host ""
    Write-Host ("Arquivo: " + $nomeArq) -ForegroundColor Cyan
    Write-Host ("Malicioso: {0}/{1}   Suspeito: {2}/{3}" -f $mal, $tot, $susp, $tot)
    if ($mal -eq 0 -and $susp -eq 0) {
        Write-Host "RESULTADO: nenhuma engine detectou nada. Sinal forte de que esta limpo." -ForegroundColor Green
        return 0
    } elseif (($mal + $susp) -le 2) {
        Write-Host "RESULTADO: poucas deteccoes. Pode ser falso positivo (comum em cheats/mod menus por hooks/injecao de memoria)." -ForegroundColor Yellow
        return 1
    } else {
        Write-Host "RESULTADO: MULTIPLAS engines detectaram. Risco real - NAO execute." -ForegroundColor Red
        $nomes = @($r.data.attributes.last_analysis_results.PSObject.Properties |
            Where-Object { $_.Value.category -in @('malicious', 'suspicious') -and $_.Value.result } |
            Select-Object -First 8 | ForEach-Object { ("  - {0}: {1}" -f $_.Name, $_.Value.result) })
        foreach ($n in $nomes) { Write-Host $n -ForegroundColor DarkGray }
        return 2
    }
}

function Verificar-ArquivoVT {
    Write-Host ""
    Write-Host "=== VERIFICAR ARQUIVO ANTES DE ABRIR (VirusTotal) ===" -ForegroundColor Cyan
    if (-not (Get-VtApiKey)) {
        Write-Host "Voce ainda nao configurou a chave da VirusTotal." -ForegroundColor Yellow
        if (-not (Set-VtApiKey)) { return }
    }
    $caminho = (Read-Host "Caminho completo do arquivo (pode arrastar o arquivo para esta janela e dar Enter)").Trim().Trim('"')
    if (-not (Test-Path -LiteralPath $caminho -PathType Leaf)) { Show-Aviso "Arquivo nao encontrado."; return }

    Write-Host "Calculando hash..." -ForegroundColor DarkGray
    $hash = (Get-FileHash -LiteralPath $caminho -Algorithm SHA256).Hash
    Write-Host "Consultando a VirusTotal..." -ForegroundColor DarkGray
    try { $r = Invoke-VtHashLookup $hash } catch { Show-Aviso ("Erro ao consultar: " + $_.Exception.Message); return }

    if (-not $r) {
        Write-Host ""
        Write-Host "Esse arquivo ainda nao e conhecido pela VirusTotal (hash novo)." -ForegroundColor Yellow
        if (-not (Ask-YesNo "Enviar o arquivo para analise agora? (1-3 min; arquivos grandes demoram mais)")) { return }
        try {
            $key = Get-VtApiKey
            $up = Invoke-RestMethod -Uri ($script:VtBase + '/files') -Headers @{ 'x-apikey' = $key } -Method Post -Form @{ file = Get-Item -LiteralPath $caminho } -ErrorAction Stop
            $aid = $up.data.id
            Write-Host "Enviado. Aguardando o resultado..." -ForegroundColor DarkGray
            $fim = (Get-Date).AddMinutes(5)
            do {
                Start-Sleep -Seconds 8
                $an = Invoke-RestMethod -Uri ($script:VtBase + '/analyses/' + $aid) -Headers @{ 'x-apikey' = $key } -Method Get
            } while ($an.data.attributes.status -ne 'completed' -and (Get-Date) -lt $fim)
            if ($an.data.attributes.status -ne 'completed') { Show-Aviso "Ainda processando. Tente de novo em alguns minutos."; return }
            $r = Invoke-VtHashLookup $hash
            if (-not $r) { Show-Aviso ("Nao consegui ler o resultado final. Veja em: https://www.virustotal.com/gui/file/" + $hash); return }
        } catch { Show-Aviso ("Erro ao enviar: " + $_.Exception.Message); return }
    }

    $v = Format-VtVeredito $r (Split-Path $caminho -Leaf)
    Write-Host ("Relatorio completo: https://www.virustotal.com/gui/file/" + $hash) -ForegroundColor DarkGray
    if ($v -eq 2 -and (Ask-YesNo "Mover este arquivo para a quarentena agora?")) {
        [void](Mover-Quarentena $caminho 'Confirmado por multiplas engines na VirusTotal')
    }
}

function Scan-PastaComDefender($pasta) {
    if (-not (Test-Path -LiteralPath $pasta)) { return }
    Write-Host ("Verificando com o Windows Defender: " + $pasta) -ForegroundColor DarkGray
    try { Start-MpScan -ScanType CustomScan -ScanPath $pasta -ErrorAction Stop; Show-Ok "Verificado" }
    catch { Show-Aviso "Nao consegui verificar essa pasta com o Defender agora." }
}

function Scan-ArquivosSemAssinatura($pastas) {
    $achados = @()
    foreach ($p in $pastas) {
        if (-not (Test-Path -LiteralPath $p)) { continue }
        foreach ($f in @(Get-ChildItem -LiteralPath $p -Recurse -Force -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Extension -match '^\.(exe|msi|scr|bat|cmd|ps1|vbs|js|jar)$' })) {
            $sig = Get-Assinatura $f.FullName
            if ($sig -notlike 'Valida*') { $achados += [pscustomobject]@{ Arquivo = $f.FullName; Assinatura = $sig; Data = $f.LastWriteTime } }
        }
    }
    return @($achados | Sort-Object Data -Descending)
}

# ---- acao principal: protege sem pedir nenhuma chave ----
function Protecao-Imediata {
    Write-Host ""
    Write-Host "=== PROTEGER AGORA ===" -ForegroundColor Cyan
    Write-Host "Vou verificar Downloads e Area de Trabalho com o proprio Windows Defender e reforcar as protecoes"
    Write-Host "do Windows. Nao precisa de nenhuma chave nem cadastro - e tudo local." -ForegroundColor DarkGray
    Write-Host ""

    $pastas = @((Join-Path $env:USERPROFILE 'Downloads'), [Environment]::GetFolderPath('Desktop'))
    foreach ($p in $pastas) { Scan-PastaComDefender $p }

    Write-Host ""
    $ameacas = @(Get-MpThreatDetection -ErrorAction SilentlyContinue | Where-Object { $_.InitialDetectionTime -ge (Get-Date).AddMinutes(-20) })
    if ($ameacas.Count -gt 0) {
        Write-Host "O Defender encontrou e ja tratou isto agora:" -ForegroundColor Red
        foreach ($a in $ameacas) { Write-Host ("  - " + $a.ThreatName) -ForegroundColor Red }
    } else {
        Show-Ok "O Defender nao encontrou ameacas conhecidas nessas pastas."
    }

    Write-Host ""
    Write-Host "Conferindo tambem arquivos sem assinatura digital (o Defender pode nao conhecer um trojan novo ainda)..." -ForegroundColor DarkGray
    $semAss = @(Scan-ArquivosSemAssinatura $pastas | Select-Object -First 15)
    if ($semAss.Count -gt 0) {
        Write-Host ""
        Write-Host "Encontrados sem assinatura confiavel (nao significa malware, mas merece atencao):" -ForegroundColor Yellow
        $i = 1
        foreach ($s in $semAss) { Write-Host ("{0,3}) {1}  [{2}]" -f $i, $s.Arquivo, $s.Assinatura); $i++ }
        Write-Host ""
        Write-Host "Se algum desses e o tal mod menu/crack e voce quer ver o relatorio detalhado (quantos antivirus detectam," -ForegroundColor DarkGray
        Write-Host "nomes de deteccao etc.), use a opcao 2 deste menu (pede uma chave gratuita da VirusTotal so nessa hora)." -ForegroundColor DarkGray
    }

    Write-Host ""
    Write-Host "Reforcando as protecoes do Windows..." -ForegroundColor Cyan
    Blindar-Sistema -Silencioso
    Write-Log "Protecao-Imediata executada"
}

function Blindar-Sistema {
    param([switch]$Silencioso)
    Write-Host ""
    if (-not $Silencioso) {
        Write-Host "=== REFORCAR WINDOWS DEFENDER E SMARTSCREEN ===" -ForegroundColor Cyan
        Write-Host "Protecoes preventivas que ajudam contra trojans disfarcados (mod menu/cheat/crack com virus junto)."
        Write-Host "Nao desativa nada que voce ja usa; so reforca." -ForegroundColor DarkGray
        Write-Host ""
        if (-not (Ask-YesNo "Continuar?")) { return }
    }

    try { Set-MpPreference -PUAProtection Enabled -ErrorAction Stop; Show-Ok "Protecao contra PUA (programas potencialmente indesejados) ativada" } catch { Show-Aviso "Nao consegui ativar protecao PUA." }
    try { Set-MpPreference -MAPSReporting Advanced -ErrorAction Stop; Show-Ok "Protecao na nuvem (MAPS) em modo avancado" } catch { }
    try { Set-MpPreference -SubmitSamplesConsent SendSafeSamples -ErrorAction Stop; Show-Ok "Envio automatico de amostras suspeitas ativado" } catch { }
    try { Set-MpPreference -DisableRealtimeMonitoring $false -ErrorAction Stop; Show-Ok "Protecao em tempo real confirmada ligada" } catch { }
    try { Set-MpPreference -DisableIOAVProtection $false -ErrorAction Stop } catch { }
    try { Set-MpPreference -DisableScriptScanning $false -ErrorAction Stop; Show-Ok "Verificacao de scripts (PowerShell/VBS/JS) ativada" } catch { }

    $pol = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
    Set-RegBackup $pol 'EnableSmartScreen' 1 'DWord'
    Set-RegBackup $pol 'ShellSmartScreenLevel' 'Warn' 'String'
    Show-Ok "SmartScreen para aplicativos e arquivos: ativado (avisa antes de abrir programa desconhecido)"

    $expl = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer'
    Set-RegBackup $expl 'SmartScreenEnabled' 'Warn' 'String'

    Write-Host ""
    Write-Host "Acesso Controlado a Pastas protege Documentos/Imagens/Desktop de qualquer programa nao reconhecido" -ForegroundColor Yellow
    Write-Host "mexer neles. De vez em quando bloqueia um instalador/mod legitimo (ai e so permitir na excecao)." -ForegroundColor Yellow
    if (Ask-YesNo "Ativar Acesso Controlado a Pastas?") {
        try { Set-MpPreference -EnableControlledFolderAccess Enabled -ErrorAction Stop; Show-Ok "Acesso Controlado a Pastas ativado" }
        catch { Show-Aviso "Nao consegui ativar (depende da versao do Defender)." }
    }

    Write-Log "Blindar-Sistema aplicado"
    Write-Host ""
    Write-Host "Pronto. Algumas mudancas valem a partir do proximo login." -ForegroundColor Green
}

# ----------------------------------------------------------------
# Caixa de Areia (Windows Sandbox) - rodar o arquivo suspeito ISOLADO
#
# Windows Sandbox e uma maquina virtual leve e descartavel que o proprio
# Windows ja traz pronta (Hyper-V por baixo). Ao fechar a janela, TUDO
# que rodou la dentro e apagado - sem deixar rastro no seu PC real.
# Configuramos aqui com a rede DESLIGADA (mesmo que seja um stealer, nao
# ha como ele mandar nada para fora) e a pasta montada so para LEITURA
# (ele nao consegue gravar nada de volta no disco real).
#
# Requisitos: Windows 10/11 PRO, Enterprise ou Education (nao existe no
# Windows Home) + virtualizacao ligada na BIOS. Nao funciona ao mesmo
# tempo que outro hipervisor incompativel (VirtualBox/VMware em alguns
# modos), ai e preciso desativar o outro primeiro.
# ----------------------------------------------------------------
function Test-SandboxDisponivel {
    return Get-WindowsOptionalFeature -Online -FeatureName 'Containers-DisposableClientVM' -ErrorAction SilentlyContinue
}

function Ativar-WindowsSandbox {
    Write-Host "Ativando o recurso Windows Sandbox..." -ForegroundColor Cyan
    try {
        Enable-WindowsOptionalFeature -Online -FeatureName 'Containers-DisposableClientVM' -All -NoRestart -ErrorAction Stop | Out-Null
        Show-Ok "Recurso ativado. Reinicie o PC para poder usar."
        return $true
    } catch {
        Show-Aviso ("Nao consegui ativar: " + $_.Exception.Message)
        Write-Host "Causas comuns: Windows HOME (Sandbox so existe no Pro/Enterprise/Education), virtualizacao desligada" -ForegroundColor Yellow
        Write-Host "na BIOS, ou outro programa de maquina virtual (VirtualBox/VMware) ocupando o Hyper-V." -ForegroundColor Yellow
        return $false
    }
}

function Abrir-CaixaDeAreia {
    Write-Host ""
    Write-Host "=== CAIXA DE AREIA: testar um arquivo suspeito ISOLADO ===" -ForegroundColor Cyan
    Write-Host "Abre uma copia descartavel do Windows (maquina virtual leve). O arquivo roda LA DENTRO, sem"
    Write-Host "rede e sem ver seus arquivos/senhas reais. Ao fechar a janela, tudo desaparece - sem rastro." -ForegroundColor DarkGray
    Write-Host ""

    $feat = Test-SandboxDisponivel
    if (-not $feat) {
        Write-Host "Esse Windows nao tem o recurso Sandbox disponivel (precisa de Windows 10/11 PRO, Enterprise ou" -ForegroundColor Yellow
        Write-Host "Education - no Windows HOME nao tem como ativar)." -ForegroundColor Yellow
        return
    }
    if ($feat.State -ne 'Enabled') {
        Write-Host "O recurso existe neste Windows mas ainda esta desativado." -ForegroundColor Yellow
        if (Ask-YesNo "Ativar agora? (o Windows vai pedir para reiniciar o PC depois)") {
            if (Ativar-WindowsSandbox) { Write-Host "Reinicie o PC e escolha esta opcao de novo." -ForegroundColor Green }
        }
        return
    }

    $caminho = (Read-Host "Caminho completo do arquivo suspeito (pode arrastar o arquivo para aqui e dar Enter)").Trim().Trim('"')
    if (-not (Test-Path -LiteralPath $caminho -PathType Leaf)) { Show-Aviso "Arquivo nao encontrado."; return }

    $pastaOrigem = Split-Path -Parent $caminho
    $nomeArq = Split-Path -Leaf $caminho
    $nomePasta = Split-Path -Leaf $pastaOrigem
    $wsb = Join-Path $env:TEMP ("sandbox-" + (Get-Date -Format 'yyyyMMddHHmmss') + '.wsb')

    $xml = "<Configuration>`n" +
        "  <VGpu>Disable</VGpu>`n" +
        "  <Networking>Disable</Networking>`n" +
        "  <AudioInput>Disable</AudioInput>`n" +
        "  <VideoInput>Disable</VideoInput>`n" +
        "  <ProtectedClient>Enable</ProtectedClient>`n" +
        "  <PrinterRedirection>Disable</PrinterRedirection>`n" +
        "  <ClipboardRedirection>Disable</ClipboardRedirection>`n" +
        "  <MappedFolders>`n" +
        "    <MappedFolder>`n" +
        "      <HostFolder>$pastaOrigem</HostFolder>`n" +
        "      <ReadOnly>true</ReadOnly>`n" +
        "    </MappedFolder>`n" +
        "  </MappedFolders>`n" +
        "  <LogonCommand>`n" +
        "    <Command>explorer.exe `"C:\Users\WDAGUtilityAccount\Desktop\$nomePasta`"</Command>`n" +
        "  </LogonCommand>`n" +
        "</Configuration>"
    Set-Content -LiteralPath $wsb -Value $xml -Encoding UTF8

    Write-Host ""
    Write-Host "Abrindo a Caixa de Areia (pode levar alguns segundos na primeira vez)..." -ForegroundColor Cyan
    Write-Host ""
    Write-Host "DENTRO da janela que vai abrir:" -ForegroundColor Yellow
    Write-Host ("  1) Vai abrir uma pasta com '{0}' dentro" -f $nomeArq) -ForegroundColor Yellow
    Write-Host "  2) Clique duas vezes nele para testar" -ForegroundColor Yellow
    Write-Host "  3) Rede esta DESLIGADA: mesmo que seja um stealer, ele nao consegue mandar nada para fora" -ForegroundColor Yellow
    Write-Host "  4) Pasta e SOMENTE LEITURA: ele nao consegue gravar nada de volta no seu PC real" -ForegroundColor Yellow
    Write-Host "  5) Por garantia, NAO digite nenhuma senha real la dentro" -ForegroundColor Yellow
    Write-Host "  6) Quando terminar, so feche a janela da Caixa de Areia - tudo e apagado sozinho" -ForegroundColor Yellow
    Start-Process $wsb
    Write-Log ("Caixa de Areia aberta para: " + $caminho)
}

function Menu-Blindagem {
    while ($true) {
        Clear-Host
        Write-Host "=========== BLINDAGEM: NAO DEIXAR O TROJAN ENTRAR ===========" -ForegroundColor Magenta
        Write-Host ""
        Write-Host "Pensado para o caso de mod menu/cheat/crack baixado que vem com trojan/stealer junto" -ForegroundColor DarkGray
        Write-Host "(nome inocente por fora, malware disfarcado por dentro)." -ForegroundColor DarkGray
        Write-Host ""
        Write-Host " 1) PROTEGER AGORA (recomendado): verifica Downloads/Area de Trabalho e reforca o Windows" -ForegroundColor Green
        Write-Host "    - automatico, local, NAO pede nenhuma chave nem cadastro"
        Write-Host ""
        Write-Host " 2) CAIXA DE AREIA: testar um arquivo suspeito ISOLADO (sem internet, sem acesso ao PC real)" -ForegroundColor Green
        Write-Host "    - roda numa maquina virtual descartavel; ao fechar, tudo desaparece"
        Write-Host ""
        Write-Host " 3) Ver o relatorio DETALHADO de um arquivo especifico (so se voce realmente quiser saber"
        Write-Host "    quantos antivirus detectam e os nomes da deteccao - usa a VirusTotal, pede uma chave gratuita)"
        Write-Host " 4) Configurar/trocar a chave da VirusTotal (so e usada pela opcao 3)"
        Write-Host " 5) Voltar"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1' { Protecao-Imediata; Pausa }
            '2' { Abrir-CaixaDeAreia; Pausa }
            '3' { Verificar-ArquivoVT; Pausa }
            '4' { Set-VtApiKey | Out-Null; Pausa }
            '5' { return }
        }
    }
}

function Menu-Seguranca {
    while ($true) {
        Clear-Host
        Write-Host "=========== SEGURANCA: CACAR MALWARE, MINERADORES E INVASORES ===========" -ForegroundColor Red
        Write-Host ""
        Write-Host " 1) Varredura RAPIDA     (processos, CPU/GPU, rede, inicializacao, configuracoes) ~1 a 2 min"
        Write-Host " 2) Varredura COMPLETA   (+ arquivos suspeitos e logs de invasao)                ~5 a 15 min"
        Write-Host " 3) Cacar so MINERADORES de criptomoeda"
        Write-Host " 4) Antivirus do Windows (atualizar, verificar, verificacao offline)"
        Write-Host " 5) Quarentena (ver / restaurar / esvaziar)"
        Write-Host " 6) Ver o ultimo relatorio"
        Write-Host " 7) BLINDAGEM: proteger agora (Defender + reforco) / ver detalhe de um arquivo" -ForegroundColor Magenta
        Write-Host " 8) Voltar ao menu principal"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1' { Cacar-Ameacas -Modo 'Rapida'; Pausa }
            '2' { Cacar-Ameacas -Modo 'Completa'; Pausa }
            '3' { Cacar-Ameacas -Modo 'Minerador'; Pausa }
            '4' { Menu-Defender }
            '5' { Menu-Quarentena }
            '6' {
                if (Test-Path -LiteralPath $script:RelFile) { Get-Content -LiteralPath $script:RelFile | ForEach-Object { Write-Host $_ } }
                else { Write-Host "Ainda nao ha relatorio." }
                Pausa
            }
            '7' { Menu-Blindagem }
            '8' { return }
        }
    }
}

# ======================================================================
# SECAO 8: APARENCIA E DESEMPENHO INTELIGENTE
#
# Equivale (e vai alem) da janela "Ajustar a aparencia e o desempenho do
# Windows" (SystemPropertiesPerformance): efeitos visuais, agendamento de
# processador, memoria virtual e DEP, escolhidos conforme o SEU hardware.
# Tudo e reversivel pela opcao Desfazer (FPS > Desfazer).
# ======================================================================
$script:VisualFile = Join-Path $env:USERPROFILE 'PC-Toolkit-visual.json'

function Initialize-Visual {
    if ('PCToolkit.Visual' -as [type]) { return $true }
    $codigo = @'
using System;
using System.Runtime.InteropServices;
namespace PCToolkit {
    public static class Visual {
        [StructLayout(LayoutKind.Sequential)]
        public struct ANIMATIONINFO { public uint cbSize; public int iMinAnimate; }
        [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", SetLastError = true)]
        private static extern bool SpiBoolGet(uint action, uint param, ref int value, uint ini);
        [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", SetLastError = true)]
        private static extern bool SpiPtr(uint action, uint param, IntPtr value, uint ini);
        [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", SetLastError = true)]
        private static extern bool SpiAnim(uint action, uint param, ref ANIMATIONINFO ai, uint ini);
        public static int GetBool(uint id) { int v = 0; if (!SpiBoolGet(id, 0, ref v, 0)) return -1; return v != 0 ? 1 : 0; }
        public static bool SetPtr(uint id, bool on) { return SpiPtr(id, 0, new IntPtr(on ? 1 : 0), 3); }
        public static bool SetParam(uint id, bool on) { return SpiPtr(id, on ? 1u : 0u, IntPtr.Zero, 3); }
        public static int GetMinAnim() {
            ANIMATIONINFO a = new ANIMATIONINFO(); a.cbSize = 8; a.iMinAnimate = 0;
            if (!SpiAnim(0x0048, 8, ref a, 0)) return -1;
            return a.iMinAnimate != 0 ? 1 : 0;
        }
        public static bool SetMinAnim(bool on) {
            ANIMATIONINFO a = new ANIMATIONINFO(); a.cbSize = 8; a.iMinAnimate = on ? 1 : 0;
            return SpiAnim(0x0049, 8, ref a, 3);
        }
    }
}
'@
    try { Add-Type -TypeDefinition $codigo -Language CSharp -ErrorAction Stop; return $true } catch { return $false }
}

# Efeitos controlados pela API do Windows (aplicam na hora, sem reiniciar).
# V = valor para cada estilo: [0] MAXIMO desempenho, [1] EQUILIBRADO, [2] VISUAL+RAPIDO
function Get-EfeitosSpi {
    return @(
        @{ K = 'ClientArea';  N = 'Animar controles e elementos dentro das janelas'; G = 0x1042; S = 0x1043; M = 'ptr';   V = @(0, 0, 0) },
        @{ K = 'MinAnim';     N = 'Animar janelas ao minimizar e maximizar';          G = 0;      S = 0;      M = 'anim';  V = @(0, 0, 0) },
        @{ K = 'MenuAnim';    N = 'Animacao de menus';                                G = 0x1002; S = 0x1003; M = 'ptr';   V = @(0, 0, 0) },
        @{ K = 'MenuFade';    N = 'Efeito de esmaecer/deslizar menus';                G = 0x1012; S = 0x1013; M = 'ptr';   V = @(0, 0, 0) },
        @{ K = 'ComboAnim';   N = 'Deslizar caixas de combinacao';                    G = 0x1004; S = 0x1005; M = 'ptr';   V = @(0, 0, 0) },
        @{ K = 'TipAnim';     N = 'Animacao de dicas de ferramenta (tooltips)';       G = 0x1016; S = 0x1017; M = 'ptr';   V = @(0, 0, 0) },
        @{ K = 'TipFade';     N = 'Esmaecer dicas de ferramenta';                     G = 0x1018; S = 0x1019; M = 'ptr';   V = @(0, 0, 0) },
        @{ K = 'SelFade';     N = 'Esmaecer itens de menu depois de clicar';          G = 0x1014; S = 0x1015; M = 'ptr';   V = @(0, 0, 0) },
        @{ K = 'ListSmooth';  N = 'Rolagem suave em listas';                          G = 0x1006; S = 0x1007; M = 'ptr';   V = @(0, 0, 1) },
        @{ K = 'CursorShadow'; N = 'Sombra sob o ponteiro do mouse';                  G = 0x101A; S = 0x101B; M = 'ptr';   V = @(0, 0, 1) },
        @{ K = 'DropShadow';  N = 'Sombras sob as janelas';                           G = 0x1024; S = 0x1025; M = 'ptr';   V = @(0, 1, 1) },
        @{ K = 'DragFull';    N = 'Mostrar conteudo da janela ao arrastar';           G = 0x26;   S = 0x25;   M = 'param'; V = @(0, 1, 1) },
        @{ K = 'FontSmooth';  N = 'Suavizar bordas das fontes (ClearType)';           G = 0x4A;   S = 0x4B;   M = 'param'; V = @(1, 1, 1) }
    )
}

# Efeitos guardados no registro (valem apos reiniciar o Explorer / sair e entrar)
function Get-EfeitosReg {
    $adv = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced'
    $dwm = 'HKCU:\Software\Microsoft\Windows\DWM'
    $dsk = 'HKCU:\Control Panel\Desktop'
    return @(
        @{ N = 'Visualizar (Aero Peek)';                     P = $dwm; Nm = 'EnableAeroPeek';             T = 'DWord';  V = @(0, 0, 1) },
        @{ N = 'Salvar previas de miniaturas da barra';      P = $dwm; Nm = 'AlwaysHibernateThumbnails';  T = 'DWord';  V = @(0, 0, 1) },
        @{ N = 'Animacoes na barra de tarefas';              P = $adv; Nm = 'TaskbarAnimations';          T = 'DWord';  V = @(0, 0, 0) },
        @{ N = 'Mostrar apenas icones (sem miniaturas)';     P = $adv; Nm = 'IconsOnly';                  T = 'DWord';  V = @(1, 0, 0) },
        @{ N = 'Retangulo de selecao translucido';           P = $adv; Nm = 'ListviewAlphaSelect';        T = 'DWord';  V = @(0, 0, 1) },
        @{ N = 'Sombra nos nomes dos icones da area de trabalho'; P = $adv; Nm = 'ListviewShadow';        T = 'DWord';  V = @(0, 1, 1) },
        @{ N = 'Efeitos de transparencia';                   P = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize'; Nm = 'EnableTransparency'; T = 'DWord'; V = @(0, 0, 0) },
        @{ N = 'Atraso para abrir menus (ms)';               P = $dsk; Nm = 'MenuShowDelay';              T = 'String'; V = @('0', '50', '100') },
        @{ N = 'Tempo para detectar programa travado (ms)';  P = $dsk; Nm = 'HungAppTimeout';             T = 'String'; V = @('3000', '3000', '3000') },
        @{ N = 'Tempo de espera ao fechar programas (ms)';   P = $dsk; Nm = 'WaitToKillAppTimeout';       T = 'String'; V = @('5000', '5000', '5000') },
        @{ N = 'Modo de efeitos visuais (3 = personalizado)'; P = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\VisualEffects'; Nm = 'VisualFXSetting'; T = 'DWord'; V = @(3, 3, 3) }
    )
}

function Ler-Efeito($e) {
    if ($e.M -eq 'anim') { return [PCToolkit.Visual]::GetMinAnim() }
    return [PCToolkit.Visual]::GetBool([uint32]$e.G)
}

function Set-Efeito($e, [bool]$ligado) {
    if ($e.M -eq 'anim') { [void][PCToolkit.Visual]::SetMinAnim($ligado) }
    elseif ($e.M -eq 'param') { [void][PCToolkit.Visual]::SetParam([uint32]$e.S, $ligado) }
    else { [void][PCToolkit.Visual]::SetPtr([uint32]$e.S, $ligado) }
}

function Atualizar-Visual {
    try { Start-Process -FilePath 'rundll32.exe' -ArgumentList 'user32.dll,UpdatePerUserSystemParameters' -WindowStyle Hidden -ErrorAction Stop } catch { }
}

function Mostrar-EstadoAparencia {
    if (-not (Initialize-Visual)) { Show-Aviso "Nao consegui carregar o modulo visual."; return }
    Write-Host ""
    Write-Host "Efeitos visuais atuais:" -ForegroundColor Cyan
    foreach ($e in (Get-EfeitosSpi)) {
        $v = Ler-Efeito $e
        $t = 'desconhecido'
        $cor = 'DarkGray'
        if ($v -eq 1) { $t = 'ligado   '; $cor = 'Yellow' } elseif ($v -eq 0) { $t = 'desligado'; $cor = 'Green' }
        Write-Host ("  [{0}] {1}" -f $t, $e.N) -ForegroundColor $cor
    }
    foreach ($r in (Get-EfeitosReg)) {
        $atual = (Get-ItemProperty -LiteralPath $r.P -Name $r.Nm -ErrorAction SilentlyContinue).($r.Nm)
        if ($null -eq $atual) { $atual = '(padrao do Windows)' }
        Write-Host ("  [{0}] {1}" -f $atual, $r.N) -ForegroundColor DarkGray
    }
}

# Aplica um estilo: idx 0 = MAXIMO, 1 = EQUILIBRADO, 2 = VISUAL+RAPIDO
function Aplicar-Aparencia($idx) {
    if (-not (Initialize-Visual)) { Show-Aviso "Nao consegui carregar o modulo visual."; return $false }
    $efs = Get-EfeitosSpi
    if (-not (Test-Path -LiteralPath $script:VisualFile)) {
        $orig = @{}
        foreach ($e in $efs) { $orig[$e.K] = Ler-Efeito $e }
        try { ConvertTo-Json -InputObject $orig | Set-Content -LiteralPath $script:VisualFile -Encoding UTF8 } catch { }
    }
    foreach ($e in $efs) { Set-Efeito $e ([bool]($e.V[$idx])) }
    foreach ($r in (Get-EfeitosReg)) { Set-RegBackup $r.P $r.Nm ($r.V[$idx]) $r.T }
    Atualizar-Visual
    Write-Log ("Aparencia aplicada (estilo " + $idx + ")")
    return $true
}

function Restaurar-Visual {
    if (-not (Test-Path -LiteralPath $script:VisualFile)) { return $false }
    $o = $null
    try { $o = Get-Content -LiteralPath $script:VisualFile -Raw | ConvertFrom-Json } catch { }
    if ($o -and (Initialize-Visual)) {
        foreach ($e in (Get-EfeitosSpi)) {
            $v = $o.($e.K)
            if ($null -ne $v -and [int]$v -ge 0) { Set-Efeito $e ([int]$v -eq 1) }
        }
        Atualizar-Visual
    }
    Remove-Item -LiteralPath $script:VisualFile -Force -ErrorAction SilentlyContinue
    return $true
}

# ----------------------------------------------------------------
# Leitura do hardware para decidir o estilo
# ----------------------------------------------------------------
function Get-PerfilHardware {
    $cs = Get-CimInstance Win32_ComputerSystem
    $ramGB = 0
    if ($cs -and $cs.TotalPhysicalMemory) { $ramGB = [math]::Round($cs.TotalPhysicalMemory / 1GB, 0) }
    $threads = 0
    foreach ($c in @(Get-CimInstance Win32_Processor)) { $threads += [int]$c.NumberOfLogicalProcessors }
    $cpuNome = ''
    $cpu1 = Get-CimInstance Win32_Processor | Select-Object -First 1
    if ($cpu1) { $cpuNome = ([string]$cpu1.Name).Trim() }

    $gpus = @(Get-CimInstance Win32_VideoController | Where-Object { $_.Name -notmatch 'Basic|Remote|Virtual|Parsec|Meta|Mirage|Citrix' })
    $gpuNome = 'desconhecida'
    $dedicada = $false
    $modernaHags = $false
    foreach ($g in $gpus) {
        if ($g.Name -match 'NVIDIA|GeForce|RTX|GTX|Radeon RX|Radeon Pro|Arc ') { $dedicada = $true; $gpuNome = [string]$g.Name }
        if ($g.Name -match 'RTX|GTX 16|Radeon RX (5[5-9]|[6-9])\d{2}|Arc ') { $modernaHags = $true }
    }
    if (-not $dedicada -and $gpus.Count -gt 0) { $gpuNome = [string]$gpus[0].Name }

    $tipoDisco = Get-TipoDisco ($env:SystemDrive.Substring(0, 1))
    $notebook = [bool](Get-CimInstance Win32_Battery)

    $pts = 0
    if ($ramGB -ge 16) { $pts += 2 } elseif ($ramGB -ge 8) { $pts += 1 }
    if ($threads -gt 8) { $pts += 2 } elseif ($threads -gt 4) { $pts += 1 }
    if ($dedicada) { $pts += 2 }
    if ($tipoDisco -eq 'SSD') { $pts += 1 }
    $estilo = 0
    if ($pts -ge 6) { $estilo = 2 } elseif ($pts -ge 3) { $estilo = 1 }

    return [pscustomobject]@{
        RamGB = $ramGB; Threads = $threads; Cpu = $cpuNome; Gpu = $gpuNome; GpuDedicada = $dedicada
        GpuModerna = $modernaHags; Disco = $tipoDisco; Notebook = $notebook; Pontos = $pts; Estilo = $estilo
    }
}

function Get-NomeEstilo($idx) {
    if ($idx -eq 0) { return 'MAXIMO DESEMPENHO' }
    if ($idx -eq 1) { return 'EQUILIBRADO' }
    return 'VISUAL + RAPIDO'
}

# ----------------------------------------------------------------
# Aba "Avancado" do Windows: agendamento do processador e memoria virtual
# ----------------------------------------------------------------
function Verificar-Paginacao {
    param([bool]$Corrigir = $false)
    $cs = Get-CimInstance Win32_ComputerSystem
    $ramMB = 0
    if ($cs -and $cs.TotalPhysicalMemory) { $ramMB = [math]::Round($cs.TotalPhysicalMemory / 1MB, 0) }
    $pf = @(Get-CimInstance Win32_PageFileUsage)
    $totalPf = 0
    foreach ($x in $pf) { $totalPf += [int]$x.AllocatedBaseSize }
    $auto = [bool]$cs.AutomaticManagedPagefile
    if ($auto) {
        Show-Ok ("Memoria virtual: gerenciada automaticamente pelo Windows (recomendado). Atual: {0} MB" -f $totalPf)
        return
    }
    $problema = $null
    if ($pf.Count -eq 0) { $problema = 'DESATIVADA: jogos e programas podem fechar sozinhos com erro de memoria.' }
    elseif ($totalPf -lt [math]::Min($ramMB, 8192)) { $problema = ("pequena demais ({0} MB para {1} MB de RAM): jogos modernos podem fechar com erro de memoria." -f $totalPf, $ramMB) }
    if ($problema) {
        Show-Aviso ("Memoria virtual (arquivo de paginacao) " + $problema)
        if ($Corrigir -and (Ask-YesNo "Voltar ao gerenciamento automatico do Windows? (recomendado)")) {
            Set-CimInstance -InputObject $cs -Property @{ AutomaticManagedPagefile = $true } -ErrorAction SilentlyContinue
            Show-Ok "Memoria virtual voltara ao automatico apos reiniciar o PC"
        }
    } else {
        Show-Ok ("Memoria virtual: tamanho fixo de {0} MB (adequado)" -f $totalPf)
    }
}

function Mostrar-DEP {
    $os = Get-CimInstance Win32_OperatingSystem
    $txt = 'desconhecido'
    switch ([int]$os.DataExecutionPrevention_SupportPolicy) {
        0 { $txt = 'DESLIGADA para tudo (nao recomendado)' }
        1 { $txt = 'ligada para todos os programas' }
        2 { $txt = 'ligada para o Windows e programas essenciais (padrao)' }
        3 { $txt = 'ligada para tudo, exceto os programas que voce liberou' }
    }
    Write-Host ("  Prevencao de execucao de dados (DEP): " + $txt)
    Write-Host "    (Nao altero o DEP: ele protege contra malware e quase nao afeta o FPS.)" -ForegroundColor DarkGray
}

# ----------------------------------------------------------------
# Fluxo principal
# ----------------------------------------------------------------
function Otimizar-Inteligente {
    Write-Host ""
    Write-Host "=== APARENCIA E DESEMPENHO INTELIGENTE ===" -ForegroundColor Green
    Write-Host "Equivale a janela 'Ajustar a aparencia e o desempenho do Windows', mas decidindo item por item"
    Write-Host "conforme o seu hardware (RAM, CPU, placa de video, tipo de disco)."
    Write-Host ""
    if (-not (Initialize-Visual)) { Show-Aviso "Nao consegui carregar o modulo visual. Abortando."; return }

    $hw = Get-PerfilHardware
    Write-Host "Seu hardware:" -ForegroundColor Cyan
    Write-Host ("  CPU:    {0} ({1} threads)" -f $hw.Cpu, $hw.Threads)
    Write-Host ("  RAM:    {0} GB" -f $hw.RamGB)
    $tg = 'integrada/basica'
    if ($hw.GpuDedicada) { $tg = 'dedicada' }
    Write-Host ("  Video:  {0} ({1})" -f $hw.Gpu, $tg)
    Write-Host ("  Disco do Windows: {0}" -f $hw.Disco)
    $tp = 'desktop'
    if ($hw.Notebook) { $tp = 'notebook' }
    Write-Host ("  Tipo:   {0}" -f $tp)
    Write-Host ("  Pontuacao: {0}/7  ->  estilo recomendado: {1}" -f $hw.Pontos, (Get-NomeEstilo $hw.Estilo)) -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Escolha o estilo:" -ForegroundColor Cyan
    Write-Host (" 1) AUTOMATICO        - aplica o estilo recomendado para o seu PC ({0})" -f (Get-NomeEstilo $hw.Estilo))
    Write-Host " 2) MAXIMO DESEMPENHO - todos os efeitos desligados (visual 'classico', mais leve possivel)"
    Write-Host " 3) EQUILIBRADO       - sem animacoes, mas mantem sombras e arrastar janelas completo"
    Write-Host " 4) VISUAL + RAPIDO   - tira so o que atrasa (animacoes/transparencia); mantem miniaturas, sombras e Peek"
    Write-Host " 5) Ver o estado atual dos efeitos"
    Write-Host " 6) Cancelar"
    $op = Read-Host "Escolha (Enter = 1)"
    $idx = $hw.Estilo
    switch ($op) {
        '2' { $idx = 0 }
        '3' { $idx = 1 }
        '4' { $idx = 2 }
        '5' { Mostrar-EstadoAparencia; return }
        '6' { return }
    }

    Write-Host ""
    Write-Host ("Estilo escolhido: {0}" -f (Get-NomeEstilo $idx)) -ForegroundColor Green
    Write-Host "Vou ajustar:"
    Write-Host "  - 13 efeitos visuais pela API do Windows (aplicam na hora) + 11 ajustes de registro"
    Write-Host "  - Fontes suaves (ClearType) SEMPRE mantidas, para o texto nao ficar feio"
    Write-Host "  - Agendamento do processador: prioridade para o programa/jogo em primeiro plano"
    Write-Host "  - Verificacao da memoria virtual e do agendamento de GPU por hardware (HAGS)"
    if (-not (Ask-YesNo "Continuar?")) { Write-Host "Cancelado."; return }
    if (Ask-YesNo "Criar antes um ponto de restauracao do Windows? (recomendado)") { Criar-PontoRestauracao 'PC Toolkit - antes de ajustar aparencia' }

    if (Aplicar-Aparencia $idx) { Show-Ok ("Efeitos visuais ajustados: " + (Get-NomeEstilo $idx)) }

    # Aba Avancado > Agendamento do processador (Programas)
    Set-RegBackup 'HKLM:\SYSTEM\CurrentControlSet\Control\PriorityControl' 'Win32PrioritySeparation' 38 'DWord'
    Show-Ok "Agendamento do processador: prioridade para programas em primeiro plano"

    # DirectX: otimizacao para jogos em janela e taxa variavel (VRR)
    $dxKey = 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'
    $dx = Get-RegString $dxKey 'DirectXUserGlobalSettings'
    $dx = Merge-DxSetting $dx 'SwapEffectUpgradeEnable' '1'
    $dx = Merge-DxSetting $dx 'VRROptimizeEnable' '1'
    Set-RegBackup $dxKey 'DirectXUserGlobalSettings' $dx 'String'
    Show-Ok "DirectX: otimizacoes para jogos em janela/tela sem borda e taxa variavel (VRR) ligadas"

    # Memoria virtual
    Verificar-Paginacao -Corrigir $true

    # HAGS: so se a GPU for moderna e o Windows oferecer a opcao
    $gd = 'HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers'
    $hags = (Get-ItemProperty -LiteralPath $gd -Name HwSchMode -ErrorAction SilentlyContinue).HwSchMode
    if ($null -eq $hags) {
        Show-Info "Agendamento de GPU por hardware (HAGS): este PC/driver nao oferece, nada a fazer."
    } elseif ($hags -eq 2) {
        Show-Ok "Agendamento de GPU por hardware (HAGS): ja esta ligado"
    } elseif ($hw.GpuModerna) {
        if (Ask-YesNo "Sua placa de video e moderna: ligar o Agendamento de GPU por hardware (HAGS)? Ajuda em jogos com Frame Generation e reduz latencia. Precisa reiniciar") {
            Set-RegBackup $gd 'HwSchMode' 2 'DWord'
            Show-Ok "HAGS sera ligado no proximo reinicio"
        }
    } else {
        Show-Info "HAGS: sua placa de video e mais antiga, deixei como esta (o ganho e incerto nela)."
    }

    Mostrar-DEP

    # Plano de energia conforme o tipo de PC
    Write-Host ""
    $sug = 'Maximo'
    $sugTxt = 'MAXIMO FPS (desktop)'
    if ($hw.Notebook) { $sug = 'Frio'; $sugTxt = 'EQUILIBRADO FRIO (notebook: menos calor e ventoinha mais baixa)' }
    if (Ask-YesNo ("Aplicar tambem o plano de energia recomendado: " + $sugTxt + "?")) {
        $m = 100
        if ($sug -eq 'Frio') { $m = 99 }
        if (Aplicar-PerfilTermico $sug $m) { Show-Ok "Plano de energia aplicado" }
    }

    # Programas de inicializacao
    $ini = @(Get-ItensInicializacao)
    $ativos = 0
    foreach ($i in $ini) { if (Test-InicialAtivo $i.Apr $i.Nome) { $ativos++ } }
    if ($ativos -gt 8) {
        Write-Host ""
        Show-Aviso ("{0} programas abrem junto com o Windows. Quanto menos, mais rapido o PC liga e responde." -f $ativos)
        if (Ask-YesNo "Abrir agora o gerenciador de programas de inicializacao?") { Gerenciar-Inicializacao }
    }

    Write-Host ""
    if (Ask-YesNo "Reiniciar o Explorer agora para aplicar miniaturas/barra de tarefas na hora? (a barra some por 2 segundos)") {
        Stop-Process -Name explorer -Force -ErrorAction SilentlyContinue
    }
    Write-Log ("Otimizar-Inteligente concluido (estilo " + $idx + ")")
    Write-Host ""
    Write-Host "Pronto. Os efeitos visuais ja valem. Reinicie o PC para o restante (HAGS, memoria virtual, prioridade)." -ForegroundColor Green
    Write-Host "Para voltar ao visual original: menu FPS > Desfazer (ou Principal > opcao de desfazer)." -ForegroundColor DarkGray
}

# ======================================================================
# SECAO 9: CENTRAL DE FPS (novo na 4.0)
#
# - RAIO-X DE FPS: nota do PC + correcao com 1 clique
# - Medidor de FPS real (PresentMon da Intel): FPS medio, 1% low, 0.1% low,
#   engasgos, e comparacao ANTES x DEPOIS
# - Analise de GARGALO (CPU x placa de video) em qualquer jogo
# - Monitor/tela: detecta monitor rodando abaixo do Hz maximo e corrige
# - Pacote TURBO do Modo Jogo: timer 0.5 ms, limpeza de memoria standby,
#   apps de fundo com prioridade baixa, nucleos P (Intel hibrido) / CCD com
#   3D V-Cache (AMD X3D)
# - Rede: teste roteador x internet (ping, jitter, perda), DNS mais rapido,
#   placa de rede sem economia de energia
# - Latencia e input: timer global, MPO, Power Throttling, mouse
# Tudo reversivel (Desfazer).
# ======================================================================
$script:BenchFile = Join-Path $env:USERPROFILE 'PC-Toolkit-benchmarks.json'
$script:HistFile  = Join-Path $env:USERPROFILE 'PC-Toolkit-sessoes.json'
$script:RedeFile  = Join-Path $env:USERPROFILE 'PC-Toolkit-rede.json'
$script:ToolsDir  = Join-Path $env:USERPROFILE 'PC-Toolkit-ferramentas'

# GUIDs extras de energia
$script:SetEpp     = '36687f9e-e3a5-4dbf-b1dc-15eb381c6863'   # PERFEPP (preferencia de energia x desempenho)
$script:SetPark    = '0cc5b647-c1df-4637-891a-dec35c318583'   # CPMINCORES (estacionamento de nucleos)
$script:SubPcie    = '501a4d13-42af-4429-9fd1-a8218c268e20'   # SUB_PCIEXPRESS
$script:SetAspm    = 'ee12f906-d277-404b-b6da-e5fa1a576df5'   # ASPM
$script:SubUsb     = '2a737441-1930-4402-8d77-b2bebba308a3'   # SUB_USB
$script:SetUsbSusp = '48e6b7a6-50f5-4782-a5d4-53bb8f07e226'   # USB selective suspend

# ----------------------------------------------------------------
# Tipos nativos: tela (Hz), timer, memoria standby, topologia da CPU
# ----------------------------------------------------------------
function Initialize-Fps {
    if ('PCToolkit.Fps' -as [type]) { return $true }
    $codigo = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
namespace PCToolkit {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
    public struct DEVMODE {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion; public short dmDriverVersion; public short dmSize; public short dmDriverExtra;
        public int dmFields; public int dmPositionX; public int dmPositionY; public int dmDisplayOrientation; public int dmDisplayFixedOutput;
        public short dmColor; public short dmDuplex; public short dmYResolution; public short dmTTOption; public short dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels; public int dmBitsPerPel; public int dmPelsWidth; public int dmPelsHeight; public int dmDisplayFlags; public int dmDisplayFrequency;
        public int dmICMMethod; public int dmICMIntent; public int dmMediaType; public int dmDitherType; public int dmReserved1; public int dmReserved2; public int dmPanningWidth; public int dmPanningHeight;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
    public struct DISPLAY_DEVICE {
        public int cb;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceString;
        public int StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceKey;
    }
    public static class Fps {
        [DllImport("user32.dll", CharSet = CharSet.Ansi)] private static extern bool EnumDisplayDevices(string lpDevice, uint iDevNum, ref DISPLAY_DEVICE lpDisplayDevice, uint dwFlags);
        [DllImport("user32.dll", CharSet = CharSet.Ansi)] private static extern bool EnumDisplaySettings(string deviceName, int modeNum, ref DEVMODE devMode);
        [DllImport("user32.dll", CharSet = CharSet.Ansi)] private static extern int ChangeDisplaySettingsEx(string lpszDeviceName, ref DEVMODE lpDevMode, IntPtr hwnd, uint dwflags, IntPtr lParam);
        [DllImport("ntdll.dll")] private static extern int NtSetTimerResolution(uint desired, [MarshalAs(UnmanagedType.U1)] bool set, out uint current);
        [DllImport("ntdll.dll")] private static extern int NtQueryTimerResolution(out uint minimum, out uint maximum, out uint current);
        [DllImport("ntdll.dll")] private static extern int RtlAdjustPrivilege(int privilege, [MarshalAs(UnmanagedType.U1)] bool enable, [MarshalAs(UnmanagedType.U1)] bool currentThread, [MarshalAs(UnmanagedType.U1)] out bool wasEnabled);
        [DllImport("ntdll.dll")] private static extern int NtSetSystemInformation(int infoClass, ref int info, int length);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetSystemCpuSetInformation(IntPtr information, uint bufferLength, out uint returnedLength, IntPtr process, uint flags);

        private static DEVMODE NovoDm() { DEVMODE d = new DEVMODE(); d.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE)); return d; }

        // "dispositivo|gpu|monitor|largura|altura|hzAtual|hzMaximo|primario"
        public static string[] Telas() {
            List<string> r = new List<string>();
            for (uint i = 0; i < 16; i++) {
                DISPLAY_DEVICE d = new DISPLAY_DEVICE(); d.cb = Marshal.SizeOf(typeof(DISPLAY_DEVICE));
                if (!EnumDisplayDevices(null, i, ref d, 0)) break;
                if ((d.StateFlags & 1) == 0) continue;
                DEVMODE cur = NovoDm();
                if (!EnumDisplaySettings(d.DeviceName, -1, ref cur)) continue;
                int max = cur.dmDisplayFrequency;
                DEVMODE m = NovoDm();
                for (int k = 0; k < 5000 && EnumDisplaySettings(d.DeviceName, k, ref m); k++) {
                    if ((m.dmDisplayFlags & 2) != 0) continue;
                    if (m.dmPelsWidth == cur.dmPelsWidth && m.dmPelsHeight == cur.dmPelsHeight && m.dmBitsPerPel == cur.dmBitsPerPel && m.dmDisplayFrequency > max && m.dmDisplayFrequency < 1000) max = m.dmDisplayFrequency;
                }
                string mon = "";
                DISPLAY_DEVICE md = new DISPLAY_DEVICE(); md.cb = Marshal.SizeOf(typeof(DISPLAY_DEVICE));
                if (EnumDisplayDevices(d.DeviceName, 0, ref md, 0)) mon = md.DeviceString;
                bool prim = (d.StateFlags & 4) != 0;
                r.Add(d.DeviceName + "|" + d.DeviceString + "|" + mon + "|" + cur.dmPelsWidth + "|" + cur.dmPelsHeight + "|" + cur.dmDisplayFrequency + "|" + max + "|" + (prim ? "1" : "0"));
            }
            return r.ToArray();
        }

        // 0 = sucesso. Testa antes de aplicar.
        public static int DefinirHz(string dev, int hz) {
            DEVMODE dm = NovoDm();
            if (!EnumDisplaySettings(dev, -1, ref dm)) return -99;
            dm.dmDisplayFrequency = hz;
            dm.dmFields = 0x80000 | 0x100000 | 0x400000;
            int t = ChangeDisplaySettingsEx(dev, ref dm, IntPtr.Zero, 2, IntPtr.Zero);
            if (t != 0) return t;
            return ChangeDisplaySettingsEx(dev, ref dm, IntPtr.Zero, 1, IntPtr.Zero);
        }

        public static double TimerAtualMs() { uint a, b, c; if (NtQueryTimerResolution(out a, out b, out c) != 0) return -1; return c / 10000.0; }
        public static double TimerMelhorMs() { uint a, b, c; if (NtQueryTimerResolution(out a, out b, out c) != 0) return -1; return b / 10000.0; }
        public static double DefinirTimer(bool ligar) {
            uint a, b, c, atual;
            if (NtQueryTimerResolution(out a, out b, out c) != 0) return -1;
            NtSetTimerResolution(b, ligar, out atual);
            return atual / 10000.0;
        }

        // Limpa a lista de memoria standby (o mesmo que o ISLC faz). 0 = sucesso.
        public static int LimparStandby() {
            bool w;
            RtlAdjustPrivilege(13, true, false, out w);
            int cmd = 4;
            return NtSetSystemInformation(80, ref cmd, 4);
        }

        // Para cada processador logico do grupo 0: indice, classe de eficiencia, cache L3, nucleo
        public static int[] CpuSets() {
            uint len = 0;
            GetSystemCpuSetInformation(IntPtr.Zero, 0, out len, IntPtr.Zero, 0);
            if (len == 0) return new int[0];
            IntPtr buf = Marshal.AllocHGlobal((int)len);
            try {
                if (!GetSystemCpuSetInformation(buf, len, out len, IntPtr.Zero, 0)) return new int[0];
                List<int> res = new List<int>();
                int off = 0;
                while (off + 24 <= (int)len) {
                    int size = Marshal.ReadInt32(buf, off);
                    if (size <= 0) break;
                    int type = Marshal.ReadInt32(buf, off + 4);
                    if (type == 0 && Marshal.ReadInt16(buf, off + 12) == 0) {
                        res.Add(Marshal.ReadByte(buf, off + 14));
                        res.Add(Marshal.ReadByte(buf, off + 18));
                        res.Add(Marshal.ReadByte(buf, off + 16));
                        res.Add(Marshal.ReadByte(buf, off + 15));
                    }
                    off += size;
                }
                return res.ToArray();
            } finally { Marshal.FreeHGlobal(buf); }
        }
    }
}
'@
    try { Add-Type -TypeDefinition $codigo -Language CSharp -IgnoreWarnings -ErrorAction Stop; return $true } catch { return $false }
}

# ----------------------------------------------------------------
# Auxiliares
# ----------------------------------------------------------------
function Get-RegDword($path, $name) {
    $p = Get-ItemProperty -LiteralPath $path -Name $name -ErrorAction SilentlyContinue
    if ($p -and ($p.PSObject.Properties.Name -contains $name)) { try { return [int64]$p.$name } catch { return $null } }
    return $null
}

function Load-JsonLista($arq) {
    if (Test-Path -LiteralPath $arq) {
        try { return @(Get-Content -LiteralPath $arq -Raw | ConvertFrom-Json) } catch { }
    }
    return @()
}

function Save-JsonLista($arq, $lista, $max) {
    $l = @($lista | Where-Object { $null -ne $_ })
    if ($max -and $l.Count -gt $max) { $l = @($l[($l.Count - $max)..($l.Count - 1)]) }
    try { ConvertTo-Json -InputObject @($l) -Depth 5 | Set-Content -LiteralPath $arq -Encoding UTF8 } catch { }
}

function Get-InterfacePrincipal {
    $rt = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Sort-Object -Property RouteMetric | Select-Object -First 1
    if (-not $rt) { return $null }
    $ad = Get-NetAdapter -InterfaceIndex $rt.InterfaceIndex -ErrorAction SilentlyContinue
    $wifi = $false
    $guid = $null
    $nome = [string]$rt.InterfaceAlias
    if ($ad) {
        if (([string]$ad.PhysicalMediaType -match '802\.11|Wireless') -or ([string]$ad.InterfaceDescription -match 'Wi-?Fi|Wireless|WLAN|802\.11')) { $wifi = $true }
        $guid = [string]$ad.InterfaceGuid
        $nome = [string]$ad.Name
    }
    return [pscustomobject]@{ Index = [int]$rt.InterfaceIndex; Alias = [string]$rt.InterfaceAlias; Nome = $nome; Gateway = [string]$rt.NextHop; Wifi = $wifi; Guid = $guid }
}

function Abrir-SiteDriver($nomeGpu) {
    $url = 'ms-settings:windowsupdate-optionalupdates'
    if ($nomeGpu -match 'NVIDIA|GeForce|RTX|GTX|Quadro') { $url = 'https://www.nvidia.com/Download/index.aspx' }
    elseif ($nomeGpu -match 'AMD|Radeon') { $url = 'https://www.amd.com/en/support/download/drivers.html' }
    elseif ($nomeGpu -match 'Intel|Arc') { $url = 'https://www.intel.com/content/www/us/en/support/detect.html' }
    Write-Host ("  Abrindo: " + $url) -ForegroundColor DarkGray
    Start-Process $url
}

# ----------------------------------------------------------------
# Tela: taxa de atualizacao (Hz)
# ----------------------------------------------------------------
function Get-Telas {
    $r = @()
    if (-not (Initialize-Fps)) { return $r }
    $linhas = @()
    try { $linhas = @([PCToolkit.Fps]::Telas()) } catch { return $r }
    foreach ($l in $linhas) {
        $p = @(([string]$l) -split '\|')
        if ($p.Count -lt 8) { continue }
        $r += [pscustomobject]@{ Dev = $p[0]; Gpu = $p[1]; Monitor = $p[2]; W = [int]$p[3]; H = [int]$p[4]; Hz = [int]$p[5]; MaxHz = [int]$p[6]; Primario = ($p[7] -eq '1') }
    }
    return $r
}

function Confirmar-Tela($seg) {
    try { while ([Console]::KeyAvailable) { [void][Console]::ReadKey($true) } } catch { }
    Write-Host ("A imagem esta normal? Aperte S nos proximos {0} segundos para MANTER. Se a tela ficou preta, so espere: eu volto sozinho." -f $seg) -ForegroundColor Yellow
    $fim = (Get-Date).AddSeconds($seg)
    while ((Get-Date) -lt $fim) {
        try {
            if ([Console]::KeyAvailable) {
                $k = [Console]::ReadKey($true)
                if ($k.Key -eq 'S' -or $k.Key -eq 'Y') { return $true }
                if ($k.Key -eq 'N') { return $false }
            }
        } catch { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

function Ajustar-TaxaTela($dev, $hz, $antigo) {
    if (-not (Initialize-Fps)) { Show-Aviso "Nao consegui carregar o modulo de tela."; return }
    Write-Host ("  Mudando {0} de {1} Hz para {2} Hz..." -f $dev, $antigo, $hz) -ForegroundColor Cyan
    $r = -1
    try { $r = [PCToolkit.Fps]::DefinirHz([string]$dev, [int]$hz) } catch { }
    if ($r -ne 0) {
        Show-Aviso ("O Windows recusou {0} Hz (codigo {1})." -f $hz, $r)
        Write-Host "  Tente pelo painel da placa de video ou em Configuracoes > Video > Video avancado." -ForegroundColor Yellow
        Write-Host "  Confira tambem o CABO: HDMI antigo ou adaptador costumam travar em 60 Hz. Use DisplayPort ou HDMI 2.0/2.1." -ForegroundColor Yellow
        Start-Process 'ms-settings:display-advanced'
        return
    }
    if (Confirmar-Tela 15) {
        Show-Ok ("Monitor agora a {0} Hz" -f $hz)
        Write-Log ("Tela {0}: {1} -> {2} Hz" -f $dev, $antigo, $hz)
    } else {
        try { [void][PCToolkit.Fps]::DefinirHz([string]$dev, [int]$antigo) } catch { }
        Show-Aviso ("Voltei para {0} Hz." -f $antigo)
    }
}

function Menu-Tela {
    Write-Host ""
    Write-Host "=== MONITOR: TAXA DE ATUALIZACAO E LIMITE DE FPS IDEAL ===" -ForegroundColor Cyan
    $telas = @(Get-Telas)
    if ($telas.Count -eq 0) { Show-Aviso "Nao consegui ler os monitores."; Start-Process 'ms-settings:display-advanced'; return }
    $n = 1
    foreach ($t in $telas) {
        $prim = ''
        if ($t.Primario) { $prim = ' [principal]' }
        $cor = 'Green'
        if ($t.MaxHz -gt ($t.Hz + 1)) { $cor = 'Red' }
        Write-Host ("{0}) {1}{2}  {3}x{4}  ATUAL {5} Hz  |  maximo nesta resolucao: {6} Hz" -f $n, $t.Monitor, $prim, $t.W, $t.H, $t.Hz, $t.MaxHz) -ForegroundColor $cor
        Write-Host ("     ligado em: {0} ({1})" -f $t.Gpu, $t.Dev) -ForegroundColor DarkGray
        $n++
    }
    Write-Host ""
    Write-Host "Limite de FPS recomendado (configure no jogo ou no painel NVIDIA/AMD):" -ForegroundColor Cyan
    foreach ($t in $telas) {
        if ($t.MaxHz -lt 50) { continue }
        $hzRef = [math]::Max($t.Hz, $t.MaxHz)
        Write-Host ("  {0} Hz  ->  com G-Sync/FreeSync ligado: limite em {1} FPS  |  sem: {2} FPS com VSync, ou sem limite em jogo competitivo" -f $hzRef, ($hzRef - [math]::Max(3, [math]::Round($hzRef * 0.03))), $hzRef)
    }
    Write-Host "  Por que: limitar um pouco abaixo do Hz mantem o G-Sync/FreeSync ativo, sem atraso de VSync e com quadros uniformes." -ForegroundColor DarkGray
    Write-Host "  Jogos com NVIDIA Reflex / AMD Anti-Lag: ligue (ON / ON+Boost) - reduz o atraso do mouse sem perder FPS." -ForegroundColor DarkGray

    $baixos = @($telas | Where-Object { $_.MaxHz -gt ($_.Hz + 1) })
    if ($baixos.Count -gt 0) {
        Write-Host ""
        foreach ($t in $baixos) {
            if (Ask-YesNo ("{0} esta em {1} Hz mas aceita {2} Hz. Subir agora? (com confirmacao de 15 s)" -f $t.Monitor, $t.Hz, $t.MaxHz)) {
                Ajustar-TaxaTela $t.Dev $t.MaxHz $t.Hz
            }
        }
    } else {
        Write-Host ""
        Show-Ok "Todos os monitores ja estao no Hz maximo da resolucao atual."
    }
}

# ----------------------------------------------------------------
# Timer, memoria standby, topologia da CPU
# ----------------------------------------------------------------
function Set-TimerResolucao([bool]$ligar) {
    if (-not (Initialize-Fps)) { return $false }
    try { $v = [PCToolkit.Fps]::DefinirTimer($ligar); return ($v -gt 0) } catch { return $false }
}

function Limpar-Standby {
    if (-not (Initialize-Fps)) { return $false }
    try { return ([PCToolkit.Fps]::LimparStandby() -eq 0) } catch { return $false }
}

function Get-TopologiaCpu {
    $r = [pscustomobject]@{ Hibrida = $false; X3D = $false; NP = 0; NE = 0; Mascara = [int64]0; Total = 0; Descricao = '' }
    if (-not (Initialize-Fps)) { return $r }
    $raw = @()
    try { $raw = @([PCToolkit.Fps]::CpuSets()) } catch { return $r }
    if ($raw.Count -lt 8) { return $r }
    $cpus = @()
    for ($i = 0; $i + 3 -lt $raw.Count; $i += 4) { $cpus += [pscustomobject]@{ Lp = [int]$raw[$i]; Ef = [int]$raw[$i + 1]; Llc = [int]$raw[$i + 2]; Core = [int]$raw[$i + 3] } }
    $r.Total = $cpus.Count
    if ($cpus.Count -gt 64) { return $r }
    $efs = @($cpus | ForEach-Object { $_.Ef } | Sort-Object -Unique)
    $nomeCpu = ''
    $c1 = Get-CimInstance Win32_Processor | Select-Object -First 1
    if ($c1) { $nomeCpu = [string]$c1.Name }
    if ($efs.Count -ge 2) {
        $maxEf = ($efs | Measure-Object -Maximum).Maximum
        $rap = @($cpus | Where-Object { $_.Ef -eq $maxEf })
        $r.Hibrida = $true
        $r.NP = @($rap | ForEach-Object { $_.Core } | Sort-Object -Unique).Count
        $r.NE = @($cpus | Where-Object { $_.Ef -ne $maxEf } | ForEach-Object { $_.Core } | Sort-Object -Unique).Count
        foreach ($x in $rap) { $r.Mascara = $r.Mascara -bor ([int64]1 -shl $x.Lp) }
        $r.Descricao = ("CPU hibrida: {0} nucleos P (rapidos) + {1} nucleos E (economicos)" -f $r.NP, $r.NE)
    } elseif ($nomeCpu -match 'X3D') {
        $llcs = @($cpus | ForEach-Object { $_.Llc } | Sort-Object -Unique)
        if ($llcs.Count -ge 2) {
            $alvo = $llcs[0]
            $sel = @($cpus | Where-Object { $_.Llc -eq $alvo })
            $r.X3D = $true
            $r.NP = @($sel | ForEach-Object { $_.Core } | Sort-Object -Unique).Count
            foreach ($x in $sel) { $r.Mascara = $r.Mascara -bor ([int64]1 -shl $x.Lp) }
            $r.Descricao = ("AMD X3D com 2 CCDs: {0} nucleos no CCD com 3D V-Cache" -f $r.NP)
        }
    }
    return $r
}

# Processos que podem rodar com prioridade baixa enquanto voce joga (NAO inclui Discord/audio)
function Rebaixar-SegundoPlano($excluirIds) {
    $nomes = @('chrome', 'msedge', 'firefox', 'brave', 'opera', 'vivaldi', 'OneDrive', 'Dropbox', 'GoogleDriveFS', 'steamwebhelper',
        'EpicGamesLauncher', 'EpicWebHelper', 'EADesktop', 'EABackgroundService', 'upc', 'UbisoftConnect', 'GalaxyClient', 'GalaxyClientService',
        'Battle.net', 'Spotify', 'Teams', 'ms-teams', 'slack', 'Zoom', 'Skype', 'PhoneExperienceHost', 'Widgets', 'WidgetService',
        'SearchApp', 'SearchHost', 'msedgewebview2', 'AdobeCollabSync', 'CCXProcess', 'Creative Cloud', 'iCloudServices', 'MicrosoftEdgeUpdate')
    $orig = @{}
    foreach ($p in @(Get-Process -Name $nomes -ErrorAction SilentlyContinue)) {
        if ($excluirIds -and ($excluirIds -contains $p.Id)) { continue }
        try {
            $antes = [string]$p.PriorityClass
            if ($antes -eq 'Normal' -or $antes -eq 'AboveNormal' -or $antes -eq 'High') {
                $p.PriorityClass = 'BelowNormal'
                $orig[$p.Id] = $antes
            }
        } catch { }
    }
    return $orig
}

function Restaurar-Prioridades($orig) {
    if (-not $orig) { return }
    foreach ($id in @($orig.Keys)) {
        $p = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($p) { try { $p.PriorityClass = [string]$orig[$id] } catch { } }
    }
}

# ----------------------------------------------------------------
# Uso de GPU (qualquer marca) e da CPU por nucleo - via WMI (funciona em qualquer idioma)
# ----------------------------------------------------------------
function Get-AmostraGpu($pidJogo) {
    $r = [pscustomobject]@{ Uso3D = $null; UsoJogo = $null; VramMB = $null }
    try {
        $eng = @(Get-CimInstance -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction Stop)
        $porLuid = @{}
        $jogo = 0.0
        foreach ($e in $eng) {
            $n = [string]$e.Name
            if ($n -notmatch 'engtype_3D') { continue }
            $m = [regex]::Match($n, 'luid_(0x[0-9a-fA-F]+_0x[0-9a-fA-F]+)')
            $l = 'x'
            if ($m.Success) { $l = $m.Groups[1].Value }
            $v = [double]$e.UtilizationPercentage
            if (-not $porLuid.ContainsKey($l)) { $porLuid[$l] = 0.0 }
            $porLuid[$l] += $v
            if ($pidJogo -and ($n -match ('^pid_' + $pidJogo + '_'))) { $jogo += $v }
        }
        if ($porLuid.Count -gt 0) { $r.Uso3D = [int][math]::Min(100, [math]::Round((($porLuid.Values | Measure-Object -Maximum).Maximum), 0)) }
        if ($pidJogo) { $r.UsoJogo = [int][math]::Min(100, [math]::Round($jogo, 0)) }
    } catch { }
    try {
        $mem = @(Get-CimInstance -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUAdapterMemory -ErrorAction Stop)
        if ($mem.Count -gt 0) { $r.VramMB = [int][math]::Round((($mem | Measure-Object -Property DedicatedUsage -Maximum).Maximum) / 1MB, 0) }
    } catch { }
    return $r
}

function Get-NucleoMax {
    try {
        $c = @(Get-CimInstance -ClassName Win32_PerfFormattedData_Counters_ProcessorInformation -ErrorAction Stop | Where-Object { $_.Name -notmatch '_Total' })
        if ($c.Count -gt 0) { return [int](($c | Measure-Object -Property PercentProcessorTime -Maximum).Maximum) }
    } catch { }
    return $null
}

# ----------------------------------------------------------------
# PresentMon (medidor de FPS oficial da Intel, codigo aberto)
# ----------------------------------------------------------------
function Get-PresentMonPath {
    $cfg = Load-Config
    if ($cfg.PresentMonPath -and (Test-Path -LiteralPath ([string]$cfg.PresentMonPath))) { return [string]$cfg.PresentMonPath }
    if (Test-Path -LiteralPath $script:ToolsDir) {
        $f = Get-ChildItem -LiteralPath $script:ToolsDir -Filter 'PresentMon*.exe' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($f) { return $f.FullName }
    }
    $c = Get-Command 'PresentMon*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return $c.Source }
    return $null
}

function Configurar-PresentMon {
    Write-Host ""
    Write-Host "Para medir FPS eu uso o PresentMon: ferramenta GRATUITA, de codigo aberto e assinada pela Intel" -ForegroundColor Cyan
    Write-Host "(github.com/GameTechDev/PresentMon), a mesma que sites de review usam. Ela so LE os quadros que o jogo" -ForegroundColor Cyan
    Write-Host "mostra (eventos do Windows); nao injeta nada no jogo e nao mexe em anti-cheat." -ForegroundColor Cyan
    Write-Host ""
    Write-Host " 1) Baixar automaticamente do GitHub oficial (recomendado, ~1-3 MB)"
    Write-Host " 2) Ja baixei: informar o caminho do PresentMon .exe"
    Write-Host " 3) Cancelar"
    $op = Read-Host "Escolha (Enter = 1)"
    if ($op -eq '3') { return $null }
    if ($op -eq '2') {
        $c = (Read-Host "Caminho completo do PresentMon .exe").Trim().Trim('"')
        if (-not (Test-Path -LiteralPath $c -PathType Leaf)) { Show-Aviso "Arquivo nao encontrado."; return $null }
        $cfg = Load-Config
        $cfg | Add-Member -NotePropertyName PresentMonPath -NotePropertyValue $c -Force
        Save-Config $cfg
        Show-Ok "Caminho salvo."
        return $c
    }
    return (Baixar-PresentMon)
}

function Baixar-PresentMon {
    if (-not (Test-Path -LiteralPath $script:ToolsDir)) { New-Item -ItemType Directory -Path $script:ToolsDir -Force | Out-Null }
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
    Write-Host "Consultando a ultima versao no GitHub..." -ForegroundColor DarkGray
    $rel = $null
    try { $rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/GameTechDev/PresentMon/releases/latest' -Headers @{ 'User-Agent' = 'PC-Toolkit' } -UseBasicParsing -ErrorAction Stop }
    catch { Show-Aviso ("Nao consegui falar com o GitHub: " + $_.Exception.Message); Start-Process 'https://github.com/GameTechDev/PresentMon/releases'; Write-Host "Baixe o arquivo 'PresentMon-...-x64.exe' e use a opcao 2." -ForegroundColor Yellow; return $null }
    $assets = @($rel.assets)
    $asset = $assets | Where-Object { $_.name -match '^PresentMon-[\d\.]+-x64\.exe$' } | Select-Object -First 1
    if (-not $asset) { $asset = $assets | Where-Object { $_.name -match 'x64\.exe$' -and $_.name -notmatch 'arm|Capture|Service|Setup|Installer' } | Select-Object -First 1 }
    if (-not $asset) { Show-Aviso "Nao achei o executavel na release."; Start-Process 'https://github.com/GameTechDev/PresentMon/releases'; return $null }
    $dest = Join-Path $script:ToolsDir ([string]$asset.name)
    Write-Host ("Baixando {0} ({1})..." -f $asset.name, (Format-Size $asset.size)) -ForegroundColor DarkGray
    try { Invoke-WebRequest -Uri ([string]$asset.browser_download_url) -OutFile $dest -UseBasicParsing -ErrorAction Stop }
    catch { Show-Aviso ("Falha no download: " + $_.Exception.Message); return $null }
    $sig = Get-AuthenticodeSignature -LiteralPath $dest
    if ($sig.Status -ne 'Valid' -or [string]$sig.SignerCertificate.Subject -notmatch 'Intel') {
        Remove-Item -LiteralPath $dest -Force -ErrorAction SilentlyContinue
        Show-Aviso "A assinatura digital NAO e da Intel. Apaguei o arquivo por seguranca."
        return $null
    }
    Show-Ok ("PresentMon pronto (assinado pela Intel): " + $dest)
    Write-Log ("PresentMon baixado: " + $dest)
    return $dest
}

function Iniciar-PresentMon($pm, $exeNome, $csv, $seg, $estilo) {
    $d = $estilo
    $a = @(($d + 'process_name'), $exeNome, ($d + 'output_file'), ('"' + $csv + '"'), ($d + 'session_name'), 'PCToolkit', ($d + 'stop_existing_session'))
    if ($seg -gt 0) { $a += @(($d + 'timed'), [string]$seg, ($d + 'terminate_after_timed')) }
    else { $a += ($d + 'terminate_on_proc_exit') }
    try { return (Start-Process -FilePath $pm -ArgumentList $a -WindowStyle Hidden -PassThru -ErrorAction Stop) } catch { return $null }
}

function Parar-PresentMon($proc) {
    if (-not $proc) { return }
    try {
        if (-not $proc.HasExited) {
            Start-Sleep -Seconds 2
            if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        }
    } catch { }
    & logman.exe stop PCToolkit -ets 2>&1 | Out-Null
}

function Get-EstilosPresentMon($pm) {
    $cfg = Load-Config
    if ($cfg.PresentMonEstilo -eq '-') { return @('-', '--') }
    if ($cfg.PresentMonEstilo -eq '--') { return @('--', '-') }
    if ([IO.Path]::GetFileName($pm) -match 'PresentMon-?1\.|PresentMon64') { return @('-', '--') }
    return @('--', '-')
}

function Medir-FpsTimed($pm, $exeNome, $csv, $seg) {
    foreach ($estilo in @(Get-EstilosPresentMon $pm)) {
        if (Test-Path -LiteralPath $csv) { Remove-Item -LiteralPath $csv -Force -ErrorAction SilentlyContinue }
        $ini = Get-Date
        $p = Iniciar-PresentMon $pm $exeNome $csv $seg $estilo
        if (-not $p) { continue }
        try { Wait-Process -Id $p.Id -Timeout ($seg + 30) -ErrorAction SilentlyContinue } catch { }
        Parar-PresentMon $p
        $rapido = (((Get-Date) - $ini).TotalSeconds -lt 5)
        if (Test-Path -LiteralPath $csv) {
            $r = Ler-CsvPresentMon $csv
            if ($r) {
                $cfg = Load-Config
                $cfg | Add-Member -NotePropertyName PresentMonEstilo -NotePropertyValue $estilo -Force
                Save-Config $cfg
                return $r
            }
        }
        if (-not $rapido) { break }
    }
    return $null
}

function Get-Percentil($ordenado, $p) {
    if (-not $ordenado -or $ordenado.Count -eq 0) { return $null }
    $i = [int][math]::Ceiling(($p / 100.0) * $ordenado.Count) - 1
    if ($i -lt 0) { $i = 0 }
    if ($i -ge $ordenado.Count) { $i = $ordenado.Count - 1 }
    return $ordenado[$i]
}

function Ler-CsvPresentMon($csv) {
    $linhas = @()
    try { $linhas = @(Import-Csv -LiteralPath $csv -ErrorAction Stop) } catch { return $null }
    if ($linhas.Count -lt 30) { return $null }
    $cols = @($linhas[0].PSObject.Properties | ForEach-Object { $_.Name })
    $colFt = $null
    foreach ($c in @('MsBetweenPresents', 'FrameTime', 'msBetweenPresents')) { if ($cols -contains $c) { $colFt = $c; break } }
    if (-not $colFt) { return $null }
    $colGpu = $null
    foreach ($c in @('GPUBusy', 'MsGPUActive', 'msGPUActive')) { if ($cols -contains $c) { $colGpu = $c; break } }
    $colCpu = $null
    if ($cols -contains 'CPUBusy') { $colCpu = 'CPUBusy' }
    $app = $null
    if ($cols -contains 'Application') { $app = ($linhas | Group-Object -Property Application | Sort-Object Count -Descending | Select-Object -First 1).Name }
    $inv = [Globalization.CultureInfo]::InvariantCulture
    $est = [Globalization.NumberStyles]::Float
    $ft = New-Object 'System.Collections.Generic.List[double]'
    $somaGpu = 0.0; $nGpu = 0; $somaCpu = 0.0; $nCpu = 0; $somaFtG = 0.0; $somaFtC = 0.0
    foreach ($r in $linhas) {
        if ($app -and ([string]$r.Application -ne $app)) { continue }
        $v = 0.0
        if (-not [double]::TryParse([string]$r.$colFt, $est, $inv, [ref]$v)) { continue }
        if ($v -le 0 -or $v -ge 2000) { continue }
        $ft.Add($v)
        if ($colGpu) { $g = 0.0; if ([double]::TryParse([string]$r.$colGpu, $est, $inv, [ref]$g) -and $g -ge 0) { $somaGpu += $g; $somaFtG += $v; $nGpu++ } }
        if ($colCpu) { $q = 0.0; if ([double]::TryParse([string]$r.$colCpu, $est, $inv, [ref]$q) -and $q -ge 0) { $somaCpu += $q; $somaFtC += $v; $nCpu++ } }
    }
    if ($ft.Count -lt 30) { return $null }
    $arr = $ft.ToArray()
    $soma = 0.0
    foreach ($x in $arr) { $soma += $x }
    $media = $soma / $arr.Count
    $ord = [double[]]$arr.Clone()
    [Array]::Sort($ord)
    $mediana = Get-Percentil $ord 50
    $p99 = Get-Percentil $ord 99
    $p999 = Get-Percentil $ord 99.9
    $eng = 0
    foreach ($x in $arr) { if ($x -gt (2.5 * $mediana) -and $x -gt 20) { $eng++ } }
    $gpuPct = $null
    if ($nGpu -gt 20 -and $somaFtG -gt 0) { $gpuPct = [int][math]::Min(100, [math]::Round(($somaGpu / $somaFtG) * 100, 0)) }
    $cpuPct = $null
    if ($nCpu -gt 20 -and $somaFtC -gt 0) { $cpuPct = [int][math]::Min(100, [math]::Round(($somaCpu / $somaFtC) * 100, 0)) }
    return [pscustomobject]@{
        App = $app; Quadros = $arr.Count; Seg = [int][math]::Round($soma / 1000, 0)
        Fps = [math]::Round(1000 / $media, 1); Low1 = [math]::Round(1000 / $p99, 1); Low01 = [math]::Round(1000 / $p999, 1)
        FtMedio = [math]::Round($media, 2); Engasgos = $eng; GpuPct = $gpuPct; CpuPct = $cpuPct
    }
}

function Mostrar-ResultadoFps($r) {
    if (-not $r) { return }
    Write-Host ""
    Write-Host ("=== FPS: {0} ===" -f $r.App) -ForegroundColor Green
    Write-Host ("  FPS medio:  {0}" -f $r.Fps) -ForegroundColor Green
    Write-Host ("  1% low:     {0}   (os 1% piores quadros - e o que voce SENTE como travadinha)" -f $r.Low1)
    Write-Host ("  0.1% low:   {0}" -f $r.Low01)
    Write-Host ("  Engasgos:   {0}   (quadros que demoraram 2.5x mais que o normal)" -f $r.Engasgos)
    Write-Host ("  Amostra:    {0} quadros em {1} s  |  tempo medio por quadro: {2} ms" -f $r.Quadros, $r.Seg, $r.FtMedio) -ForegroundColor DarkGray
    $est = 0
    if ($r.Fps -gt 0) { $est = [math]::Round(($r.Low1 / $r.Fps) * 100, 0) }
    $cor = 'Green'; $txt = 'estavel'
    if ($est -lt 50) { $cor = 'Red'; $txt = 'INSTAVEL (muitos engasgos)' } elseif ($est -lt 70) { $cor = 'Yellow'; $txt = 'regular' }
    Write-Host ("  Estabilidade (1% low / medio): {0}% -> {1}" -f $est, $txt) -ForegroundColor $cor
    if ($est -lt 70) {
        Write-Host "  Para menos engasgos: NAO apague cache de shaders a toda hora, ative XMP/EXPO, feche apps de fundo," -ForegroundColor DarkGray
        Write-Host "  use o pacote TURBO do Modo Jogo e limite o FPS um pouco abaixo do que o PC alcanca." -ForegroundColor DarkGray
    }
}

function Get-Gargalo($amGpu, $amCore, $fps) {
    if ($fps -and $null -ne $fps.GpuPct) {
        if ($fps.GpuPct -ge 85) { return 'GPU' }
        if ($null -ne $fps.CpuPct -and $fps.CpuPct -lt 65 -and $fps.GpuPct -lt 65) { return 'LIMITE' }
        if ($fps.GpuPct -lt 70) { return 'CPU' }
        return 'EQUILIBRADO'
    }
    $g = @($amGpu | Where-Object { $null -ne $_ })
    $c = @($amCore | Where-Object { $null -ne $_ })
    if ($g.Count -lt 2) { return $null }
    $mg = ($g | Measure-Object -Average).Average
    $mc = 0
    if ($c.Count -gt 0) { $mc = ($c | Measure-Object -Average).Average }
    if ($mg -ge 90) { return 'GPU' }
    if ($mg -lt 75 -and $mc -ge 85) { return 'CPU' }
    if ($mg -lt 60 -and $mc -lt 70) { return 'LIMITE' }
    return 'EQUILIBRADO'
}

function Mostrar-Gargalo($g) {
    if (-not $g) { return }
    Write-Host ""
    switch ($g) {
        'GPU' {
            Write-Host "GARGALO: PLACA DE VIDEO (ela esta no limite - a CPU da conta)." -ForegroundColor Yellow
            Write-Host "  Mais FPS: baixe a resolucao ou a escala, use DLSS/FSR/XeSS em Qualidade/Equilibrado," -ForegroundColor DarkGray
            Write-Host "  reduza sombras, reflexos, volumetricos e ray tracing (FPS > Otimizar graficos dos jogos)." -ForegroundColor DarkGray
        }
        'CPU' {
            Write-Host "GARGALO: CPU (a placa de video fica esperando)." -ForegroundColor Yellow
            Write-Host "  Mais FPS: pacote TURBO do Modo Jogo, plano MAXIMO FPS, XMP/EXPO ligado na BIOS, feche apps de fundo," -ForegroundColor DarkGray
            Write-Host "  reduza distancia de visao, multidao/populacao e fisica. Subir a resolucao quase nao custa FPS aqui." -ForegroundColor DarkGray
        }
        'LIMITE' {
            Write-Host "Nem CPU nem placa de video estao no limite: o FPS esta PRESO por limitador, VSync ou pelo proprio jogo." -ForegroundColor Cyan
            Write-Host "  Confira o limite de FPS/VSync no jogo e no painel da NVIDIA/AMD (isso pode ser intencional e e bom para temperatura)." -ForegroundColor DarkGray
        }
        default { Write-Host "CPU e placa de video bem equilibradas nesta sessao." -ForegroundColor Green }
    }
}

function Benchmark-Fps {
    Write-Host ""
    Write-Host "=== MEDIR FPS / BENCHMARK ANTES x DEPOIS ===" -ForegroundColor Green
    Write-Host "Mede o FPS REAL de qualquer jogo DirectX/Vulkan/OpenGL: medio, 1% low, 0.1% low e engasgos."
    Write-Host "Como fazer um teste justo: 1) abra o jogo num lugar que voce joga sempre  2) meca  3) otimize" -ForegroundColor DarkGray
    Write-Host "4) meca de novo no MESMO lugar, mesmo tempo. O toolkit compara sozinho com a medicao anterior." -ForegroundColor DarkGray
    $pm = Get-PresentMonPath
    if (-not $pm) { $pm = Configurar-PresentMon; if (-not $pm) { return } }
    Write-Host ""
    Write-Host "Abra o jogo (pode deixar no menu/partida) e escolha ele na lista:" -ForegroundColor Cyan
    $proc = Selecionar-ProcessoAberto
    if (-not $proc) { Write-Host "Abra o jogo primeiro e tente de novo."; return }
    $seg = Read-Host "Duracao da medicao em segundos (Enter = 60; 20 a 600)"
    if ($seg -notmatch '^\d+$') { $seg = 60 }
    $seg = [int]$seg
    if ($seg -lt 20) { $seg = 20 }
    if ($seg -gt 600) { $seg = 600 }
    $rot = (Read-Host "Nome desta medicao (ex: antes, depois, perfil frio). Enter = sem nome").Trim()
    Write-Host ""
    Write-Host ("Volte para o jogo AGORA e jogue normalmente. Comeca em 5 s e dura {0} s. Um bip avisa no fim." -f $seg) -ForegroundColor Yellow
    for ($i = 5; $i -ge 1; $i--) { Write-Host -NoNewline ("{0}... " -f $i); Start-Sleep -Seconds 1 }
    Write-Host "medindo"
    $csv = Join-Path $env:TEMP ("pctk-bench-" + (Get-Date -Format 'yyyyMMddHHmmss') + '.csv')
    $res = Medir-FpsTimed $pm ($proc.ProcessName + '.exe') $csv $seg
    try { [Console]::Beep(880, 250); [Console]::Beep(1175, 250) } catch { }
    if (-not $res) {
        Show-Aviso "Nao consegui medir. Causas comuns: o jogo ficou minimizado (nao desenhou quadros), ou o anti-cheat bloqueou a leitura."
        return
    }
    Remove-Item -LiteralPath $csv -Force -ErrorAction SilentlyContinue
    Mostrar-ResultadoFps $res
    $garg = Get-Gargalo $null $null $res
    if ($garg) { Mostrar-Gargalo $garg }

    $hist = @(Load-JsonLista $script:BenchFile)
    $ant = @($hist | Where-Object { [string]$_.App -eq [string]$res.App }) | Select-Object -Last 1
    if ($ant) {
        $dF = 0; $dL = 0
        if ([double]$ant.Fps -gt 0) { $dF = [math]::Round((([double]$res.Fps - [double]$ant.Fps) / [double]$ant.Fps) * 100, 1) }
        if ([double]$ant.Low1 -gt 0) { $dL = [math]::Round((([double]$res.Low1 - [double]$ant.Low1) / [double]$ant.Low1) * 100, 1) }
        $corC = 'Green'
        if ($dF -lt -2) { $corC = 'Red' } elseif ($dF -lt 2) { $corC = 'Gray' }
        Write-Host ""
        Write-Host ("COMPARADO COM A MEDICAO ANTERIOR ({0} {1}):" -f $ant.Data, $ant.Rotulo) -ForegroundColor Cyan
        Write-Host ("  FPS medio: {0} -> {1}  ({2:+0.0;-0.0;0}%)" -f $ant.Fps, $res.Fps, $dF) -ForegroundColor $corC
        Write-Host ("  1% low:    {0} -> {1}  ({2:+0.0;-0.0;0}%)" -f $ant.Low1, $res.Low1, $dL) -ForegroundColor $corC
        Write-Host "  (Diferencas de ate ~3% sao variacao normal entre medicoes.)" -ForegroundColor DarkGray
    }
    $o = [pscustomobject]@{ Data = (Get-Date -Format 'yyyy-MM-dd HH:mm'); App = $res.App; Rotulo = $rot; Seg = $res.Seg; Fps = $res.Fps; Low1 = $res.Low1; Low01 = $res.Low01; Engasgos = $res.Engasgos; Gargalo = $garg }
    $hist += $o
    Save-JsonLista $script:BenchFile $hist 300
    Write-Log ("Benchmark {0}: {1} fps / 1% {2}" -f $res.App, $res.Fps, $res.Low1)
}

function Registrar-SessaoHistorico($o) {
    $l = @(Load-JsonLista $script:HistFile)
    $l += $o
    Save-JsonLista $script:HistFile $l 200
}

function Mostrar-Historico {
    Write-Host ""
    Write-Host "=== HISTORICO ===" -ForegroundColor Cyan
    $b = @(Load-JsonLista $script:BenchFile)
    Write-Host ""
    Write-Host "Ultimas medicoes de FPS (benchmark):" -ForegroundColor Yellow
    if ($b.Count -eq 0) { Write-Host "  nenhuma ainda (FPS > Medir FPS)" -ForegroundColor DarkGray }
    foreach ($x in @($b | Select-Object -Last 15)) {
        Write-Host ("  {0}  {1,-22} {2,-12} FPS {3,6}  1% {4,6}  0.1% {5,6}  engasgos {6}" -f $x.Data, ([string]$x.App), ([string]$x.Rotulo), $x.Fps, $x.Low1, $x.Low01, $x.Engasgos)
    }
    $s = @(Load-JsonLista $script:HistFile)
    Write-Host ""
    Write-Host "Ultimas sessoes do Modo Jogo:" -ForegroundColor Yellow
    if ($s.Count -eq 0) { Write-Host "  nenhuma ainda" -ForegroundColor DarkGray }
    foreach ($x in @($s | Select-Object -Last 15)) {
        $f = ''
        if ($x.Fps) { $f = ("FPS {0} / 1% {1}" -f $x.Fps, $x.Low1) }
        Write-Host ("  {0}  {1,-22} {2,4} min  perfil {3,-10} CPU pico {4,3} C  {5}  gargalo: {6}" -f $x.Data, ([string]$x.Jogo), $x.Min, $x.Perfil, $x.PicoCpu, $f, $x.Gargalo)
    }
}

# ----------------------------------------------------------------
# Pacote de ajustes de jogo do Windows (usado pelo Otimizar e pelo Raio-X)
# ----------------------------------------------------------------
function Aplicar-TweaksJogo {
    $mm = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile'
    $tweaks = @(
        @{ P = 'HKCU:\Software\Microsoft\GameBar'; N = 'AutoGameModeEnabled'; V = 1; T = 'DWord' },
        @{ P = 'HKCU:\Software\Microsoft\GameBar'; N = 'AllowAutoGameMode'; V = 1; T = 'DWord' },
        @{ P = 'HKCU:\Software\Microsoft\GameBar'; N = 'UseNexusForGameBarEnabled'; V = 0; T = 'DWord' },
        @{ P = 'HKCU:\System\GameConfigStore'; N = 'GameDVR_Enabled'; V = 0; T = 'DWord' },
        @{ P = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR'; N = 'AppCaptureEnabled'; V = 0; T = 'DWord' },
        @{ P = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\GameDVR'; N = 'AllowGameDVR'; V = 0; T = 'DWord' },
        @{ P = $mm; N = 'SystemResponsiveness'; V = 0; T = 'DWord' },
        @{ P = $mm; N = 'NetworkThrottlingIndex'; V = -1; T = 'DWord' },
        @{ P = ($mm + '\Tasks\Games'); N = 'GPU Priority'; V = 8; T = 'DWord' },
        @{ P = ($mm + '\Tasks\Games'); N = 'Priority'; V = 6; T = 'DWord' },
        @{ P = ($mm + '\Tasks\Games'); N = 'Scheduling Category'; V = 'High'; T = 'String' },
        @{ P = ($mm + '\Tasks\Games'); N = 'SFIO Priority'; V = 'High'; T = 'String' },
        @{ P = 'HKLM:\SYSTEM\CurrentControlSet\Control\PriorityControl'; N = 'Win32PrioritySeparation'; V = 38; T = 'DWord' },
        @{ P = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize'; N = 'EnableTransparency'; V = 0; T = 'DWord' },
        @{ P = 'HKCU:\Control Panel\Desktop\WindowMetrics'; N = 'MinAnimate'; V = '0'; T = 'String' },
        @{ P = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced'; N = 'TaskbarAnimations'; V = 0; T = 'DWord' },
        @{ P = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\BackgroundAccessApplications'; N = 'GlobalUserDisabled'; V = 1; T = 'DWord' },
        @{ P = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeliveryOptimization'; N = 'DODownloadMode'; V = 0; T = 'DWord' }
    )
    foreach ($t in $tweaks) { Set-RegBackup $t.P $t.N $t.V $t.T }
    $dxKey = 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'
    $dx = Get-RegString $dxKey 'DirectXUserGlobalSettings'
    $dx = Merge-DxSetting $dx 'SwapEffectUpgradeEnable' '1'
    $dx = Merge-DxSetting $dx 'VRROptimizeEnable' '1'
    Set-RegBackup $dxKey 'DirectXUserGlobalSettings' $dx 'String'
    Show-Ok "Modo Jogo, Game DVR, MMCSS, prioridade, animacoes, apps de fundo e DirectX (janela otimizada + VRR) ajustados"
}

# ----------------------------------------------------------------
# RAIO-X DE FPS
# ----------------------------------------------------------------
function New-Checagem($grupo, $nome, $estado, $detalhe, $fix, $arg, $peso, $auto) {
    return [pscustomobject]@{ Grupo = $grupo; Nome = $nome; Estado = $estado; Detalhe = $detalhe; Fix = $fix; Arg = $arg; Peso = [int]$peso; Auto = [bool]$auto }
}

function Get-ChecagensFps {
    $L = New-Object System.Collections.ArrayList
    $os = Get-CimInstance Win32_OperatingSystem
    $build = 0
    try { $build = [int]$os.BuildNumber } catch { }
    $bat = @(Get-CimInstance Win32_Battery)
    $notebook = ($bat.Count -gt 0)
    $vids = @(Get-CimInstance Win32_VideoController)
    $gpus = @($vids | Where-Object { $_.Name -notmatch 'Basic Display|Basic Render|Remote|Virtual|Parsec|Meta Virtual|Citrix|Mirage|Indirect' })

    # ---------- TELA ----------
    Show-Info "  [1/8] monitores..."
    foreach ($t in @(Get-Telas)) {
        $nm = $t.Monitor
        if (-not $nm) { $nm = $t.Dev }
        if ($t.MaxHz -gt ($t.Hz + 1)) {
            [void]$L.Add((New-Checagem 'Tela' ("{0}: rodando a {1} Hz, mas aceita {2} Hz" -f $nm, $t.Hz, $t.MaxHz) 'PROBLEMA' 'Voce ve menos quadros do que o monitor consegue mostrar. E o erro mais comum (e a correcao mais facil) em PC gamer.' { param($a) Ajustar-TaxaTela $a.Dev $a.MaxHz $a.Hz } $t 6 $true))
        } else {
            [void]$L.Add((New-Checagem 'Tela' ("{0}: {1} Hz (maximo da resolucao atual)" -f $nm, $t.Hz) 'OK' '' $null $null 6 $false))
        }
    }

    # ---------- PLACA DE VIDEO ----------
    Show-Info "  [2/8] placa de video e driver..."
    foreach ($v in $vids) {
        if ($v.Name -match 'Basic Display|Basic Render') {
            [void]$L.Add((New-Checagem 'Video' 'Placa de video SEM driver (Microsoft Basic Display)' 'PROBLEMA' 'Sem o driver do fabricante o FPS fica minusculo e varios jogos nem abrem.' { param($a) Abrir-SiteDriver $a } ([string]$v.Name) 8 $false))
        }
    }
    foreach ($v in $gpus) {
        if ($v.DriverDate -and $v.DriverDate -lt (Get-Date).AddDays(-300)) {
            [void]$L.Add((New-Checagem 'Video' ("Driver de {0} e de {1:yyyy-MM-dd}" -f $v.Name, $v.DriverDate) 'ATENCAO' 'Drivers novos trazem otimizacoes especificas para jogos recentes (as vezes +10-20% de FPS) e corrigem engasgos.' { param($a) Abrir-SiteDriver $a } ([string]$v.Name) 3 $false))
        } else {
            [void]$L.Add((New-Checagem 'Video' ("Driver de {0} recente" -f $v.Name) 'OK' '' $null $null 3 $false))
        }
    }
    $rxDed = 'NVIDIA|GeForce|RTX|GTX|Quadro|Radeon RX|Radeon Pro|Arc\(TM\) [AB]\d|Arc [AB]\d'
    $dedic = @($gpus | Where-Object { $_.Name -match $rxDed }).Count -gt 0
    $integ = @($gpus | Where-Object { $_.Name -notmatch $rxDed -and $_.Name -match 'Intel|AMD Radeon\(TM\)|Radeon Vega|Radeon Graphics' }).Count -gt 0
    if ($gpus.Count -ge 2 -and $integ -and $dedic) {
        $prefs = 0
        $k = Get-Item -LiteralPath 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences' -ErrorAction SilentlyContinue
        if ($k) { foreach ($nmV in $k.GetValueNames()) { if ([string]$k.GetValue($nmV) -match 'GpuPreference=2') { $prefs++ } } }
        if ($prefs -eq 0) {
            [void]$L.Add((New-Checagem 'Video' 'PC com 2 placas de video e nenhum jogo forcado na DEDICADA' 'ATENCAO' 'Se o jogo abrir na integrada o FPS cai pela metade ou mais. Use Ajustes por jogo (ou o Modo Jogo) para forcar a dedicada.' { Ajustes-PorJogo } $null 4 $false))
        } else {
            [void]$L.Add((New-Checagem 'Video' ("{0} programa(s) forcado(s) na placa dedicada" -f $prefs) 'OK' '' $null $null 4 $false))
        }
    }
    $nv = Get-NvidiaSmiPath
    if ($nv) {
        $lin = (& $nv --query-gpu=pcie.link.width.current,pcie.link.width.max --format=csv,noheader,nounits 2>$null | Select-Object -First 1)
        $pp = @(([string]$lin) -split ',\s*')
        if ($pp.Count -ge 2 -and $pp[0] -match '^\d+$' -and $pp[1] -match '^\d+$') {
            if ([int]$pp[0] -lt [int]$pp[1]) {
                [void]$L.Add((New-Checagem 'Video' ("Placa de video rodando em PCIe x{0} (aceita x{1})" -f $pp[0], $pp[1]) 'ATENCAO' 'Placa mal encaixada, no slot errado (o de baixo) ou com riser ruim. Desligue o PC e coloque a placa no slot PCIe x16 de cima, bem encaixada.' $null $null 3 $false))
            } else {
                [void]$L.Add((New-Checagem 'Video' ("Placa de video em PCIe x{0} (completo)" -f $pp[0]) 'OK' '' $null $null 3 $false))
            }
        }
    }
    $gd = 'HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers'
    $hags = Get-RegDword $gd 'HwSchMode'
    $moderna = @($gpus | Where-Object { $_.Name -match 'RTX|GTX 16|Radeon RX (5[5-9]|[6-9])\d{2}|Arc' }).Count -gt 0
    if ($null -ne $hags -and $moderna) {
        if ($hags -ne 2) {
            [void]$L.Add((New-Checagem 'Video' 'Agendamento de GPU por hardware (HAGS) desligado' 'ATENCAO' 'Em placas modernas reduz a latencia e e obrigatorio para Frame Generation (DLSS 3/FSR 3). Vale apos reiniciar.' { Set-RegBackup 'HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers' 'HwSchMode' 2 'DWord'; Show-Ok 'HAGS sera ligado no proximo reinicio' } $null 2 $true))
        } else {
            [void]$L.Add((New-Checagem 'Video' 'HAGS ligado' 'OK' '' $null $null 2 $false))
        }
    }

    # ---------- MEMORIA ----------
    Show-Info "  [3/8] memoria RAM..."
    $mods = @(Get-CimInstance Win32_PhysicalMemory)
    if ($mods.Count -gt 0) {
        $totalGB = [math]::Round((($mods | Measure-Object -Property Capacity -Sum).Sum) / 1GB, 0)
        if ($mods.Count -eq 1) {
            [void]$L.Add((New-Checagem 'RAM' 'RAM em canal unico (1 pente so)' 'ATENCAO' 'Com 2 pentes iguais (dual channel) o FPS sobe 10-30% em muitos jogos, e MUITO mais com video integrada. E upgrade de hardware: nao da para corrigir por software.' $null $null 4 $false))
        } else {
            [void]$L.Add((New-Checagem 'RAM' ("{0} pentes de RAM (dual channel provavel)" -f $mods.Count) 'OK' '' $null $null 4 $false))
        }
        if ($totalGB -lt 8) { [void]$L.Add((New-Checagem 'RAM' ("So {0} GB de RAM" -f $totalGB) 'PROBLEMA' 'Jogos atuais pedem 16 GB. Enquanto isso, use o Modo Jogo com o pacote TURBO e feche o navegador.' $null $null 3 $false)) }
        elseif ($totalGB -lt 16) { [void]$L.Add((New-Checagem 'RAM' ("{0} GB de RAM" -f $totalGB) 'ATENCAO' 'Funciona, mas jogos novos ja pedem 16 GB. O pacote TURBO do Modo Jogo ajuda a evitar engasgos.' $null $null 3 $false)) }
        else { [void]$L.Add((New-Checagem 'RAM' ("{0} GB de RAM" -f $totalGB) 'OK' '' $null $null 3 $false)) }
        $m1 = $mods | Select-Object -First 1
        $cfgMhz = 0; $tipoMem = 0
        try { $cfgMhz = [int]$m1.ConfiguredClockSpeed } catch { }
        try { $tipoMem = [int]$m1.SMBIOSMemoryType } catch { }
        $lim = 0
        $ddr = ''
        if ($tipoMem -eq 26) { $lim = 2666; $ddr = 'DDR4' } elseif ($tipoMem -eq 34) { $lim = 4800; $ddr = 'DDR5' }
        if ($lim -gt 0 -and $cfgMhz -gt 0) {
            if ($cfgMhz -le $lim -and -not $notebook) {
                [void]$L.Add((New-Checagem 'RAM' ("{0} a {1} MHz: provavelmente XMP/EXPO DESLIGADO" -f $ddr, $cfgMhz) 'ATENCAO' 'Memorias gamer sao vendidas para 3200-3600 (DDR4) ou 6000+ (DDR5), mas so rodam assim com o perfil XMP (Intel) / EXPO ou DOCP (AMD) ligado na BIOS. Confira a etiqueta do pente. Ganho tipico: 5-15% de FPS e 1% low bem melhor.' $null $null 3 $false))
            } else {
                [void]$L.Add((New-Checagem 'RAM' ("{0} a {1} MHz" -f $ddr, $cfgMhz) 'OK' '' $null $null 3 $false))
            }
        }
    }

    # ---------- ENERGIA ----------
    Show-Info "  [4/8] energia..."
    if ($notebook -and @($bat | Where-Object { $_.BatteryStatus -eq 1 }).Count -gt 0) {
        [void]$L.Add((New-Checagem 'Energia' 'Notebook na BATERIA agora' 'PROBLEMA' 'Na bateria a CPU e a placa de video rodam limitadas (o FPS pode cair pela metade). Jogue na tomada e use o modo Desempenho/Turbo do software do fabricante.' $null $null 5 $false))
    }
    $ativo = Get-EsquemaAtivo
    $mx = Get-ValorEsquema $script:SetMax
    $bo = Get-ValorEsquema $script:SetBoost
    $estE = Load-EnergiaEstado
    $intencional = ($estE -and ([string]$estE.Perfil -match '^(Frio|Silencioso|Custom)$'))
    $fixEnergia = { param($a) if (Aplicar-PerfilTermico 'Maximo' 100) { Show-Ok 'Plano de energia MAXIMO FPS aplicado' } }
    if ($ativo -eq 'a1841308-3541-4fab-bc81-f71556f20b4a') {
        [void]$L.Add((New-Checagem 'Energia' 'Plano de energia em ECONOMIA' 'PROBLEMA' 'A CPU fica presa em clock baixo. Corrigir aplica o plano MAXIMO FPS do toolkit (reversivel).' $fixEnergia $null 5 $true))
    } elseif ($intencional) {
        [void]$L.Add((New-Checagem 'Energia' ("Perfil de temperatura '{0}' escolhido por voce" -f $estE.Perfil) 'INFO' 'Ele troca um pouco de FPS por menos calor. Para FPS maximo use o perfil MAXIMO FPS.' $null $null 0 $false))
    } elseif (($mx -and $mx.AC -lt 100) -or ($bo -and $bo.AC -eq 0)) {
        [void]$L.Add((New-Checagem 'Energia' 'Plano de energia limitando a CPU (limite < 100% ou turbo desligado)' 'PROBLEMA' 'A CPU nao chega no clock maximo nos jogos. Corrigir aplica o plano MAXIMO FPS (reversivel).' $fixEnergia $null 5 $true))
    } else {
        [void]$L.Add((New-Checagem 'Energia' 'Plano de energia libera o turbo da CPU' 'OK' '' $null $null 5 $false))
    }

    # ---------- WINDOWS ----------
    Show-Info "  [5/8] configuracoes do Windows..."
    $gm = Get-RegDword 'HKCU:\Software\Microsoft\GameBar' 'AutoGameModeEnabled'
    if ($gm -eq 0) {
        [void]$L.Add((New-Checagem 'Windows' 'Modo de Jogo do Windows DESLIGADO' 'PROBLEMA' 'O Modo de Jogo segura o Windows Update e da prioridade ao jogo.' { Set-RegBackup 'HKCU:\Software\Microsoft\GameBar' 'AutoGameModeEnabled' 1 'DWord'; Set-RegBackup 'HKCU:\Software\Microsoft\GameBar' 'AllowAutoGameMode' 1 'DWord'; Show-Ok 'Modo de Jogo ligado' } $null 2 $true))
    } else { [void]$L.Add((New-Checagem 'Windows' 'Modo de Jogo do Windows ligado' 'OK' '' $null $null 2 $false)) }
    $dvr = Get-RegDword 'HKCU:\System\GameConfigStore' 'GameDVR_Enabled'
    $cap = Get-RegDword 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' 'AppCaptureEnabled'
    if ($dvr -ne 0 -or $cap -eq 1) {
        [void]$L.Add((New-Checagem 'Windows' 'Gravacao em segundo plano (Game DVR) ligada' 'PROBLEMA' 'O Windows fica gravando os ultimos minutos do jogo o tempo todo: custa FPS e causa engasgos.' { Set-RegBackup 'HKCU:\System\GameConfigStore' 'GameDVR_Enabled' 0 'DWord'; Set-RegBackup 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' 'AppCaptureEnabled' 0 'DWord'; Set-RegBackup 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\GameDVR' 'AllowGameDVR' 0 'DWord'; Show-Ok 'Game DVR desligado' } $null 3 $true))
    } else { [void]$L.Add((New-Checagem 'Windows' 'Game DVR desligado' 'OK' '' $null $null 3 $false)) }
    $mm = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile'
    $sr = Get-RegDword $mm 'SystemResponsiveness'
    $gp = Get-RegDword ($mm + '\Tasks\Games') 'GPU Priority'
    $ps = Get-RegDword 'HKLM:\SYSTEM\CurrentControlSet\Control\PriorityControl' 'Win32PrioritySeparation'
    if ($sr -ne 0 -or $gp -ne 8 -or $ps -ne 38) {
        [void]$L.Add((New-Checagem 'Windows' 'Prioridade do Windows para jogos (MMCSS / primeiro plano) no padrao' 'ATENCAO' 'Aplica o pacote de ajustes de jogo do toolkit: MMCSS, prioridade de primeiro plano, animacoes, apps de fundo e DirectX.' { Aplicar-TweaksJogo } $null 2 $true))
    } else { [void]$L.Add((New-Checagem 'Windows' 'Prioridade para jogos (MMCSS) otimizada' 'OK' '' $null $null 2 $false)) }
    if ($build -ge 22000) {
        $gtr = Get-RegDword 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\kernel' 'GlobalTimerResolutionRequests'
        if ($gtr -ne 1) {
            [void]$L.Add((New-Checagem 'Windows' 'Timer de alta resolucao global desligado (Windows 11)' 'ATENCAO' 'Permite que o pacote TURBO do Modo Jogo deixe o timer em 0.5 ms tambem para o jogo: quadros mais uniformes quando o FPS e limitado. Vale apos reiniciar.' { Set-RegBackup 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\kernel' 'GlobalTimerResolutionRequests' 1 'DWord'; Show-Ok 'Timer global liberado (reinicie)' } $null 1 $true))
        } else { [void]$L.Add((New-Checagem 'Windows' 'Timer de alta resolucao global liberado' 'OK' '' $null $null 1 $false)) }
    }
    $dg = Get-CimInstance -Namespace 'root\Microsoft\Windows\DeviceGuard' -ClassName Win32_DeviceGuard -ErrorAction SilentlyContinue
    if ($dg -and (@($dg.SecurityServicesRunning) -contains 2)) {
        [void]$L.Add((New-Checagem 'Windows' 'Integridade de memoria (HVCI/VBS) ligada' 'ATENCAO' 'Protege o kernel contra malware, mas pode custar 5-10% de FPS (mais em CPUs antigas). OPCIONAL: so desligue se aceitar menos seguranca. Nunca entra no "corrigir tudo".' { Write-Host '  Isso REDUZ a seguranca do Windows contra malware de kernel.' -ForegroundColor Yellow; if (Ask-YesNo '  Desativar a Integridade de memoria mesmo assim?') { Set-RegBackup 'HKLM:\SYSTEM\CurrentControlSet\Control\DeviceGuard\Scenarios\HypervisorEnforcedCodeIntegrity' 'Enabled' 0 'DWord'; Show-Ok 'Sera desativada no proximo reinicio' } } $null 1 $false))
    }
    $cs = Get-CimInstance Win32_ComputerSystem
    if ($cs -and -not $cs.AutomaticManagedPagefile) {
        $pf = @(Get-CimInstance Win32_PageFileUsage)
        $totPf = 0
        foreach ($x in $pf) { $totPf += [int]$x.AllocatedBaseSize }
        $ramMB = [math]::Round($cs.TotalPhysicalMemory / 1MB, 0)
        if ($pf.Count -eq 0 -or $totPf -lt [math]::Min($ramMB, 8192)) {
            [void]$L.Add((New-Checagem 'Windows' 'Memoria virtual desativada ou pequena demais' 'PROBLEMA' 'Jogos modernos fecham sozinhos ou travam por falta de memoria. Corrigir volta ao automatico do Windows.' { Verificar-Paginacao -Corrigir $true } $null 3 $true))
        } else { [void]$L.Add((New-Checagem 'Windows' 'Memoria virtual com tamanho adequado' 'OK' '' $null $null 3 $false)) }
    } else { [void]$L.Add((New-Checagem 'Windows' 'Memoria virtual automatica' 'OK' '' $null $null 3 $false)) }
    $tr = Get-RegDword 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize' 'EnableTransparency'
    if ($tr -ne 0) {
        [void]$L.Add((New-Checagem 'Windows' 'Transparencia e animacoes do Windows ligadas' 'ATENCAO' 'Pesam em PCs mais fracos e em video integrada. Corrigir aplica o estilo visual recomendado para o seu hardware.' { $hw = Get-PerfilHardware; if (Aplicar-Aparencia $hw.Estilo) { Show-Ok ('Efeitos visuais: ' + (Get-NomeEstilo $hw.Estilo)) } } $null 1 $true))
    } else { [void]$L.Add((New-Checagem 'Windows' 'Efeitos visuais leves' 'OK' '' $null $null 1 $false)) }
    $ms = Get-RegString 'HKCU:\Control Panel\Mouse' 'MouseSpeed'
    if ($ms -and $ms -ne '0') {
        [void]$L.Add((New-Checagem 'Windows' 'Aceleracao do mouse ligada ("aprimorar precisao")' 'ATENCAO' 'Deixa a mira inconsistente: o mesmo movimento da a mao gira distancias diferentes conforme a velocidade.' { Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseSpeed' '0' 'String'; Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseThreshold1' '0' 'String'; Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseThreshold2' '0' 'String'; Show-Ok 'Aceleracao do mouse desligada (vale apos sair e entrar na conta)' } $null 1 $true))
    } else { [void]$L.Add((New-Checagem 'Windows' 'Mouse sem aceleracao' 'OK' '' $null $null 1 $false)) }

    # ---------- DISCO E INICIALIZACAO ----------
    Show-Info "  [6/8] disco e programas de fundo..."
    $sd = Get-CimInstance Win32_LogicalDisk -Filter ("DeviceID='" + $env:SystemDrive + "'")
    if ($sd -and $sd.Size) {
        $pct = [math]::Round(($sd.FreeSpace / $sd.Size) * 100, 0)
        if ($pct -lt 12) { [void]$L.Add((New-Checagem 'Disco' ("Disco do Windows com so {0}% livre" -f $pct) 'PROBLEMA' 'Com o disco quase cheio o Windows e os jogos travam (cache de shaders, memoria virtual, atualizacoes).' { Limpar-PC } $null 2 $false)) }
        else { [void]$L.Add((New-Checagem 'Disco' ("Disco do Windows com {0}% livre" -f $pct) 'OK' '' $null $null 2 $false)) }
    }
    $ini = @(Get-ItensInicializacao)
    $ativos = 0
    foreach ($i in $ini) { if (Test-InicialAtivo $i.Apr $i.Nome) { $ativos++ } }
    if ($ativos -gt 10) { [void]$L.Add((New-Checagem 'Disco' ("{0} programas abrem junto com o Windows" -f $ativos) 'ATENCAO' 'Cada um rouba RAM/CPU em segundo plano. Desative os que nao usa (nao desinstala).' { Gerenciar-Inicializacao } $null 2 $false)) }
    else { [void]$L.Add((New-Checagem 'Disco' ("{0} programas na inicializacao" -f $ativos) 'OK' '' $null $null 2 $false)) }
    $top = @(Get-TopCpu 8 | Where-Object { $_.Uso -ge 10 -and $_.Nome -notmatch '^(Idle|System|_Total|powershell|pwsh|WmiPrvSE|conhost|dwm|csrss|MsMpEng|Memory Compression)$' })
    if ($top.Count -gt 0) {
        $txtTop = (@($top | ForEach-Object { "{0} ({1}%)" -f $_.Nome, $_.Uso }) -join ', ')
        [void]$L.Add((New-Checagem 'Disco' ("Programas usando CPU AGORA, sem voce jogar: " + $txtTop) 'ATENCAO' 'Se continuar assim com o PC parado, isso rouba FPS. Se nao reconhece algum, rode Seguranca > Cacar MINERADORES.' $null $null 2 $false))
    }
    $ov = @(Get-Process -Name 'RTSS', 'MSIAfterburner', 'Overwolf', 'OverwolfBrowser', 'Medal', 'MedalEncoder', 'obs64', 'Outplayed', 'RazerCortex', 'XboxGameBarWidgets', 'nvsphelper64', 'Lively', 'wallpaper32', 'wallpaper64' -ErrorAction SilentlyContinue | ForEach-Object { $_.ProcessName } | Sort-Object -Unique)
    if ($ov.Count -gt 0) {
        [void]$L.Add((New-Checagem 'Disco' ('Overlays / gravadores / papel de parede animado abertos: ' + ($ov -join ', ')) 'INFO' 'Cada um custa um pouco de FPS e pode causar engasgos. Mantenha so o que usa (o Modo Jogo fecha alguns).' $null $null 0 $false))
    }

    # ---------- TEMPERATURA ----------
    Show-Info "  [7/8] temperatura..."
    $tm = Get-Termica
    if ($tm.Throttle) { [void]$L.Add((New-Checagem 'Temperatura' 'CPU limitada por CALOR agora (throttling)' 'PROBLEMA' 'Com o PC parado isso e grave: poeira, ventoinha parada ou pasta termica seca. Veja o menu Temperatura.' $null $null 4 $false)) }
    elseif ($null -ne $tm.TempC -and $tm.TempC -ge 85) { [void]$L.Add((New-Checagem 'Temperatura' ("CPU a {0:N0} C sem jogo" -f $tm.TempC) 'ATENCAO' 'Quente demais parado: em jogo vai limitar o FPS. Veja o menu Temperatura (limpeza, perfil frio).' $null $null 4 $false)) }
    elseif ($null -ne $tm.TempC) { [void]$L.Add((New-Checagem 'Temperatura' ("CPU a {0:N0} C" -f $tm.TempC) 'OK' '' $null $null 4 $false)) }

    # ---------- REDE ----------
    Show-Info "  [8/8] rede..."
    $ip = Get-InterfacePrincipal
    if ($ip -and $ip.Wifi) { [void]$L.Add((New-Checagem 'Rede' 'Internet pelo Wi-Fi' 'INFO' 'Para jogos online, cabo de rede reduz ping, jitter e perda de pacotes. Teste em FPS > Rede.' $null $null 0 $false)) }
    return $L
}

function RaioX-Fps {
    Write-Host ""
    Write-Host "=================== RAIO-X DE FPS ===================" -ForegroundColor Green
    Write-Host "Confere tudo que costuma derrubar o FPS (tela, driver, RAM, energia, Windows, temperatura, rede),"
    Write-Host "da uma NOTA ao seu PC e mostra o que corrigir. Nada muda sem voce mandar." -ForegroundColor DarkGray
    Write-Host ""
    $lista = @(Get-ChecagensFps)
    $total = 0; $ok = 0
    foreach ($c in $lista) { if ($c.Estado -ne 'INFO') { $total += $c.Peso; if ($c.Estado -eq 'OK') { $ok += $c.Peso } } }
    $nota = 100
    if ($total -gt 0) { $nota = [int][math]::Round(($ok / $total) * 100, 0) }
    $rank = @{ 'PROBLEMA' = 0; 'ATENCAO' = 1; 'INFO' = 2; 'OK' = 3 }
    $ordem = @($lista | Sort-Object -Property @{ Expression = { $rank[$_.Estado] } }, @{ Expression = { $_.Peso }; Descending = $true })
    $pend = @($ordem | Where-Object { $_.Estado -ne 'OK' })
    $oks = @($ordem | Where-Object { $_.Estado -eq 'OK' })

    Write-Host ""
    foreach ($c in $oks) { Write-Host ("   [ok]  {0}" -f $c.Nome) -ForegroundColor DarkGreen }
    Write-Host ""
    $n = 1
    foreach ($c in $pend) {
        $cor = 'Cyan'
        if ($c.Estado -eq 'PROBLEMA') { $cor = 'Red' } elseif ($c.Estado -eq 'ATENCAO') { $cor = 'Yellow' }
        $marca = ''
        if ($c.Fix) { if ($c.Auto) { $marca = '  [correcao automatica]' } else { $marca = '  [correcao guiada]' } }
        Write-Host ("{0,3}) [{1}] {2}{3}" -f $n, $c.Estado, $c.Nome, $marca) -ForegroundColor $cor
        if ($c.Detalhe) { Write-Host ("        " + $c.Detalhe) -ForegroundColor DarkGray }
        $n++
    }

    $cfg = Load-Config
    $corN = 'Green'
    if ($nota -lt 60) { $corN = 'Red' } elseif ($nota -lt 85) { $corN = 'Yellow' }
    Write-Host ""
    Write-Host "=====================================================" -ForegroundColor $corN
    $antes = ''
    if ($cfg.UltimaNotaFps) { $antes = ("   (ultima vez: {0})" -f $cfg.UltimaNotaFps) }
    Write-Host ("   NOTA DE FPS DO SEU PC: {0}/100{1}" -f $nota, $antes) -ForegroundColor $corN
    Write-Host "=====================================================" -ForegroundColor $corN
    $cfg | Add-Member -NotePropertyName UltimaNotaFps -NotePropertyValue $nota -Force
    Save-Config $cfg
    Write-Log ("Raio-X FPS: nota {0}" -f $nota)

    $corrigiveis = @($pend | Where-Object { $_.Fix })
    if ($corrigiveis.Count -eq 0) {
        if ($pend.Count -gt 0) { Write-Host "Os itens acima dependem de hardware/BIOS ou de voce: siga a explicacao de cada um." -ForegroundColor DarkGray }
        return
    }
    Write-Host ""
    Write-Host "Corrigir: digite os numeros (ex: 1,3)  |  A = todas as correcoes AUTOMATICAS  |  Enter = nao mexer" -ForegroundColor Yellow
    $sel = Read-Host "Escolha"
    $alvos = @()
    if ($sel -match '^[aA]') { $alvos = @($pend | Where-Object { $_.Fix -and $_.Auto }) }
    elseif ($sel) { foreach ($ix in @(ConvertTo-Indices $sel $pend.Count)) { if ($pend[$ix - 1].Fix) { $alvos += $pend[$ix - 1] } } }
    if ($alvos.Count -eq 0) { return }
    if (Ask-YesNo "Criar antes um ponto de restauracao do Windows? (recomendado)") { Criar-PontoRestauracao 'PC Toolkit - antes do Raio-X de FPS' }
    foreach ($c in $alvos) {
        Write-Host ""
        Write-Host ("--> " + $c.Nome) -ForegroundColor Cyan
        try { & $c.Fix $c.Arg } catch { Show-Aviso ("Falhou: " + $_.Exception.Message) }
    }
    Write-Host ""
    Show-Ok "Correcoes aplicadas. Reinicie o PC e rode o Raio-X de novo para ver a nota nova."
    Write-Host "Dica: meca o FPS ANTES e DEPOIS (FPS > Medir FPS) para ver o ganho real no seu jogo." -ForegroundColor DarkGray
}

# ----------------------------------------------------------------
# REDE: teste completo, placa de rede, DNS
# ----------------------------------------------------------------
function Medir-Ping($h, $n) {
    $r = @(Test-Connection -ComputerName $h -Count $n -ErrorAction SilentlyContinue)
    if ($r.Count -eq 0) { return $null }
    $t = @($r | ForEach-Object { [double]$_.ResponseTime })
    $j = 0.0
    for ($i = 1; $i -lt $t.Count; $i++) { $j += [math]::Abs($t[$i] - $t[$i - 1]) }
    if ($t.Count -gt 1) { $j = $j / ($t.Count - 1) }
    $m = $t | Measure-Object -Average -Minimum -Maximum
    return [pscustomobject]@{ Media = [math]::Round($m.Average, 0); Min = $m.Minimum; Max = $m.Maximum; Jitter = [math]::Round($j, 1); Perda = [int][math]::Round((($n - $t.Count) / $n) * 100, 0) }
}

function Testar-ConexaoCompleta {
    Write-Host ""
    Write-Host "=== TESTE DE CONEXAO PARA JOGOS ===" -ForegroundColor Cyan
    Write-Host "Mede ping, JITTER (variacao, o que causa 'teleporte'/lag) e PERDA de pacotes ate o seu roteador e ate a internet." -ForegroundColor DarkGray
    Write-Host "Assim da para saber se o problema e na sua casa (Wi-Fi/cabo) ou no provedor. Leva ~45 segundos." -ForegroundColor DarkGray
    $ip = Get-InterfacePrincipal
    if ($ip) {
        $tipo = 'cabo'
        if ($ip.Wifi) { $tipo = 'Wi-Fi' }
        Write-Host ("Conexao principal: {0} ({1})" -f $ip.Nome, $tipo)
        if ($ip.Wifi) {
            $sinal = $null
            foreach ($ln in @(& netsh.exe wlan show interfaces 2>$null)) { $mm = [regex]::Match([string]$ln, ':\s*(\d{1,3})\s*%'); if ($mm.Success) { $sinal = [int]$mm.Groups[1].Value; break } }
            if ($null -ne $sinal) {
                $corS = 'Green'
                if ($sinal -lt 50) { $corS = 'Red' } elseif ($sinal -lt 75) { $corS = 'Yellow' }
                Write-Host ("Sinal do Wi-Fi: {0}%" -f $sinal) -ForegroundColor $corS
            }
        }
    }
    $alvos = @()
    if ($ip -and $ip.Gateway -and $ip.Gateway -ne '0.0.0.0') { $alvos += @{ N = 'Seu roteador'; H = $ip.Gateway; Local = $true } }
    $alvos += @{ N = 'Cloudflare (1.1.1.1)'; H = '1.1.1.1'; Local = $false }
    $alvos += @{ N = 'Google (8.8.8.8)'; H = '8.8.8.8'; Local = $false }
    $res = @{}
    foreach ($a in $alvos) {
        Write-Host ("  medindo {0}..." -f $a.N) -ForegroundColor DarkGray
        $m = Medir-Ping $a.H 15
        $res[$a.N] = $m
        if (-not $m) { Write-Host ("  {0,-22} sem resposta" -f $a.N) -ForegroundColor Yellow; continue }
        $cor = 'Green'
        if ($m.Perda -gt 0 -or $m.Jitter -gt 15) { $cor = 'Red' } elseif ($m.Jitter -gt 5 -or ($a.Local -and $m.Media -gt 5)) { $cor = 'Yellow' }
        Write-Host ("  {0,-22} ping {1,4} ms | min {2} | max {3} | jitter {4} ms | perda {5}%" -f $a.N, $m.Media, $m.Min, $m.Max, $m.Jitter, $m.Perda) -ForegroundColor $cor
    }
    Write-Host ""
    $loc = $null
    if ($ip -and $ip.Gateway) { $loc = $res['Seu roteador'] }
    $net = $res['Cloudflare (1.1.1.1)']
    if ($loc -and ($loc.Jitter -gt 5 -or $loc.Perda -gt 0 -or $loc.Media -gt 5)) {
        Show-Aviso "O problema esta DENTRO DE CASA (entre o PC e o roteador)."
        Write-Host "  Use cabo de rede. Se for Wi-Fi: fique perto do roteador, use 5 GHz, evite paredes e outros aparelhos baixando." -ForegroundColor Yellow
    } elseif ($net -and ($net.Jitter -gt 10 -or $net.Perda -gt 0)) {
        Show-Aviso "Sua rede local esta boa; a instabilidade vem do PROVEDOR ou de alguem usando muito a internet (download/stream)."
        Write-Host "  Pause downloads/streaming durante o jogo. Se continuar, reclame com o provedor mostrando estes numeros." -ForegroundColor Yellow
    } elseif ($net) {
        Show-Ok "Conexao estavel para jogar."
    }
    Write-Host "  Referencia: ping ate 30 ms otimo | jitter ate 5 ms otimo | perda tem que ser 0%." -ForegroundColor DarkGray
}

function Otimizar-PlacaRede {
    Write-Host ""
    Write-Host "=== PLACA DE REDE PARA JOGOS ===" -ForegroundColor Cyan
    Write-Host "Desliga a economia de energia e a moderacao de interrupcoes da placa de rede (menos atraso por pacote"
    Write-Host "e menos picos de ping). Os valores antigos ficam guardados: 'Desfazer' devolve tudo." -ForegroundColor DarkGray
    $alvos = @(
        @{ K = '*InterruptModeration'; V = '0'; N = 'Moderacao de interrupcoes' },
        @{ K = '*EEE'; V = '0'; N = 'Ethernet com eficiencia energetica (EEE)' },
        @{ K = 'EEELinkAdvertisement'; V = '0'; N = 'Anuncio de EEE' },
        @{ K = 'AdvancedEEE'; V = '0'; N = 'EEE avancado' },
        @{ K = 'EnableGreenEthernet'; V = '0'; N = 'Green Ethernet' },
        @{ K = 'GigaLite'; V = '0'; N = 'Giga Lite' },
        @{ K = 'PowerSavingMode'; V = '0'; N = 'Modo de economia de energia' },
        @{ K = '*SelectiveSuspend'; V = '0'; N = 'Suspensao seletiva (adaptador USB)' }
    )
    $ads = @(Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' })
    if ($ads.Count -eq 0) { Show-Aviso "Nenhuma placa de rede fisica conectada."; return }
    $plano = @()
    foreach ($ad in $ads) {
        foreach ($k in $alvos) {
            $p = Get-NetAdapterAdvancedProperty -Name $ad.Name -RegistryKeyword $k.K -ErrorAction SilentlyContinue | Select-Object -First 1
            if (-not $p) { continue }
            $atual = [string](@($p.RegistryValue) | Select-Object -First 1)
            if ($atual -eq $k.V) { continue }
            $validos = @($p.ValidRegistryValues | Where-Object { $_ })
            if ($validos.Count -gt 0 -and ($validos -notcontains $k.V)) { continue }
            $plano += [pscustomobject]@{ Adapter = [string]$ad.Name; K = $k.K; N = $k.N; Old = $atual; New = $k.V }
        }
    }
    if ($plano.Count -eq 0) { Show-Ok "Nada a ajustar: a placa ja esta em baixa latencia (ou nao oferece essas opcoes)."; return }
    Write-Host ""
    foreach ($x in $plano) { Write-Host ("  {0}: {1} ({2} -> {3})" -f $x.Adapter, $x.N, $x.Old, $x.New) }
    Write-Host ""
    if (-not (Ask-YesNo "Aplicar? (a internet pode cair por 2 a 5 segundos)")) { return }
    $bak = @(Load-JsonLista $script:RedeFile)
    foreach ($x in $plano) {
        $ja = @($bak | Where-Object { $_.Tipo -eq 'Nic' -and $_.Adapter -eq $x.Adapter -and $_.K -eq $x.K })
        if ($ja.Count -eq 0) { $bak += [pscustomobject]@{ Tipo = 'Nic'; Adapter = $x.Adapter; K = $x.K; Old = $x.Old } }
        try { Set-NetAdapterAdvancedProperty -Name $x.Adapter -RegistryKeyword $x.K -RegistryValue $x.New -NoRestart -ErrorAction Stop; Show-Ok ("{0}: {1}" -f $x.Adapter, $x.N) }
        catch { Show-Aviso ("Nao consegui mudar {0} em {1}" -f $x.N, $x.Adapter) }
    }
    Save-JsonLista $script:RedeFile $bak 500
    if (Ask-YesNo "Reiniciar a placa de rede agora para valer? (senao vale no proximo reinicio)") {
        foreach ($a in @($plano | ForEach-Object { $_.Adapter } | Sort-Object -Unique)) { Restart-NetAdapter -Name $a -Confirm:$false -ErrorAction SilentlyContinue }
        Show-Ok "Placa reiniciada"
    }
    Write-Log "Otimizar-PlacaRede aplicado"
}

function Medir-Dns($servidor, $dominios) {
    $tempos = @()
    foreach ($d in $dominios) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $ok = $false
        try { $null = Resolve-DnsName -Name $d -Type A -Server $servidor -DnsOnly -QuickTimeout -ErrorAction Stop; $ok = $true } catch { }
        $sw.Stop()
        if ($ok) { $tempos += $sw.Elapsed.TotalMilliseconds }
    }
    if ($tempos.Count -lt [math]::Ceiling($dominios.Count / 2)) { return $null }
    return [math]::Round((($tempos | Measure-Object -Average).Average), 1)
}

function Testar-Dns {
    Write-Host ""
    Write-Host "=== DNS MAIS RAPIDO ===" -ForegroundColor Cyan
    Write-Host "O DNS nao muda o ping DENTRO do jogo, mas acelera login, matchmaking, lojas e carregamento de paginas." -ForegroundColor DarkGray
    $ip = Get-InterfacePrincipal
    if (-not $ip) { Show-Aviso "Sem conexao de internet."; return }
    $atuais = @((Get-DnsClientServerAddress -InterfaceIndex $ip.Index -AddressFamily IPv4 -ErrorAction SilentlyContinue).ServerAddresses)
    $cands = @()
    if ($atuais.Count -gt 0) { $cands += [pscustomobject]@{ N = ('Atual (' + $atuais[0] + ')'); S1 = $atuais[0]; S2 = $null; Atual = $true } }
    $cands += [pscustomobject]@{ N = 'Cloudflare'; S1 = '1.1.1.1'; S2 = '1.0.0.1'; Atual = $false }
    $cands += [pscustomobject]@{ N = 'Google'; S1 = '8.8.8.8'; S2 = '8.8.4.4'; Atual = $false }
    $cands += [pscustomobject]@{ N = 'Quad9'; S1 = '9.9.9.9'; S2 = '149.112.112.112'; Atual = $false }
    $cands += [pscustomobject]@{ N = 'OpenDNS'; S1 = '208.67.222.222'; S2 = '208.67.220.220'; Atual = $false }
    $dom = @('steampowered.com', 'riotgames.com', 'epicgames.com', 'discord.com', 'ea.com', 'xbox.com', 'rockstargames.com', 'ubisoft.com')
    $res = @()
    foreach ($c in $cands) {
        Write-Host ("  testando {0}..." -f $c.N) -ForegroundColor DarkGray
        [void](Medir-Dns $c.S1 $dom)
        $ms = Medir-Dns $c.S1 $dom
        $res += [pscustomobject]@{ C = $c; Ms = $ms }
    }
    Write-Host ""
    $validos = @($res | Where-Object { $null -ne $_.Ms } | Sort-Object Ms)
    foreach ($r in $res) {
        if ($null -eq $r.Ms) { Write-Host ("  {0,-26} sem resposta" -f $r.C.N) -ForegroundColor Yellow; continue }
        $barra = '#' * [int][math]::Min(40, [math]::Max(1, [math]::Round($r.Ms / 3)))
        $cor = 'Gray'
        if ($validos.Count -gt 0 -and $r -eq $validos[0]) { $cor = 'Green' }
        Write-Host ("  {0,-26} {1,6} ms  {2}" -f $r.C.N, $r.Ms, $barra) -ForegroundColor $cor
    }
    if ($validos.Count -eq 0) { return }
    $melhor = $validos[0]
    $atual = @($res | Where-Object { $_.C.Atual -and $null -ne $_.Ms }) | Select-Object -First 1
    if ($melhor.C.Atual) { Write-Host ""; Show-Ok "O seu DNS atual ja e o mais rapido daqui."; return }
    if ($atual -and ($atual.Ms - $melhor.Ms) -lt 3) { Write-Host ""; Show-Ok "Diferenca menor que 3 ms: nao vale trocar."; return }
    Write-Host ""
    if (Ask-YesNo ("Trocar o DNS de '{0}' para {1} ({2} / {3})? (reversivel)" -f $ip.Nome, $melhor.C.N, $melhor.C.S1, $melhor.C.S2)) {
        Trocar-Dns $ip $melhor.C.S1 $melhor.C.S2
    }
}

function Trocar-Dns($ip, $s1, $s2) {
    $bak = @(Load-JsonLista $script:RedeFile)
    $ja = @($bak | Where-Object { $_.Tipo -eq 'Dns' -and [int]$_.Index -eq $ip.Index })
    if ($ja.Count -eq 0) {
        $estatico = $false
        if ($ip.Guid) {
            $ns = Get-RegString ('HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\' + $ip.Guid) 'NameServer'
            if ($ns -and $ns.Trim()) { $estatico = $true }
        }
        $atuais = @((Get-DnsClientServerAddress -InterfaceIndex $ip.Index -AddressFamily IPv4 -ErrorAction SilentlyContinue).ServerAddresses)
        $bak += [pscustomobject]@{ Tipo = 'Dns'; Index = $ip.Index; Alias = $ip.Alias; Static = $estatico; Servers = @($atuais) }
        Save-JsonLista $script:RedeFile $bak 500
    }
    try {
        Set-DnsClientServerAddress -InterfaceIndex $ip.Index -ServerAddresses @($s1, $s2) -ErrorAction Stop
        Clear-DnsClientCache -ErrorAction SilentlyContinue
        Show-Ok ("DNS trocado para {0} / {1}" -f $s1, $s2)
        Write-Log ("DNS trocado: " + $s1)
    } catch { Show-Aviso ("Nao consegui trocar o DNS: " + $_.Exception.Message) }
}

function Restaurar-DnsItem($x) {
    try {
        if ($x.Static -and @($x.Servers).Count -gt 0) { Set-DnsClientServerAddress -InterfaceIndex ([int]$x.Index) -ServerAddresses @($x.Servers) -ErrorAction Stop }
        else { Set-DnsClientServerAddress -InterfaceIndex ([int]$x.Index) -ResetServerAddresses -ErrorAction Stop }
    } catch {
        try {
            if ($x.Static -and @($x.Servers).Count -gt 0) { Set-DnsClientServerAddress -InterfaceAlias ([string]$x.Alias) -ServerAddresses @($x.Servers) -ErrorAction Stop }
            else { Set-DnsClientServerAddress -InterfaceAlias ([string]$x.Alias) -ResetServerAddresses -ErrorAction Stop }
        } catch { }
    }
    Clear-DnsClientCache -ErrorAction SilentlyContinue
}

function Restaurar-Dns {
    $bak = @(Load-JsonLista $script:RedeFile)
    $dns = @($bak | Where-Object { $_.Tipo -eq 'Dns' })
    if ($dns.Count -eq 0) { Write-Host "O toolkit nao trocou nenhum DNS."; return }
    foreach ($x in $dns) { Restaurar-DnsItem $x; Show-Ok ("DNS original restaurado em: " + $x.Alias) }
    Save-JsonLista $script:RedeFile @($bak | Where-Object { $_.Tipo -ne 'Dns' }) 500
}

# Devolve placa de rede, DNS e QoS ao original (usado pelo Desfazer)
function Restaurar-Rede {
    $feito = $false
    $bak = @(Load-JsonLista $script:RedeFile)
    $reiniciar = @()
    foreach ($x in $bak) {
        if ($x.Tipo -eq 'Nic') {
            try { Set-NetAdapterAdvancedProperty -Name ([string]$x.Adapter) -RegistryKeyword ([string]$x.K) -RegistryValue ([string]$x.Old) -NoRestart -ErrorAction Stop; $reiniciar += [string]$x.Adapter } catch { }
            $feito = $true
        } elseif ($x.Tipo -eq 'Dns') { Restaurar-DnsItem $x; $feito = $true }
    }
    foreach ($a in @($reiniciar | Sort-Object -Unique)) { Restart-NetAdapter -Name $a -Confirm:$false -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $script:RedeFile) { Remove-Item -LiteralPath $script:RedeFile -Force -ErrorAction SilentlyContinue }
    foreach ($q in @(Get-NetQosPolicy -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'PC-Toolkit*' })) {
        Remove-NetQosPolicy -Name $q.Name -Confirm:$false -ErrorAction SilentlyContinue
        $feito = $true
    }
    return $feito
}

function Menu-Rede {
    while ($true) {
        Clear-Host
        Write-Host "=========== REDE PARA JOGOS ONLINE ===========" -ForegroundColor Cyan
        Write-Host ""
        Write-Host " 1) Teste completo de conexao (ping, jitter, perda: roteador x internet)"
        Write-Host " 2) Ajustes TCP: desligar Nagle + Otimizacao de Entrega sem compartilhar"
        Write-Host " 3) Placa de rede para jogos (sem economia de energia / moderacao de interrupcoes)"
        Write-Host " 4) DNS: testar qual e o mais rapido e trocar"
        Write-Host " 5) DNS: voltar ao original"
        Write-Host " 6) Voltar"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1' { Testar-ConexaoCompleta; Pausa }
            '2' { Otimizar-Rede; Pausa }
            '3' { Otimizar-PlacaRede; Pausa }
            '4' { Testar-Dns; Pausa }
            '5' { Restaurar-Dns; Pausa }
            '6' { return }
        }
    }
}

# ----------------------------------------------------------------
# LATENCIA E INPUT
# ----------------------------------------------------------------
function Get-EstadoTxt($ligado) {
    if ($ligado) { return 'APLICADO' }
    return 'padrao  '
}

function Otimizar-Latencia {
    while ($true) {
        Clear-Host
        Write-Host "=========== LATENCIA E INPUT (resposta do mouse/teclado) ===========" -ForegroundColor Cyan
        $tAtual = -1; $tMelhor = -1
        if (Initialize-Fps) { try { $tAtual = [PCToolkit.Fps]::TimerAtualMs(); $tMelhor = [PCToolkit.Fps]::TimerMelhorMs() } catch { } }
        if ($tAtual -gt 0) { Write-Host ("Timer do Windows agora: {0:N2} ms (melhor possivel: {1:N2} ms)" -f $tAtual, $tMelhor) -ForegroundColor DarkGray }
        $build = 0
        try { $build = [int](Get-CimInstance Win32_OperatingSystem).BuildNumber } catch { }
        $gtr = (Get-RegDword 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\kernel' 'GlobalTimerResolutionRequests') -eq 1
        $mpo = (Get-RegDword 'HKLM:\SOFTWARE\Microsoft\Windows\Dwm' 'OverlayTestMode') -eq 5
        $pt = (Get-RegDword 'HKLM:\SYSTEM\CurrentControlSet\Control\Power\PowerThrottling' 'PowerThrottlingOff') -eq 1
        $ms = (Get-RegString 'HKCU:\Control Panel\Mouse' 'MouseSpeed') -eq '0'
        Write-Host ""
        Write-Host (" 1) [{0}] Timer global de alta resolucao (Windows 11) - o pacote TURBO passa a valer para o jogo" -f (Get-EstadoTxt $gtr))
        Write-Host (" 2) [{0}] Mouse sem aceleracao (mira consistente)" -f (Get-EstadoTxt $ms))
        Write-Host (" 3) [{0}] Desligar MPO (Multi-Plane Overlay) - corrige tela piscando/engasgos com 2 monitores ou video aberto" -f (Get-EstadoTxt $mpo))
        Write-Host (" 4) [{0}] Desligar Power Throttling (desktop) - Discord/OBS/apps de fundo param de ser 'economizados'" -f (Get-EstadoTxt $pt))
        Write-Host " 5) Voltar"
        Write-Host ""
        Write-Host "Tudo aqui e reversivel em Desfazer. Itens 1, 3 e 4 valem depois de reiniciar o PC." -ForegroundColor DarkGray
        $op = Read-Host "Escolha"
        switch ($op) {
            '1' {
                if ($build -lt 22000) { Show-Info "Isso so existe no Windows 11. No Windows 10 o pacote TURBO ja ajuda sozinho." }
                else { Set-RegBackup 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\kernel' 'GlobalTimerResolutionRequests' 1 'DWord'; Show-Ok "Timer global liberado (reinicie)" }
                Pausa
            }
            '2' {
                Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseSpeed' '0' 'String'
                Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseThreshold1' '0' 'String'
                Set-RegBackup 'HKCU:\Control Panel\Mouse' 'MouseThreshold2' '0' 'String'
                Show-Ok "Aceleracao do mouse desligada (vale apos sair e entrar na conta)"
                Pausa
            }
            '3' {
                Write-Host "So use se voce tem tela piscando, engasgos com video/Discord aberto no outro monitor ou tela preta ao alt+tab." -ForegroundColor Yellow
                Write-Host "Recomendado pela propria NVIDIA nesses casos. Em algumas versoes recentes do Windows 11 nao faz efeito." -ForegroundColor DarkGray
                if (Ask-YesNo "Desligar o MPO?") { Set-RegBackup 'HKLM:\SOFTWARE\Microsoft\Windows\Dwm' 'OverlayTestMode' 5 'DWord'; Show-Ok "MPO desligado (reinicie)" }
                Pausa
            }
            '4' {
                if ([bool](Get-CimInstance Win32_Battery)) { Show-Aviso "Notebook: isso aumenta o consumo da bateria. Use so se jogar na tomada." }
                if (Ask-YesNo "Desligar o Power Throttling?") { Set-RegBackup 'HKLM:\SYSTEM\CurrentControlSet\Control\Power\PowerThrottling' 'PowerThrottlingOff' 1 'DWord'; Show-Ok "Power Throttling desligado (reinicie)" }
                Pausa
            }
            '5' { return }
        }
    }
}

# ======================================================================
# SECAO 6: MANUTENCAO, ASSISTENTE E MENU PRINCIPAL
# ======================================================================
function Reparar-Sistema {
    Write-Host ""
    Write-Host "=== REPARAR ARQUIVOS DO SISTEMA ===" -ForegroundColor Cyan
    Write-Host "Roda DISM (repara a imagem do Windows, precisa de internet) e depois SFC (repara arquivos corrompidos)."
    Write-Host "Resolve travamentos, erros estranhos e apps que nao abrem. Pode levar de 10 a 40 minutos."
    if (-not (Ask-YesNo "Executar agora?")) { return }
    Write-Host ""
    Write-Host "[1/2] DISM /RestoreHealth ..." -ForegroundColor Yellow
    & Dism.exe /Online /Cleanup-Image /RestoreHealth
    Write-Host ""
    Write-Host "[2/2] SFC /scannow ..." -ForegroundColor Yellow
    & sfc.exe /scannow
    Write-Host ""
    Show-Ok "Reparo concluido. Se ele corrigiu algo, reinicie o PC."
    Write-Log "Reparar-Sistema executado"
}

function Otimizar-Disco {
    Write-Host ""
    Write-Host "=== OTIMIZAR DISCOS ===" -ForegroundColor Cyan
    Write-Host "SSD: envia TRIM (mantem a velocidade). HD comum: desfragmenta. Outros tipos sao ignorados."
    foreach ($v in @(Get-Volume -ErrorAction SilentlyContinue | Where-Object { $_.DriveType -eq 'Fixed' -and $_.DriveLetter })) {
        $letra = [string]$v.DriveLetter
        $tipo = Get-TipoDisco $letra
        Write-Host ""
        Write-Host ("Disco {0}: ({1})" -f $letra, $tipo) -ForegroundColor Yellow
        if ($tipo -eq 'SSD') { Optimize-Volume -DriveLetter $letra -ReTrim -Verbose }
        elseif ($tipo -eq 'HDD') { Optimize-Volume -DriveLetter $letra -Defrag -Verbose }
        else { Write-Host "  Tipo nao identificado, pulei." -ForegroundColor DarkGray }
    }
    Show-Ok "Otimizacao de discos concluida"
}

function Atualizar-Drivers {
    Write-Host ""
    Write-Host "=== DRIVERS ===" -ForegroundColor Cyan
    Write-Host "Driver de video desatualizado e uma das maiores causas de FPS baixo e travamentos."
    foreach ($v in @(Get-CimInstance Win32_VideoController)) {
        $dt = ''
        if ($v.DriverDate) { $dt = $v.DriverDate.ToString('yyyy-MM-dd') }
        Write-Host ("  {0}: driver {1} ({2})" -f $v.Name, $v.DriverVersion, $dt)
    }
    Write-Host ""
    Write-Host "  NVIDIA: nvidia.com/drivers (ou NVIDIA App)   |   AMD: amd.com/support (Adrenalin)   |   Intel: Intel Driver & Support Assistant"
    Write-Host "  Depois, abra Windows Update > Opcoes avancadas > Atualizacoes opcionais para drivers de chipset, rede e audio."
    if (Ask-YesNo "Abrir o Windows Update agora?") { Start-Process "ms-settings:windowsupdate" }
}

function Menu-Manutencao {
    while ($true) {
        Clear-Host
        Write-Host "=========== MANUTENCAO DO WINDOWS ===========" -ForegroundColor Yellow
        Write-Host ""
        Write-Host " 1) Reparar arquivos do sistema (DISM + SFC)"
        Write-Host " 2) Otimizar discos (TRIM no SSD / desfragmentar HD)"
        Write-Host " 3) Verificar drivers (video) e abrir Windows Update"
        Write-Host " 4) Voltar ao menu principal"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1' { Reparar-Sistema; Pausa }
            '2' { Otimizar-Disco; Pausa }
            '3' { Atualizar-Drivers; Pausa }
            '4' { return }
        }
    }
}

function Assistente-Completo {
    Write-Host ""
    Write-Host "=== ASSISTENTE: LIMPEZA + FPS + APARENCIA + SEGURANCA ===" -ForegroundColor Green
    Write-Host "Vou guiar voce em 8 etapas, perguntando antes de cada uma:"
    Write-Host "  1) Ponto de restauracao   2) Limpeza profunda       3) RAIO-X DE FPS (nota + correcoes)"
    Write-Host "  4) Otimizar Windows + perfil de temperatura         5) Aparencia e desempenho"
    Write-Host "  6) Graficos dos jogos     7) Diagnostico termico    8) Varredura de seguranca"
    if (-not (Ask-YesNo "Comecar?")) { return }

    Write-Host ""
    Write-Host "[1/8] Ponto de restauracao" -ForegroundColor Cyan
    if (Ask-YesNo "Criar um ponto de restauracao agora?") { Criar-PontoRestauracao 'PC Toolkit - Assistente' }

    Write-Host ""
    Write-Host "[2/8] Limpeza profunda" -ForegroundColor Cyan
    if (Ask-YesNo "Fazer a limpeza profunda agora?") { Limpar-PC -Profundo }

    Write-Host ""
    Write-Host "[3/8] Raio-X de FPS" -ForegroundColor Cyan
    if (Ask-YesNo "Rodar o Raio-X de FPS (nota do PC e correcoes com 1 clique)?") { RaioX-Fps }

    Write-Host ""
    Write-Host "[4/8] Otimizar Windows + perfil de energia/temperatura" -ForegroundColor Cyan
    if (Ask-YesNo "Aplicar agora?") { Otimizar-Windows -SemPonto }

    Write-Host ""
    Write-Host "[5/8] Aparencia e desempenho inteligente" -ForegroundColor Cyan
    if (Ask-YesNo "Ajustar efeitos visuais e desempenho conforme o seu hardware?") { Otimizar-Inteligente }

    Write-Host ""
    Write-Host "[6/8] Graficos dos jogos" -ForegroundColor Cyan
    if (Ask-YesNo "Otimizar os arquivos de configuracao dos jogos agora?") { Otimizar-Graficos }

    Write-Host ""
    Write-Host "[7/8] Diagnostico termico" -ForegroundColor Cyan
    Diagnostico-Termico

    Write-Host ""
    Write-Host "[8/8] Varredura de seguranca (malware, minerador, invasores)" -ForegroundColor Cyan
    if (Ask-YesNo "Fazer a varredura RAPIDA de ameacas agora?") { Cacar-Ameacas -Modo 'Rapida' }

    Write-Host ""
    Write-Host "Assistente concluido. Reinicie o PC. Depois, para jogar, use o MODO JOGO (PC-Toolkit.bat jogar)." -ForegroundColor Green
    Write-Host "Para ver o ganho real: FPS > Medir FPS antes e depois, no mesmo lugar do jogo." -ForegroundColor DarkGray
}

function Menu-Principal {
    while ($true) {
        Clear-Host
        $d = Get-PSDrive C
        Write-Host "==================================================" -ForegroundColor Cyan
        Write-Host ("   PC TOOLKIT {0}  -  Limpeza | FPS | Seguranca" -f $script:Versao) -ForegroundColor Cyan
        $cfgP = Load-Config
        if ($cfgP.UltimaNotaFps) { Write-Host ("   Nota de FPS do seu PC: {0}/100" -f $cfgP.UltimaNotaFps) -ForegroundColor DarkGray }
        Write-Host "==================================================" -ForegroundColor Cyan
        Write-Host ("Disco C: livre {0} de {1}" -f (Format-Size $d.Free), (Format-Size ($d.Used + $d.Free))) -ForegroundColor DarkGray
        Write-Host ""
        Write-Host " 1) ASSISTENTE: fazer tudo (limpeza + FPS + aparencia + seguranca, passo a passo)" -ForegroundColor Green
        Write-Host " 2) Limpeza do PC (padrao, profunda, simulacao, arquivos grandes, jogos...)"
        Write-Host " 3) CENTRAL DE FPS: Raio-X, Modo Jogo TURBO, medidor de FPS, monitor, rede, latencia" -ForegroundColor Green
        Write-Host " 4) Aparencia e desempenho INTELIGENTE (efeitos visuais, processador, memoria virtual)" -ForegroundColor Green
        Write-Host " 5) Temperatura e refrigeracao (diagnostico, monitor ao vivo, perfis frios, teste)"
        Write-Host " 6) SEGURANCA: cacar malware, minerador de bitcoin e invasores" -ForegroundColor Red
        Write-Host " 7) Manutencao do Windows (reparar sistema, discos, drivers)"
        Write-Host " 8) Megazord: backup + reset de fabrica"
        Write-Host " 9) Desfazer TODAS as mudancas do toolkit"
        Write-Host "10) Sair"
        Write-Host ""
        $op = Read-Host "Escolha"
        switch ($op) {
            '1'  { Assistente-Completo; Pausa }
            '2'  { Menu-Limpeza }
            '3'  { Menu-FPS }
            '4'  { Otimizar-Inteligente; Pausa }
            '5'  { Menu-Temperatura }
            '6'  { Menu-Seguranca }
            '7'  { Menu-Manutencao }
            '8'  { Menu-Megazord }
            '9'  { Desfazer; Pausa }
            '10' { return }
        }
    }
}

# ======================================================================
# EXECUCAO
# ======================================================================
if (-not (Test-Admin)) {
    Write-Host "Este programa precisa ser executado como Administrador." -ForegroundColor Red
    Write-Host "Use PC-Toolkit.bat (ele pede a elevacao sozinho)."
    Read-Host "Enter para sair" | Out-Null
    exit
}

Recuperar-Sessao

if ($Monitor) { Monitor-Temperatura; exit }
if ($LimpezaRapida) { Limpar-PC; exit }
if ($ModoJogo) { Modo-Jogo -Preselecionado $Jogo; exit }
if ($Scan) { Cacar-Ameacas -Modo 'Rapida'; exit }
if ($ScanCompleto) { Cacar-Ameacas -Modo 'Completa'; exit }
if ($Aparencia) { Otimizar-Inteligente; exit }
if ($Blindagem) { Protecao-Imediata; Pausa; exit }
if ($RaioX) { RaioX-Fps; exit }
if ($Benchmark) { Benchmark-Fps; exit }

Menu-Principal
