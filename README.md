# Litematica Printer - EMT

![GitHub stars](https://img.shields.io/github/stars/MoMortis/litematica-printer-EMT)
![GitHub last commit](https://img.shields.io/github/last-commit/MoMortis/litematica-printer-EMT)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11%20%7C%2026.1.2-blue)
![License](https://img.shields.io/badge/License-AGPL--3.0-green)

为 [Litematica](https://modrinth.com/mod/litematica) 投影添加自动建造功能的 Minecraft Fabric 模组。

本分支（1.4-EMT）基于 [BiliXWhite 二改版](https://github.com/BiliXWhite/litematica-printer)修改，面向大型原理图（如 1000×64×1000）做了大量性能与功能强化，支持 **1.21.11 与 26.1.2 双版本单 jar**。

如果你觉得好用，欢迎给项目点个 Star ⭐️

---

## 🎮 支持的游戏版本

| 版本            |
|---------------|
| 1.21.11 · 26.1.2 |

> [!NOTE]
> 两个版本打包在一个 jar 内（versionpack），放入 mods 文件夹即可自动适配。

---

## 📦 前置模组

### 必需
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [MaLiLib](https://modrinth.com/mod/malilib)
- [Litematica](https://modrinth.com/mod/litematica)

### 可选
- [Tweakeroo](https://modrinth.com/mod/tweakeroo) - 破基岩模式

---

## ✨ 特性

### 🚀 性能优化
- 原理图状态判定缓存（SchematicStateCache）：subregion 盒索引与变换预计算，按位置缓存判定结论
- 待办物品清单记忆化、有界空闲退避：空闲时自动降低扫描频率，有活立刻恢复
- 「工作时长预算」：扫描、放置、挖掘、重试、缺料统计等全部主流程受迭代时长约束，不卡主线程
- 「优先同种方块」定向扫描 + 缺料方块跳过：缺料的方块先跳过继续打印其他方块，不全场停工
- 数据包模式失败重试表：放置失败的方块冷却后直接重试，不占用放置额度
- 原理图指纹感知：移动/旋转/增删原理图立即感知，打印无缝切到新位置

### 🔍 扫描自动寻路
- BFS / DFS 双算法，均先全局选「离玩家最近的原理图子区块」为中心（玩家悬在原理图上方也能正确寻路）
- DFS：指针按配置方向序深度探索到底，围死回溯；BFS：从中心环形扩散搜索下一个中心
- 只在客户端渲染距离内（看得见地形）扫描与寻路
- 目标完成后锚定工作区块继续（≤32 格），不丢扫描进度
- 优先使用验证器缺失列表（离玩家最近），扫描器兜底
- 扫描对象 = 「扫描白名单」∪ 验证器高亮的缺失方块

### 🔊 音效与反馈
- 数据包模式音效修复：确认回调同时挂单方块与批量子区块更新包，破坏/放置音效不再缺失

### ⏩ 自动寻路移动
- 「强制疾跑」为跑酷跳总开关：关闭时不允许任何跑酷跳（含 1 格缺口），路径自动绕开缺口
- 平地长直线路段边跑边跳提速（方向锁定直线段远端路点）
- 路点飞越（含 45° 对角路段）沿路径方向正常推进
- 目标完成后玩家未远离则锚定继续，被带回工作区块

### 🎨 投影渲染
- 「仅渲染方块」+「仅渲染方块列表」：严格匹配列表内方块才渲染原理图（含流体与方块实体外观），列表外方块隐藏；多余/错误方块标记保留
- 配置变化自动全量重建原理图渲染

### ⏩ 功能改进（继承二改版）
- 数据包打印模式（速度更快，避免幽灵方块）
- 可视化放置进度条（HUD 显示）
- 代替放置 + 代替列表：每行「代替1,代替2,...:目标」严格匹配注册路径/完整ID/精确译名，代替方块豁免「破坏错误方块」
- 扫描白名单、破坏错误方块（破冰、放水）、填充功能
- 快捷潜影盒模拟点击取货（需服务器支持背包内打开潜影盒）
- 服务器卡顿检测，防止因延迟导致的大量方块放置错误

### 🛠️ 方块放置修复（继承二改版）
- 合成器、拉杆、红石粉（非连接模式）
- 枯叶、各种花簇的方向
- 发光浆果、带花的花盆
- 楼梯、藤蔓、缠怨藤、垂泪藤
- 砂轮、门、活版门、漏斗、箱子

---

## 📖 使用方法

1. 在世界中加载一个 Litematica 原理图（Schematic）
2. 移动到可以接触到原理图方块的位置
3. 按下 `Caps Lock` 键开启打印机
4. 等待自动建造完成 🎉

> [!TIP]
> 大部分功能都含有游戏内注释可供参考使用；「扫描自动寻路」需要在打印机配置中开启「扫描白名单」与「扫描自动寻路」。

---

## ⚠️ 未支持方块

以下方块由于特殊原因暂未实现，打印时会自动跳过或呈现错误状态：

- 装有液体的炼药锅
- 实体方块（物品展示框、盔甲架、画等）
- 非原版游戏内容

> [!TIP]
> 如发现其他方块放置错误，请尝试降低建造速度。若问题依旧存在，请提交 [Issue](https://github.com/MoMortis/litematica-printer-EMT/issues)

---

## 🔨 编译

### 命令行编译

```bash
git clone https://github.com/MoMortis/litematica-printer-EMT.git
cd litematica-printer-EMT
./gradlew build
```

### IDEA 编译

1. 用 IDEA 打开项目
2. 在 Gradle 面板中找到 `Tasks → build`，双击 `build`
3. 等待编译完成

### 构建产物位置

| 类型      | 位置                                                |
|---------|---------------------------------------------------|
| 多版本 jar（versionpack） | `./fabricWrapper/build/libs/`                     |
| 单版本 jar | `./fabricWrapper/build/tmp/submods/META-INF/jars` |

---

## ❓ 常见问题

### Q: 开启打印后，打印机不工作？

**可能原因：**
1. 服务器反作弊检测 — 投影打印机基于静默看向方式放置方块，可能被检测
2. 打印机工作间隔设置过小 — 有放置速率限制的服务器无法及时响应

**解决方案：**
- 开启「使用数据包打印」模式
- 调大「打印机工作间隔」

---

### Q: 打印机放置的方块是错的？

**可能原因：**
1. 服务器反作弊插件干扰
2. 打印机工作间隔过小，服务器响应不及时
3. 识别算法未考虑该方块特性

**解决方案：**
- 增大「打印机工作间隔」
- 降低建造速度

---

## 🙏 感谢

- [aleksilassila](https://github.com/aleksilassila/litematica-printer) - 原创基础
- [zhaixianyu](https://github.com/zhaixianyu/litematica-printer) - 二改版本
- [BiliXWhite](https://github.com/BiliXWhite/litematica-printer) - 二改版本（本分支的直接基础）
- [bunnyi116](https://github.com/bunnyi116/fabric-bedrock-miner) - 新的破基岩
- [masa](https://github.com/masa-fn) - Litematica / MaLiLib

以及所有支持开发的朋友，包括你！💖

---

## 📄 协议

本项目基于 [AGPL-3.0](LICENSE) 协议开源。
