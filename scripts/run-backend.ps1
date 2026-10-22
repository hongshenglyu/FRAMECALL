$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$RunRoot = $ProjectRoot

# Spring Boot's Maven plugin can lose classpath entries when the workspace path
# contains non-ASCII characters, so run through an ASCII subst drive.
cmd /c "subst Z: `"$ProjectRoot`"" | Out-Null
if (Test-Path "Z:\backend\pom.xml") {
  $RunRoot = "Z:\"
}

$ToolsRoot = Join-Path $RunRoot "tools"
$LocalJdk = Get-ChildItem $ToolsRoot -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1
$LocalMaven = Get-ChildItem $ToolsRoot -Directory -Filter "apache-maven-*" -ErrorAction SilentlyContinue | Select-Object -First 1
$MavenRepo = Join-Path $ToolsRoot "m2-repository"

if ($LocalJdk) {
  $env:JAVA_HOME = $LocalJdk.FullName
  $env:Path = "$($LocalJdk.FullName)\bin;$env:Path"
}

if ($LocalMaven) {
  $env:Path = "$($LocalMaven.FullName)\bin;$env:Path"
}

Set-Location (Join-Path $RunRoot "backend")
mvn "-Dmaven.repo.local=$MavenRepo" spring-boot:run
