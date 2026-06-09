# 修复系统托盘右键菜单中文显示为方块（□）问题

## 根因分析

**核心原因：JEP 400（JDK 18+）导致 AWT 字体回退链断裂**

JDK 18 开始（本项目使用 JDK 21），`file.encoding` 默认值从 `GBK` 改为 `UTF-8`（JEP 400）。
AWT 字体系统根据 `file.encoding` 的值查找对应的字体序列，但 JDK 的 `fontconfig.properties.src`
中 **没有为 UTF-8 字符集配置中文字体序列**，只有 GBK 的有。

```
file.encoding = UTF-8 (JEP 400 默认值)
    ↓
AWT 查找 sequence.allfonts.UTF-8.zh  → 不存在！
AWT 查找 sequence.allfonts.UTF-8     → 不存在！
    ↓
回退到 sequence.allfonts = alphabetic, dingbats, symbol（无中文字体）
    ↓
GraphicsEnvironment.getAvailableFontFamilyNames() 不返回中文字体
    ↓
AWT PopupMenu 渲染中文时无对应 glyph → 显示为方块 □
```

**为什么你之前的 8 种尝试都失败了：**
- 方案 1~6：都是在设置 Java 层面的 Font，但 AWT PopupMenu 在 Windows 上是**原生重量级组件**，Font 设置对其无效（或 AWT 已初始化太晚）
- 方案 7：成功复现了问题（`getAvailableFontFamilyNames()` 找不到中文字体）
- 方案 8：`Font.createFont()` 能加载物理字体，但 AWT PopupMenu 不用它渲染

---

## 修复方案（推荐）

### 方案：在 classpath 中放置自定义 `fontconfig.properties`

Java AWT 字体系统启动时会从 classpath 加载 `fontconfig.properties`，覆盖 JRE 自带配置。
我们只需要在项目中添加这个文件，为 UTF-8 字符集补上中文字体序列。

#### 步骤 1：创建 `src/main/resources/fontconfig.properties`

从 Oracle JDK 21 的 `fontconfig.properties.src` 复制模板，在 Search Sequences 区域添加 UTF-8 中文字体序列。

**获取模板的命令**（在 bash 中执行）：
```bash
# 复制 Oracle JDK 21 的字体配置模板到项目资源目录
cp "/c/Program Files/Java/jdk-21.0.11/lib/fontconfig.properties.src" \
   "G:/桌面/CODE/Java/Screen-Time-Management/src/main/resources/fontconfig.properties"
```

> 如果 JDK 安装路径不同，请先确认实际路径（通常在 `C:\Program Files\Java\jdk-21.x.x\lib\fontconfig.properties.src`）
>
> 注意：复制后文件名为 `fontconfig.properties`（去掉 `.src` 后缀），AWT 才能正确读取。

**关键修改**：在文件的 Search Sequences 区域（约 199 行附近），在现有 UTF-8 序列后添加：

```properties
# 添加 UTF-8 中文字体序列（JEP 400 后缺失的部分）
sequence.allfonts.UTF-8.zh=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb
sequence.allfonts.UTF-8=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb

sequence.dialog.UTF-8.zh=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb
sequence.dialog.UTF-8=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb

sequence.sansserif.UTF-8=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb
sequence.serif.UTF-8=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb
sequence.monospaced.UTF-8=chinese-ms936,alphabetic,dingbats,symbol,chinese-ms936-extb
```

同时把 `sequence.fallback` 中的中文字体**前置**（让 fallback 优先级更高）：

```properties
sequence.fallback=chinese-ms936,chinese-gb18030,chinese-ms950,\
                  symbols,chinese-hkscs,chinese-ms950-extb,chinese-ms936-extb,\
                  japanese,korean-fallback
```

#### 步骤 2：确认 `fontconfig.properties` 打包到正确位置

`pom.xml` 中已配置 `<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>`，
`src/main/resources/fontconfig.properties` 会被 Maven 自动打包到 JAR 根目录，无需额外配置。

#### 步骤 3：无需修改 Java 代码

`App.java` 中的 PopupMenu 创建代码**保持原样**即可，AWT 会自动使用 classpath 中的字体配置。

---

## 备选方案

### 备选 1：改用 Swing `JPopupMenu`（彻底避开 AWT 原生组件）

用 `javax.swing.TrayIcon`（Java 6+ 支持）配合 Swing 的 `JPopupMenu`，
Swing 菜单是纯 Java 渲染，字体完全可控。

**需要修改的文件**：`App.java` 第 90~141 行（重写 `setupTray()` 方法）

### 备选 2：临时验证用 `-Dfile.encoding=GBK`

在 `launch.json` 的 `vmArgs` 中添加 `-Dfile.encoding=GBK`，
恢复 JDK 17 及以前的行为，**仅用于验证根因**，不推荐生产使用。

---

## 需要修改的文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/resources/fontconfig.properties` | **新建** | 从 JDK 模板复制并添加 UTF-8 中文字体序列 |
| `App.java` | 无需修改 | 方案 1 不需要改代码 |
| `launch.json` | 无需修改 | 不需要额外 JVM 参数 |

---

## 验证步骤

1. 创建 `src/main/resources/fontconfig.properties`
2. 通过 IDE（VSCode）运行 `Debug with JavaFX` 启动应用
3. 确认系统托盘右键菜单中文正常显示
4. 如果用 `mvn javafx:run` 启动，同样验证
5. （可选）打包后验证 `java -jar xxx.jar` 的中文显示

---

## 其他可能原因（如方案 1 不生效，再排查这些）

| # | 可能原因 | 验证方法 |
|---|---------|---------|
| 1 | Windows 系统区域设置非中文，系统菜单字体本身不支持中文 | 检查「设置→时间和语言→语言和区域→管理语言设置→非 Unicode 程序的语言」 |
| 2 | JDK 安装不完整，缺少 `lib/fonts/` 目录下的中文字体配置文件 | 检查 `%JAVA_HOME%/lib/fonts/` 是否存在 |
| 3 | 使用了精简版/嵌入式 JRE（如 jlink 自定义 JRE 时排除了 `jdk.font` 模块） | 当前是开发阶段，暂不涉及 |
| 4 | Oracle JDK 的 `fontconfig` 实现与 OpenJDK 不同，存在 Bug | 尝试切换到 Eclipse Temurin JDK 21 验证 |
