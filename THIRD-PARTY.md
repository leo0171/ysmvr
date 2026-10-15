# Third-Party Notices

ysmvr 采用 LGPL-3.0 授权。以下第三方组件仅作为编译期依赖或运行时兼容目标，
本仓库不包含它们的本体、反编译源码或美术资源。

## Vivecraft

- 项目: https://github.com/Vivecraft/VivecraftMod
- 许可证: GNU Lesser General Public License v3.0 (LGPLv3)
- 用途: 运行时兼容目标；`MixinVivecraftJointDir` 包含针对 Vivecraft 的
  `ModelUtils.estimateJointDir` 的兼容性修改代码，属于 LGPLv3 派生部分。

## Yes Steve Model (官方 YSM 3.0)

- 许可证: Apache License 2.0
- 用途: 运行时兼容目标；通过编译期依赖和反射/桥接交互，不随本仓库分发。

## ModernYSM / OpenYSM

- 许可证: MIT License
- 用途: 社区重实现，运行时兼容目标；不随本仓库分发。

## Minecraft / Forge

- Minecraft 版权归 Mojang AB 所有，Forge 版权归 Forge 开发团队所有。
- 本仓库不包含 Minecraft 客户端/服务端代码或资源；
- 使用/分发本项目仍需遵守 Minecraft EULA 与 Forge 的许可条款。

## 构建依赖

`gradle.properties` 中的 YSM / ModernYSM dev jar 路径指向本地构建，
不包含在本仓库中。使用本项目构建时需自行准备对应依赖并遵守其许可证。

### 编译期 API 桩（摘自 Vivecraft 源码，LGPL-3.0）

- `src/stubs` 下的类型签名与文件注释摘自 Vivecraft 源码
  （<https://github.com/Vivecraft/VivecraftMod>，LGPL-3.0），并裁剪为编译 Mixin 所需的最小集合；
- 它们只参与编译，不会打进最终 jar；运行时使用 Vivecraft 本体提供的真实类；
- 本仓库整体采用 LGPL-3.0，因此与该部分来源许可一致；
  若单独再分发这些文件，请保留其 LGPL-3.0 归属与版权声明。
