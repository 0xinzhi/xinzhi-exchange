# 其他附属注册 EMC —— 方案（XinZhiExchange）

> 目标：让第三方 Slimefun 附属在自己的代码里声明「我的物品值多少」，
> 服主装好就能用，不用手改 `prices.yml`。
>
> **状态：已实现（2026-10-18）**。API 类已落地为 `net.me.xinzhi.exchange.api.EmcRegistry`，
> 并已在隔离服用一个测试附属验证（注册 unpriced 物品 / 覆盖内置表 / 被 prices.yml 覆盖 / 运行期防抖重建）。
> 本文保留为设计说明 + 接入手册。

---

## 0. 先分清两套系统

| 系统 | 类 | 现状 |
|---|---|---|
| 主附属 XinZhiTech（市场 / 等价） | `net.me.xinzhi.xinzhitech.api.EmcRegistry` | **已有**运行时注册表（register / registerAll / 监听器） |
| 独立版 XinZhiExchange | `net.me.xinzhi.exchange.api.EmcRegistry` | **已有**（2026-10-18 第九轮加的同形 API） |

两边互不读取：往主附属注册的值，独立版看不到；反之亦然。
所以「其他附属注册 EMC」要**两个系统各自接一次**。本方案只改独立版（主附属已有同类 API，照抄即可）。

---

## 1. 现在就能用的方式（零代码）

| 方式 | 谁操作 | 写在哪 | 优先级 | 生效 |
|---|---|---|---|---|
| 服主改 `prices.yml` | 服主 | `plugins/XinZhiExchange/prices.yml` 的 `slimefun:` 段 | 高于内置表 | `/xzex reload` |
| 服主用 `/xzex set <id> <价>` | 服主 | 同文件的 `manual:` 段 | **最高** | 立即 |
| 内置表 `prices-builtin.tsv` | 只有本插件自己发版能带 | jar 内 | 最低 | 重启 |

**问题**：`prices.yml` 是服主的文件，附属作者不能「随插件发布」；服主每次装新附属都要手抄一遍。
这正是要加运行时 API 的原因。

---

## 2. 推荐方案：运行时静态注册表

新增 `net.me.xinzhi.exchange.api.EmcRegistry`，接口与主附属**同名同形**（作者学一次两边都会）。

### 2.1 对外方法

```java
EmcRegistry.register("MY_ITEM", 5000.0);          // 单个（SF id，不带 SF:）
EmcRegistry.registerVanilla(Material.DIAMOND, 3000.0);
EmcRegistry.registerAll(Map<ItemKey, Double>);    // 批量
EmcRegistry.unregister(ItemKey);                  // 一般不用
EmcRegistry.get(ItemKey);                         // 未注册返回 -1
EmcRegistry.all();                                // 只读快照
EmcRegistry.addListener(ChangeListener);          // 供价表重建
```

### 2.2 优先级（从高到低）

1. `/xzex set` / `prices.yml → manual:`（服主手工价，绝对刻度）
2. `prices.yml → slimefun:` / `vanilla:`（服主覆盖）
3. **附属内嵌（本 API）**
4. `prices-builtin.tsv`（内置兜底）
5. 配方推导

> 也就是说：附属给的是「默认值」，服主随时能覆盖，但覆盖不会写回附属。

### 2.3 加载顺序无关

- API 是**静态表**，附属在 `onEnable` 里注册即可，早于/晚于独立版 enable 都行；
- 独立版 enable 时 `PriceBook.reload()` 会把静态表并进锚点；
- 独立版已经开跑后注册 → 监听器触发**防抖重建**（1 秒合并一次），不用重启。

### 2.4 多附属冲突：价高者胜

同一个物品被两个及以上附属注册不同值时，**只保留最高的那个值**（腐竹 2026-10-19「价高者胜吧」）：
先注册 30、后注册 50 → 50；先注册 50、后注册 30 → **仍 50**。也就是说内嵌值只会往上走，
不会被后加载的低价盖掉。它和服主价的优先级不变：手工价 / `prices.yml` 永远压过内嵌值。

> 推论：附属卸载不会清掉它注册的值（静态表只在 JVM 重启 / `unregister` 时消失），
> 排查「为什么这个物品这么贵」先用 `/xzex emc` 看有没有残留的第三方值。

### 2.5 只在「没有更高优先级」时生效

`PriceBook` 的合并顺序就是优先级：内置 → **内嵌** → `prices.yml` → `manual`。
内嵌值被 `prices.yml` 覆盖后，条目来源标记会变成 `prices.yml`，`/xzex price` 能看出是谁的。

---

## 3. 落地清单（要改哪些文件）

| 文件 | 改动 |
|---|---|
| `src/main/java/net/me/xinzhi/exchange/api/EmcRegistry.java` | **新增**，见 §4 |
| `price/PriceBook.java` | `reload()` 里在内置表之后、`prices.yml` 之前 `applyEmbedded()`；来源标 `addon` |
| `exchange/ExchangeService.java` | `enable()` 注册监听器（防抖重建）；`disable()` 移除；`info` 打印内嵌条数 |
| `exchange/ExchangeCommand.java` | `/xzex info` 加一行「附属内嵌 N 条」；可选 `/xzex emc` 列出内嵌条目 |
| `src/main/resources/plugin.yml` | 不用改（API 是类，不是命令） |
| 文档 | 交接书 Q §113 + 本文件 |

工作量：1 个新类（~150 行）+ 3 处小改，半天内。

---

## 4. API 完整实现（可直接复制）

```java
package net.me.xinzhi.exchange.api;

import net.me.xinzhi.exchange.core.ItemKey;
import org.bukkit.Material;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 附属内嵌 EMC（价值）注册表 —— 与主附属 {@code xinzhitech.api.EmcRegistry} 同形。
 *
 * <p>附属在自己的 onEnable 里注册，独立版会把它当成「默认锚点」；
 * 服主仍可用 prices.yml / 手工价覆盖。值必须 &gt; 0（0 = 没价，不收）。
 *
 * <p>线程安全；注册早于/晚于独立版加载都可以。
 */
public final class EmcRegistry {

    public interface ChangeListener {
        void onEmcRegistered(Map<ItemKey, Double> added);
    }

    private static final Map<ItemKey, Double> EMBEDDED = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ChangeListener> LISTENERS = new CopyOnWriteArrayList<>();

    private EmcRegistry() {}

    // ---- 注册 ----

    /** Slimefun 物品 id（不带 SF: 前缀） */
    public static void register(String sfId, double value) {
        if (sfId == null || sfId.isBlank() || !(value > 0)) return;
        register(ItemKey.slimefun(sfId), value);
    }

    public static void registerVanilla(Material mat, double value) {
        if (mat == null || !(value > 0)) return;
        register(ItemKey.vanilla(mat), value);
    }

    public static void register(ItemKey key, double value) {
        if (key == null || !(value > 0) || Double.isNaN(value) || Double.isInfinite(value)) return;
        EMBEDDED.put(key, value);
        notifyListeners(Map.of(key, value));
    }

    public static void registerAll(Map<ItemKey, Double> map) {
        if (map == null || map.isEmpty()) return;
        Map<ItemKey, Double> added = new ConcurrentHashMap<>();
        for (Map.Entry<ItemKey, Double> e : map.entrySet()) {
            ItemKey k = e.getKey();
            Double v = e.getValue();
            if (k == null || v == null || !(v > 0) || Double.isNaN(v) || Double.isInfinite(v)) continue;
            EMBEDDED.put(k, v);
            added.put(k, v);
        }
        if (!added.isEmpty()) notifyListeners(added);
    }

    public static void unregister(ItemKey key) {
        if (key != null) EMBEDDED.remove(key);
    }

    // ---- 查询 ----

    public static boolean contains(ItemKey key) { return key != null && EMBEDDED.containsKey(key); }

    /** 未注册返回 -1 */
    public static double get(ItemKey key) {
        if (key == null) return -1;
        Double v = EMBEDDED.get(key);
        return v == null ? -1 : v;
    }

    public static Map<ItemKey, Double> all() { return Collections.unmodifiableMap(EMBEDDED); }

    public static int size() { return EMBEDDED.size(); }

    // ---- 监听器（价表重建用） ----

    public static void addListener(ChangeListener l) { if (l != null) LISTENERS.addIfAbsent(l); }

    public static void removeListener(ChangeListener l) { if (l != null) LISTENERS.remove(l); }

    private static void notifyListeners(Map<ItemKey, Double> added) {
        for (ChangeListener l : LISTENERS) {
            try { l.onEmcRegistered(added); } catch (Throwable ignored) {}
        }
    }
}
```

---

## 5. 接入改动（片段）

### 5.1 PriceBook.reload()：内置之后、prices.yml 之前

```java
loadBuiltin();
anchors.putAll(builtin);
for (ItemKey k : builtin.keySet()) origin.put(k, "builtin");

// ★ 附属内嵌：优先级在 prices.yml 之下、内置表之上
for (Map.Entry<ItemKey, Double> e : EmcRegistry.all().entrySet()) {
    anchors.put(e.getKey(), e.getValue());
    origin.put(e.getKey(), "addon");
}

// 之后照旧 mergeSection(vanilla/slimefun) → manual
```

### 5.2 ExchangeService：注册监听 + 防抖重建

```java
private final java.util.concurrent.atomic.AtomicBoolean pending = new AtomicBoolean();

// enable() 里（book.reload() 之后）
EmcRegistry.addListener(added -> {
    if (!pending.compareAndSet(false, true)) return;
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
        pending.set(false);
        if (plugin.isEnabled()) rebuildPrices("附属注册 EMC（" + added.size() + " 条）");
    }, 20L);
});

// disable() 里
EmcRegistry.removeListener(listener);
```

### 5.3 /xzex info

```java
s.sendMessage("§7附属内嵌: §e" + EmcRegistry.size() + " §7条");
```

---

## 6. 附属侧接入（给作者看的）

### 6.1 plugin.yml

```yaml
depend: [ Slimefun, XinZhiExchange ]
```

> 如果想让「没装独立版也能跑」：改成 `softdepend: [ XinZhiExchange ]`，
> 注册前判 `Bukkit.getPluginManager().isPluginEnabled("XinZhiExchange")`，并 try/catch `NoClassDefFoundError`。

### 6.2 onEnable

```java
import net.me.xinzhi.exchange.api.EmcRegistry;
import net.me.xinzhi.exchange.core.ItemKey;
import java.util.LinkedHashMap;
import java.util.Map;

public void onEnable() {
    // 单个
    EmcRegistry.register("MY_CUSTOM_ITEM", 5000.0);

    // 批量（推荐：一次注册完整张表，减少重建次数）
    Map<ItemKey, Double> map = new LinkedHashMap<>();
    map.put(ItemKey.slimefun("MY_INGOT"), 120.0);
    map.put(ItemKey.slimefun("MY_MACHINE"), 8000.0);
    EmcRegistry.registerAll(map);
}
```

### 6.3 注意事项

- **id 必须是 Slimefun 注册时的 id**（`SlimefunItemStack#getItemId()`），大小写敏感；
- 价格单位与独立版一致（圆石 = 1）；
- 不要给「由配方能算出来」的物品乱写高值——内嵌是锚点，会封顶该物品的价；
- 附属重载（`/reload` 不建议）时要重新注册，独立版不持久化附属值。

---

## 7. 验收用例

1. 附属先于独立版 enable 注册 → 独立版启动后 `/xzex price MY_ITEM` 显示内嵌价，来源 `addon`；
2. 独立版已启动后注册 → 1 秒内 `/xzex price` 更新（日志出现「附属注册 EMC」重建行）；
3. `prices.yml` 里写同一个 id → 服主值胜出，来源 `prices.yml`；
4. `/xzex set` 同一个 id → 手工价胜出，来源 `manual`；
5. 注册 0 / 负数 / 拼错 id → 不生效、不报错（可选：`/xzex emc` 里能看到拼错的条目）；
6. 附属没装独立版（softdepend）→ 不抛 `NoClassDefFoundError`。

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 附属 id 拼错，静默不生效 | `/xzex emc` 列出「注册了但 Slimefun 查不到」的条目 + 后台 warn |
| 附属注册得太早，物品还没注册 | API 不查 Slimefun，只存 key；真正校验放到 `rebuildPrices` 时 |
| 值 0 / 负数 | 直接忽略（0 在价表里等于「没价」） |
| 多附属注册同一个 id | 后注册的覆盖先注册的（与主附属一致）；日志可打印冲突 |
| 内嵌值被当成「绝对刻度」 | 是设计如此：锚点优先于配方推导 |
| 跨版本 API 变动 | 只暴露静态方法，加方法不算破坏；删方法要升 major |

---

## 9. 与主附属的关系

- 主附属已有 `xinzhitech.api.EmcRegistry`，本方案是**同形实现**，作者两边各调一次；
- 主附属的「批量定价」面板已经能导出 `EmcRegistry.registerAll(...)` 代码（`MarketBatchPricingListener`），可直接当范例；
- 不建议独立版去读主附属的注册表（会重新耦合）；
- 如果以后要做「一次注册、两边生效」，再加一个薄的 `XinZhiEmcBridge` 适配层，而不是让两边互相依赖。
