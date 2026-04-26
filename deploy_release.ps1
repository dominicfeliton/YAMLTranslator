param(
    [string]$PublishVersion = $(if ($env:PUBLISH_VERSION) { $env:PUBLISH_VERSION } else { $env:VERSION })
)

# Change to the directory where the script is located
Set-Location -Path $PSScriptRoot

$GroupId = "com.dominicfeliton.yamltranslator"
$GroupPath = "com\dominicfeliton\yamltranslator"
$ArtifactId = "YAMLTranslator"
$JarName = "YAMLTranslator.jar"

if ($env:SOURCE_BRANCH) {
    $SourceBranch = $env:SOURCE_BRANCH
}
elseif ($env:MASTER_BRANCH) {
    $SourceBranch = $env:MASTER_BRANCH
}
else {
    $SourceBranch = "main"
}

if ($env:SOURCE_WORKTREE_DIR) {
    $SourceWorktreeDir = $env:SOURCE_WORKTREE_DIR
}
elseif ($env:MASTER_WORKTREE_DIR) {
    $SourceWorktreeDir = $env:MASTER_WORKTREE_DIR
}
else {
    $SourceWorktreeDir = Join-Path $PSScriptRoot "..\YAMLTranslator-main"
}

if (-not [System.IO.Path]::IsPathRooted($SourceWorktreeDir)) {
    $SourceWorktreeDir = Join-Path $PSScriptRoot $SourceWorktreeDir
}
$SourceWorktreeDir = [System.IO.Path]::GetFullPath($SourceWorktreeDir)

function Test-Command($cmdname) {
    return [bool](Get-Command -Name $cmdname -ErrorAction SilentlyContinue)
}

function Ensure-Command {
    param(
        [string]$Command,
        [string]$Package,
        [bool]$CanInstall = $true
    )

    if (Test-Command $Command) {
        return
    }

    if (-not $CanInstall) {
        Write-Host "$Command is not installed. Please install it manually."
        exit 1
    }

    if (-not $Package) {
        $Package = $Command
    }

    if (-not (Test-Command choco)) {
        Write-Host "$Command is not installed, and Chocolatey is not available to install $Package."
        exit 1
    }

    Write-Host "$Command is not installed. Attempting to install via Chocolatey..."
    choco install $Package -y
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed to install $Command. Please install it manually."
        exit 1
    }
}

function Invoke-CheckedCommand {
    param(
        [string]$Command,
        [string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        Write-Host "$Command failed. Exiting."
        exit 1
    }
}

function Prepare-SourceWorktree {
    if (-not (Test-Path $SourceWorktreeDir)) {
        Write-Host "Creating $SourceBranch worktree at $SourceWorktreeDir..."
        Invoke-CheckedCommand "git" @("fetch", "origin", $SourceBranch)

        git show-ref --verify --quiet "refs/heads/$SourceBranch"
        if ($LASTEXITCODE -eq 0) {
            Invoke-CheckedCommand "git" @("worktree", "add", $SourceWorktreeDir, $SourceBranch)
        }
        else {
            Invoke-CheckedCommand "git" @("worktree", "add", "-b", $SourceBranch, $SourceWorktreeDir, "origin/$SourceBranch")
        }
    }
    elseif (-not (Test-Path (Join-Path $SourceWorktreeDir ".git"))) {
        Write-Host "Error: $SourceWorktreeDir exists but is not a git worktree."
        exit 1
    }

    Push-Location $SourceWorktreeDir

    $currentBranch = (git branch --show-current).Trim()
    if ($currentBranch -ne $SourceBranch) {
        Write-Host "Error: $SourceWorktreeDir is on '$currentBranch', not '$SourceBranch'."
        exit 1
    }

    Write-Host "Updating $SourceBranch..."
    Invoke-CheckedCommand "git" @("fetch", "origin", $SourceBranch)
    Invoke-CheckedCommand "git" @("pull", "--ff-only", "origin", $SourceBranch)

    Write-Host "Building $ArtifactId from $SourceBranch..."
    Invoke-CheckedCommand "mvn" @("clean", "package")

    Pop-Location
}

function Read-SourceVersion {
    Push-Location $SourceWorktreeDir
    $version = (& mvn help:evaluate "-Dexpression=project.version" "-q" "-DforceStdout")
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed to read project.version from $SourceBranch."
        exit 1
    }
    Pop-Location

    return $version.Trim()
}

function Write-CertutilHash {
    param(
        [string]$File,
        [string]$Algorithm,
        [string]$OutputPath
    )

    $hashLine = certutil -hashfile $File $Algorithm |
        Where-Object { $_ -match "^[0-9a-fA-F ]+$" } |
        Select-Object -First 1

    if (-not $hashLine) {
        Write-Host "Failed to generate $Algorithm hash for $File."
        exit 1
    }

    $hash = ($hashLine -replace " ", "").ToLowerInvariant()
    Set-Content -Path $OutputPath -Value $hash
}

Ensure-Command -Command "git"
Ensure-Command -Command "mvn" -Package "maven"
Ensure-Command -Command "certutil" -CanInstall $false

if (-not (Test-Command java)) {
    Write-Host "java is not installed. Please install JDK 17 or newer."
    exit 1
}

Prepare-SourceWorktree

$jarPath = Join-Path (Join-Path $SourceWorktreeDir "target") $JarName

if (-not (Test-Path $jarPath -PathType Leaf)) {
    Write-Host "Error: Could not find built jar at $jarPath"
    exit 1
}

$sourceVersion = Read-SourceVersion
if (-not $sourceVersion) {
    Write-Host "Error: Could not read project.version from $SourceBranch."
    exit 1
}

if ($PublishVersion) {
    $version = $PublishVersion
    if ($version -ne $sourceVersion) {
        Write-Host "Using publish version $version; source version is $sourceVersion."
    }
}
else {
    $version = $sourceVersion
}

Write-Host "Publishing $ArtifactId $version from $jarPath..."

mvn install:install-file `
    "-DgroupId=$GroupId" `
    "-DartifactId=$ArtifactId" `
    "-Dversion=$version" `
    "-Dfile=$jarPath" `
    "-Dpackaging=jar" `
    "-DlocalRepositoryPath=." `
    "-DcreateChecksum=true" `
    "-DgeneratePom=true"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Maven install failed. Exiting."
    exit 1
}

Write-Host "Generating checksums..."
Set-Location -Path "$GroupPath\$ArtifactId\$version"

$files = @("$ArtifactId-$version.jar", "$ArtifactId-$version.pom")
foreach ($file in $files) {
    Write-CertutilHash -File $file -Algorithm "SHA1" -OutputPath "$file.sha1"
    Write-CertutilHash -File $file -Algorithm "MD5" -OutputPath "$file.md5"
}

Write-Host "Deployment complete. Files are ready to be committed and pushed to the repository."
