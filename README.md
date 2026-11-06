# MikuFirstSpawn

> 作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　非开源项目，请勿二次分发

MikuFirstSpawn 让新玩家第一次进入服务器时直接出现在管理员设定的出生点，不会先落在原版世界出生点再被传送。插件同时支持 Paper 与 Folia，无前置依赖。

## 功能特性

- 新玩家首次进服直接落在设定出生点，已进过服的玩家不受影响
- 出生点世界未加载或配置错误时只提示、不传送，不影响玩家进服
- 可选把首次出生点同时设为该玩家的重生点
- 可选首次进服欢迎消息（MiniMessage，支持 `{player}`）
- 支持配置热重载，不生成玩家数据文件

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 26.3 或 Folia 26.3 |
| Java | 25 |

> Folia 26.3 目前为 ALPHA 构建（当前唯一受支持的版本线）。若你的服务端停留在其他版本线，请按对应版本重新编译。

## 安装

1. 将 `MikuFirstSpawn-<版本>.jar` 放入服务端 `plugins/` 目录。
2. 启动服务器。首次启动会生成 `plugins/MikuFirstSpawn/config.yml`。
3. 进服后执行 `/firstspawn set`，把当前位置设为首次出生点（需要 OP 或 `mikufirstspawn.admin` 权限）。
4. 新玩家首次进服会直接出现在该位置，老玩家不受影响。

## 配置说明

`plugins/MikuFirstSpawn/config.yml`：

| 键 | 说明 | 默认值 |
| --- | --- | --- |
| `enabled` | 是否启用自动传送；关闭后命令仍可使用 | `true` |
| `spawn.world` | 出生点世界名；**留空表示尚未设置，不会传送任何玩家** | `''` |
| `spawn.x` / `spawn.y` / `spawn.z` | 出生点坐标 | `0 / 64 / 0` |
| `spawn.yaw` / `spawn.pitch` | 出生朝向 | `0 / 0` |
| `fallback.enabled` | 登录阶段未成功应用时，进服后是否尝试补传送（最多 `fallback.max-attempts` 次） | `true` |
| `fallback.delay-ticks` | 兜底传送延迟（tick，0-200） | `1` |
| `fallback.max-attempts` | 兜底传送最多尝试次数（1-20） | `3` |
| `fallback.retry-delay-ticks` | 兜底传送重试间隔（tick，1-200） | `20` |
| `set-respawn-location` | 是否把首次出生点同时设为该玩家的重生点 | `true` |
| `messages.welcome` | 首次进服欢迎消息（MiniMessage）；留空则关闭 | 见默认配置 |
| `debug` | 调试日志 | `false` |

修改配置后执行 `/firstspawn reload` 生效。使用 `/firstspawn set` 保存时，插件会先读取当前 `config.yml`、只更新出生点；其他配置项与自定义键保留，插件自带注释会刷新。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/firstspawn` 或 `/fs` | 查看帮助 |
| `/firstspawn set` | 把当前位置设为首次出生点（玩家） |
| `/firstspawn set <world> <x> <y> <z> [yaw] [pitch]` | 指定坐标设置出生点（控制台可用） |
| `/firstspawn info` | 查看出生点、世界加载状态、兜底设置与运行平台 |
| `/firstspawn reload` | 重新读取 config.yml |
| `/firstspawn tp` | 传送到出生点（自测用） |
| `/firstspawn help` | 查看帮助 |

## 权限节点

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `mikufirstspawn.admin` | OP | 允许使用全部 `/firstspawn` 命令 |

## 常见问题

**Q：新玩家还是出现在原版出生点？**
A：先执行 `/firstspawn info` 查看“出生点状态”。若提示世界未加载或坐标非法，用 `/firstspawn set` 重新设置；出生点世界必须由服务端加载，插件不会主动创建世界。

**Q：老玩家会被传送吗？**
A：不会，只有从未进过服的新玩家会触发。

**Q：死亡后没有回到首次出生点？**
A：确认 `set-respawn-location` 为 `true`，并确保出生点脚下与头顶可站立；落在固体方块里的坐标在死亡重生时会被服务端忽略。

**Q：服务器装了登录或传送类插件，会冲突吗？**
A：首次落点在登录阶段完成，通常不受影响。如果进服后仍被其他插件移动，可适当调大 `fallback.delay-ticks` 或关闭兜底传送。

**Q：支持 Folia 吗？**
A：支持，插件已声明并适配 Folia。

**Q：会生成玩家数据文件吗？**
A：不会，插件只读写自己的 `config.yml`。

## 反馈

问题与建议请到 MikuMC系列插件交流群（1105054380）反馈。
