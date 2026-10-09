# 苍穹外卖 · Sky Take Out

基于 Spring Boot 的外卖业务后端学习项目，围绕商家管理和用户点餐，实践接口开发、数据库访问、缓存、订单处理、消息通知与运营报表。

本仓库包含 Java 后端。管理端 Vue 页面和微信小程序需单独准备，不包含在本仓库中。

## 功能概览

| 模块 | 源码覆盖的功能 |
| --- | --- |
| 商家管理 | 员工登录与管理、分类管理、菜品与套餐管理、营业状态设置 |
| 用户点餐 | 微信登录、地址簿、商品浏览、购物车、提交订单 |
| 订单业务 | 支付、历史订单、详情、取消、再来一单、催单及管理端订单处理 |
| 消息与任务 | WebSocket 来单与催单通知、订单定时处理 |
| 运营分析 | 工作台、营业额统计、用户统计、订单统计、销量 Top10 |
| 文件处理 | 阿里云 OSS 图片上传、基于 Excel 模板导出运营数据 |

功能列表按当前源码整理；完整运行需要数据库、Redis 及相应第三方服务配置。

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Spring Boot 2.7.3 | 应用启动、配置与 Web 接口 |
| MyBatis Starter 2.2.0 / MySQL | 数据访问与持久化 |
| Redis / Spring Cache | 缓存与营业状态存储 |
| JWT / 拦截器 | 登录校验与用户身份识别 |
| Spring AOP / Lombok 1.18.20 | 公共字段填充与简化代码 |
| WebSocket / Spring Scheduling | 实时消息与定时任务 |
| Knife4j | 接口文档与调试 |
| Apache POI 3.16 | Excel 报表处理 |
| 阿里云 OSS / 微信支付 | 图片存储与支付接入 |

## 项目结构

```text
sky-take-out/
├── pom.xml                     # Maven 父工程与依赖管理
├── sky-common/                 # 常量、异常、上下文、响应结果、工具类
├── sky-pojo/                   # Entity、DTO、VO
├── sky-server/
│   └── src/main/
│       ├── java/com/sky/
│       │   ├── SkyApplication.java
│       │   ├── controller/     # admin 商家接口 / user 用户接口
│       │   ├── service/        # 业务接口及实现
│       │   ├── mapper/         # 数据访问接口
│       │   ├── config/         # Web、Redis、OSS 等配置
│       │   ├── interceptor/    # JWT 登录校验
│       │   ├── annotation/     # 自定义注解
│       │   ├── aspect/         # 公共字段填充切面
│       │   ├── task/           # 定时任务
│       │   └── websocket/      # 消息推送
│       └── resources/
│           ├── application.yml
│           ├── application-dev.yml
│           ├── mapper/         # MyBatis XML
│           └── template/       # 运营数据报表模板
├── AGENTS.md                   # AI 项目导航与协作约定
└── README.md
```

典型调用链：`Controller → Service → Mapper → MySQL`。DTO 用于接收或传递参数，Entity 对应业务实体，VO 用于组织响应数据。

## 本地启动

### 1. 准备环境

- Java 与 Maven：本项目本地开发沿用 JDK 11，使用 `java -version` 和 `mvn -version` 确认实际版本。
- MySQL：准备与实体及 Mapper SQL 匹配的数据库表和初始数据。
- Redis：确认地址、端口、认证和数据库编号。
- 使用图片上传、微信登录、地图或真实支付时，准备对应服务配置。

**当前仓库没有纳入 Git 管理的数据库初始化 SQL。** 首次部署需另外准备配套建表与初始数据，不能只克隆代码就完成数据库初始化。

### 2. 获取代码并配置

```bash
git clone https://github.com/xiaochen666666/sky-take-out.git
cd sky-take-out
```

检查 [application.yml](sky-server/src/main/resources/application.yml) 与开发环境配置。默认启用 `dev`，后端端口为 `8080`；实际值可能被环境变量或启动参数覆盖。

| 配置项 | 需要核对的内容 |
| --- | --- |
| `sky.datasource.*` | 驱动、主机、端口、数据库名与账号 |
| `sky.redis.*` | 主机、端口、密码与数据库编号 |
| `sky.jwt.*` | 管理端和用户端 JWT 配置 |
| `sky.alioss.*` | OSS Endpoint、Bucket 与访问凭据 |
| `sky.wechat.*` | 小程序登录及支付所需配置 |
| `sky.shop.address`、`sky.baidu.*` | 店铺地址与地图服务配置 |
| `payment.mock-enabled` | 是否启用本地模拟支付 |

填写自己环境的配置，并通过本地配置或环境变量管理凭据；不要将密码、密钥和证书提交到仓库。相关配置会参与应用初始化，未使用某项业务也应检查其配置绑定是否完整。

### 3. 构建与运行

在仓库根目录执行：

```bash
# 构建各模块并安装到本地 Maven 仓库，跳过测试执行
mvn -pl sky-server -am install -DskipTests

# 启动后端
mvn -pl sky-server spring-boot:run
```

也可以在 IDE 中运行 [SkyApplication.java](sky-server/src/main/java/com/sky/SkyApplication.java)。启动前确认 MySQL、Redis 可访问，且 `8080` 端口未被其他进程占用。

### 4. 查看接口文档

启动成功后访问 [Knife4j 接口文档](http://localhost:8080/doc.html)。接口分组可通过 [Swagger 资源列表](http://localhost:8080/swagger-resources) 检查。

| 接口类型 | 路径 / 请求头 |
| --- | --- |
| 管理端接口 | `/admin/**`；登录后的 JWT 请求头为 `token` |
| 用户端接口 | `/user/**`；登录后的 JWT 请求头为 `authentication` |
| WebSocket | `/ws/{sid}` |

前端联调时需要单独设置请求地址及代理。文档页面可打开仅表示文档服务可访问，业务仍需结合登录状态和数据库数据验证。

## 模拟支付说明

订单业务提供 `payment.mock-enabled` 开关，可在本地开发配置中设置：

```yaml
payment:
  mock-enabled: true
```

模拟支付用于开发流程验证，不产生真实微信扣款，前端也需要识别模拟支付返回结果。真实支付需要独立配置商户信息、证书和回调地址；不能把模拟流程通过视为真实支付接入完成。

## 学习与维护

建议从员工或分类管理入手，沿 Controller、Service、Mapper 阅读，再学习 JWT 用户上下文、AOP 自动填充、Redis 缓存、订单事务、WebSocket 和报表导出。

- 修改 SQL 时同步核对 Mapper 参数与 XML 占位符。
- 修改后未生效时，检查实际运行进程、配置、编译产物和重启情况。
- Excel 导出依赖 `sky-server/src/main/resources/template/运营数据报表模板.xlsx`，打包时需保留该资源。
- 执行测试前检查其 Redis、网络和本机文件依赖；跳过测试的构建不能视为测试通过。

更详细的源码导航、验证方法和 AI 协作约定见 [AGENTS.md](AGENTS.md)。
