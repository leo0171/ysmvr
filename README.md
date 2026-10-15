# YSM VR Tracking Addon (ysmvr)

把 Vivecraft 的 VR 全身追踪数据实时驱动到 Yes Steve Model 的玩家模型骨骼上，
并提供客户端调试指令手动验证骨骼写入路径。

## 当前版本

**v0.3.33-dev（测试版）**

本项目当前是兼容 Vivecraft + Yes Steve Model 的客户端追踪适配测试版，
核心功能仍在实机迭代中，接口与行为可能随版本变化。

| 适配组件 | 测试版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 1.20.1-47.4.3 |
| Vivecraft | 1.20.1 1.3.x（开发期参考 1.3.15） |
| 官方 Yes Steve Model | 3.0-dev 源码构建 |
| ModernYSM / OpenYSM | 2.6.x 社区重实现 |

- 客户端 mod，仅驱动本地玩家 YSM 模型姿态；
- 支持 Vivecraft 全身追踪（腰、脚、膝、肘、手）；
- 新增模式 5 `全身（T字校准实验）`：通过 T 字校准让完整身体跟随玩家，
  包含躺下 / 趴下 Root 适配，目前仍属实验功能。

## 支持平台（二选一，自动探测）

| 平台 | modId | 说明 |
|---|---|---|
| 官方 YSM 3.0 | `ysm` | 源码构建，需 `@YsmExtension` checker 通过 |
| ModernYSM 2.6.x | `yes_steve_model` | 社区重实现（OpenYSM），替代官方混淆 2.6.5 |

运行时按已加载 mod 自动选择桥接（[ActiveBridge.java](src/main/java/com/elfmcys/ysmvr/ActiveBridge.java)）。
注意：ModernYSM 的类名与官方混淆 2.6.5 jar 不互通，必须安装 ModernYSM 构建产物。

## 工作原理

- `VivecraftPoseTracker`（Vivecraft `Tracker`，PER_FRAME）每帧读取 `VRClientAPI.getWorldRenderPose()`，
  把追踪数据写入本地玩家的 `PoseOverrideStore`。
- 各平台的 pose controller（`VrPoseController` / `ModernVrPoseController`）实现 YSM 的
  `IAnimationController`，把覆盖值作为骨骼变换队列提交给 `AnimationProcessor`。
  挂载时机晚于 YSM 内建 controller，队列提交顺序靠后，因此覆盖 coded controller 的结果；
  清除覆盖后由 processor 的尾过渡在 ~3 tick 内平滑回到默认姿态。
- 调试指令直接向 `PoseOverrideStore` 写入，绕过 Vivecraft，用于先验证骨骼驱动路径本身。

## 构建前置依赖

构建本模组前，需要准备以下环境与本地依赖：

### 1. 基础环境

- JDK 17（Gradle Java toolchain 使用 17；
  `build.gradle` 的 Minecraft artifact 任务默认尝试 Java 22，可通过 `JAVA_HOME` 覆盖）；
- Minecraft 1.20.1 Forge 开发环境（由 `legacyForge` / NeoForge ModDevGradle 自动下载）；
- Gradle Wrapper（仓库自带 `gradlew` / `gradlew.bat`）。

### 2. 平台依赖 dev jar（二选一或都准备）

| 依赖 | 说明 | 默认路径属性 |
|---|---|---|
| 官方 YSM 3.0 dev jar | 源码构建：`./gradlew build -x test -x verifyBuiltinModelIndex -x generateBuiltinModelIndex -x generateBuiltinDefaultContract` | `ysm_dev_libs` + `ysm_dev_jar_name` |
| ModernYSM / OpenYSM forge dev jar | `cd ModernYSM-1.20.1-forge && ./gradlew :forge:build` | `moderysm_dev_libs` + `moderysm_dev_jar_name` |

在 `gradle.properties` 中把这两个目录指向你自己的本地构建产物：

```properties
ysm_dev_libs=../extracted/dev-1.20/YesSteveModel-dev-1.20/build/libs
ysm_dev_jar_name=ysm-3.0-dev-forge+mc1.20.1.jar

moderysm_dev_libs=../extracted/moderysm/ModernYSM-1.20.1-forge/forge/build/libs
moderysm_dev_jar_name=openysm-forge-2.6.6.6-dev-shadow.jar
```

### 3. 编译

```powershell
.\gradlew.bat build --offline
```

- `src/stubs` 是编译期 Vivecraft API 桩，只参与编译，不会打进最终 jar；
- 运行时需要安装真实的 Vivecraft 本体与 YSM / ModernYSM。

## 运行验证（游戏内）

mods 目录需要（二选一平台）：

- **ModernYSM 路线（当前验证目标）**：`openysm-forge-*.jar`（forge 构建产物）+
  Vivecraft Mod 1.20.1  1.3.0 + 本模组 jar + Forge 1.20.1
- **官方 3.0 路线**：含 `assets/ysm/builtin-index.json` 的完整 3.0 jar（需原生工具链构建）

## 调试指令（客户端）

| 指令 | 说明 |
|---|---|
| `/ysmvr bone <name> rot <rx> <ry> <rz>` | 设置骨骼旋转（度，模型空间） |
| `/ysmvr bone <name> pos <x> <y> <z>` | 设置骨骼位置（模型单位，1 格 = 16） |
| `/ysmvr bone <name> scale <x> <y> <z>` | 设置骨骼缩放 |
| `/ysmvr bones` | 列出当前模型骨骼（带 `[覆盖中]` 标记） |
| `/ysmvr clear [<name>]` | 清除全部/指定骨骼覆盖 |
| `/ysmvr head <pitch> <yaw>` | 快捷设置 Head 骨骼（度） |
| `/ysmvr vr on\|off` | Vivecraft 追踪开关 |
| `/ysmvr status` | 桥接平台/模型/控制器/VR 状态 |

验证顺序建议：先在非 VR 环境用 `bone`/`head`/`clear` 确认手动驱动生效，再开 VR 看追踪映射。

## 当前限制

- 模式 5 `全身（T字校准实验）` 仍在实机调试，躺下 / 趴下 / 坐姿可能继续调整；
- 追踪姿态只作用于本地客户端，不含跨端同步；
- 不包含 Minecraft / Forge / Vivecraft / YSM / OpenYSM 本体或美术资源；
- 使用前请确认所安装的各个 mod 版本与上表兼容。

## 发布新版本

本仓库的 GitHub Actions（`.github/workflows/release.yml`）在推送 `v*` tag 时：
校验 tag 与 `gradle.properties` 的 `mod_version` 一致，然后创建 Release（`-dev` 后缀自动标为 prerelease）。

**它不构建 jar**：编译依赖两个平台 dev jar（见上文「构建前置依赖」），无法在 CI 中取得。
jar 在本机构建后附加即可，推荐用仓库自带脚本一条命令完成：

```powershell
$env:GITHUB_TOKEN = '<你的 token>'
.\scripts\release.ps1 -Version 0.3.34-dev -NotesFile .\release-notes.md
```

该脚本会依次：更新 `gradle.properties` → `gradlew build` → 提交 → 打 tag → 推送 → 创建 Release → 上传 jar（并打印 SHA256）。

## 许可证

本项目采用 **GNU Lesser General Public License v3.0 (LGPL-3.0)**。

Copyright (C) 2026 ysmvr contributors。

- 完整许可证见 [LICENSE](LICENSE)；
- 第三方组件与归属见 [THIRD-PARTY.md](THIRD-PARTY.md)；
- 本仓库不包含 Minecraft / Forge / Vivecraft / YSM / OpenYSM 本体或美术资源。