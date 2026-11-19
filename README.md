# 合照都满意

基于多用户偏好与公平性约束的协同选片系统。5 人本科课程设计，面向 5–10 人、每场不超过 100 张照片的小型活动。

当前是**项目启动脚手架**：具备 Vue 主页、健康检查页面、Spring Boot 健康 API、基础数据库连接配置及 CI。活动、用户权限、图片、发布授权和推荐业务均尚未实现；这里只安排项目第一、二周的工作。

## 技术与边界

- Vue 3 + TypeScript + Vite + Vue Router；Node.js 24、pnpm 11.25.0。
- Spring Boot 3.5 + Java 21 编译目标 + Maven Wrapper；按业务包划分的单体服务。
- MySQL 8.4；H2 仅用于无 Docker 的启动演示和独立测试。
- 图片走私有 `FileStorage` / S3 适配边界。MinIO 镜像来源待验证，单独的 Compose overlay 不影响基础启动。
- REST `/api`，Vite 开发代理 / Nginx 容器代理；当前不引入微服务、Redis、消息队列或独立 Python 算法服务。
- 喜好不代表发布授权。推荐必须满足出镜成员对指定照片版本及用途的授权；图片变更必须重新确认。规则详见 `docs/` 的待确认业务设计。

## 快速启动：无需 Docker

准备 Git、Java 21（当前本机也可使用 Java 25）、Node.js 24、pnpm 11.25.0。Maven 由 Wrapper 提供，首次需要网络。没有 pnpm 时，在常规 Node 环境使用 `corepack enable` 和 `corepack prepare pnpm@11.25.0 --activate`，或 `npm install --global pnpm@11.25.0`。

```sh
./scripts/check.sh
./scripts/dev.sh
```

打开 <http://127.0.0.1:5173>，进入健康检查页验证前端通过代理访问后端。直接访问 <http://127.0.0.1:8080/api/health> 应返回：

```json
{"status":"UP","application":"group-photo-consensus"}
```

`/actuator/health/readiness` 同时检查当前数据库是否可连接。`/api/health` 仅用于存活检查，不能代表 MySQL/图片服务已就绪。默认 `demo` 配置连接内存 H2，不存储业务数据，也不代表已经验证 MySQL。

按 Ctrl+C 会停止脚本启动的两个服务。5173/8080 已占用时脚本报错退出，不终止已有进程。Mac/Linux 使用这些 shell 脚本；Windows 建议 WSL 或分别在 IDE/终端启动。脚本会优先使用系统工具，必要时识别 Codex 已安装的 bundled Node/pnpm；这是本机便利措施，其他成员仍应按上述版本安装环境。

## 使用 MySQL 开发

安装并启动 Docker Desktop（或兼容 Docker Engine + Compose），然后：

```sh
./scripts/init-env.sh
docker compose up -d mysql
docker compose ps
./scripts/dev.sh --local
```

`init-env.sh` 生成仅本地保存的随机密码，已有 `.env` 会保留。`--local` 从 `.env` 读取数据库变量并切换至 MySQL，数据库不可用时 readiness 应失败。初次连通后，业务表迁移由第二周任务评审确定；当前没有业务建表脚本。

修改 MySQL 密码不会自动修改已有数据卷中的数据库密码，需通过 MySQL 正常管理流程同步，勿直接删除数据卷。停止基础服务使用 `docker compose stop`，本仓库不提供自动删卷脚本。

可选全容器启动（**本机尚未验证**）：

```sh
docker compose --profile app up --build
```

MinIO 单独配置：在 `.env` 填入来源和版本已经团队审核的 `MINIO_IMAGE`，然后执行：

```sh
docker compose -f docker-compose.yml -f docker-compose.storage.yml up -d mysql minio
```

不预填未经验证的第三方镜像。创建 bucket 时保持私有，不允许匿名下载；第二周先以本地私有文件适配器和合成图片验证接口。镜像拉取、存储联调和 bucket 初始化均仍待完成。

## 独立开发与验证

```sh
# 前端：独立终端运行；后端未启动时，健康页会明确显示连接失败
pnpm --dir frontend dev
pnpm --dir frontend test
pnpm --dir frontend build

# 后端：独立终端运行，默认 demo
./scripts/maven.sh spring-boot:run
./scripts/maven.sh -B verify
```

构建缓存位于忽略的 `.cache/`；依赖锁文件随代码提交。CI 使用 Java 21 与 Node 24，检查前端测试/类型/构建和后端集成测试。Docker 配置、CI 文件的存在不等于已在线运行，实际验证记录见 `docs/验证记录.md`。

## 目录

```text
frontend/                   Vue 主页、健康检查、请求封装和测试
backend/                    Spring Boot、数据库配置、集成测试、Maven Wrapper
docs/                       启动背景、调研、用例、架构、分工、两周计划和风险
scripts/                    启动、检查、生成本地环境、Issue 发布工具
.github/                    CI、Issue/PR 模板、待应用的保护规则
docker-compose.yml          MySQL + 可选前后端容器
docker-compose.storage.yml  待确认镜像后的可选 MinIO
.env.example                配置样例；没有真实密码
```

文档索引见 [项目启动文档](docs/README.md)。文档为待团队确认的启动草案，不能替代真人调研或教师审批；`docs/issues.json` 是可复制的任务源。未创建大量空模块，业务代码由对应成员在第二周各自提交。

## GitHub 连接与团队协作

仓库名 `group-photo-consensus` 简洁且符合项目含义，默认私有。本地 `main` 已完成启动工程基础提交；远程仓库、Issues、成员权限和分支保护尚未创建或开启。连接器可识别 `Jdeciderz23`，但没有创建仓库能力；本机未安装 `gh`，浏览器创建入口需要登录。尝试下载官方 CLI 因网络超时未完成。

安装 GitHub CLI 后，由本人在终端登录（不要把 token 发到聊天或写入仓库）：

```sh
gh auth login --hostname github.com --git-protocol https --web
gh auth status
gh api user --jq .login
```

确认显示的账号是目标所有者；以下命令假定使用个人账号 `Jdeciderz23`。先查询名称，404 也可能是权限不足，须在登录后确认；如果已有同名仓库，停止并核实，不能覆盖：

```sh
gh repo view Jdeciderz23/group-photo-consensus
# 确认目标不存在，且本地已有首个提交后再执行
gh repo create Jdeciderz23/group-photo-consensus --private --source=. --remote=origin --push
git remote -v
```

如已手动创建了**空的**远程仓库，使用下面两条替代 `gh repo create`，不要两种方式都执行：

```sh
git remote add origin https://github.com/Jdeciderz23/group-photo-consensus.git
git push -u origin main
```

远程创建并推送成功后发布两周 Issues（可重复执行，按任务编号去重）：

```sh
node scripts/publish-issues.mjs
node scripts/publish-issues.mjs --repo Jdeciderz23/group-photo-consensus --apply
```

在仓库 Settings → Collaborators 邀请其余四人的真实账号，成员接受后设置 Issue Assignees；不要对未知账号发送邀请。成员编号与账号映射待团队确认。

CI 首次成功后，设置 main：必须通过 PR、至少一名他人批准、`frontend`/`backend` 检查通过、禁止 force push/删除、解决所有评审对话。可在 Settings → Branches / Rules 配置，或由管理员执行：

```sh
gh api --method PUT repos/Jdeciderz23/group-photo-consensus/branches/main/protection --input .github/branch-protection.json
```

私有仓库的强制保护能力取决于账号套餐。若 API 返回不支持，记录阻塞并与教师确认认可的私有平台或已有学生权益；不要擅自公开仓库。无法强制时，书面约定只能作为临时纪律，不能标记“已开启保护”。

每项任务先建 Issue，再从最新 main 开功能分支；使用 `feat(events): ...` 等提交说明，通过 PR 与评审合并。详细流程、模块路径和评审轮换见 `docs/`。本次 AI 生成的脚手架不应被拆分冒充五位成员独立完成的成果；每位成员需要理解、修改、测试并提交自己的真实代码。
