# MikuFirstSpawn

> 作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　非开源项目，请勿二次分发

MikuFirstSpawn 是一款面向 Paper / Folia 服务端的首次进服插件：**新玩家第一次进入服务器时，会直接出现在管理员设定的出生点**，而不是先落在原版世界出生点再被拉走。插件基于 Paper 原生登录出生点事件实现，没有传送闪烁，也不依赖任何前置插件。

## 功能特性

- 首次进服直接落在设定出生点（玩家配置阶段生效，无空降、无摔落、无传送闪烁）
- 已经进过服的玩家完全不受影响
- 出生点世界未加载、坐标非法时只输出提示、不执行传送，**绝不阻断玩家进服**
- 可选：把首次出生点同时设为该玩家的重生点（未设置床时死亡会回到这里）
- 可选：首次进服欢迎消息（MiniMessage 格式，支持 `{player}` 占位符）
- 不创建玩家数据文件、无数据库、无网络请求、无第三方依赖
- Paper / Folia 双端兼容：区域调度器 + `teleportAsync`，全程不使用 `BukkitScheduler`
- 命令：`/firstspawn set|info|reload|tp`，支持配置热重载

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 26.3 或 Folia 26.3 |
| Java | 25 |

> Folia 26.3 目前为 ALPHA 构建（当前唯一受支持的版本线）。若你的服务端停留在其他版本线，请按对应版本重新编译。

## 安装

1. 将 `MikuFirstSpawn-<版本>.jar` 放入服务端 `plugins/` 目录。
2. 启动服务器，插件会生成 `plugins/MikuFirstSpawn/config.yml`。
3. 进服后执行 `/firstspawn set`，把当前位置设为首次出生点（需要 OP 或 `mikufirstspawn.admin` 权限）。
4. 新玩家首次进服会直接出现在该位置，老玩家不受影响。

## 配置说明

`plugins/MikuFirstSpawn/config.yml`：

| 键 | 说明 | 默认值 |
| --- | --- | --- |
| `enabled` | 是否启用自动传送；关闭后命令仍可使用 | `true` |
| `spawn.world` | 出生点所在世界名；**留空表示尚未设置，不会传送任何玩家** | `''` |
| `spawn.x` / `spawn.y` / `spawn.z` | 出生点坐标 | `0 / 64 / 0` |
| `spawn.yaw` / `spawn.pitch` | 出生朝向 | `0 / 0` |
| `fallback.enabled` | 登录阶段未成功应用时，进服后是否补一次兜底传送 | `true` |
| `fallback.delay-ticks` | 兜底传送延迟（tick） | `1` |
| `fallback.max-attempts` | 兜底传送最多尝试次数 | `3` |
| `fallback.retry-delay-ticks` | 兜底传送重试间隔（tick） | `20` |
| `set-respawn-location` | 首次出生点是否同时设为该玩家的重生点 | `true` |
| `messages.welcome` | 首次进服欢迎消息（MiniMessage）；留空则关闭 | 见默认配置 |
| `debug` | 调试日志 | `false` |

修改配置后执行 `/firstspawn reload` 即可生效，无需重启。使用 `/firstspawn set` 保存出生点时，插件会保留配置注释。

## 命令与权限

| 命令 | 说明 |
| --- | --- |
| `/firstspawn` 或 `/fs` | 查看帮助 |
| `/firstspawn set` | 把当前位置设为首次出生点（玩家） |
| `/firstspawn set <world> <x> <y> <z> [yaw] [pitch]` | 指定坐标设置出生点（控制台可用） |
| `/firstspawn info` | 查看出生点、世界加载状态、兜底设置与运行平台 |
| `/firstspawn reload` | 重新读取 config.yml |
| `/firstspawn tp` | 传送到出生点（自测用） |
| `/firstspawn help` | 查看帮助 |

权限：`mikufirstspawn.admin`（默认 OP），覆盖以上全部命令。

## 行为说明

- **首次判定**使用服务端玩家数据（新玩家标记），插件不保存任何玩家记录；重启、崩溃、换端都不会误判或重复传送。
- **主路径**在玩家登录配置阶段直接设定落点，玩家不会先出现在原版出生点。
- **兜底路径**仅在主路径未成功时触发（例如出生点世界当时尚未加载），通过 `teleportAsync` 补一次传送，并按配置重试；失败只记录日志，不影响进服。
- 出生点所在世界必须由服务端加载，插件不会主动创建世界；若世界在开服后才加载，插件会输出提示，之后的首次进服会正常生效。
- 若同服装有登录/传送类插件，可适当调大 `fallback.delay-ticks`，避免与其争抢玩家位置；主路径通常不受影响。

## 常见问题

**Q：新玩家还是出现在原版出生点？**
A：先执行 `/firstspawn info` 检查“出生点状态”。若提示世界未加载或坐标非法，用 `/firstspawn set` 重新设置；若出生点世界是其他插件延迟加载的，等世界加载完成后再设置一次。

**Q：老玩家会被传送吗？**
A：不会。首次判定只针对从未进过服的新玩家。

**Q：支持 Folia 吗？**
A：支持。插件声明了 `folia-supported: true`，并使用 Paper 区域调度器与 `teleportAsync`；没有任何 `BukkitScheduler` 调用。

**Q：会生成玩家数据文件吗？**
A：不会。插件只读写自己的 `config.yml`。

**Q：怎么测试首次进服效果？**
A：用一个从未进过服的新账号进服即可；管理员也可以先 `/firstspawn tp` 检查出生点是否可用。

## 反馈

问题与建议请到 MikuMC系列插件交流群（1105054380）反馈。

> 本项目为非开源项目，请勿二次分发。
