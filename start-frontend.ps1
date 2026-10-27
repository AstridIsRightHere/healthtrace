<#
.SYNOPSIS
  本地启动前端 Vite 开发服务器（/api 代理到 http://127.0.0.1:8080）。

.DESCRIPTION
  - 首次运行或 package-lock.json 比 node_modules 新时执行 npm ci。
  - npm 缓存沿用全局配置（已指向 D 盘）；临时文件放到 D:\Development\Caches\tmp。

.EXAMPLE
  .\start-frontend.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$frontend = Join-Path $PSScriptRoot 'frontend'

$tmp = 'D:\Development\Caches\tmp'
if (-not (Test-Path $tmp)) { New-Item -ItemType Directory -Path $tmp | Out-Null }
$env:TEMP = $tmp
$env:TMP = $tmp

$cache = (npm config get cache).Trim()
if ($cache -match '^[Cc]:') {
    Write-Error "npm 缓存目录位于 C 盘（$cache），请先执行 npm config set cache D:\Development\Caches\npm-cache"
}

Push-Location $frontend
try {
    $modules = Join-Path $frontend 'node_modules'
    $lock = Join-Path $frontend 'package-lock.json'
    if (-not (Test-Path $modules) -or (Get-Item $lock).LastWriteTime -gt (Get-Item $modules).LastWriteTime) {
        Write-Host '安装前端依赖（npm ci）…'
        npm ci
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    Write-Host '启动前端：http://localhost:5173'
    npm run dev
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
