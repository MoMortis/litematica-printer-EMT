# Litematica Printer - EMT

![GitHub stars](https://img.shields.io/github/stars/MoMortis/litematica-printer-EMT)
![GitHub last commit](https://img.shields.io/github/last-commit/MoMortis/litematica-printer-EMT)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11%20%7C%2026.1.2-blue)
![License](https://img.shields.io/badge/License-AGPL--3.0-green)

为 [Litematica](https://modrinth.com/mod/litematica) 投影添加自动建造功能的 Minecraft Fabric 模组。

本分支（1.4-EMT）基于 [BiliXWhite 二改版](https://github.com/BiliXWhite/litematica-printer)修改，使用GPT、DeepSeek、GLM，做了一些性能与功能强化，支持 **1.21.11 与 26.1.2 双版本单 jar**。

如果你觉得好用，欢迎给项目点个 Star ⭐️

---

## 🎮 支持的游戏版本

| 版本 |
|---------------|
| 1.21.11 · 26.1.2 |

> [!NOTE]
> 两个版本打包在一个 jar 内（versionpack），放入 mods 文件夹即可自动适配。

---

## 📦 前置模组

- [Fabric API](https://modrinth.com/mod/fabric-api)
- [MaLiLib](https://modrinth.com/mod/malilib)
- [Litematica](https://modrinth.com/mod/litematica)

---

## ✨ 特性

### 🚀 大图打印性能
- 原理图判定缓存与盒索引预计算，待办物品清单记忆化
- 空闲退避：无待办时自动降低扫描频率，有活立刻恢复
- 「工作时长预算」约束全部主流程，不卡主线程
- 「优先同种方块」定向扫描；缺料方块跳过继续打印，不全场停工
- 数据包模式失败重试表，重试不占放置额度
- 原理图移动/旋转/增删即时感知，打印无缝切到新位置

### 🔍 扫描自动寻路
- BFS / DFS 双算法，先全局选「离玩家最近的原理图子区块」为中心
- DFS 按配置方向序深度探索 + 回溯；BFS 环形扩散搜索下一个中心
- 仅在渲染距离内（看得见地形）扫描与寻路
- 目标完成后锚定工作区块继续，不丢扫描进度
- 优先验证器缺失列表；扫描对象 = 「扫描白名单」∪ 验证器高亮的缺失方块

### ⏩ 移动与交互
- 「强制疾跑」为跑酷跳总开关：关闭时路径自动绕开缺口（含 1 格缺口）
- 平地长直线路段边跑边跳提速；路点飞越（含 45° 对角）正常推进
- 数据包模式音效确认覆盖批量更新包，破坏/放置音效不再缺失

### 🎨 投影渲染
- 「仅渲染方块」+「仅渲染方块列表」：列表外方块不渲染，缺失标记隐藏，多余/错误方块标记保留
- 配置变化自动重建原理图渲染

### 🧩 继承二改版的功能
- 数据包打印模式（更快，避免幽灵方块）、放置进度条 HUD、服务器卡顿检测
- 代替放置 + 代替列表（继承并泛化自「代替失活珊瑚」，代替方块豁免「破坏错误方块」）
- 扫描白名单、破坏错误方块（破冰、放水）、填充功能
- 快捷潜影盒模拟点击取货（需服务器支持背包内打开潜影盒）
- 方块放置修复：合成器、拉杆、红石粉、枯叶、花簇、发光浆果、带花的花盆、楼梯、藤蔓、砂轮、门、活版门、漏斗、箱子等

---

## 📖 使用方法

1. 在世界中加载一个 Litematica 原理图（Schematic）
2. 移动到可以接触到原理图方块的位置
3. 按下 `Caps Lock` 键开启打印机
4. 等待自动建造完成 🎉

> [!TIP]
> 大部分功能都含有游戏内注释可供参考使用；「扫描自动寻路」需要同时开启「扫描白名单」与「扫描自动寻路」。

---

## ⚠️ 未支持方块

- 装有液体的炼药锅
- 实体方块（物品展示框、盔甲架、画等）
- 非原版游戏内容

> [!TIP]
> 如发现方块放置错误，请先尝试降低建造速度。若问题依旧，欢迎提交 [Issue](https://github.com/MoMortis/litematica-printer-EMT/issues)

---

## 🔨 编译

```bash
git clone https://github.com/MoMortis/litematica-printer-EMT.git
cd litematica-printer-EMT
./gradlew build
```

| 产物 | 位置 |
|------|------|
| 多版本 jar（versionpack） | `./fabricWrapper/build/libs/` |
| 单版本 jar | `./fabricWrapper/build/tmp/submods/META-INF/jars` |

---

## ❓ 常见问题

### Q: 开启打印后，打印机不工作？

服务器反作弊可能检测静默看向放置；或服务器有放置速率限制。可尝试开启「使用数据包打印」、调大「打印机工作间隔」。

### Q: 打印机放置的方块是错的？

多为服务器响应不及时或识别算法未覆盖该方块特性。可增大「打印机工作间隔」、降低建造速度。

---

## 🙏 感谢

- [aleksilassila](https://github.com/aleksilassila/litematica-printer) - 原创基础
- [zhaixianyu](https://github.com/zhaixianyu/litematica-printer) - 二改版本
- [BiliXWhite](https://github.com/BiliXWhite/litematica-printer) - 二改版本（本分支的直接基础）
- [masa](https://github.com/masa-fn) - Litematica / MaLiLib

以及所有支持开发的朋友，包括你！💖

---

## 📄 协议

本项目基于 [AGPL-3.0](LICENSE.md) 协议开源。
