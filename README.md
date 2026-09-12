# Litematica Printer - EMT

![GitHub stars](https://img.shields.io/github/stars/MoMortis/litematica-printer-EMT)
![GitHub last commit](https://img.shields.io/github/last-commit/MoMortis/litematica-printer-EMT)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11%20%7C%2026.1.2-blue)
![License](https://img.shields.io/badge/License-AGPL--3.0-green)

为 [Litematica](https://modrinth.com/mod/litematica) 投影添加自动建造功能的 Minecraft Fabric 模组。

本分支（1.4-EMT）基于 [BiliXWhite 二改版](https://github.com/BiliXWhite/litematica-printer)修改，支持 **1.21.11 与 26.1.2 双版本单 jar**。

> [!IMPORTANT]
> 🤖 **AI 深度参与开发**：本分支的全部功能实现与性能优化均由 AI 协作完成——**GPT · DeepSeek · GLM**

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

### 🔍 扫描白名单与自动寻路
- 扫描白名单：只有列表内的方块（∪ 验证器高亮的缺失方块）才会被打印与寻路
- BFS / DFS 双算法，先全局选「离玩家最近的原理图子区块」为中心
- DFS 按配置方向序深度探索 + 回溯；BFS 环形扩散搜索下一个中心
- 仅在渲染距离内（看得见地形）扫描与寻路
- 目标完成后锚定工作区块继续，不丢扫描进度
- 优先验证器缺失列表（离玩家最近）

### 🧱 放置与破坏
- 数据包打印 / 数据包挖掘，音效确认覆盖批量更新包
- 代替放置 + 代替列表：每行「代替1,代替2,...:目标」严格匹配注册路径/完整ID/精确译名，代替方块豁免「破坏错误方块」
- 破坏错误方块：破冰、放水（后置顺序，独立「优化放水逻辑」开关）
- 侦测器安全放置（等待服务器确认输入面）；漏斗/箱子朝向错误时破坏重放
- 协同挖掘（非阻塞式）、防流体挖掘、不破坏支撑方块
- 纱幕：列表内方块同 tick 发包秒破
- 修复旋转/镜像放置朝向；换挡类方块打印不再要求空手

### 📦 补货与容器
- 快捷潜影盒模拟点击取货：隐藏容器会话、最大取货数量
- 快捷潜影盒自动补货：自主检测消耗（烟花火箭、不死图腾、箭矢等）
- 云仓库补货：「打印机补货」与「手动补货」分离，中键取货原理图方块优先

### 🎨 投影渲染
- 「仅渲染方块」+「仅渲染方块列表」：列表外方块不渲染，缺失标记隐藏，多余/错误方块标记保留
- 配置变化自动重建原理图渲染

### ⏩ 自动寻路移动
- 「强制疾跑」为跑酷跳总开关：关闭时路径自动绕开缺口（含 1 格缺口）
- 平地长直线路段边跑边跳提速；路点飞越（含 45° 对角）正常推进
- 进入服务器自启动：进服自动开启打印机，死亡重生不重复触发

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
