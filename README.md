# XinZhiExchange · 等价交换（独立版）

一个独立的 [Slimefun](https://github.com/Slimefun/Slimefun4) 附属：给服务器加一套自洽的等价交换体系——
自带配方扫描、定价表、玩家余额，不依赖其他附属，可与别的附属同服共存。

> 说明：本项目是从大一统附属里摘出来的独立版本，方便单独试验定价与配方扫描逻辑。

## 它做什么

- **兑换界面**：把物品卖成「等价物」（EMC 类点数），再用点数买别的物品；支持搜索、分类、批量与 Shift 快捷操作。
- **定价**：优先用服务器上真实机器的配方反算单位价（取最低价），算不出来时退回内置基础定价表（约 1600 条锚点）。
- **机器**：自动出售 / 自动收购 / 合成机 / 传输机 / EMC 生成器，五种机器可拼接成产线。
- **功能道具**：飞行护符、光环护符（可配置为消耗品式的能力解锁）。
- **管理工具**：价格定制 / 批量定价道具，配合 `/xzex admin` 直接改价，无需改文件重载。

## 环境要求

| 项 | 版本 |
|---|---|
| 服务端 | Paper / Spigot 1.21+（`api-version: 1.21`） |
| Slimefun | Slimefun4（2025.x） |
| JDK | 21（编译与运行） |

## 构建

```bash
# 需要 JDK 21 与 Maven
mvn clean package
# 产物：target/xinzhi-exchange-0.1.0.jar
```

把 jar 丢进 `plugins/` 与 Slimefun 一起启动即可。首次启动会在 `plugins/XinZhiExchange/` 生成：

```
recipes/            扫描出的配方库（index + 每台机器一个 json）
prices.yml          vanilla / slimefun / manual 三段定价表
balance.yml         玩家余额
players.yml         玩家设置
mob-drop-prices.yml 生物掉落定价
diag/               扫描诊断输出
```

## 命令

主命令 `/xzex`（别名 `exchangex`、`djjh`）。

| 命令 | 说明 | 权限 |
|---|---|---|
| `/xzex` | 打开兑换界面 | `xzex.open` |
| `/xzex price <物品>` | 查询物品价格 | `xzex.use` |
| `/xzex balance` | 查看余额 | `xzex.use` |
| `/xzex top` / `scan` / `rebuild` | 榜单 / 重扫配方 / 重建定价 | `xzex.viewall` / `xzex.admin` |
| `/xzex set` / `unset` / `reload` / `export` | 改价 / 删价 / 重载 / 导出 | `xzex.admin` |
| `/xzex give <道具>` | 发放道具 | `xzex.admin` |

## 配置

主要开关在 `plugins/XinZhiExchange/config.yml`，所有键的默认值同时硬编码在代码里，
所以老配置文件缺键也能正常跑。常用项：

- `price.tax` 手续费（卖出扣、买入加，0 = 平价）
- `price.trade-max` 单件价格上限（高于它的物品整件禁止交易，0 = 不限）
- `price.max-per-transaction` 单笔兑换上限（0 = 不限）
- `price.compressed-sell-cap` 压缩类物品卖价封顶

## 许可

见仓库内 LICENSE（未附带时默认保留所有权利，使用前请先联系作者）。
