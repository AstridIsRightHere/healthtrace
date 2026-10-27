<#
.SYNOPSIS
  本地启动后端（dev profile）。从根目录 .env 读取环境变量后执行 mvn spring-boot:run。

.DESCRIPTION
  - 只把 .env 中的变量注入当前进程，不打印任何变量值，不写回磁盘。
  - 只在当前进程未设置该变量时才注入，便于临时覆盖（例如 $env:DB_URL=... 后再运行）。
  - 缺少必需变量时直接退出并列出变量名（与 application.yml “缺失即失败”的约定一致）。
  - 临时文件放到 D:\Development\Caches\tmp，不落 C 盘。

.EXAMPLE
  .\start-dev.ps1
  .\start-dev.ps1 -SkipDbCheck
#>
[CmdletBinding()]
param(
    # 跳过 Docker MySQL 健康检查（例如数据库不在 Docker 里）
    [switch]$SkipDbCheck
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$envFile = Join-Path $root '.env'

if (-not (Test-Path $envFile)) {
    Write-Error "未找到 $envFile。请复制 .env.example 为 .env 并填写本机值。"
}

# 1. 解析 .env：KEY=VALUE，忽略空行与 # 注释，去掉两侧引号
$loaded = 0
foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $idx = $trimmed.IndexOf('=')
    if ($idx -lt 1) { continue }
    $key = $trimmed.Substring(0, $idx).Trim()
    $value = $trimmed.Substring($idx + 1).Trim()
    if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    if ($value -eq '') { continue }
    if (-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key, 'Process'))) { continue }
    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    $loaded++
}
Write-Host "已从 .env 注入 $loaded 个变量（不显示值）。"

if ([string]::IsNullOrEmpty($env:SPRING_PROFILES_ACTIVE)) { $env:SPRING_PROFILES_ACTIVE = 'dev' }
if ($env:SPRING_PROFILES_ACTIVE -ne 'dev') {
    Write-Error "本脚本只用于 dev 环境，当前 SPRING_PROFILES_ACTIVE=$($env:SPRING_PROFILES_ACTIVE)。"
}

# 2. 校验必需变量（只报变量名）
$required = @(
    'DB_URL', 'DB_USERNAME', 'DB_PASSWORD',
    'HT_EMAIL_ENCRYPTION_KEY', 'HT_EMAIL_INDEX_KEY',
    'HT_STORAGE_LOCAL_ROOT',
    'HT_OCR_ENCRYPTION_KEY', 'HT_BAIDU_OCR_API_KEY', 'HT_BAIDU_OCR_SECRET_KEY'
)
$missing = $required | Where-Object { [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($_, 'Process')) }
if ($missing) {
    Write-Error ("以下变量在 .env 中缺失或为空：" + ($missing -join ', '))
}

# 存储目录必须在 C 盘之外（29-环境配置说明）
if ($env:HT_STORAGE_LOCAL_ROOT -match '^[Cc]:') {
    Write-Error 'HT_STORAGE_LOCAL_ROOT 不能位于 C 盘。'
}
if (-not (Test-Path -LiteralPath $env:HT_STORAGE_LOCAL_ROOT)) {
    New-Item -ItemType Directory -Path $env:HT_STORAGE_LOCAL_ROOT | Out-Null
    Write-Host "已创建存储目录 $($env:HT_STORAGE_LOCAL_ROOT)"
}

# 3. 临时目录放到 D 盘缓存区
$tmp = 'D:\Development\Caches\tmp'
if (-not (Test-Path $tmp)) { New-Item -ItemType Directory -Path $tmp | Out-Null }
$env:TEMP = $tmp
$env:TMP = $tmp

# 4. 检查 Docker MySQL 是否 healthy
if (-not $SkipDbCheck) {
    $status = docker inspect --format '{{.State.Health.Status}}' healthtrace-mysql 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Error '未找到 healthtrace-mysql 容器。请先在项目根目录执行 docker compose up -d（或加 -SkipDbCheck）。'
    }
    if ($status -ne 'healthy') {
        Write-Error "MySQL 容器状态为 $status，请等待 healthy 后重试。"
    }
    Write-Host 'MySQL 容器状态：healthy'
}

# 5. 启动后端
Write-Host '启动后端：http://127.0.0.1:8080（profile=dev）'
Push-Location (Join-Path $root 'backend')
try {
    mvn -B spring-boot:run "-Dspring-boot.run.jvmArguments=-Djava.io.tmpdir=$tmp"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
