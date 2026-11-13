# JAR 镜像、Nginx 与 Portainer

这是 Windows Docker Desktop 的本机部署练习。业务入口为 http://127.0.0.1:18080，管理入口为 https://127.0.0.1:9443。业务使用 local 环境和 HTTP；对外上线需要配置域名、HTTPS 和 prod 环境。

## 架构与文件

请求经过 Nginx，再以轮询方式分发给同一镜像创建的多个 backend 容器。后端共享 PostgreSQL 和 Redis。Portainer 通过本机 Docker socket 管理整个应用 Stack；管理端口只绑定本机地址。

- compose.yaml：现有 PostgreSQL、Redis 和命名卷。
- compose.image.yaml：JAR 应用镜像、环境变量、默认实例数。
- compose.gateway.yaml：关闭后端宿主机端口，增加 Nginx、独立代理网络和可信代理设置。
- compose.portainer.yaml：独立管理面，保存 Portainer 账号与 Stack 元数据。
- deploy/nginx/：Nginx 镜像与负载均衡配置。
- deploy/Start-ManagedDeployment.ps1：构建网关镜像、启动 Portainer、注册本机环境并创建/更新应用 Stack。
- deploy/Test-LoadBalance.ps1：检查真实响应来自多少个后端，可选验证跨实例共享 CSRF。

## 首次启动

需要 PowerShell 7、运行中的 Docker Desktop、已配置的 .env，以及本机应用镜像 after-sales-agent:1.0.0。已有 JAR 镜像可以直接运行：

~~~powershell
cd A:\A_workSpace\AgentProjects\after-sales-agent
pwsh -File .\deploy\Start-ManagedDeployment.ps1
pwsh -File .\deploy\Test-LoadBalance.ps1 -CheckSharedCsrf
~~~

没有应用镜像时，先在 backend 执行 mvn clean package，再把可执行 JAR 复制成 deploy/image/app.jar，执行 docker build -t after-sales-agent:1.0.0 deploy/image。Dockerfile 的 EXPOSE 只是元数据，应用实际监听 8080。

脚本只合并 compose.yaml、compose.image.yaml、compose.gateway.yaml，不合并开发用的 compose.java.yaml。应用不再挂载源码，也不执行 Maven。

脚本生成随机管理员初始密码，保存在 data/local/portainer-admin.password；该目录已被 .gitignore 排除。它通过 Portainer 官方支持的 --admin-password-file 初始化 admin，不关闭初始化保护，也不在终端输出密码。首次登录后可在 Portainer 中修改密码；如果修改后要重新运行初始化脚本，需要同步该本机密码文件。

~~~powershell
Get-Content .\data\local\portainer-admin.password
~~~

Portainer 默认使用自签名证书，本机首次访问可能显示证书提示。脚本只对本机 Portainer API 跳过该证书验证。

现有 postgres-data 和 redis-data 会按原名称复用，在 Portainer Stack 中标记为 external，不复制、不清空数据库。未启用的 postgres-eval 不注册到本业务 Stack。

## 在网页中管理

1. 打开 https://127.0.0.1:9443，以 admin 和本机密码文件中的密码登录。
2. 选择本机 Docker environment，进入 Stacks，选择 after-sales-agent（若配置了其他项目名，则使用该名称）。
3. Containers 可以查看各实例的状态、日志、统计、控制台，并执行停止/重启。
4. Stack 的 Environment variables 中，APP_REPLICAS 是实例数，APP_IMAGE 是 JAR 镜像版本。
5. 修改变量后选择 Update the stack。当前镜像保存在本机，不勾选强制重新拉取镜像；模板的 pull_policy: never 也会阻止尝试从公共仓库拉取本机镜像。
6. APP_REPLICAS 改成 4 后，用测试脚本的 -ExpectedInstances 4 重新验证；改回 3 则缩容。

应用由 Portainer 创建为可编辑 Stack，而不只是显示为外部 Compose 项目。编辑器内容是合并后的 Compose JSON，与 YAML 等价；实际密码作为 Environment variables 传入，不写入编辑器模板。安全模板另存 data/local/portainer-stack.template.json。不要同时通过另一套 CLI 配置更新同一 Stack；CLI 修改不会自动更新 Portainer 保存的定义。重新运行初始化脚本则会明确用本机配置更新 Stack。

## 发布镜像与回退

重新打包 JAR、复制到 deploy/image/app.jar，构建新的版本：

~~~powershell
docker build -t after-sales-agent:1.0.1 deploy/image
~~~

在 Portainer 中把 APP_IMAGE 改成 after-sales-agent:1.0.1 并更新 Stack。保留旧镜像时可改回 1.0.0；数据库结构变更需要单独考虑兼容性。单机 Compose 更新不保证零停机或滚动发布。

若修改 nginx.conf，需要构建一个新网关版本，例如 docker build -t after-sales-agent-gateway:1.0.1 deploy/nginx，再修改 GATEWAY_IMAGE。仅修改宿主机上的配置文件不会改变已构建镜像。

## 如何确认分流和会话

访问 /actuator/health 检查后端健康。响应头 X-Upstream-Addr 显示实际后端 IP:8080；18 次请求应观察到 3 个地址。/gateway-healthz 只检查 Nginx 进程，不代表后端正常。

~~~powershell
pwsh -File .\deploy\Test-LoadBalance.ps1 -ExpectedInstances 3 -CheckSharedCsrf
~~~

可选的 CSRF 检查在实例 A 获取 token 和 Cookie，再向实例 B 提交一个随机不存在的账号。预期是通过 CSRF 后返回 401（认证失败），而不是 403。此检查不验证真实账号登录成功；它会消耗一次登录限流额度。

Nginx 通过 Docker DNS 动态解析 backend，解析有效期 5 秒。扩缩容后允许几秒钟更新；不会为 3 个实例固定填写 IP。Nginx 的 172.30.67.10 是独立代理网络中的固定入口地址，后端仅信任这个代理，Nginx 覆盖外部提交的转发头。若此子网与其他网络重叠，需要同时修改 compose.gateway.yaml 的子网、固定 IP 和可信代理表达式。

本机演示保留 X-Upstream-Addr 便于观察；对外部署可移除该诊断响应头。当前登录使用共享 Redis Session；这不代表尚未实现的文档处理和 worker 已完成集群化。

## 停止与保留数据

在 Portainer 中停止 Stack 即可停止业务；管理面保持可用。删除 Stack 前核对卷设置，数据库和 Redis 使用 external 命名卷。不要执行 docker volume prune 或 down -v 来代替日常停止。

官方参考：
- https://docs.portainer.io/advanced/cli
- https://docs.portainer.io/user/docker/stacks/add
- https://nginx.org/en/docs/http/ngx_http_upstream_module.html

## 本机验证记录（2026-11-13）

- Portainer CE 2.45.3 已启动，本机 Docker environment 中的 after-sales-agent 为可管理 Stack，ID 为 1。
- Windows 排除端口范围 8016—8115 包含 8080；实际入口已改成 18080，并加入部署前的绑定检查。管理入口为 9443。
- 三实例真实请求：18 次健康请求均为 UP，3 个后端各处理 6 次。
- 跨实例 CSRF：在一个后端获取 Cookie/token，另一个后端通过 CSRF 校验后对随机不存在账号返回预期 401。未测试真实账号登录成功。
- 通过 Portainer API 扩到 4 个实例：24 次请求分到 4 个后端，各处理 6 次，无需修改 Nginx 配置或手动重载。测试后恢复为 3 个实例。
- nginx -t 通过。测试和等待脚本兼容 Actuator 的 application/vnd.spring-boot.actuator.v3+json 字节数组响应，并直接访问本机、绕过系统代理。
- 本次复用已有 after-sales-agent:1.0.0 JAR 镜像，没有重新构建 Java 源码，也没有验证云端部署、真实模型或完整文档/worker 集群功能。
