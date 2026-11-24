# W1-08 启动复现与协作核查

2026-11-24（北京时间），`zqjiejie` 汇总 [W1-08 / #8](https://github.com/Jdeciderz23/frame-and-say/issues/8)。本次启动复现使用 GitHub 主线提交 [`bd2c171`](https://github.com/Jdeciderz23/frame-and-say/commit/bd2c171b246a732e833d7db94e3507b145ddf151)，依赖任务 [#1](https://github.com/Jdeciderz23/frame-and-say/issues/1)、[#2](https://github.com/Jdeciderz23/frame-and-say/issues/2)、[#3](https://github.com/Jdeciderz23/frame-and-say/issues/3) 当时均有 PR 等待合并。

## 本机复现

环境为 Windows、Temurin 21.0.13.1、Node 24.22.3、pnpm 11.25.0、Maven Wrapper 3.9.9。先在仓库根目录用 Git Bash（或带 Git 工具的 `sh`）运行 `./scripts/init-env.sh`。本机执行结果为退出 `0`，生成了 Git 忽略的 `.env`，其中两枚演示令牌均已配置。

以下命令从仓库根目录在 PowerShell 执行。先让 `JAVA_HOME` 指向本机的 Java 21 安装目录。推荐以 README 的依赖版本复现。

```powershell
$env:CI = 'true'
$env:npm_config_cache = "$PWD/.cache/npm"
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend test
pnpm --dir frontend build

$env:MAVEN_USER_HOME = "$PWD/.cache/maven"
Push-Location backend
./mvnw.cmd '-Dmaven.repo.local=../.cache/m2' -B clean verify
Pop-Location
node scripts/dev.mjs
```

本机通过 Windows Wrapper 执行构建，再直接调用 `dev.sh` 使用的 `scripts/dev.mjs`。前端锁文件安装、6 项测试、类型检查及生产构建均通过。后端 `clean verify` 成功，30 项测试通过，0 失败、0 错误、0 跳过，生成 `backend/target/group-photo-consensus.jar`。

启动后在另一终端检查，表中命令均指向此次启动的进程。实测结构由 Node `fetch` 断言，可用同样的 HTTP 请求复查。

| PowerShell 复查命令 | 实测结果 |
|---|---|
| `curl.exe -sS -o NUL -w '%{http_code}' http://127.0.0.1:5173/` | `200`，HTML 包含 Vue 应用挂载点 |
| `curl.exe -sS -o NUL -w '%{http_code}' http://127.0.0.1:5173/health` | `200`，返回 Vue 入口 HTML |
| `curl.exe -sS -i http://127.0.0.1:8080/api/health` | `200`，`{"status":"UP","application":"group-photo-consensus"}` |
| `curl.exe -sS -i http://127.0.0.1:5173/api/health` | `200`，Vite 代理返回同一 JSON |
| `curl.exe -sS -i http://127.0.0.1:8080/actuator/health/readiness` | `200`，`{"status":"UP"}`，包含数据库就绪探针 |
| `curl.exe -sS -i http://127.0.0.1:8080/actuator/health/liveness` | `200`，`{"status":"UP"}` |
| `curl.exe -sS -i http://127.0.0.1:5173/api/events` | `401`，代理未绕过身份校验 |

本次通过启动脚本的 `SIGINT` 处理路径停止服务，退出码为 `0`，随后重新绑定 `5173`、`8080` 验证端口已释放。本机日志保存在 Git 忽略的 `.tools/w1-08-backend.log` 和 `.tools/w1-08-health.log`。

## 数据库与对象存储

| 环境 | 本次证据 | 下一步 |
|---|---|---|
| 本机 H2 | `test` 构建实际执行 Flyway V1、V2。运行时启用 `demo`，日志显示 `jdbc:h2:mem:group_photo_demo`，成功应用两版迁移并通过 readiness。 | 继续作为日常构建和 HTTP 冒烟环境。 |
| 本机 MySQL | Windows 未发现 Docker CLI 或服务，已有 Ubuntu WSL 也未发现 Docker CLI。未运行 README 的 `docker compose up -d --wait mysql` 和 `./scripts/dev.sh --local`。 | 具备 Docker 后执行 README 的 MySQL 流程，再检查 readiness 和重启后读取。 |
| GitHub Actions MySQL | `mysql-compose` 实际完成构建、迁移、经 Nginx 的业务 API，以及重启 MySQL 和后端后的活动、照片元数据、缩略图读取。见[主线任务日志](https://github.com/Jdeciderz23/frame-and-say/actions/runs/45122811305/job/137520254098)及 [W1-03 PR 任务日志](https://github.com/Jdeciderz23/frame-and-say/actions/runs/45376567938/job/138384318086)。 | 保留 CI 作为远程证据，本机复现另记。 |
| MinIO | 本机 `MINIO_IMAGE` 未设置，`.env` 对应值为空，Docker 环境缺失。未拉取镜像、启动服务、验证私有桶或联调存储适配器。 | 先核验具体镜像及可用性，按 [W2-05 / #13](https://github.com/Jdeciderz23/frame-and-say/issues/13) 跟进。 |

## 五位成员的权限与启动记录

以 `zqjiejie` 登录读取 GitHub 的[协作者列表](https://api.github.com/repos/Jdeciderz23/frame-and-say/collaborators?affiliation=all)，五个预期账号均在有效列表中，并提供 `pull: true`、`push: true`。下表每个 PR 的来源仓库均为 `Jdeciderz23/frame-and-say`，可核对成员向同一仓库推送分支及创建 PR 的实际记录。邀请管理接口返回 `403`，邀请发出及接受的历史时间暂无法读取。已生效的成员身份和写入权限以协作者列表及同仓 PR 为证。

| 账号 | 当前角色 | 同仓 PR 证据 | 成员机器的构建或启动证据 |
|---|---|---|---|
| `Jdeciderz23` | `admin` | [#57](https://github.com/Jdeciderz23/frame-and-say/pull/57) | PR 记录 macOS 下前后端构建、浏览器连接正常及 H2 readiness。 |
| `kinosakuraxuan` | `write` | [#50](https://github.com/Jdeciderz23/frame-and-say/pull/50) | PR 记录 Node 24.16.0 下前端类型检查、6 项测试和构建；待补本机后端启动及代理 HTTP 实测。 |
| `lzndXJ` | `write` | [#58](https://github.com/Jdeciderz23/frame-and-say/pull/58) | PR 记录 Windows 下前后端构建、H2 demo JAR 启动及后端三个健康端点；待补同次 Vite 代理实测。 |
| `toepyue` | `write` | [#18](https://github.com/Jdeciderz23/frame-and-say/pull/18) | PR 记录完整 `./scripts/check.sh` 通过；待补本机前后端启动及健康请求。 |
| `zqjiejie` | `write` | [#56](https://github.com/Jdeciderz23/frame-and-say/pull/56) | 本文记录 Windows 下的前后端构建、H2 demo 启动及 Vite 代理实测。 |

成员机器一栏引用各 PR 的实际报告。上述待补项由对应成员运行[本机复现](#本机复现)中的 HTTP 检查，并把命令和结果补到 #8，由 `zqjiejie` 汇总。若需要核对邀请历史或经典保护规则的全部设置，由仓库所有者在 Settings 核对并补充证据。

## 主线保护、评审与 CI

- [当前 `main` 分支接口](https://api.github.com/repos/Jdeciderz23/frame-and-say/branches/main) 返回 `protected: true`。经典保护摘要显示 `backend`、`frontend`、`mysql-compose` 是必需检查，执行范围为 `everyone`。完整保护配置接口对此账号不可见，未独立核对该接口中的其他细项。
- [规则集 27667734](https://github.com/Jdeciderz23/frame-and-say/rules/27667734) 为 `active`，应用到默认分支。对 `main` 查询到的生效规则要求通过 PR、至少一位批准且需要代码所有者评审、新推送会使旧批准失效，并禁止删除及非快进推送。[主线 CODEOWNERS](https://github.com/Jdeciderz23/frame-and-say/blob/bd2c171b246a732e833d7db94e3507b145ddf151/.github/CODEOWNERS) 指向 `@Jdeciderz23`。
- 代码所有者 `Jdeciderz23` 已批准 `zqjiejie` 的 [PR #56 当前提交](https://github.com/Jdeciderz23/frame-and-say/pull/56#pullrequestreview-6037823411)，该 PR 在核查时仍为开放状态。`toepyue` 的 [PR #18](https://github.com/Jdeciderz23/frame-and-say/pull/18) 在[代码所有者批准](https://github.com/Jdeciderz23/frame-and-say/pull/18#pullrequestreview-6017282112)后合并，产生本次复现的主线提交。
- 主线 `bd2c171` 的 [`CI` 运行](https://github.com/Jdeciderz23/frame-and-say/actions/runs/45122811305)三项必需任务均成功，[`push-policy`](https://github.com/Jdeciderz23/frame-and-say/actions/runs/45358241130) 也成功。未合并的 [#58 CI](https://github.com/Jdeciderz23/frame-and-say/actions/runs/45376567938) 三项任务同样成功。`pr-policy` 的定位见[协作规范](../CONTRIBUTING.md)。

Codex 参与执行本机构建、HTTP 断言、GitHub 只读核查和文档整理。此记录供 #8 的人工核查和评审使用，仍待汇总剩余成员的本机启动证据。
