$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
Set-Location $root

$db = "jdbc:mysql://localhost:3306/vehiclemoment_dev?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
$jar = Join-Path $root "target\glivt-0.0.1-SNAPSHOT.jar"
$log = Join-Path $root "vehiclemoment-backend.log"

java -jar $jar `
  "--server.address=0.0.0.0" `
  "--server.port=8085" `
  "--spring.datasource.url=$db" `
  "--spring.jpa.hibernate.ddl-auto=create" `
  "--spring.flyway.enabled=false" `
  "--app.demo-mode.enabled=true" `
  "--app.cors.allowed-origins=*" `
  *> $log
