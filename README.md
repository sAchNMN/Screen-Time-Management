# Screen-Time-Management - 屏幕使用时间管理

## 环境要求
- **JDK:** 21 或以上
- **Maven:** 3.8+
- **IDE:** IntelliJ IDEA（推荐，已附带 .idea 配置）

## 快速启动
1. 用 IntelliJ IDEA 打开项目根目录
2. 等待 Maven 自动下载依赖（或在终端执行 `mvn clean package`）
3. 运行入口类：`com.screentime.App`

## 项目结构
```
src/
├── main/
│   ├── java/com/screentime/   ← 业务逻辑代码
│   └── resources/             ← 界面 FXML、图标、数据库 Schema
└── test/java/com/screentime/  ← 测试代码
```

## 数据库
- 使用 SQLite，运行时自动在 `%userprofile%/ScreenTime/screentime.db` 创建
- DDL 脚本位于 `src/main/resources/database/schema.sql`

## 注意
- 首次运行会自动创建数据库和目录，无需手动配置
- JavaFX 通过 Maven 依赖引入，不依赖 JDK 内置 JavaFX
