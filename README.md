# Screen-Time-Management

一个基于 JavaFX 的 Windows 屏幕使用时间管理工具，用于记录前台应用的使用时长，并提供应用监控、历史记录、统计图表和基础设置功能。

## 功能概览

- 监控当前前台窗口对应的应用进程
- 记录应用使用时长，并写入本地 SQLite 数据库
- 管理需要关注的应用列表
- 查看历史使用记录和统计数据
- 支持关闭到托盘、窗口尺寸保存等基础设置
- 提供开机自启相关工具逻辑

## 技术栈

- Java 21
- JavaFX 21
- Maven
- SQLite
- JNA（调用 Windows 原生 API 获取前台窗口信息）
- JUnit 5

## 环境要求

- Windows 系统
- JDK 21
- Maven 3.8 或更高版本
- 推荐使用 IntelliJ IDEA 打开项目

> 注意：项目依赖 Windows 前台窗口 API，核心监控功能不适合直接在 macOS 或 Linux 上运行。

## 快速运行

在项目根目录执行：

```bash
mvn clean javafx:run
```

推荐使用上面的 Maven 命令启动项目。不要在 IntelliJ IDEA 的 VM options 里手写本机 Maven 仓库路径，例如 `C:\Users\...\ .m2\...` 这类 `--module-path`；这些路径只在某一台电脑上有效，换电脑后会失效。

如果使用 IntelliJ IDEA，推荐从 Maven 工具窗口运行：

```text
Plugins -> javafx -> javafx:run
```

或者在运行配置中选择 Maven，命令填写：

```text
clean javafx:run
```

如果只想先确认项目能否编译和测试：

```bash
mvn test
```

打包生成 JAR：

```bash
mvn package
```

打包后会生成：

```text
target/screen-time-management-1.0-SNAPSHOT.jar
target/screen-time-management-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## 项目结构

```text
src/
├── main/
│   ├── java/com/screentime/
│   │   ├── controller/   # JavaFX 控制器
│   │   ├── dao/          # SQLite 数据访问
│   │   ├── model/        # 数据模型
│   │   ├── service/      # 前台应用监控服务
│   │   └── util/         # 数据库、时间、图标、Windows API 等工具
│   └── resources/
│       ├── database/     # SQLite 表结构脚本
│       ├── fxml/         # JavaFX 页面
│       └── icon.png      # 应用图标
└── test/
    └── java/com/screentime/ # 单元测试
```

## 数据存储

项目使用 SQLite。本地数据库会在运行时自动创建：

```text
%USERPROFILE%/ScreenTime/screentime.db
```

数据库表结构定义在：

```text
src/main/resources/database/schema.sql
```

## 常用命令

```bash
# 运行测试
mvn test

# 编译并打包
mvn package

# 启动 JavaFX 应用
mvn javafx:run
```

## 常见问题

### 1. Maven 下载依赖失败

检查网络、Maven 配置和本地仓库权限。首次运行需要下载 JavaFX、SQLite JDBC、JNA、JUnit 等依赖。

### 2. Java 版本不匹配

项目使用 Java 21。请确认：

```bash
java -version
mvn -version
```

### 3. 监控功能没有正常工作

该项目依赖 Windows 原生 API 获取前台窗口信息。请确认应用运行在 Windows 环境，并且没有被安全软件拦截。

## 开发说明

- 入口类：`com.screentime.Main`
- JavaFX 主应用类：`com.screentime.App`
- 前台应用监控逻辑：`com.screentime.service.ForegroundMonitorService`
- 数据库初始化逻辑：`com.screentime.util.DatabaseUtil`
- Windows 原生 API 封装：`com.screentime.util.WindowsNativeUtil`

提交代码前建议至少运行：

```bash
mvn test
```
