# Easy-DB 分布式 NoSQL 数据库系统

## 项目简介

Easy-DB 是一个基于 Java 语言实现的轻量级 NoSQL 数据库系统，采用 C/S 架构，支持单机和集群两种部署模式。底层使用 LSM-Tree 存储引擎，支持数据持久化、索引加速、文件 Rotate 压缩和基于类 Raft 协议的高可用集群。

---

## 工程重建注意事项

### 1. 开发环境要求

| 项目 | 版本要求 | 说明 |
|------|---------|------|
| **JDK** | **8**（1.8） | 必须使用 JDK 8，更高版本可兼容但未充分测试 |
| **Maven** | 3.3.0+ | 用于项目构建和依赖管理 |
| **操作系统** | Windows / Linux / macOS | 跨平台，无操作系统限制 |
| **IDE** | IntelliJ IDEA / Eclipse | 推荐使用 IntelliJ IDEA 2020+ 直接导入 Maven 工程 |

**JDK 环境变量检查**：

```bash
java -version
# 应输出类似: java version "1.8.0_xxx"

mvn -version
# 应输出 Maven 版本和使用的 JDK 版本
```

### 2. 第三方依赖

项目仅依赖 **1 个** 第三方包，Maven 会自动下载，无需手动配置：

| 依赖 | 版本 | 用途 |
|------|------|------|
| **fastjson** | **1.2.83** | 阿里巴巴 JSON 解析库，用于 List/Set/Hash 复杂数据类型的序列化与反序列化 |

> 请在 `pom.xml` 同级目录执行 `mvn clean package -DskipTests`，首次运行会自动下载 fastjson-1.2.83.jar 到本地 Maven 仓库。如果网络环境无法访问 Maven 中央仓库，请提前配置公司/学校内部的 Maven 镜像（修改 `~/.m2/settings.xml`）。

### 3. 配置文件

**本项目无外部配置文件**，所有参数均通过以下方式配置：

1. **`Constants.java`**（[src/main/java/com/easydb/common/constants/Constants.java](src/main/java/com/easydb/common/constants/Constants.java)）：硬编码端口、文件路径、编码等常量
2. **命令行参数**：启动时通过 `--port`、`--cluster` 等参数动态覆盖

> 无需创建任何 `.properties` 或 `.xml` 配置文件。数据自动存储在运行目录下的 `./data/` 文件夹中。

### 4. 端口占用说明

程序启动需要以下端口（确保未被其他程序占用）：

| 端口 | 默认值 | 用途 |
|------|--------|------|
| Socket 端口 | **8092** | 客户端命令行/Shell/GUI/SDK 连接 |
| HTTP 端口 | **8093** | REST API HTTP 接口 |
| 集群端口 | **8094** | 集群节点间心跳/选举/复制通信 |

如果需要修改端口，通过启动参数指定：

```bash
java -jar easy-db.jar --server --port 9000 --http-port 9001 --cluster-port 9002
```

### 5. 登录账号信息

**本项目无登录/认证机制**，客户端直接通过 Socket 或 HTTP 连接即可操作数据库，无需用户名和密码。

### 6. 工程导入步骤（IntelliJ IDEA）

1. 打开 IntelliJ IDEA，选择 `File → Open`
2. 选择项目根目录（即包含 `pom.xml` 的目录），点击 `OK`
3. 选择 `Open as Project`（不要选 `Open as File`）
4. IDEA 会自动识别 Maven 项目并开始下载依赖
5. 等待右下角进度条完成（首次可能需要 2-5 分钟下载 fastjson）
6. 确认 Project SDK 配置为 **JDK 1.8**：`File → Project Structure → Project → SDK`
7. 确保 Language Level 为 `8 - Lambdas, type annotations etc.`

### 7. 编译打包

在项目根目录（`pom.xml` 所在目录）打开终端，执行：

```bash
mvn clean package -DskipTests
```

编译成功后会生成两个 JAR 文件：

| 文件 | 路径 | 说明 |
|------|------|------|
| easy-db-1.0.0.jar | `target/` | 不含依赖，不能直接运行 |
| **easy-db-1.0.0-jar-with-dependencies.jar** | `target/` | **包含 fastjson 依赖，可直接运行** |

> 请使用 `easy-db-1.0.0-jar-with-dependencies.jar` 运行程序。

### 8. 运行方式

#### 启动服务器（单机模式）

```bash
java -jar target/easy-db-1.0.0-jar-with-dependencies.jar --server
```

#### 交互式命令行客户端

```bash
java -jar target/easy-db-1.0.0-jar-with-dependencies.jar --cli
```

#### 单次命令执行

```bash
java -jar target/easy-db-1.0.0-jar-with-dependencies.jar --shell SET name zhangsan
java -jar target/easy-db-1.0.0-jar-with-dependencies.jar --shell GET name
```

#### GUI 图形界面

```bash
java -jar target/easy-db-1.0.0-jar-with-dependencies.jar --gui
```

#### HTTP API 调用

```bash
curl -X POST -d "SET name zhangsan" http://localhost:8093
curl -X POST -d "GET name" http://localhost:8093
```

#### 集群模式

```bash
# 终端1 - 启动主节点
java -jar target/easy-db-1.0.0-jar-with-dependencies.jar --server --cluster --node-id node-1

# 终端2 - 加入从节点（注意端口不能冲突）
java -jar target/easy-db-1.0.0-jar-with-dependencies.jar --server --cluster --node-id node-2 --join localhost:8094 --port 8095 --http-port 8096
```

### 9. 数据存储位置

所有数据存储在**运行命令的当前工作目录**下的 `./data/` 文件夹中，目录结构如下：

```
data/
├── easy-db.wal          # WAL 预写日志
├── index.dat            # 全局稀疏索引文件
└── sstables/
    ├── level-0/         # LSM-Tree 第0层 SSTable（.sst + .idx）
    ├── level-1/         # 第1层
    └── level-2/         # 第2层（.sst.gz 压缩）
```

> 建议始终在项目根目录下运行 `java -jar` 命令，避免数据散落在不同目录。

### 10. 常见问题排查

| 问题 | 可能原因 | 解决方法 |
|------|---------|---------|
| `java: 错误: 不支持发行版本 xx` | IDE Language Level 设置过高 | 设为 JDK 8: `File → Project Structure → Language Level → 8` |
| 端口被占用 | 其他程序占用了 8092-8094 | 关闭占用程序，或通过 `--port` 指定其他端口 |
| `ClassNotFoundException: com.alibaba.fastjson.JSON` | 依赖未正确打包 | 使用 `easy-db-1.0.0-jar-with-dependencies.jar` 而不是 `easy-db-1.0.0.jar` |
| Maven 无法下载 fastjson | 网络不通 / 未配置镜像 | 配置 Maven 镜像（阿里云/华为云），或手动下载 fastjson-1.2.83.jar |
| IDEA 不识别 Maven 项目 | 未正确导入 | 选择 `pom.xml` → 右键 → `Add as Maven Project` |
| 找不到或无法加载主类 | 未用 `jar-with-dependencies` | 确认使用带 `-jar-with-dependencies.jar` 后缀的文件 |
| `Unsupported class file major version 61` | 编译用了 JDK 17 但运行用了 JDK 8 | 统一使用 JDK 8 编译和运行 |

### 11. 项目源文件清单

```
src/main/java/com/easydb/
├── Launcher.java                          # 启动入口
├── common/constants/Constants.java        # 常量定义
├── client/
│   ├── EasyDBClient.java                  # Java SDK 客户端
│   ├── cli/CliSession.java                # CLI 交互命令行
│   ├── shell/ShellClient.java             # Shell 单次命令
│   └── gui/GuiClient.java                 # GUI 图形界面
└── server/
    ├── bootstrap/ServerBootstrap.java     # 服务器启动引导
    ├── net/
    │   ├── SocketServer.java              # TCP 多线程服务器
    │   ├── ClientHandler.java             # 客户端连接处理
    │   ├── CommandHandler.java            # 命令路由分发
    │   └── RequestDecoder.java            # TCP 协议解析
    ├── http/
    │   ├── HttpServer.java                # HTTP 服务器
    │   └── RestDispatcher.java            # REST 请求分发
    ├── engine/
    │   ├── StoreEngine.java               # 存储引擎接口
    │   ├── DefaultStoreEngine.java        # 存储引擎实现
    │   ├── lsm/
    │   │   ├── LSMTree.java               # LSM-Tree 多层管理
    │   │   ├── MemTable.java              # 内存有序表
    │   │   └── SSTable.java               # 有序字符串表
    │   ├── disk/
    │   │   ├── WalManager.java            # WAL 日志管理
    │   │   └── Compactor.java             # 文件压缩合并
    │   ├── index/
    │   │   ├── SparseIndex.java           # 全局稀疏索引
    │   │   └── Trie.java                  # 前缀树索引
    │   └── mem/
    │       ├── ConcurrentHashStore.java   # 并发内存存储
    │       └── LruCache.java              # LRU 读缓存
    └── cluster/
        ├── ClusterConfig.java             # 集群配置（任期等）
        ├── Node.java                      # 节点信息
        ├── NodeRole.java                  # 角色枚举
        ├── HeartbeatManager.java          # 心跳管理
        ├── RoleElector.java               # 角色选举
        └── ReplicationManager.java        # 数据复制
```
