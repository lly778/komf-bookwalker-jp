# komf-dmm-jp

面向 Komga 日本漫画库的 [Komf 2.0.1](https://github.com/Snd-R/komf/releases/tag/2.0.1) 定制版。识别优先使用 **DMM Books**，并以 **BOOK☆WALKER 日本站**和 **eBookJapan** 补充。

## 识别行为

- 自动识别和按书名手动识别优先查 DMM Books，找不到符合条件的结果时继续查其他来源。Komf 会优先沿用已保存的手动匹配。
- 根据系列名、文件名、卷号和版本匹配。遇到同时收录电子单行本、分册版或特装版的作品时，会区分这些版本。
- 按来源提供的资料写入书名、作者、出版社、发行日期、简介和系列状态。eBookJapan 商品页需要年龄确认时，仍可从公开搜索和卷目录补录作者、卷号、日期、出版社和レーベル。简介仅在商品页可读取时写入。
- 使用商店公开网页搜索，无需商店账号、API 密钥或本地目录索引。

## 在 Windows 构建

准备 [JDK 21](https://adoptium.net/temurin/releases/?version=21)，将环境变量 `JAVA_HOME` 指向 JDK 的根目录。便携版 JDK 也可以解压到 `work/jdk21-runtime`，使脚本能找到 `work/jdk21-runtime/<JDK 目录>/bin/java.exe`。

从仓库根目录运行以下 PowerShell 命令。它们下载官方 Komf JAR 和固定版本的 Kotlin 构建依赖，再编译补丁并运行联网识别测试。

```powershell
git clone https://github.com/lly778/komf-dmm-jp.git
Set-Location komf-dmm-jp

$stage = 'work/komf-jp-build'
$central = 'https://repo.maven.apache.org/maven2'
$dependencies = @(
    'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.20-RC2/kotlin-compiler-embeddable-2.4.20-RC2.jar'
    'org/jetbrains/kotlin/kotlin-stdlib/2.4.20-RC2/kotlin-stdlib-2.4.20-RC2.jar'
    'org/jetbrains/kotlin/kotlin-script-runtime/2.4.20-RC2/kotlin-script-runtime-2.4.20-RC2.jar'
    'org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar'
    'org/jetbrains/kotlin/kotlin-daemon-embeddable/2.4.20-RC2/kotlin-daemon-embeddable-2.4.20-RC2.jar'
    'org/jetbrains/kotlin/kotlin-build-tools-api/2.4.20-RC2/kotlin-build-tools-api-2.4.20-RC2.jar'
    'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar'
    'org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar'
)

Invoke-WebRequest -Uri 'https://github.com/Snd-R/komf/releases/download/2.0.1/komf-2.0.1.jar' -OutFile (Join-Path $stage 'komf-2.0.1.jar')
foreach ($dependency in $dependencies) {
    $file = $dependency.Split('/')[-1]
    Invoke-WebRequest -Uri "$central/$dependency" -OutFile (Join-Path $stage $file)
}

& ./work/komf-jp-build/rebuild_search.ps1
```

产物为 `work/komf-jp-build/komf-dmm-jp-2.0.1.jar`。构建只需要仓库源码、官方 JAR 和上述依赖。

## 部署到 Docker 或群晖 NAS

在 NAS 上创建项目目录，将构建产物和配置放成下面的结构。

```text
komf-dmm-jp/
├── docker-compose.yml
└── config/
    ├── application.yml
    └── komf-dmm-jp/
        └── komf-dmm-jp-2.0.1.jar
```

在 `docker-compose.yml` 中使用 Komf 官方镜像作为运行环境。以下示例适用于 amd64 和 arm64，补丁需要 Java 21。

```yaml
services:
  komf:
    image: sndxr/komf:2.0.1
    container_name: komf-dmm-jp
    entrypoint: ["java", "-jar", "/config/komf-dmm-jp/komf-dmm-jp-2.0.1.jar"]
    ports:
      - "8085:8085"
    volumes:
      - ./config:/config
    environment:
      KOMF_CONFIG_DIR: /config
      KOMF_BOOKWALKER_JP_INDEX_ENABLED: "false"
    restart: unless-stopped
```

将 `config/application.yml` 中的 Komga 地址换成容器可以访问的地址，并填写自己的 Komga 管理员账户。如果 Komga 也运行在容器中，通常可以使用 NAS 的局域网地址或同一 Docker 网络中的 Komga 服务名。

```yaml
komga:
  baseUri: "http://komga.example.net:25600"
  komgaUser: "admin@example.net"
  komgaPassword: "CHANGE_ME"
  eventListener:
    enabled: true
  metadataUpdate:
    default:
      libraryType: MANGA

database:
  file: /config/database.sqlite

metadataProviders:
  defaultProviders:
    bookWalker:
      enabled: true
      priority: 10
      mediaType: MANGA
      seriesMetadata:
        thumbnail: false
      bookMetadata:
        thumbnail: false
```

三个商店共用 Komf 的 `bookWalker` 配置项。上面的缩略图设置会保留 Komga 使用的本地封面。升级现有安装时，停掉原 Komf 容器并沿用其 `application.yml` 和 `database.sqlite`，然后把同一份配置中的数据库路径调整到容器内的挂载位置。

在群晖 Container Manager 中，以这个目录创建项目，选择现有 Compose 文件并启动。使用命令行时，在项目目录执行 `docker compose up -d`。启动后通过 NAS 的 8085 端口访问 Komf，并在 Komga 的 Komf 扩展里填入同一个服务地址。

## 使用

- 在系列详情页用 **Identify** 按书名搜索并确认结果。
- 在漫画库上运行 **Auto-Identify** 批量识别。识别任务运行期间应等它结束再重启容器。
- 在识别后的系列或单卷资料中查看来源链接，确认实际使用的是哪一家书店。

更多配置项见 [Komf 2.0.1 上游文档](https://github.com/Snd-R/komf/tree/2.0.1#example-applicationyml-config)。官方镜像见 [Docker Hub](https://hub.docker.com/r/sndxr/komf/tags)。

## 许可

本项目基于 [Snd-R/komf](https://github.com/Snd-R/komf)，使用 [MIT 许可证](LICENSE)。
