# Windows 本地开发指南

安装 Java 21、Node.js 24 和 pnpm 11.25.0。将 `JAVA_HOME` 设为 JDK 的安装目录，确保 `node` 和 `pnpm.cmd` 可从 PowerShell 调用。Maven 由仓库的 Windows Wrapper 提供，首次安装依赖和构建需要网络。启动器使用本机端口 `5173` 和 `8080`。

## PowerShell 启动

在仓库根目录运行：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -version
node --version
pnpm.cmd --version

pnpm.cmd --dir frontend install --frozen-lockfile
if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }

$env:MAVEN_USER_HOME = Join-Path $PWD '.cache/maven'
Push-Location backend
try {
    .\mvnw.cmd '-Dmaven.repo.local=../.cache/m2' -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw '后端打包失败' }
} finally {
    Pop-Location
}

node scripts/dev.mjs
```

打开 [项目首页](http://127.0.0.1:5173)，从首页进入「服务连接」检查前后端连通。默认使用 `demo` 配置和内存 H2，演示数据随应用重启清空。用 `Ctrl+C` 停止本次启动的前后端。

如需在本机体验需要身份的业务 API，先在仓库根目录打开 Git Bash，运行 `./scripts/init-env.sh` 生成 Git 忽略的 `.env`，然后启动或重启服务。已有 `.env` 会原样保留。具体业务请求见[本地体验步骤](api/local-walkthrough.md)。

## 健康检查

保持应用运行，在另一个 PowerShell 窗口执行：

```powershell
curl.exe -fsS http://127.0.0.1:5173/api/health
curl.exe -fsS http://127.0.0.1:8080/api/health
curl.exe -fsS http://127.0.0.1:8080/actuator/health/readiness
curl.exe -fsS http://127.0.0.1:8080/actuator/health/liveness
```

前两条分别通过 Vite 代理和直接访问后端，预期均返回 `{"status":"UP","application":"group-photo-consensus"}`。后两条预期均返回 `{"status":"UP"}`。需要核对数据库连接时，查看包含数据库探针的 readiness。

## 构建与测试

在仓库根目录执行，前端依赖安装沿用启动步骤，后端打包前会运行测试：

```powershell
pnpm.cmd --dir frontend test
pnpm.cmd --dir frontend build

$env:MAVEN_USER_HOME = Join-Path $PWD '.cache/maven'
Push-Location backend
try {
    .\mvnw.cmd '-Dmaven.repo.local=../.cache/m2' -B clean verify
} finally {
    Pop-Location
}
```

前端的 `build` 包含类型检查。后端以 `BUILD SUCCESS` 为通过，生成 `backend/target/group-photo-consensus.jar`。

验证连接失败时，完成打包后在两个 PowerShell 窗口分别运行 `pnpm.cmd --dir frontend dev` 和 `node scripts/dev.mjs --backend-only`。只停止第二个窗口里的后端，前端页面可以继续打开。恢复步骤见[连接恢复验收](api/local-walkthrough.md#验证连接失败与恢复)。

## 数据库与图片存储

| 环境 | 配置和实测范围 |
|---|---|
| 本机 H2 | 上述 Windows 构建和启动步骤已实测，H2 通过 Flyway 迁移与 readiness 检查。默认内存数据库用于演示与快速测试。 |
| 本机 MySQL | 需要 Docker Compose 和本机 `.env`。后端打包后，在仓库根目录执行 `docker compose up -d --wait mysql`，再执行 `node scripts/dev.mjs --local`，用 readiness 核对连接。Windows 指南的本机复现未运行此路径。 |
| CI 的 MySQL Compose | `mysql-compose` 使用 MySQL、后端容器及 Nginx，检查迁移、业务 API 和重启后的照片元数据与图片文件。每次执行结果见 [GitHub Actions](https://github.com/Jdeciderz23/frame-and-say/actions/workflows/ci.yml)。 |
| MinIO | 可选的 [Compose 配置](../docker-compose.storage.yml)需要已验证的 `MINIO_IMAGE`。上述本机启动和 CI 的 MySQL Compose 均使用私有文件目录，未验证 MinIO 镜像、私有桶或存储联调。 |
