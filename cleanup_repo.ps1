Write-Host "Démarrage du grand nettoyage..." -ForegroundColor Cyan

# 1. Recherche de Git (Global ou GitHub Desktop)
$gitExe = "git"
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "Git global non trouvé. Recherche dans GitHub Desktop..." -ForegroundColor Yellow
    $ghPath = "$env:LOCALAPPDATA\GitHubDesktop"
    
    if (Test-Path $ghPath) {
        # Cherche git.exe dans le dossier cmd (le plus sûr) ou bin
        $foundGit = Get-ChildItem -Path $ghPath -Filter "git.exe" -Recurse -ErrorAction SilentlyContinue | 
                    Where-Object { $_.FullName -like "*\cmd\git.exe" -or $_.FullName -like "*\bin\git.exe" } | 
                    Select-Object -First 1
        
        if ($foundGit) {
            $gitExe = $foundGit.FullName
            Write-Host "Git trouvé ici : $gitExe" -ForegroundColor Green
        } else {
            Write-Error "Désolé, impossible de trouver Git même dans GitHub Desktop."
            Write-Host "Veuillez installer Git for Windows : https://git-scm.com/download/win"
            pause
            exit
        }
    } else {
        Write-Error "Git non trouvé et GitHub Desktop non détecté."
        pause
        exit
    }
}

# Fonction wrapper pour appeler git proprement
function Run-Git {
    param([Parameter(ValueFromRemainingArguments=$true)]$Args)
    if ($gitExe -eq "git") {
        git @Args
    } else {
        & $gitExe @Args
    }
}

# 2. Suppression des fichiers poubelle (locaux)
Write-Host "Suppression des fichiers temporaires..." -ForegroundColor Yellow
$junkFiles = @("temp_final_check.zip", "temp_inspect.zip")
$junkDirs = @("temp_final_jar", "temp_jar_inspect")

foreach ($file in $junkFiles) {
    if (Test-Path $file) { Remove-Item $file -Force; Write-Host "Supprimé : $file" -ForegroundColor Gray }
}
foreach ($dir in $junkDirs) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force; Write-Host "Supprimé : $dir" -ForegroundColor Gray }
}

# 3. Nettoyage de Git (retirer les fichiers build/bin du suivi)
Write-Host "Nettoyage de l'index Git (build, bin, gradle)..." -ForegroundColor Yellow
Run-Git rm -r --cached build/ bin/ .gradle/ 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "Fichiers retirés du suivi Git." -ForegroundColor Green
} else {
    Write-Host "Rien à nettoyer dans l'index (ou ils étaient déjà ignorés)." -ForegroundColor Gray
}

# 4. Ajout des nouveaux fichiers corrects (.gitignore, workflows)
Write-Host "Préparation des fichiers..." -ForegroundColor Yellow
Run-Git add .

# 5. Commit
Write-Host "Création du commit..." -ForegroundColor Yellow
Run-Git commit -m "Auto-Cleanup: Configuration propre du projet et Workflows"

# 6. Push
Write-Host "Envoi vers GitHub..." -ForegroundColor Yellow
Run-Git push

if ($LASTEXITCODE -eq 0) {
    Write-Host "SUCCÈS ! Votre projet est propre et sur GitHub." -ForegroundColor Green
} else {
    Write-Host "Erreur lors du push. Vérifiez votre connexion ou vos accès." -ForegroundColor Red
}

pause
