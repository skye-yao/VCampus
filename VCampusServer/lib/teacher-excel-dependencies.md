# 教师端 Excel 依赖清单（Apache POI，服务端）

本目录下的这些 jar 由 `scripts/test-teacher.ps1` 的通配符 classpath（`VCampusServer/lib/*`）自动
带上编译与运行期 classpath，因此**没有引入 Maven/Gradle，也没有改动项目构建方式**：新增一个 jar
就等于新增一个依赖，删除同理。清单记录的是「实际放进本目录的字节」，不是某个构建工具解析出来的名义
版本——仓库里的 jar 就是唯一事实来源。

生成日期：2026-09-16。起点：设计第 9 节要求的 `org.apache.poi:poi-ooxml:5.4.1`。

## 1. 已放入本目录的 jar（13 个）

组坐标 `g:a:v` 的完整写法为 `<groupId>:<artifactId>:<version>`；「来源」均为 Maven Central
（`https://repo1.maven.org/maven2/`）。

| # | jar（本目录文件名） | 坐标 | 版本 | 许可证 | 为什么需要 | 字节数 | SHA-256 |
|---|---|---|---|---|---|---|---|
| 1 | `poi-ooxml-5.4.1.jar` | `org.apache.poi:poi-ooxml` | 5.4.1 | Apache-2.0 | 起点依赖：`XSSFWorkbook`/`WorkbookFactory` 等 `.xlsx`（OOXML）实现 | 2037787 | `fd200c9e6f74d704160a97e9d52041995ed87439454530001edd920688f19f53` |
| 2 | `poi-5.4.1.jar` | `org.apache.poi:poi` | 5.4.1 | Apache-2.0 | `poi-ooxml` 的编译依赖：公共 SS 模型、`DataFormatter`、`CellType`、加密/OLE 基础 | 2996461 | `da5abf42da4604c5a7bca38956af6e9d6f196d9b6d4cb7eabee4f480b580d505` |
| 3 | `poi-ooxml-lite-5.4.1.jar` | `org.apache.poi:poi-ooxml-lite` | 5.4.1 | Apache-2.0 | `poi-ooxml` 的编译依赖：精简版 OOXML schema 类，缺它 `.xlsx` 读写直接 `NoClassDefFoundError` | 5996003 | `dc590461efdfcd4f27e2a892737979ab5e30b4132a7adfc7c9e56447b71a45b0` |
| 4 | `xmlbeans-5.3.0.jar` | `org.apache.xmlbeans:xmlbeans` | 5.3.0 | Apache-2.0 | `poi-ooxml-lite` 与 `poi-ooxml` 的编译依赖：解析/生成 OOXML XML（`org.apache.xmlbeans.*`） | 2211661 | `6cc69da3b4d35b83c5e477cd4daba204e44109833e34af2b9a8a2c8788289917` |
| 5 | `commons-compress-1.27.1.jar` | `org.apache.commons:commons-compress` | 1.27.1 | Apache-2.0 | `poi-ooxml` 的编译依赖：`.xlsx` 本质是 ZIP，OPC 包读写走它 | 1087319 | `293d80f54b536b74095dcd7ea3cf0a29bbfc3402519281332495f4420d370d16` |
| 6 | `commons-io-2.18.0.jar` | `commons-io:commons-io` | 2.18.0 | Apache-2.0 | `poi`、`poi-ooxml`、`commons-compress` 的编译依赖：流/文件工具 | 538910 | `f3ca0f8d63c40e23a56d54101c60d5edee136b42d84bfb85bc7963093109cf8b` |
| 7 | `commons-codec-1.18.0.jar` | `commons-codec:commons-codec` | 1.18.0 | Apache-2.0 | `poi` 的编译依赖（摘要/编码），也是 `commons-compress` 的编译依赖（此处取较新版本） | 373045 | `ba005f304cef92a3dede24a38ad5ac9b8afccf0d8f75839d6c1338634cf7f6e4` |
| 8 | `commons-collections4-4.4.jar` | `org.apache.commons:commons-collections4` | 4.4 | Apache-2.0 | `poi` 与 `poi-ooxml` 的编译依赖：POI 内部集合类型 | 751914 | `1df8b9430b5c8ed143d7815e403e33ef5371b2400aadbe9bda0883762e0846d1` |
| 9 | `commons-math3-3.6.1.jar` | `org.apache.commons:commons-math3` | 3.6.1 | Apache-2.0 | `poi` 的编译依赖：统计/线性代数（图表、公式求值相关） | 2213560 | `1e56d7b058d28b65abd256b8458e3885b674c1d588fa43cd7d1cbb9c7ef2b308` |
| 10 | `commons-lang3-3.18.0.jar` | `org.apache.commons:commons-lang3` | 3.18.0（`commons-compress` 声明 3.16.0，见 §3） | Apache-2.0 | `commons-compress` 的非可选编译依赖；`poi` 本身不引用它，但闭包必须完整 | 702952 | `4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720` |
| 11 | `curvesapi-1.08.jar` | `com.github.virtuald:curvesapi` | 1.08 | BSD 3-Clause（POM `<licenses>` 写「BSD License」） | `poi-ooxml` 的编译依赖：XSSF 图表/几何曲线 | 117159 | `ad95b08b8bbf9d7d17e5e00814898fa23324f32bc5b62f1a37801e6a56ce0079` |
| 12 | `SparseBitSet-1.3.jar` | `com.zaxxer:SparseBitSet` | 1.3 | Apache-2.0 | `poi` 的编译依赖：公式求值用的稀疏位图 | 25843 | `f76b85adb0c00721ae267b7cfde4da7f71d3121cc2160c9fc00c0c89f8c53c8a` |
| 13 | `log4j-api-2.25.5.jar` | `org.apache.logging.log4j:log4j-api` | 2.25.5（POI 的 log4j-bom 管理 2.24.3，见 §3） | Apache-2.0 | `poi` 与 `poi-ooxml` 的编译依赖：POI 的日志门面 | 351427 | `64777f73ea0b3104c04eb82befbdccc30a425a19e83ad06cb2f93aa303511863` |

许可证证据：POI 三个 jar 内含 `META-INF/LICENSE`（Apache-2.0 全文）与 `META-INF/NOTICE`；
`xmlbeans`、`commons-*`、`log4j-api` 内含`LICENSE.txt`/`LICENSE`（同样的 Apache-2.0 全文）；
`commons-compress`、`commons-io` 的 POM 没有 `<licenses>`，许可证继承自父 POM
`org.apache.commons:commons-parent`（Apache-2.0），jar 内 `META-INF/LICENSE.txt` 也是 Apache-2.0 全文；
`curvesapi`、`SparseBitSet` 的 jar 内没有许可证文件，许可证取自各自 POM 的 `<licenses>` 段。

**刻意没有解析为「另一份」的版本**：`poi-ooxml` 同时声明 `commons-io 2.18.0` 与 `commons-codec 1.18.0`，
`commons-compress` 声明 `commons-io 2.16.1`、`commons-codec 1.17.1`——按 Maven「最近优先」是 2.18.0/1.18.0，
本目录也只放这两个版本，不出现第二份同名 jar。

## 2. 验证方式（可复现）

1. 依赖闭包不是猜的：逐个下载并阅读 `poi-ooxml-5.4.1.pom`、`poi-5.4.1.pom`、`poi-ooxml-lite-5.4.1.pom`、
   `xmlbeans-5.3.0.pom`、`commons-compress-1.27.1.pom`、`commons-io-2.18.0.pom`、`commons-codec-1.18.0.pom`、
   `commons-collections4-4.4.pom`、`commons-math3-3.6.1.pom`、`commons-lang3-3.16.0.pom`、
   `curvesapi-1.08.pom`、`SparseBitSet-1.3.pom`、`log4j-api-2.24.3.pom`，按下表逐条核对
   `<dependency>` 的 groupId/artifactId/version/scope/optional。
2. **`poi` 自己的传递依赖**（容易漏的一条，必须在实现前核对）：
   `commons-codec 1.18.0`、`commons-collections4 4.4`、`commons-math3 3.6.1`、`commons-io 2.18.0`、
   `com.zaxxer:SparseBitSet 1.3`、`log4j-api`（版本由 `log4j-bom 2.24.3` 管理）。
   `poi-ooxml` 侧则是：`poi 5.4.1`、`poi-ooxml-lite 5.4.1`、`xmlbeans 5.3.0`、`commons-compress 1.27.1`、
   `commons-io 2.18.0`、`curvesapi 1.08`、`log4j-api`（同样由 BOM 管理）、`commons-collections4 4.4`。
   别忘了 `poi-ooxml-lite` 自己还声明了 `xmlbeans 5.3.0`。
3. `commons-compress 1.27.1` 还声明了一个**非可选**编译依赖 `commons-lang3 3.16.0`（其余
   `zstd-jni`/`brotli`/`xz`/`asm` 都是 `optional=true`）。实测 `commons-compress-1.27.1.jar` 内有 6 个类
   引用 `org/apache/commons/lang3`（`TarArchiveEntry`、`TarArchiveOutputStream`、`BinaryTree`、
   `HuffmanDecoder`、`LZ77Compressor`、`Pack200UnpackerAdapter`）。我们只用 `.xlsx` 的 ZIP 读写，
   正常路径不会碰到这 6 个类；但闭包要完整，因此照样放入。
4. 最小 Workbook 读写探针（在实现真实服务之前跑过，产物在工作树 `.codex-tmp/` 下，不提交）：
   用 `-cp "VCampusServer/lib/*"` 编译并运行，`XSSFWorkbook` 写出「学号=000123（文本单元格）、姓名=张三」，
   再用 `WorkbookFactory` 读回，`DataFormatter(Locale.ROOT)` 得到 `000123`、`CellType` 仍是 `STRING`。
   输出：`probe ok: sheets=1 uid=000123`。

## 3. 安全补丁核对（OSV，2026-09-16 实查）

用 OSV API（`POST https://api.osv.dev/v1/query`，ecosystem=Maven）按**精确版本**查了上表 13 个坐标，
返回空 `{}` 的记「无已知漏洞」。两处命中，均已通过换成同一 artifact 的已修复版本解决：

| 坐标 | POI 闭包声明的版本 | 实查结果 | 最终放入的版本 | 依据 |
|---|---|---|---|---|
| `org.apache.commons:commons-lang3` | 3.16.0 | `GHSA-j288-q9x7-2f5v`（`ClassUtils` 超长输入导致不受控递归；`[3.0, 3.18.0)` 受影响） | **3.18.0** | 修复版本恰为 3.18.0，同 artifact 补丁级升级 |
| `org.apache.logging.log4j:log4j-api` | 2.24.3 | `GHSA-qv9r-c865-cp47`（`MapMessage` JSON 序列化非有限浮点值编码错误；`[2.13.1, 2.25.5)` 受影响） | **2.25.5** | 修复版本为 2.25.5，同 artifact 补丁级升级 |

换版本后重新查询 3.18.0 与 2.25.5，均返回空 `{}`。
**这两处是刻意的、有记录的升级，不是随手改版本**：`poi-ooxml` 的 `log4j-bom` 把 `log4j-api` 管到
2.24.3，`commons-compress` 声明 `commons-lang3` 3.16.0；我们放入的 `log4j-api-2.25.5.jar` 与
`commons-lang3-3.18.0.jar` 分别是这两个 artifact 的**最小已修复版本**（同为 2.x/3.x 补丁级，API 兼容），
除此之外这两个坐标没有第二份 jar，也没有其它版本偏离。其余坐标
（`poi-ooxml`/`poi`/`poi-ooxml-lite` 5.4.1、`xmlbeans` 5.3.0、`commons-compress` 1.27.1、
`commons-io` 2.18.0、`commons-codec` 1.18.0、`commons-collections4` 4.4、`commons-math3` 3.6.1、
`curvesapi` 1.08、`SparseBitSet` 1.3）在 OSV 上对各自精确版本**没有**已知漏洞记录。
注：`commons-collections4 4.4` 老，但它没有反向序列化 gadget 之外的已知 CVE，且我们只把 POI 当调用方、
不做 Java 反序列化，风险不适用；如上游将来发补丁版可直接替换 jar。

另外注意 **`log4j-api` 没有配套的 `log4j-core`**：POI 只用门面，缺后端时 JVM 第一次记日志会在 stderr
打印一行 `main ERROR Log4j API could not find a logging provider.`，随后静默降级为 no-op。这是有意为之
（不引入日志后端与配置文件），不影响任何功能，测试里也会看到这一行。

POI 自带压缩炸弹防线：`ZipSecureFile` 默认限制单项解压大小（约 4 GiB）与最小压缩比 0.01，
与设计第 9 节「拒绝超限压缩内容」一致，无需额外配置。

## 4. 明确排除的可选依赖（`<optional>true</optional>`，不得放入本目录）

以下 9 个 artifact 在 `poi-ooxml-5.4.1.pom` 里全部标记为 `optional=true`，即「只有用到对应功能才需要」，
我们的读写路径（XSSF 单元格/样式、无签名、无 PDF、无 SVG 导出）一条都不碰，放进来只会扩大攻击面与体积：

| 坐标 | 版本 | 用途（我们不用的功能） |
|---|---|---|
| `org.apache.santuario:xmlsec` | 3.0.5 | OOXML 数字签名 |
| `org.bouncycastle:bcpkix-jdk18on` | 1.80 | 签名/证书 |
| `org.bouncycastle:bcutil-jdk18on` | 1.80 | 签名/证书 |
| `org.apache.pdfbox:pdfbox` | 3.0.4 | 把 Sheet 导出为 PDF |
| `de.rototor.pdfbox:graphics2d` | 3.0.3 | 绘图上下文经 PDFBox 输出 |
| `org.apache.xmlgraphics:batik-svggen` | 1.18 | 导出 SVG |
| `org.apache.xmlgraphics:batik-svgrasterizer` | 1.18 | 导出 SVG |
| `org.apache.xmlgraphics:batik-codec` | 1.18 | 导出 SVG |
| `org.apache.xmlgraphics:batik-bridge` | 1.18 | 导出 SVG |

对应的 `provided`/`test` 依赖（`jspecify`、`org.osgi.core`、JUnit、Mockito、jmh 等）同样不放入。
未采用任何其它新依赖：没有 Spring、没有 OpenCSV、没有 EasyExcel。
