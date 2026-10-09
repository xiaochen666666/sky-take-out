# AGENTS.md — 苍穹外卖项目协作指南

本文件适用于本仓库及其子目录，供 AI 在开始任务时读取。所有路径默认相对于仓库根目录；若子目录存在更具体的 AGENTS.md，则同时遵循其适用范围内的约定。结构与配置核对日期：2026-10-09；后续以当前源码和实际运行环境为准，不把本文当成测试通过证明。

## 1. 先掌握仓库边界

这是苍穹外卖学习项目的 Java 后端，采用 Maven 多模块结构，根目录包含 `pom.xml`。

| 位置 | 职责 |
| --- | --- |
| `sky-common/src/main/java/com/sky/` | 公共常量、上下文、异常、配置属性、响应结果与工具 |
| `sky-pojo/src/main/java/com/sky/` | entity 实体、DTO 请求/传输对象、VO 响应对象 |
| `sky-server/src/main/java/com/sky/` | 启动入口、接口、业务、数据访问、拦截器、定时任务、WebSocket |
| `sky-server/src/main/resources/mapper/` | MyBatis XML SQL |
| `sky-server/src/main/resources/application.yml` | 公共配置、环境选择、配置占位符 |
| `sky-server/src/main/resources/application-dev.yml` | 开发环境配置，读取时避免泄露凭据 |
| `sky-server/src/main/resources/template/运营数据报表模板.xlsx` | 运营报表 Excel 模板 |

本机父目录还存在 `../project-sky-admin-vue-ts/`（Vue 2 + TypeScript + Element UI 管理端）和 `../mp-weixin/`（微信小程序）。它们不属于此 Git 仓库；从 GitHub 克隆后不能假设它们存在。联调时先确认实际目录和部署位置。

## 2. 最短阅读路线

1. 查看 `git status --short`、根 `pom.xml` 与 `sky-server/pom.xml`，确认当前改动、依赖和模块。
2. 阅读 `sky-server/src/main/java/com/sky/SkyApplication.java` 与配置文件，了解启动和外部依赖；不输出密码、密钥、令牌。
3. 阅读 `config/WebMvcConfiguration.java` 和 `interceptor/`，确认接口分组、JWT 校验和登录用户上下文。
4. 根据用户问题定位具体 Controller，再沿 `Controller → Service → service/impl → Mapper 接口 → mapper XML` 阅读；同步核对 `sky-pojo` 中相关 DTO、VO 和实体。
5. 只检查问题相关代码，不遍历依赖、编译产物和整个前端构建目录。

上面第 3、4 步的 Java 短路径相对于 `sky-server/src/main/java/com/sky/`。SQL 可能直接写在 Mapper 注解中，也可能位于 XML，两者都要检查。

快速检索（在仓库根目录执行）：

```powershell
git status --short
rg --files sky-server/src sky-common/src sky-pojo/src -g '!**/target/**'
rg -n '关键词或方法名' sky-server/src/main sky-common/src/main sky-pojo/src/main
```

## 3. 按业务定位

以下路径相对于 `sky-server/src/main/java/com/sky/`：

| 问题 | 优先入口 |
| --- | --- |
| 员工、分类、菜品、套餐管理 | `controller/admin/` 中对应 Controller，以及同名 Service/Mapper |
| 用户登录、地址、商品浏览、购物车 | `controller/user/` 中对应 Controller |
| 下单、支付、取消、退款、再来一单 | 两端 `OrderController`、`service/impl/OrderServiceImpl.java`、`mapper/OrderMapper.java` |
| 支付回调 | `controller/PayNotifyController.java` |
| 营业状态 | 两端 `ShopController` 与 Redis 配置 |
| 工作台、统计、Top10、Excel 导出 | `controller/admin/WorkSpaceController.java`、`ReportController.java`，以及 `WorkspaceServiceImpl`、`ReportServiceImpl` |
| JWT 与当前用户 | `interceptor/JwtTokenAdminInterceptor.java`、`JwtTokenUserInterceptor.java`；公共模块 `context/BaseContext.java` |
| 公共审计字段自动填充 | `annotation/AutoFill.java`、`aspect/AutoFillAspect.java` 和 Mapper 上的注解 |
| 来单提醒与催单 | `websocket/WebSocketServer.java`、订单业务；WebSocket 路径 `/ws/{sid}` |
| 超时订单与定时处理 | `task/OrderTask.java`、`task/WebSocketTask.java` |
| 上传与异常响应 | `config/OssConfiguration.java`、`controller/admin/CommonController.java`、`handler/GlobalExceptionHandler.java` |

## 4. 技术栈和本地运行

根 POM 当前使用 Spring Boot 2.7.3、MyBatis Starter 2.2.0、Lombok 1.18.20、Apache POI 3.16；服务还依赖 MySQL、Redis，并集成 JWT、Knife4j、阿里云 OSS、微信支付。

本机历史兼容性经验是优先使用 JDK 11；JDK 25 曾与此 Lombok 版本发生编译问题。这是历史经验，不代表本次已重新编译验证。先执行 `java -version` 和 `mvn -version`，确认 Maven 实际使用的 JDK，勿仅查看 IDE 配置。

当前公共配置默认 `dev` 环境、端口 `8080`，Redis 数据库来自 `${sky.redis.database}`。管理端请求头名称为 `token`，用户端为 `authentication`。环境变量、启动参数和外部配置可能覆盖这些值。

在仓库根目录执行：

```powershell
# 构建并将依赖模块安装到本地 Maven 仓库；此命令跳过测试执行
mvn -pl sky-server -am install -DskipTests

# 确认 MySQL、Redis 和开发配置可用后启动服务
mvn -pl sky-server spring-boot:run
```

也可在 IDE 中运行 `SkyApplication`。启动前检查端口占用，避免重复启动。服务运行后检查 `http://localhost:8080/doc.html` 和 `http://localhost:8080/swagger-resources`；能打开文档不等于业务验证通过。

若同级管理端源码存在，可在其目录按锁文件安装依赖，再运行 `npm run serve` 或 `npm run build`。当前脚本包含 OpenSSL 兼容选项，修改前检查 `package.json`，不要顺手升级整个旧版依赖树。小程序在微信开发者工具中确认请求地址、登录、网络请求与页面表现。

## 5. 修改和验证规则

- 默认使用中文沟通。学习、解释、阅读请求先检查源码并讲清调用链；用户要求修复或实现时再做对应范围修改。
- 保留用户已有改动。提交前检查差异，只暂存本次相关文件；禁止为了方便执行硬重置、覆盖未知改动或强制推送。
- 遵循现有 Controller/Service/Mapper 分层和公共响应结构。修改接口时核对 DTO/VO、两端调用者、JWT 身份和 SQL 参数。
- 多表写操作检查事务；订单接口检查所属用户、订单状态、金额与重复请求。变更状态时使用实体已有常量。
- 支付业务存在模拟开关和真实支付分支，执行前核对实际配置。模拟支付成功不能称为真实扣款成功；不得擅自发起真实支付或退款。
- 不把数据库密码、OSS 密钥、微信凭据、JWT 或个人配置复制到文档、日志、提交和对话输出中。
- 数据库/Redis 验证使用可识别的临时数据，记录并清理自己创建的数据；不清空现有库或缓存。
- 执行测试前先阅读测试内容：现有示例可能依赖真实 Redis、网络和本机文件。`.gitignore` 忽略大部分测试文件，磁盘存在不代表 GitHub 克隆可获得。
- 按变更选择编译、针对性测试、接口或 UI 验证。`-DskipTests` 不能作为测试通过证据；后端成功也不能作为浏览器播放声音或小程序完整流程通过的证据。
- 交付时说明修改内容、验证方式与结果、尚未验证的部分；仅文档修改通常检查路径、内容和 Git 差异即可。

## 6. 常见排错路线

| 现象 | 检查顺序 |
| --- | --- |
| 改代码后没变化 | 实际 Java 进程和启动目录 → 活动 profile/端口 → `target/classes` 或运行 JAR → 重编译、重启后复测 |
| MyBatis 参数找不到 | Mapper 方法参数、`@Param`、Map 键与 XML 占位符是否一致，特别是日期范围的 begin/end 命名 |
| 审计字段为空 | JWT 解析 → BaseContext 当前用户 → AutoFill 注解/切面 → SQL 写入 |
| Redis 数据不对 | 实际运行服务的配置、认证、数据库编号、键值；不能仅凭源码中的 YAML 推断运行状态 |
| Excel 导出失败 | ReportServiceImpl 的 classpath 模板路径 → 模板是否进入构建产物 → 输入流与工作簿读取；旧版 POI 日志不一定是根因 |
| WebSocket 不通 | `/ws/{sid}` → 后端端口 → Nginx 实际配置与转发 → 浏览器握手和消息；声音另行验证 |
| 前端 404/500 或页面没更新 | 浏览器实际请求 URL/响应 → 代理 → 后端日志；检查实际被 Nginx 提供的静态目录 |

这些是检查路线，不是对当前版本仍有缺陷的断言。项目结构、构建方式或接口变化时，同步更新本文；统一维护根目录 `AGENTS.md`，不再使用单独的 `AGENT.md`。
