
免责声明
------------
1.此客户端由ai生成，ReadMe也由AI生成
2.此客户端用于模组测试，不要服务器使用
3.如果造成封禁后果概不负责
------------
九个模块，加上一个常开功能：

| 模块 | 行为 | 开关 | 默认 |
| --- | --- | --- | --- |
| **Eagle · 潜行** | 快踩空时替你按 Shift，用原版潜行挡住你 | `V` | 开 |
| **Eagle · 右键提速** | 长按右键时把放置节奏提到 6~9 CPS | 同 `V` | 开 |
| **AutoArmour** | 自动识别并穿上背包里最好的盔甲 | `F6` | **关** |
| **AimAssist** | 往目标方向修正鼠标向量，镜头真的转过去 | `R` | **关** |
| **InvChest** | 打开箱子后每 0.85 秒取一件，高级物品优先 | `N` | **关** |
| **ESP** | 透墙高亮玩家轮廓与名字，带距离 | `G` | **关** |
| **BlockIn** | 自动在自己四周砌墙把自己围住，平滑转头 | `K` | **关** |
| **Account** | 换成自定义名字的离线账户 | 多人界面按钮 | **关** |
| **RemoteShop** | 关掉箱子/商店后不真正关闭，按 H 随时唤回 | `H` | **关** |
| **Sprint** | 按住前进就疾跑 | GUI（Movement） | 开 |

按 **右 Shift** 打开 ClickGUI，所有开关和参数都能在里面直接调。

聊天框里的客户端指令（`.inv speed <秒>`）可以随时调 InvChest 的节奏，详见第四节。

Eagle 只改输入记录和原版的放置冷却计数，**永远不动世界**；BlockIn 会真的放置方块，
AimAssist 会真的转动镜头，InvChest 会真的取走箱子里的东西，所以它们默认关闭，需要你手动开。

AimAssist、InvChest、ESP、AutoArmour、BlockIn 与 Eagle 都无关，各自独立，`enabled`（V 键）不影响它们。
ESP 是纯本地显示，不改任何游戏状态。

**右 Shift 打开的 ClickGUI**（第六节）把八个模块的全部参数放在一起调，
它和热键、聊天指令读写的是同一份配置，不存在两边不一致的问题。

* Minecraft **1.21.4** / Fabric Loader ≥ 0.16 / Fabric API / Java 21
* 纯客户端（`"environment": "client"`），单机、局域网、服务器都能用

---

## 一、Eagle

### 1. 判定：预测下一次落脚

每一客户端刻（tick）取一次玩家「想走的方向」，把方向向量归一化后，
在**身体中心前方 `0.3 + edgeOffset` 格**处放一个很小的探测盒（下方 0.12 格），
问一次世界：

```java
world.isSpaceEmpty(player, probe)   // 返回 true 说明前方脚下是空气
```

没有任何碰撞体 → 前方就是方块边缘 → 该潜行了。

`0.3` 是原版玩家碰撞箱的半宽（0.6 / 2）。所以探针离开方块的那一刻，
身体前沿距离边缘还有整整 `edgeOffset` 格的安全余量。

方向来源按优先级：

1. `player.input.movementForward / movementSideways`（键盘，同刻生效，最灵敏）
2. 水平速度（处理被推动的情况）
3. 上一次的已知方向（**在边缘停下放方块时保持潜行**）

### 2. 执行：只改原版的输入记录

注入 `KeyboardInput#tick()` 的 TAIL：

```java
this.playerInput = new PlayerInput(
        sampled.forward(), sampled.backward(), sampled.left(), sampled.right(),
        sampled.jump(),
        true,              // <-- 只把 sneak 置为 true
        sampled.sprint());
```

之所以这样就够了，是因为 1.21.4 里这条链是直连的：

```
input.playerInput.sneak()
  → ClientPlayerEntity.isSneaking()
    → PlayerEntity.clipAtLedge()          // 实测：就是 return this.isSneaking()
      → PlayerEntity.adjustMovementForSneaking(...)   // 把位移裁成 0.05 格一步 → 停在边缘
```

同时在 `ClientPlayerEntity.sendMovementPackets()` 里，`PlayerInput` 一变就会发出
`PlayerInputC2SPacket`，所以**服务端收到的就是一次普通的潜行输入**。

### 3. 人性化

* **迟滞（hysteresis）**：触发用远端探针（`0.3 + edgeOffset`），
  解除用近端探针（`0.3 + edgeOffset - releaseMargin`），避免贴着接缝走时状态抖动。
* **随机延迟**：`releaseDelayMs` 默认 40ms，且随机化到 `0..40`，潜行时长没有固定节奏。
* **不抢玩家的手**：读到玩家自己按住了 Shift 就整个让开。
* **不改按键状态**：只改输入记录，`KeyBinding.wasPressed()` 不受影响，
  不会误触发「潜行下车」之类的原版逻辑。

### 4. 右键 CPS 提速

原版**长按**右键的节奏是写死的：

```java
doItemUse()          // itemUseCooldown = 4
tick()               // if (itemUseCooldown > 0) itemUseCooldown--;
handleInputEvents()  // if (useKey.isPressed() && itemUseCooldown == 0 && !isUsingItem) doItemUse();
```

冷却 4 刻一减，等于**固定 5 CPS**，想快也快不了。

提速的做法不是自己去调 `doItemUse()`——它是 private，而且绕过去会丢掉准星刷新和
`isUsingItem` 保护——而是**在 `MinecraftClient.tick()` 的第一行替原版调度这个计数器**：

* 这一 tick 该出手 → 把 `itemUseCooldown` 置 `0`。原版自己那条路径照常触发，
  用的是同一 tick 刚刷新过的准星目标，所有保护也都还在。
* 这一 tick 不出手 → 把计数器压在 `≥ 2`，减完之后永远到不了 0，原版就不会自己放。

节奏由一个毫秒累加器控制，每次间隔在 `1000/cpsMax ~ 1000/cpsMin` 之间重新随机会。
不这么做的话，20 刻/秒的网格只会把速率钉死在 20/2 = 10 或 20/3 ≈ 6.7 CPS 上。
默认 6~9 的长时实测均值约 **7.2 CPS**。

**它不会自己放方块。** 唯一的启动条件是 `mc.options.useKey.isPressed()`——
玩家自己按住右键的原始按键状态，模组从头到尾不碰任何 KeyBinding。
松手累加器立刻清零，所以点一下仍然只算一下。

> 注意 `wasPressed()` 那条离散按下的路径不受冷却影响，所以快点点右键本来就是想多快有多快。
> 提速只作用于「按住不放」。

### 5. 强制疾跑

按着**前进**就自动疾跑，不用双击 W，也不用按疾跑键。

开关在 ClickGUI 的 **Movement** 分类里，`Sprint` 那一行；配置文件里是 `sprintEnabled`，默认开。
**没有快捷键**——它是个常驻便利功能，不值得占一个键位。

它在每个客户端 tick 都重新请求一次疾跑状态，因为原版会在它自己认为不该疾跑的时候把这个状态丢掉：

```java
if (!cfg.sprintEnabled) return;
if (!mc.options.forwardKey.isPressed()) return;
if (player.isSprinting()) return;
if (player.isSneaking() || player.isUsingItem()) return;
player.setSprinting(true);
```

两个状态**故意让开**：

* **潜行优先**。强行穿过潜行会和玩家自己的输入打架，也会直接破坏 Eagle 的边缘保护——那个功能
  正是靠潜行工作的。
* **使用物品时跳过**。拉弓和吃东西期间疾跑本身就是非法状态，下一 tick 就会被原版纠正回来。

还有一条：打开任何界面时不动手。按键绑定在开界面时会保留之前的状态，所以按住 W 再按 Esc，
`isPressed()` 仍然是 true，但那时候玩家并没有在走路。

---

## 二、AutoArmour：自动换甲

按 **F6** 开关。每 `autoArmourDelayMs`（默认 **300ms**）检查一次，把背包里最好的盔甲穿上。

### 1. 怎么把盔甲穿上

用的是玩家**自己的**物品栏 handler——它在没有打开任何界面时也是同步的，所以直接朝它发 shift-click 就行。
而 shift-click 一件盔甲本来就是原版会做的事：`PlayerScreenHandler.quickMove` 会认出
`EquipmentSlot.Type.HUMANOID_ARMOR` 并把它挪过去。

```java
mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, player);
```

槽位号没有硬编码，用的是原版自己的常量（实测 `EQUIPMENT_START=5`、`INVENTORY_START=9`）：

* 某个盔甲槽 = `EQUIPMENT_START + (3 - equipmentSlot.getEntitySlotId())`
* 候选来源 = `INVENTORY_START` 起 36 格（27 主背包 + 9 快捷栏）

**这里有个坑**：`PlayerScreenHandler.quickMove` 的盔甲分支带一个条件——
`!this.slots.get(...).hasStack()`，也就是**只有盔甲槽是空的时候才会穿上去**。
所以替换必须点两次：先把旧的 shift-click 挪回背包，再把新的 shift-click 穿上。

盔甲不能堆叠，所以第二次点击不可能干扰第一次，候选的槽位号也不会移位——
这就是为什么可以在同一 tick 里连着发这两个包。

### 2. 「最好」怎么算

```
分数 = 材质等级 × 10000 + 附魔等级总和 × 500 + 剩余耐久比例 × 200
```

材质等级：下界合金 6 > 钻石 5 > 海龟壳 4 > 铁 3 > 锁链 2 > 金 1 > 皮革 0。

**材质的权重是故意做得极大的**：说「最好的盔甲」指的是最好的**材质**，
不该出现一堆附魔让皮革帽压过光板钻石头。附魔和耐久只在同材质之间分高下。

实测排序严格单调：

```
LEATHER 200  <  GOLD 10200  <  CHAIN 20200  <  IRON 30200
             <  TURTLE 40200  <  DIAMOND 50200  <  NETHERITE 60200
```

耐久项也真的生效：磨损到 160 的铁头盔是 **30006**，低于全新的 **30200**，所以快坏的会被换掉。

### 3. 静止闸门

`autoArmourIdleOnly` 默认开启：**疾跑中或还在移动时，换甲整个挂起，站定之后才继续。**

```java
if (cfg.autoArmourIdleOnly && (player.isSprinting()
        || player.getVelocity().horizontalLengthSquared() > 1.0E-4D)) {
    return;   // 站定再换
}
```

阈值是 0.01 格/刻（约 0.2 格/秒），站立时水平速度是 0，所以走起来就停、站住就恢复。

这不是「让检测看不见」，而是**让那个动作根本不发生**：在移动时点物品栏格子本身就是一条违规，
不管包怎么包装都还是。所以这里的做法是等——慢一点，但不需要对服务端说任何假话。

不想要的话在 GUI 的 AutoArmour 里把 `Idle Only` 关掉。

### 4. 其他

* **一次只处理一件**，而不是四件一起换。整套换完 4 件约 1.2 秒，
  看起来像一串有节奏的切换，而不是一串爆发包。
* **打开任何界面时不动手**——那时候 `player.currentScreenHandler` 是别人的，
  朝玩家的 handler 发包会指错对象。
* `autoArmourKeepElytra` 默认开：胸甲位置穿着鞘翅时跳过，不会把你飞行中的鞘翅换成胸甲。
* 鞘翅本身也不会被当作候选穿上（它的护甲值是 0）。

---

## 三、AimAssist

默认只做「辅助瞄准」，**不自动攻击**——打不打、什么时候打完全由你控制。
想要它自己挥刀的话，在 AimAssist 的设置里打开 `Auto Attack`（默认关），见第 5 节。

### 1. 实现路径：修正鼠标向量

原理里说的「在操作系统层面拦截并修正鼠标移动信号」我没有做——那是在系统层挂钩输入，
属于另一类东西，对一个模组来说也没必要。游戏内有一个等价且干净得多的位置。

`Mouse#tick()` 的名字有误导性：它其实是从 `MinecraftClient#render` **每帧**调用的，
而且一次调用只做三件事——

```java
double d = this.cursorDeltaX;      // 本帧累积的真实鼠标位移
double e = this.cursorDeltaY;
if (client.player != null) {
    this.updateMouse(frameTime);   // 消费这两个值，转成视角
}
this.cursorDeltaX = 0.0;           // 清零
this.cursorDeltaY = 0.0;
```

所以在 `updateMouse` 的**开头**往这两个字段里加一笔，会被**恰好消费一次**，
而且是和真实鼠标位移在同一个表达式里被消费的：

```java
@Inject(method = "updateMouse", at = @At("HEAD"))
private void eagle$aimAssist(double time, CallbackInfo ci) {
    double[] c = AimAssistLogic.mouseCorrection(MinecraftClient.getInstance());
    if (c == null) return;
    this.cursorDeltaX += c[0];
    this.cursorDeltaY += c[1];
}
```

三个好处是自己掉出来的：

* 镜头**真的会转**（你选的可见旋转），不是只骗服务端
* 原版的鼠标灵敏度曲线自动作用在这笔修正上
* 你自己的鼠标输入完全不受影响，辅助是**叠加**上去的

### 2. 度数换算

vanilla 整条单位链是：

```
视角度数 = cursorDelta × ( 灵敏度 × 0.6 + 0.2 )³ × 8 × 0.15
                          └─────────┬─────────┘
                              记作 h
```

`h` 在灵敏度 50% 处正好等于 **1.0**。这个值实测过：

| 灵敏度 | 0% | 25% | 50% | 100% |
| --- | --- | --- | --- | --- |
| `h` | 0.064 | 0.343 | **1.000** | 4.096 |

`aimSensitivitySync` 决定这笔修正要不要也乘上 `h`：

* **开（默认）**——加固定 cursor 单位，实际度数 = 设定值 × `h`。
  灵敏度越高，辅助和你的鼠标一起变快，这是原理里说的「灵敏度同步」。
* **关**——先除以 `h`，实际度数**恒等于设定值**，与灵敏度完全无关。

实测：两种模式在灵敏度 50% 时完全一致，其余档位按上表缩放。
如果你把游戏灵敏度调得很高（`h` 最大到 4.1），建议把 `aimHorizontalSpeed` 相应调小。

### 3. 平滑：不瞬间锁定

指数逼近，并把速度归一化到 **50 ms 参考窗口**，所以 60 fps 和 240 fps 手感一致：

```java
alpha = 1 - (1 - speed) ^ (dt / 0.05);
step  = 误差角度 × alpha;
```

`aimHorizontalSpeed` / `aimVerticalSpeed` 就是那个 speed（0~1）。
垂直速度默认比水平低一点，用来保持爆头线稳定。

### 4. 目标筛选与人性化

* 在 `aimMaxAngle` 锥形 + `aimDistance` 距离内搜索
* 取**离准星最近**的那个，而不是最近的实体——这样才有「磁吸」感，而不是被硬拽过去
* `aimThroughWalls` 关闭时用 `LivingEntity.canSee()` 做视线检查
* 瞄准点在碰撞箱高度的 82%（上胸 / 爆头线）
* `aimJitterDegrees` 加一个缓慢漂移的随机偏移：每 150~400 ms 重掷目标点，
  再用指数插值**平滑滑过去**，所以重掷不会表现为抖动
* `aimStrafeIncrease`：你在移动时按水平速度把辅助速度最多提高 50%

### 5. 自动攻击（默认关）

在 AimAssist 的设置里打开 `Auto Attack`。它是这个模块里**唯一会动手**的部分，
所以单独做成一个要你主动勾的开关，而不是跟着模块一起开。

跑在**客户端 tick** 上而不是每帧——它依赖的两个量（触及距离、攻击冷却）都是按刻推进的。

四条全部成立才允许挥一次：

```java
if (!cfg.aimThroughWalls && !self.canSee(target)) return;              // 视线
double reach = self.getEntityInteractionRange() + target.getWidth() * 0.5D;
if (distance > reach) return;                                          // 距离
if (cos < ATTACK_CONE_COS) return;                                     // 准星真的压上去了
if (self.getAttackCooldownProgress(0.5F) < 1.0F) return;               // 蓄满力
```

`ATTACK_CONE_COS` 是 **8°**，比辅助自己的捕获锥（默认 90°）紧得多。原因是：
在偏离准星 40° 的地方挥刀，对服务端来说就是砍空气，所以先让辅助把视角收敛过去，再出手。

`getAttackCooldownProgress` 必须到 1.0 才挥，也就是**等蓄力条满**——这样攻速就是原版的攻速，
不会出现服务端一眼看出不可能的连续攻击。

`attackEntity` + `swingHand` 是原版 `doAttack()` 的两行，走的是同一条发包路径。

### 6. Team：靠染色识别队友（默认关）

在 AimAssist 的设置里打开 `Team`。打开后，**护甲染色和自己一样的玩家不会被瞄准**。

组队服务器普遍用皮甲染色区分阵营，所以「染色相同」是客户端能拿到的最可靠信号。
读的是 `DataComponentTypes.DYED_COLOR`——1.21 里皮甲颜色是数据组件，不再是 `DyeableItem`：

```java
DyedColorComponent dyed = stack.get(DataComponentTypes.DYED_COLOR);
return dyed == null ? -1 : dyed.rgb();      // 未染色 = -1
```

判定规则**刻意做得严格**：自己身上**每一处**有颜色的格子都必须和对方完全一致，
并且至少要有这么一处。也就是说：

* 头盔对、胸甲不对 → **不是**队友，照瞄
* 自己全身都没染色（没有可比信息）→ **谁都不排除**，照常工作
* 自己只有一格染色且对方一致 → 是队友

把这条判定抽成了纯函数 `sameColours(int[] mine, int[] theirs)`，所以能直接喂数据验证。
实测（真实 `ItemStack` + 真实读取路径）：

```
dyedColor(未染色皮甲) = -1      dyedColor(红色皮甲) = 16711680 (0xFF0000)
red/red   = true    ← 同队，排除
red/blue  = false   ← 不同，瞄准
none/none = false   ← 无信息，不排除
mixed     = false   ← 头盔对胸甲错，不算队友
```

默认关，因为它会**静默忽略**一部分玩家，这种事应该手动决定。

---

## 四、InvChest

按 **N** 开关。开启后只要打开箱子（或木桶、末影箱、潜影盒、发射器、投掷器）就开始工作。

### 1. 取物本身完全是原版交互

没有伪造 `ClickSlotC2SPacket`。把一件物品从容器挪进背包，本来就是游戏会的操作：

```java
mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, player);
```

这正是玩家 shift + 左键点那一格时走的调用，服务端看到的是一次普通的容器交互，
客户端的预测也照常同步。

### 2. 等级评分

难点全在**取什么**上。`InvChestLogic.tierOf()` 把物品打成 1~5 分：

| 分 | 内容 |
| --- | --- |
| **5** | 下界合金全套、下界合金锭/块/残骸、鞘翅、不死图腾、附魔金苹果、下界之星、龙蛋、信标、潮涌核心、附魔书、**所有颜色的潜影盒** |
| **4** | 钻石全套、钻石、绿宝石、三叉戟、弓、弩、末影水晶、重生锚、金苹果、经验瓶、末影珍珠/之眼、潜影壳 |
| **3** | 铁全套、铁/金锭与块、盾牌、黑曜石、红石、青金石、石英、紫水晶碎片、各种箭、烈焰棒/粉、金胡萝卜、熟食 |
| **2** | 皮革/锁链/金装备、石制工具、煤炭、皮革、线、羽毛、燧石、木棍、骨头、粘液球、火药、鸡蛋 |
| **1** | 其余全部（泥土、圆石这类） |

两个设计细节：

* **原版稀有度是不够用的**。钻石和下界合金装备在 `Rarity` 里都是 `COMMON`，
  所以真正重要的物品必须显式列表，剩下的才回落到稀有度。
  实测：`NETHERITE_SWORD=5  DIAMOND_SWORD=4  IRON_SWORD=3  LEATHER_BOOTS=2  DIRT=1`。
* **附魔会往上抬**：`tier = max(tier, 3) + 1`，所以一把附魔铁剑（3 → 4）会排在普通铁剑前面。
* 潜影盒用的是 `instanceof ShulkerBoxBlock` 通配，不用把 17 种颜色列一遍。
  实测 `SHULKER_BOX=5  LIME_SHULKER_BOX=5`。

同分时取**堆叠数量大**的，因为一次点击搬走一整堆，64 个远比 1 个划算。

### 3. 背包已有的就跳过

开启 `invChestSkipExisting`（默认）时，容器里任何**玩家背包里已经有同种物品**的格子都会被跳过——
比对的是 `Item` 本身，不比较数量、不比较 NBT。

这样一箱圆石不会把那颗钻石淹掉。副手也算在内。

### 4. 节奏

一次点击之后等 `invChestDelayMs`（默认 **850 ms**）再取下一件，正好是需求里的 0.85 秒。

另外，**刚打开容器时会先等满一个间隔**才动手，所以开箱的瞬间不会立刻飞出去一次点击。

`invChestCloseWhenDone` 打开后，等到没有任何可取的物品时会自动关掉容器——
同样要等满一个间隔，避免「一打开就关」的闪烁。

### 5. 客户端指令

在游戏聊天框里直接输入即可，**支持小数**：

```
.inv speed 0.85      间隔 0.85 秒/件（约 1.176 件/秒）
.inv speed 0.5       半秒一件
.inv speed 1.25      1.25 秒一件
.inv                 查看当前设置和用法
```

指令**只在本机处理，不会发给服务器**。拦截点是
`ClientPlayNetworkHandler#sendChatMessage(String)`——所有普通聊天消息都从这里出去
（斜杠指令走的是隔壁的 `sendChatCommand`，我们完全不碰）：

```java
@Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
private void eagle$clientCommand(String content, CallbackInfo ci) {
    if (EagleCommands.handle(MinecraftClient.getInstance(), content)) {
        ci.cancel();
    }
}
```

`ci.cancel()` 一按，这段文字就**根本没有变成数据包**。回显走的是
`ChatHud#addMessage(Text)`，那是纯客户端调用，同样不发任何东西。

两个行为上的细节：

* **只吞自己的指令**。`.inv` 开头的会被拦下，但 `.hello` 不是我们的，会照常发到服务器，
  而不是凭空消失。指令词表在 `EagleCommands.ROOTS` 里。
* **回显同时给出两种说法**（`0.85 秒/件` 和 `约 1.176 件/秒`），
  这样数字的含义永远不会有歧义。

解析用的是 `Double.parseDouble`，所以 `0.85` / `1` / `1.25` / `.5` 都行；
非数字会报错并保持原值；超出允许范围（0.05 ~ 10 秒）会被钳制到边界并明确告诉你钳到了哪里。

---

## 五、ESP

按 **G** 开关。纯本地显示，不改任何游戏状态、不发任何包。

### 1. 轮廓：直接写发光标志位

第一版这里我用的是 `entity.setGlowing(true)`，**它是错的，轮廓根本没出来**。原因在 `Entity` 里：

```java
public boolean isGlowing() {
    if (getWorld().isClient()) return getFlag(6);   // 客户端读的是 DataTracker 标志位
    return this.glowing;                             // 服务端才读本地字段
}

public final void setGlowing(boolean glowing) {
    this.glowing = glowing;
    this.setFlag(6, this.isGlowing());   // ← 客户端这里读回来的是「旧值」
}
```

`setGlowing` 是给服务端用的。客户端调用时，第二步把标志位又写回了原样，**等于空操作**。

所以改成直接写标志位。`getFlag` / `setFlag` 都是 `protected`，用一个 `@Invoker` 接口 mixin 打开：

```java
@Mixin(Entity.class)
public interface EntityFlagsMixin {
    @Invoker("getFlag") boolean eagle$getFlag(int index);
    @Invoker("setFlag") void eagle$setFlag(int index, boolean value);
}
```

原版渲染器对发光实体画的描边天然穿墙（光谱箭就是这个效果），代价接近零。

释放时会**恢复原标志位**而不是直接清掉，所以别人身上本来就有光谱箭 / 发光药水时不会被我们误清。

### 2. 名字：自己画，因为原版的会「看情况」

原版名签的图层是这样选的：

```java
boolean bl = !state.sneaking;                      // 不潜行 = true
TextLayerType layer = bl ? SEE_THROUGH : NORMAL;   // 潜行 → 退回深度测试
```

也就是说**潜行的玩家，名字会被墙挡住**。对 ESP 来说这是不能接受的（搭路、蹲点的人全在潜行）。

所以改成自己画。原版那边只需要把标签位置清空，它自己的名签通道就会立刻返回：

```java
if (EspLogic.isTracked(entity)) {
    state.nameLabelPos = null;   // 原版名签通道在 nameLabelPos == null 时直接 return
}
```

然后在 `WorldRenderEvents.AFTER_ENTITIES` 里用 `TextLayerType.SEE_THROUGH` 画我们自己的：

```java
matrices.translate(pos.x - cam.x, pos.y + entity.getHeight() + 0.5 - cam.y, pos.z - cam.z);
matrices.multiply(camera.getRotation());        // 面向摄像机
matrices.scale(0.025F, -0.025F, 0.025F);
textRenderer.draw(text, -width / 2.0F, 0.0F, 0xFFFFFFFF, true, matrix, consumers,
        TextRenderer.TextLayerType.SEE_THROUGH, 0x40000000, 0xF000F0);   // 全亮，暗处也看得清
```

`AFTER_ENTITIES` 拿到的世界矩阵栈已经转到摄像机朝向，所以按 `世界坐标 - 摄像机坐标` 平移即可，
和原版自己渲染实体和名签的方式一致。

### 3. 性能

| 做法 | 原因 |
| --- | --- |
| 追踪集合**每 `espRefreshTicks` 刻重建一次**（默认 4 刻 = 200ms） | 实体扫描、`Text` 分配、写标志位全部放在定时器里，不放在每帧 |
| 每帧路径只有 `isEmpty()` 判断 + 一次 HashMap 查表 | `isTracked` 每帧、每个被渲染实体都会调用 |
| **只在实体进出集合时**写标志位 | 不是每次刷新都写 |
| 距离过滤用 `squaredDistanceTo`，不做开方 | 热路径 |
| `Text` 对象**缓存**在 map 里，不每帧重建 | 避免每秒上百次对象分配 |
| 关掉或换世界时立即 `releaseAll()` | 不留悬挂引用、不留被改过的标志位 |
| 用 `getEntitiesByClass(..., box, ...)` 而不是遍历全世界的实体 | 走原版的实体分区索引 |

HUD 上的 `[N]` 就是当前追踪数量，方便你确认它没有失控。

---

## 六、ClickGUI

按 **右 Shift** 打开。四列分类面板，模块开关和全部参数都能直接点。

### 1. 布局与交互

```
┌─ Movement ─────┐ ┌─ Combat ──────┐ ┌─ Player ────────┐ ┌─ Render ──────┐
│ ■ Eagle    V ▼ │ │ □ AimAssist R │ │ □ InvChest    N │ │ □ ESP       G │
│   Edge Offset  │ │   Max Angle   │ │ □ AutoArmour F6 │ │   Range       │
│   Release Marg │ │   Distance    │ │ □ BlockIn     K │ │   Refresh     │
└────────────────┘ └───────────────┘ └─────────────────┘ └───────────────┘
```

* **点左边的方块** → 开关模块（方块填色 = 开，深灰 = 关）
* **点行内其它位置** → 展开/收起参数（右侧箭头跟着翻）
* **勾选项** → 点一下切换
* **数值项** → 按住左右拖，背景的填充条就是当前比例；右侧显示数值
* **滚轮** → 悬停在哪一列就滚哪一列，右侧有滚动条
* **右 Shift / Esc** → 关闭

分类和图标配色：Movement 蓝、Combat 红、Player 绿、Render 紫。

### 2. 渲染细节

**背景**用的是原版自己的 `renderBackground`——它自带模糊和压暗，所以不用自己写 shader 就能
有 LiquidBounce 那种毛玻璃感。`Screen.render` 本来就会调它，所以这里覆盖 `render` 时**故意不调
`super`**，否则会叠两次。

**圆角**没有用 shader：主体是一块方矩形，四个角各用 `radius` 行一像素高的小矩形去近似一个圆。
在 4px 这个量级上看不出差别，代价是每块面板多十几个 quad。

**动画**三个：

* 打开时面板从右侧滑入 + 淡入，每列依次延迟 0.12——用的是 `easeOut` 三次曲线
* 展开参数时高度做指数逼近（帧率无关），同时在收尾处叠一层渐隐
* 悬停高亮是渐变的，不是硬切

### 3. 一个热键时序的坑

热键**不能用 `wasPressed()`**。如果 GUI 开着的时候又被按了一次，那次按下会留在按键的计数器里；
等界面关掉，下一 tick 就会读到它、把界面又弹开。

所以用的是 `isPressed()` 加一个自锁标志：只有**物理松开之后**才重新武装。

```java
if (guiLatched) {
    if (!down) guiLatched = false;   // 松开才复位
} else if (down && !guiOpen) {
    guiLatched = true;
    mc.setScreen(new EagleScreen());
}
```

### 4. 面板与配置是同一份数据

GUI 里每个设置项都是直接读写 `EagleConfig` 的字段的 lambda，不做任何镜像或同步。
所以热键、聊天指令和 GUI 三条路改的都是同一个值，不会出现「GUI 显示的和实际生效的不一致」。
改动会在松手或关界面时写回 `eagle.json`。

模块行的按键标签是**调用时才解析**的（`Supplier<KeyBinding>`），因为面板是第一次打开时才构建，
而按键是模组初始化时注册的——直接存引用会在顺序变化时永久锁死成 null。

---

## 七、BlockIn

按 **K** 开关。开启后持续把你四周砌起来，被拆了会补。

### 1. 放置

用的是一套共用放置原语 `BlockPlacement`：找到目标方块旁边可点击的支撑面，
构造 `BlockHitResult`，交给 `ClientPlayerInteractionManager.interactBlock`。
客户端预测、sequence、发包仍然是原版的。

目标是**你脚下那一层的四个正方向**（可选第二层齐头高、可选四个对角）：

```
        □              □ = 目标
      □ ● □            ● = 你
        □
```

**层是自下而上填的**：第二层的支撑是它下面那一格，所以先把脚边那圈填掉，头那圈才放得下去。

放不进去的格子会被跳过——`BlockPlacement.clippedByPlayer` 用玩家自己的碰撞箱做交集判断，
因为原版 `BlockItem.canPlace` 本来就会用玩家的 shape context 拒掉这些位置，
先判掉就不用白烧一次转头和一次发包。

### 2. 平滑转头

要摸到身旁方块的面，视角得转开很远（可能 90 度以上），**一 tick 内转过去就是最典型的机器特征**。
所以朝向不是直接赋值，而是存在 `aimYaw/aimPitch` 里，每 tick 朝目标走**最多 N 度**：

```java
distance = hypot(wrapDegrees(wantYaw - aimYaw), wantPitch - aimPitch);
if (distance <= maxStep) { aim = want; return true; }
aimYaw   += deltaYaw   / distance * maxStep;
aimPitch += deltaPitch / distance * maxStep;
```

`blockInRotationSpeed` 就是这个 N（默认 28 度/刻）。实测：从 (0,0) 转向 (90,45)
距离 100.6 度，恰好 **4 步**收敛。

走的是**直线插值**而不是分别插值 yaw 和 pitch，所以转角速度和距离成正比，
不会出现"先猛转水平再慢慢补俯仰"的割裂感。

### 3. 两种转头方式

| `blockInVisibleRotation` | 表现 | 代价 |
| --- | --- | --- |
| `true`（默认） | 镜头**真的**平滑转过去，砌完再平滑转回原位 | 视野会被拽走 |
| `false` | 只改发包里的朝向，你镜头纹丝不动 | 你自己动鼠标时原版仍会发出真实朝向，两者会交替 |

**默认给了可见转头**，因为你说的是「平滑转头」，而且它没有冲突——原版自己的朝向包和我们发的是同一个值。
静默模式下这个冲突是固有的：客户端的 `sendMovementPackets` 只在自己朝向变化时才发包，
你在砌墙时一动鼠标，服务端就会看到一次跳变。放置本身不受影响（服务端不校验朝向），但观感上是断的。

`blockInReturnRotation` 控制砌完后要不要转回原位（只对可见模式有意义）。

---

## 八、Account：离线换号

**在多人游戏界面左上角点「Offline Account」按钮**。整个账户就是一个字段。

### 1. 为什么按钮在多人游戏界面

客户端拿哪个账户去连服务器，是在**建立连接时**决定的，所以改它的地方就在你选服务器的地方。
按钮由一处 Mixin 追加到 `MultiplayerScreen.init()` 的末尾，不重建列表、不动原版任何按钮：

```java
@Inject(method = "init", at = @At("TAIL"))
private void eagle$accountButton(CallbackInfo ci) {
    addDrawableChild(ButtonWidget.builder(Text.literal("Offline Account"),
            button -> this.client.setScreen(new AccountScreen(this)))
            .dimensions(6, 6, 110, 20).build());
}
```

点进去是 `AccountScreen`：一个文本框 + `Enable` / `Original` / `Back` 三个按钮。
文本框用 `setTextPredicate` 挡掉了游戏会拒绝的字符，所以名字只要出现在框里就是合法的。

那一页**不直接碰 Session**，只改配置标志。真正换号的是 `AccountLogic` 的 tick 处理器，
这样按钮、手改配置文件、以及以后再加的入口全都走同一条代码路径。

### 2. 为什么替换一个字段就够

`MinecraftClient.session` 是 `private final Session`，而 `getSession()` 返回的就是它。
登录处理器发给服务端的、集成服务器用来建房主玩家的，读的都是这同一个字段——
所以把它换掉就是全部功能。

字段是 `final`，accessor 需要 `@Mutable` 才能去掉 final 标志：

```java
@Mixin(MinecraftClient.class)
public interface MinecraftClientSessionMixin {
    @Accessor("session") Session eagle$getSession();
    @Mutable @Accessor("session") void eagle$setSession(Session session);
}
```

### 3. 名字与 UUID 必须自洽

离线 UUID 是这么推的（和原版一致）：

```java
UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
new Session(name, uuid, "0", Optional.empty(), Optional.empty(), Session.AccountType.LEGACY);
```

实测换号结果：`TestPlayer` → `bb77495a-a740-3169-a238-69654c8bd2c1`，
与独立算出的离线 UUID **完全一致**。

名字校验按原版规则：3~16 个 `[A-Za-z0-9_]`。实测
`"TestPlayer"=true  "abc"=true  "ab"=false  "aaaaaaaaaaaaaaaaa"=false`
`"bad name"=false  "bad-name"=false  "Player_1"=true  ""=false`。

### 4. 原来的账户不会丢

第一次覆盖前会把原 Session 存下来，点 `Original` 时**原样放回**。
实测恢复后是 `sameInstance=true`——放回的是原来那个对象，不是复制出来的。

### 5. 右下角的动画提示

启用和关闭时，右下角会滑出一张卡片，停 2.4 秒再滑走并淡出：

```
                                    ┌──────────────────────┐
                                    │▎Offline Account      │
                                    │ Disabled - back to … │
                                    └──────────────────────┘
```

时间线只有三个区段，全部由一个时间戳算出来，没有需要逐帧维护的状态：

```java
if (elapsed < SLIDE_IN_MS)        progress = elapsed / SLIDE_IN_MS;        // 滑入
else if (elapsed < IN + HOLD_MS)  progress = 1.0F;                          // 停留
else                              progress = 1 - (elapsed - IN - HOLD) / OUT; // 滑出
```

提示必须能画在两个地方：游戏内的 HUD，以及**任何界面之上**——换号发生在多人游戏界面，
而 HUD 是被那个界面盖住的。所以有两个入口，而且 HUD 那个在有界面时会让位，避免画两遍：

```java
public static void renderHud(DrawContext ctx)    { if (currentScreen == null) draw(ctx); }
public static void renderScreen(DrawContext ctx) { draw(ctx); }
```

界面那一侧用 Fabric 的 `ScreenEvents` 挂到每一个屏幕上：

```java
ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
        ScreenEvents.afterRender(screen).register(
                (s, context, mouseX, mouseY, tickDelta) -> ToastManager.renderScreen(context)));
```

### 6. 什么时候生效——这条很重要

**Session 是在建立连接时读取的，所以换名只在【下一次】连服务器或进世界时生效，
当前这次连接不会改名。**

这是游戏本身的性质，不是这里的取巧。所以：

* 想换名连服务器 → 在多人游戏界面点按钮设好，然后连接（这时就是新名字了）
* 如果连上之后才改的 → 退出重连才生效
* 配置里已经启用了的话，**开游戏时就会装好**，第一次连接用的就是新名字

单人存档同理：已经在玩的那个存档不会改名，退出重进才会。

`Custom Name` 的开关也在 ClickGUI 的 **Player** 分类里，和按钮是同一个配置项。

---

## 九、RemoteShop：远程商店

按 **H** 把商店唤回来。关掉商店窗口时不会真的关，只是藏起来。

### 1. 原版的关闭链路只有一条

在 `HandledScreen.close()` 的**开头**就能把它整条拦下来：

```
HandledScreen.close()
  → client.player.closeHandledScreen()   // 发 CloseHandledScreenC2SPacket，并重置 currentScreenHandler
  → Screen.close()  → setScreen(null)    // 界面消失
```

所以取消这个方法、再自己 `setScreen(null)`，正好得到需要的两半结果：**关闭包没发出去**、
**界面也确实没了**。`ScreenHandler` 还挂着原来的 sync id，之后用同一个 handler 重建界面就是正常的。

```java
@Inject(method = "close", at = @At("HEAD"), cancellable = true)
private void eagle$keepContainerAlive(CallbackInfo ci) {
    if (!RemoteShopLogic.stash(self)) return;
    MinecraftClient.getInstance().setScreen(null);
    ci.cancel();
}
```

**选这个方法而不是 `closeHandledScreen()` 是刻意的**：服务器关闭容器走的是
`ClientPlayNetworkHandler` 那条路，不经过这里。所以这里永远不会去跟「服务端已经认为关掉了」
的容器对着干——只拦玩家主动关窗。

`setScreen(null)` 仍会跑 `removed()` → `handler.onClosed(player)`，但箱子和潜影盒的 handler
对这个方法是空实现，所以 handler 完好无损。

### 2. 只收箱子

`GenericContainerScreenHandler`（箱子 / 木桶 / 末影箱 / 发射器…）和 `ShulkerBoxScreenHandler`。
村民交易那种 `MerchantScreenHandler` **不收**——它的 `onClosed` 会清空报价，藏起来再唤回就是个空商店。

### 3. 自动失效

存的 handler 一旦不是当前的 `currentScreenHandler` 就立刻丢掉：

```java
if (mc.player.currentScreenHandler != stash) reset();
```

这一条同时覆盖两种下场：**服务器把容器关了**（客户端的 handler 被重置回玩家自己的），
和**你又开了别的东西**（handler 被替换）。两种情况存档都已经过期，
继续显示只会得到一堆被服务端丢弃的点击。

### 4. 决定权在服务端，这条得说清楚

客户端能做的只有「不告诉服务端我关了」。**服务端要不要继续认为这个容器开着，是它的事。**

不少插件会在玩家走远、离开区域、或者进入战斗时主动关掉背包。真出现这种情况，
上面那条自动失效会让 H 变成无操作——不会给你一个点得动但没反应的幽灵窗口。

所以这个功能在《唤醒战争》里能不能用，取决于那套商店插件有没有距离或区域检查。

另外：如果那是个**真实方块箱子**，服务端会一直认为有人在看，箱盖会保持打开——别人看得见。
插件自绘的虚拟商店没有这个问题。

---

## 十、界面动效

### 灵动岛（顶部状态条）

屏幕正上方有一个药丸形的小岛，平时显示一行状态：

```
Eagle | 21:23:13 | 143 | play.example.com
```

依次是**客户端名 | 当前时间 | 当前帧率 | 服务器地址**。任何模块被开关时它会横向撑开，
中间滑出 **`[+] 模块名`**（关闭是 `[-] 模块名`），停 1.7 秒再收回。

* 时间取 `LocalTime`，格式 `HH:mm:ss`
* 帧率取 `MinecraftClient.getCurrentFps()`
* 服务器地址取 `getCurrentServerEntry().address`，**超过 22 字符会截断**——否则长域名会把药丸
  推到半个屏幕宽。单人存档显示 `singleplayer`，没连服务器显示 `no server`

这一行**每 250ms 才重建一次**，不是每帧：时间只需要秒级分辨率，但帧率如果用秒级会看起来像卡住，
而每帧格式化一次又太浪费。

实测输出：`Eagle | 21:23:13 | 0 | no server`（启动瞬间帧率为 0）。

### 1. 形状

「药丸」就是一个圆角半径等于**高度一半**的圆角矩形——高度 18，半径 9，
`GuiTheme.roundedRect` 逐行内缩绘制，所以没有贴图和 shader 参与。

```java
int radius = HEIGHT / 2;
GuiTheme.roundedRect(context, x, TOP, w, HEIGHT, radius, 0xF0121216);
```

### 2. 动画只有两个平滑量

```java
presence = approach(presence, flashing ? 1.0F : 0.0F, 0.45F, dt);   // 公告露出多少
width    = approach(width, idleWidth + (peakWidth - idleWidth) * presence, 0.4F, dt);
```

宽度目标直接由 `presence` 算出来，所以**药丸的宽度和时间/公告文字的交叉淡化永远同步**，
不会出现「文字已经换好了但框还没撑开」的错位。

文字是叠在同一中心的：时间按 `1 - presence` 淡出，公告按 `presence` 淡入。

### 3. 状态行每 250ms 才重建一次

```java
if (!statusText.isEmpty() && now - statusAt < STATUS_REFRESH_MS) return statusText;
statusText = "Eagle | " + LocalTime.now().format(CLOCK) + " | " + mc.getCurrentFps() + " | " + serverLabel(mc);
```

`DateTimeFormatter` 和三次字符串拼接每帧跑一次太浪费，但也不能按秒缓存——帧率会看起来像卡住。
250ms 是这两者之间的折中。

### 4. 挂载点

* 游戏内：`HudLayerRegistrationCallback` 的一个独立 layer，和左侧模块列表分开。
* 界面上：只有公告进行中才画（`presence > 0.02`），所以状态行是「游戏内」的东西，
  但你从 ClickGUI 里开关模块时公告会跟过去——那正是需要反馈的地方。

左侧的模块状态列表整体下移了 22px，给顶部的小岛让位。

### 5. 触发点只有两处

* 快捷键：挂在 `notify()` 里，六个开关共用一个汇合点。
* ClickGUI：挂在 `GuiModule.toggle()` 里，名称直接用行上的名字。

两条路互斥，所以一次开关只会闪一次。

### 主菜单动态效果

标题界面上叠了五层环境动效，全部按屏幕比例和秒级 delta 计算，分辨率和帧率都不影响观感：

| 层 | 效果 | 实现 |
| --- | --- | --- |
| 1 | 主题色缓慢呼吸的整屏薄雾 | 一个全屏 `fill`，alpha 约 2~4% |
| 2 | 一道柔光横带从上往下扫过并循环 | 两个 `fillGradient` 拼出上淡入下淡出 |
| 3 | 56 颗向上飘的微粒，各自有速度、大小、颜色、相位 | 每颗三个小四边形（外层光晕 + 亮核） |
| 4 | 上下边缘的暗角，和薄雾反相呼吸 | 两个 `fillGradient` |
| 5 | 跟随光标的一团柔光 | 三个同心圆角矩形（半径 = 半边长 → 圆形） |

```java
context.fillGradient(0, top, width, middle, 0x00000000, tint);
context.fillGradient(0, middle, width, top + bandHeight, tint, 0x00000000);
```

**为什么叠在上面而不是换掉背景**：想换掉背景得改 `TitleScreen` 自己的渲染路径，
而这五层的 alpha 都刻意压得很低，按钮的文字完全读得清。要的是气氛，不是新壁纸。

**跟鼠标一起动**：鼠标位置被归一化成 -1..1，再**平滑**逼近，所以整片效果会略微滞后于光标然后
跟上。这个滞后正是「镜头在漂」和「一层贴纸钉在鼠标上」的区别。两个用途：

* 微粒按 `lookX * 26`、`lookY * 16` 像素位移（视差）
* 扫光带按 `lookY * 12` 上下浮动
* 光标本体有一团带拖尾的柔光，位置也做了平滑

微粒用**固定种子**生成，所以每次启动排列都一样，不会每开一次就洗牌。
总开销约 183 个四边形，全程不分配对象。

挂载点在已有的 `ScreenEvents.afterRender` 回调里加一个 `instanceof TitleScreen` 判断，
没有为它新增 Mixin。`titleEffects` 设为 `false` 可以整个关掉。

### Tab 面板：灵动岛长成圆角方块

**按住 Tab**，顶部的药丸会横向+纵向撑开成一个圆角正方形，里面是你自己的玩家列表。
松开就缩回药丸。

三态共用同一套几何：空闲是药丸（半径 = 高度一半），公告时横向撑开，按住 Tab 时长成方块。
药丸本来就是「半径等于高度一半的圆角矩形」，所以只要把高度抬高、再把半径收回到固定值，
一个形状就变形出了另一个，不需要两套绘制。

```java
float targetWidth  = pillWidth + (TabPanel.WIDTH - pillWidth) * tab;
float targetHeight = HEIGHT + (TabPanel.preferredHeight(mc) - HEIGHT) * tab;
int radius = Math.round(HEIGHT / 2.0F + (TAB_RADIUS - HEIGHT / 2.0F) * tab);
```

宽度、高度、圆角三个目标全部由同一个 `tab` 进度算出来，所以框和内容不会各说各话。
列表内容要等 `tab > 0.75` 才淡入——否则文字会在还没长大的框里溢出。

**只是原版的内容，不是原版的 GUI。** 数据取自 `ClientPlayNetworkHandler.getPlayerList()`，
名字用 `PlayerListHud.getPlayerName()`（这样队伍颜色、旁观者格式都跟原版一致），
延迟取 `PlayerListEntry.getLatency()` 显示成文字而不是原版那五张信号格贴图——
那些贴图绑死了原版的布局尺寸，塞不进这个盒子。

自己画的部分：圆角容器、斑马纹行背景、延迟分色（<80 绿 / <200 黄 / 其余红）、
自己的行高亮成绿色、服务端的 header/footer 各取第一行。

**原版列表必须被压掉**，而且 `setVisible(false)` 做不到——`PlayerListHud.render` 一进去就
直接 `collectPlayerEntries()`，从头到尾没看那个 `visible` 字段。所以只能用 Mixin 取消 `render`：

```java
@Inject(method = "render", at = @At("HEAD"), cancellable = true)
private void eagle$hideVanillaList(CallbackInfo ci) {
    if (TabPanel.isOpen(MinecraftClient.getInstance())) ci.cancel();
}
```

`TabPanel.isOpen()` 的条件就是「Tab 按着 + 在有世界的游戏里 + 有网络 handler」，
所以原版列表不会平白消失，只在这块面板真的顶上来的时候让位。

`header`/`footer` 只有 setter 没有 getter，字段还是 private，所以另开了一个 `@Accessor` 接口。
面板每 200ms 才重建一次条目列表（要分配 + 排序），不是每帧。

### 计分板：同样的风格，完整的内容

右侧的侧边栏计分板换成和灵动岛同一套视觉：圆角深色面板、主题色标题、斑马纹行、
分数靠右显示成红色。**原版的内容一条不少**——目标名、每一行、每一行的分数都在。

数据全部取自游戏自己的记分板：

```java
ScoreboardObjective objective = mc.world.getScoreboard()
        .getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
Collection<ScoreboardEntry> entries = scoreboard.getScoreboardEntries(objective);
```

保留了两条原版规则：**最多 15 行**，**按分数从高到低排序**。过滤掉 `entry.hidden()` 的行。

### 那个「只有分数、没有文字」的 bug

第一版这里是空的，只有右边的数字显示——原因是**行文字根本不在 entry 里**。

服务端的侧边栏条目几乎从不直接把文字写进 entry 名字。它把 owner 设成一个很短的不可见标记
（比如 `§1`），真正的文字挂在**队伍的前缀和后缀**上。所以原版每一行是这么拼的：

```java
Team team = scoreboard.getScoreHolderTeam(entry.owner());
MutableText name = Team.decorateName(team, entry.name());
```

漏掉 `decorateName` 那一步，`entry.name()` 就是空字符串。而空字符串还会连锁出第二个症状：
面板宽度是按「最长的一行」算的，名字变空之后宽度就只由标题决定了，于是文字宽度被算成 0，
数字却被右对齐画了出来——看起来就是「只有积分，没有内容」。

修好之后实测（直接用 `Team` 构造了服务端那种情况）：

```
decorateName(null, "Hello")                 = [Hello]            ← 无队伍时原样返回，不会 NPE
decorateName(team("PREFIX " / " SUFFIX"), "") = [PREFIX  SUFFIX]  ← 空名字被前后缀补齐
```

分数也不再自己拼字符串，而是尊重服务端的 `NumberFormat`（千分位、单位之类）。

### 第二个 bug：`NullPointerException: defaultObj`

第一版我用了 `entry.formatted(objective.getNumberFormat())`，**进服务器直接崩**：

```
java.lang.NullPointerException: defaultObj
    at java.util.Objects.requireNonNullElse(Objects.java:314)
    at net.minecraft.scoreboard.ScoreboardEntry.formatted(...)
    at com.dmod.eagle.gui.ScoreboardPanel.scoreText(...)
```

`formatted` 的实际实现是：

```java
NumberFormat effective = Objects.requireNonNullElse(this.numberFormatOverride, format);
return effective.format(this.value);
```

`requireNonNullElse(a, b)` 在 **a 和 b 都是 null** 时抛 `NullPointerException: defaultObj`。
而「目标没有数字格式」恰恰是绝大多数计分板的常态，entry 通常也没有 override——两个都是空，必崩。

`NumberFormat` 是个接口，没有 `blank()` 之类的兜底实现，所以正确做法是**自己解析优先级再手动兜底**，
根本不让它走到那个 `requireNonNullElse`：

```java
NumberFormat format = entry.numberFormatOverride() != null
        ? entry.numberFormatOverride()
        : objectiveFormat;

return format == null ? Text.literal(Integer.toString(entry.value())) : format.format(entry.value());
```

修好后四个用例实测（用真实 `ScoreboardEntry` 和匿名 `NumberFormat` 构造）：

```
1 无格式（崩溃现场）      = [42]
2 目标有格式              = [[42]]
3 entry override 优先     = [[7]]
4 无队伍的行文字          = [Line text]
```

> **这次的教训**：上一轮我测的是 `Team.decorateName` 这个**邻近的原语**，没有测
> `scoreText` 这条**真正会被调用的路径**，所以漏掉了「null 格式」这个必然发生的输入。
> 现在测的是要跑的那段代码本身，并且喂的是崩溃现场的数据。

### 拖动位置

**打开聊天框就能拖。** 鼠标停在面板上按住左键拖走，松手时位置写进配置。

选聊天框当拖动手柄，是因为它是战斗中最快能打开的界面、而且本来就已经接管了鼠标。
面板在聊天框打开时会**改画在聊天界面之上**（否则会被聊天背景盖住，看不见就没法拖），
并带一圈主题色描边提示「现在可以拖」。

拖动逻辑没有用 `ScreenMouseEvents` 的 drag 事件——那个 API 在这一版里根本没有 drag。
改成：点击用 `allowMouseClick` 判定是否落在面板上（是则吞掉这次点击，不让聊天框拿去做别的事），
位置更新则挂在已经存在的「每帧带鼠标坐标」的界面渲染钩子上，松手用 `allowMouseRelease`。

```java
ScreenMouseEvents.allowMouseClick(screen).register((clicked, mouseX, mouseY, button) ->
        button != 0 || !ScoreboardPanel.beginDrag(client, (int) mouseX, (int) mouseY));
```

位置存成**缩放后的像素坐标**，每帧再夹回屏幕范围内，所以改 GUI 缩放或窗口尺寸都不会跑到屏幕外。
`scoreboardX` / `scoreboardY` 为负数表示「自动」（右上角）。

**两个重载都要拦。** `renderScoreboardSidebar` 有两个版本：`(DrawContext, RenderTickCounter)`
是 HUD 渲染循环里的入口，`(DrawContext, ScoreboardObjective)` 才是真正画的那层。
只取消其中一个都会留下一条原版侧边栏。

这里用的是 `require = 0`，不是默认的 1。理由：这是个纯外观替换，某个重载如果哪天改名了，
应该代价是「样式失效」，而不是「游戏起不来」。代价是失败是**静默**的——所以我用反射确认过两个
handler 都真的挂上了：

```
InGameHud = [handler$zha000$eagle$hideSidebar handler$zha000$eagle$hideSidebarForObjective]
```

列表每 100ms 才重建一次。被计时器驱动的计分板每刻都在变，但没有人能读得比这更快，
而每帧在渲染线程上做一次排序 + 分配没有意义。

`showScoreboard` 设为 `false` 会关闭替换，**原版侧边栏原样回来**——不是把计分板整个藏掉。

---

## 十一、安装

推荐直接用 **`launch.bat`**（Windows），双击即可：

```
launch.bat        装 jar 到 .minecraft\mods，然后拉起官方启动器
launch.bat dev    用开发环境直接启动游戏（不经过启动器）
```

脚本会挑 `build\libs` 里最新的 `eagle-*.jar`（跳过 sources 包），删掉 mods 里的旧版本再复制，
并检查 Fabric API 和启动器里的 Fabric 档案。设置环境变量 `EAGLE_MC` 可以指向自定义的 `.minecraft`。

> 脚本内容**故意全是 ASCII**：批处理是按字节偏移读取的，UTF-8 文本加上 `chcp 65001`
> 会让 cmd 在中途丢失位置、把命令行截断。纯 ASCII 绕开整个问题。

非 Windows 平台从源码构建见 [第十五节](#十五命令行构建)。

手动安装：

1. 装好 **Fabric Loader**（≥ 0.16）和一个 **1.21.4** 的档案
2. 下载 **Fabric API**（1.21.4 版）放进 `mods`
3. 把 `eagle-2.10.0.jar` 放进 `mods`
4. 启动游戏，进世界后按 **V** 开关 Eagle，**F6** 开关 AutoArmour，**R** 开关 AimAssist，
   **N** 开关 InvChest，**G** 开关 ESP，**K** 开关 BlockIn，**H** 唤回远程商店，
   **右 Shift** 打开 GUI

换离线账户在**多人游戏界面左上角的 `Offline Account` 按钮**里，不在游戏内。

左上角是一块**带标题的圆角面板**，标题为 `EagleClient`，下面竖排显示各模块状态：

```
┌──────────────────┐
│ EagleClient      │
│ Eagle ON sneak   │
│ CPS ON hold      │
│ Sprint ON        │
│ Armour OFF       │
│ ...              │
└──────────────────┘
```

面板宽度按**最长那一行**算，所以显示名字、延迟标签时它会自动变宽，不会截断。

聊天框里可以用 `.inv speed 0.85` 调 InvChest 的取物节奏。

---

## 十二、配置

首次启动会生成 `.minecraft/config/eagle.json`：

```json
{
  "enabled": true,
  "edgeOffset": 0.08,
  "releaseMargin": 0.06,
  "keepSneakWhileIdle": true,
  "sneakDelayMs": 0,
  "releaseDelayMs": 40,
  "randomizeDelay": true,
  "onlyWhileBridging": false,
  "showHud": true,
  "showIsland": true,
  "titleEffects": true,
  "showScoreboard": true,
  "scoreboardX": -1,
  "scoreboardY": -1,

  "sprintEnabled": true,

  "cpsBoostEnabled": true,
  "cpsMin": 6.0,
  "cpsMax": 9.0,
  "cpsRequireBlock": true,

  "aimEnabled": false,
  "aimMaxAngle": 90.0,
  "aimDistance": 4.5,
  "aimThroughWalls": false,
  "aimTargetPlayers": true,
  "aimTargetMobs": false,
  "aimClickOnly": true,
  "aimWeaponOnly": false,
  "aimHorizontalSpeed": 0.35,
  "aimVerticalSpeed": 0.28,
  "aimStrafeIncrease": true,
  "aimSensitivitySync": true,
  "aimJitterDegrees": 0.6,
  "aimWhitelist": [],
  "aimAutoAttack": false,
  "aimTeamCheck": false,

  "invChestEnabled": false,
  "invChestDelayMs": 850,
  "invChestSkipExisting": true,
  "invChestMinTier": 1,
  "invChestCloseWhenDone": false,

  "autoArmourEnabled": false,
  "autoArmourDelayMs": 300,
  "autoArmourKeepElytra": true,
  "autoArmourIdleOnly": true,

  "customNameEnabled": false,
  "customName": "",

  "remoteShopEnabled": false,

  "espEnabled": false,
  "espRange": 64.0,
  "espGlow": true,
  "espShowNames": true,
  "espShowDistance": true,
  "espTargetPlayers": true,
  "espTargetMobs": false,
  "espRefreshTicks": 4,

  "blockInEnabled": false,
  "blockInLayers": 1,
  "blockInCorners": false,
  "blockInDelayMs": 60,
  "blockInRotationSpeed": 28.0,
  "blockInVisibleRotation": true,
  "blockInReturnRotation": true
}
```

### Eagle

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | Eagle 总开关，V 键切换 |
| `edgeOffset` | `0.08` | 探针在身体半宽之外再前移的距离。**调大 = 更早触发 = 更安全，但更不像人**；钳制在 0 ~ 0.30 |
| `releaseMargin` | `0.06` | 迟滞量，防止潜行状态抖动 |
| `keepSneakWhileIdle` | `true` | 停下不动时仍保持潜行。在边缘上放方块很有用 |
| `sneakDelayMs` | `0` | 检测到边缘后延迟多久才潜行。**不建议调大**，见下方余量换算 |
| `releaseDelayMs` | `40` | 边缘消失后延迟多久才解除潜行 |
| `randomizeDelay` | `true` | 把两个延迟随机化到 `0..设定值`，打散固定节奏 |
| `onlyWhileBridging` | `false` | 只在手持可放置方块时生效 |
| `showHud` | `true` | 左上角圆角面板（EagleClient 标题 + 竖排状态） |
| `showIsland` | `true` | 顶部药丸：`Eagle | 时间 | 帧率 | 服务器`，以及模块开关时的 `[+]` 公告 |
| `titleEffects` | `true` | 主菜单上的五层环境动效（含鼠标视差） |
| `showScoreboard` | `true` | 用同风格面板重画侧边栏计分板；关掉则原版侧边栏回来 |
| `scoreboardX` | `-1` | 计分板面板的 X 坐标（缩放像素）。**负数 = 自动**（右上角）。打开聊天框拖动即可修改 |
| `scoreboardY` | `-1` | 同上，Y 坐标 |
| `sprintEnabled` | `true` | 按住前进即疾跑。GUI 的 Movement → `Sprint` 就是它 |

### Eagle · 右键提速

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `cpsBoostEnabled` | `true` | 右键提速子开关。**仍然受 `enabled` 约束**——按 V 关掉 Eagle 会把提速一起关掉 |
| `cpsMin` | `6.0` | 目标速率下限（次/秒），钳制在 1 ~ 20 |
| `cpsMax` | `9.0` | 目标速率上限（次/秒），钳制在 1 ~ 20 |
| `cpsRequireBlock` | `true` | 只在主手拿着可放置方块时提速，也就是「搭路的时候」 |

### AimAssist

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `aimEnabled` | `false` | AimAssist 总开关，R 键切换。**不受 `enabled` 约束**，是独立模块 |
| `aimMaxAngle` | `90.0` | 捕获锥的半角（度）。锥外的东西永远不会被选中 |
| `aimDistance` | `4.5` | 最大目标距离（格） |
| `aimThroughWalls` | `false` | 是否继续追踪墙后的目标 |
| `aimTargetPlayers` | `true` | 是否把玩家算作目标 |
| `aimTargetMobs` | `false` | 是否把生物算作目标 |
| `aimClickOnly` | `true` | 只在按住攻击键时生效（Click Aim） |
| `aimWeaponOnly` | `false` | 只在主手拿着剑或斧时生效 |
| `aimHorizontalSpeed` | `0.35` | 每 50 ms 收敛掉剩余水平误差的比例。0 = 关闭，1 = 瞬间锁定 |
| `aimVerticalSpeed` | `0.28` | 同上，垂直方向。默认更低，用来保持爆头线稳定 |
| `aimStrafeIncrease` | `true` | 你在移动时把辅助速度最多提高 50% |
| `aimSensitivitySync` | `true` | 让修正量随鼠标灵敏度缩放，实际度数 = 设定值 × `h`，见上表 |
| `aimJitterDegrees` | `0.6` | 瞄准点随机漂移的角半径（度）。设为 0 就是完全精准 |
| `aimWhitelist` | `[]` | 玩家名白名单，大小写不敏感，名单里的人不会被瞄 |
| `aimAutoAttack` | `false` | 自动挥刀。**默认关**，需要在 AimAssist 设置里打开，且 `aimEnabled` 也要开。见第三节第 5 小节 |
| `aimTeamCheck` | `false` | 染色护甲与自己相同的玩家不瞄。**默认关**，见第三节第 6 小节 |

### InvChest

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `invChestEnabled` | `false` | InvChest 总开关，N 键切换。独立模块，不受 `enabled` 约束 |
| `invChestDelayMs` | `850` | 两次取物之间的间隔（毫秒），钳制在 50 ~ 10000。可用 `.inv speed <秒>` 在线修改 |
| `invChestSkipExisting` | `true` | 背包里已经有同种物品就跳过 |
| `invChestMinTier` | `1` | 最低取用等级 1~5。调到 2 就会把泥土圆石这类垃圾留下 |
| `invChestCloseWhenDone` | `false` | 没有可取的物品时自动关闭容器 |

### AutoArmour

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `autoArmourEnabled` | `false` | AutoArmour 总开关，F6 键切换。独立模块，不受 `enabled` 约束 |
| `autoArmourDelayMs` | `300` | 两次换装之间的间隔（毫秒），钳制在 50 ~ 5000。**GUI 里可拖** |
| `autoArmourKeepElytra` | `true` | 胸甲位穿着鞘翅时跳过，不把飞行中的鞘翅换成胸甲 |
| `autoArmourIdleOnly` | `true` | 疾跑或移动时挂起换甲，站定后才换。见第二节「静止闸门」 |

### Account

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `customNameEnabled` | `false` | 是否用自定义的离线账户。多人界面按钮和 GUI 里的 `Custom Name` 开关都是它 |
| `customName` | `""` | 离线名字，3~16 个 `[A-Za-z0-9_]`。在多人游戏界面的 `Offline Account` 里填写 |

### RemoteShop

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `remoteShopEnabled` | `false` | 关掉箱子/商店时只隐藏不关闭，H 键唤回。GUI 里的 `Keep On Close` 开关就是它 |

### ESP

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `espEnabled` | `false` | ESP 总开关，G 键切换。独立模块，不受 `enabled` 约束 |
| `espRange` | `64.0` | 最大高亮距离（格），钳制在 4 ~ 256 |
| `espGlow` | `true` | 画原版的发光轮廓，这个天然穿墙 |
| `espShowNames` | `true` | 替换头顶名签 |
| `espShowDistance` | `true` | 在名字后面追加距离，例如 `Steve 12.3m` |
| `espTargetPlayers` | `true` | 是否高亮玩家 |
| `espTargetMobs` | `false` | 是否高亮生物 |
| `espRefreshTicks` | `4` | 追踪集合的重建间隔（刻），钳制在 1 ~ 40。调大更省性能，代价是进出范围的判定变迟钝 |

### BlockIn

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `blockInEnabled` | `false` | BlockIn 总开关，K 键切换。独立模块，不受 `enabled` 约束 |
| `blockInLayers` | `1` | 墙的高度：1 = 只围脚边，2 = 再加一层齐头高。钳制在 1 ~ 2 |
| `blockInCorners` | `false` | 是否连四个对角也填 |
| `blockInDelayMs` | `60` | 两次放置的间隔（毫秒），钳制在 0 ~ 1000 |
| `blockInRotationSpeed` | `28.0` | 转头速度上限（度/刻），钳制在 1 ~ 180。调小更平滑但更慢 |
| `blockInVisibleRotation` | `true` | 镜头真的转（true）还是只改发包（false） |
| `blockInReturnRotation` | `true` | 砌完是否转回原视角，只对可见模式有意义 |

### 关于安全余量

原版行走 4.317 格/秒 = **每刻 0.216 格**，检测每刻做一次。

探针位于身体中心前方 `0.3 + edgeOffset`，因此探针刚离开方块时，
身体前沿距离边缘还有 `edgeOffset` 格。潜行会在**同一刻**生效，
所以这一格余量只被消耗一次、且以潜行速度（约 0.065 格/刻）消耗。

注意玩家碰撞箱 0.6 宽，**整个箱子离开方块才会真的掉下去**。
探针触发时你离真正坠落还有约 `0.6 + edgeOffset` 格的缓冲，大约 3 刻。

如果调大 `sneakDelayMs`，每 50ms 就吃掉约 0.216 格的余量，**很容易直接摔下去**。

---

## 十三、行为边界

**会生效**：站在地面正常行走、搭桥、跑搭、倒退搭、侧向搭。

**两个模块都会让开**：

* 创造飞行中、鞘翅滑翔中
* 游泳 / 水中 / 爬梯 / 骑乘
* 打开任何 GUI 时
* 手上没有 `BlockItem`（自动放置与右键提速专用；Eagle 潜行只在开了 `onlyWhileBridging` 时看这个）

**右键提速额外不做**：玩家不按右键时一次都不点。它只是把原版的 5 CPS 提到 6~9，
放哪个方块、朝哪个方向、什么时候放，仍然全部由你自己决定。

**AimAssist 额外不做**：默认**不自动攻击**。它只往你当前正在瞄的方向拉，
你的鼠标输入始终是叠加的基底，辅助只是那一点「磁吸」。关掉它的方式是把
`aimHorizontalSpeed` / `aimVerticalSpeed` 设为 0，或者按 R。

自动攻击是单独一个 `Auto Attack`（默认关，见第三节第 5 小节）。打开之后它只做一件事：
目标**已经在准星 8° 以内**、在触及距离内、看得见、且蓄力条已满时，替你挥一次。
它不会主动转向目标去追、不会穿墙砍、也不会加速攻击频率。想回到「只晕不动手」，
把这个勾去掉就行。

**InvChest 额外不做**：不自动走去开箱子、不自动打开容器、不碰末影箱之外的特殊容器界面
（工作台、熔炉、漏斗这些一律不动）。它只在你**自己打开了**箱子之后才开始取，
而且每 0.85 秒才取一件。

**ESP 额外不做**：不发任何包、不改任何游戏状态。它只写本地实体上的发光标志（释放时恢复原值）
和渲染时的名签文字，全部是纯客户端的东西。

**AutoArmour 额外不做**：不丢东西、不整理背包、不碰你的快捷栏。它只做一件事——
把背包里材质更好的盔甲穿到对应的槽位上，一次一件。

**BlockIn 额外不做**：不追着你走、不自动拆、只砌你自己站的那一格四周。

**Account 额外不做**：不改皮肤、不改披风、不动正版账户。它只是把客户端的 Session
换成一个带自定义名字的离线 Session，并在关闭时原样放回原来的那个。

**RemoteShop 额外不做**：不改服务端的容器状态，也不伪造点击。它只是不告诉服务端「我关了」，
以及用同一个 handler 重建界面。服务端一旦自己关掉容器，它就立刻失效。

**Sprint 额外不做**：不改任何 KeyBinding，不碰腿的移动速度，不伪造状态。它只是在每个 tick
把玩家本来就能自己做到的那个疾跑状态再请求一次；潜行和使用物品时主动让开。

**Team 额外不做**：不猜队伍、不读服务器插件、不发包。它只比较双方皮甲上的染色数据，
没有染色就一律不排除。

**十个功能都不做**：不防摔落伤害、不改位置、不自动攻击（除非自己开 `Auto Attack`）、不自动移动。

> 关于服务器规则：除 ESP 外都会向服务端发送自动化输入，在多数服务器的规则下属于作弊。
> AimAssist 会显著改变 PvP 对抗的公平性，InvChest 在共享箱子的服务器上等同于快速搬空公共物资。
> ESP 不发包，但很多服务器把它列为禁止的客户端模组。
> 请只在你自己开的存档或允许的环境里使用。

---

## 十四、项目结构

仓库根目录：

```
D:\Mod\
├── launch.bat                    一键安装 + 启动脚本（纯 ASCII，见第十一节）
├── build.gradle / settings.gradle
├── gradle.properties             版本号与依赖版本都在这里
├── gradlew / gradlew.bat / gradle/
└── src/main/                     源码
```

```
src/main/java/com/dmod/eagle/
├── EagleClient.java              入口：按键、HUD、tick 注册、开关逻辑
├── EagleConfig.java              JSON 配置读写与数值钳制
├── EagleLogic.java               Eagle：边缘预测 + 潜行判定 + 延迟状态机 + 共享探针
├── CpsBoostLogic.java            Eagle：右键提速的节奏调度
├── SprintLogic.java              强制疾跑：GUI 可开关，按住前进即疾跑
├── AutoArmourLogic.java          AutoArmour：候选筛选 + 材质评分 + 换装点击
├── AimAssistLogic.java           AimAssist：目标筛选 + 指数逼近 + 度数换算 + 抖动
├── InvChestLogic.java            InvChest：容器识别 + 等级评分 + 取物节奏
├── EagleCommands.java            聊天框客户端指令（.inv speed）
├── EspLogic.java                 ESP：目标追踪 + 标志位发光 + 低频刷新 + 名签缓存
├── EspRenderer.java              ESP：自绘透墙名签（AFTER_ENTITIES）
├── BlockPlacement.java           共用放置原语：找支撑面、可达性、右键
├── BlockInLogic.java             BlockIn：四周目标 + 平滑转头状态机
├── AccountLogic.java             Account：Session 替换 + 离线 UUID 推导 + 校验
├── RemoteShopLogic.java          RemoteShop：handler 暂存 + 失效检测 + 界面重建
├── EagleCommands.java            聊天框客户端指令（.inv speed）
├── EspLogic.java                 ESP：目标追踪 + 标志位发光 + 低频刷新 + 名签缓存
├── EspRenderer.java              ESP：自绘透墙名签（AFTER_ENTITIES）
├── BlockPlacement.java           共用放置原语：找支撑面、可达性、右键
├── EagleHud.java                 左上角圆角面板：EagleClient 标题 + 竖排状态
├── gui/
│   ├── EagleScreen.java          ClickGUI：布局、动画、鼠标/滚轮/键盘交互
│   ├── AccountScreen.java        离线账户编辑页（文本框 + 启用/还原）
│   ├── ToastManager.java         右下角滑入/停留/滑出的动画提示
│   ├── DynamicIsland.java        顶部时钟药丸 + 模块开关公告动画
│   ├── TitleScreenEffects.java   主菜单五层环境动效（含鼠标视差）
│   ├── TabPanel.java             Tab 面板：玩家列表内容与自定义绘制
│   ├── ScoreboardPanel.java      侧边栏计分板：同风格面板 + 完整内容 + 可拖动
│   ├── GuiCategory.java          分类与模块/设置注册表
│   ├── GuiModule.java            模块行（开关、按键标签、展开动画）
│   ├── GuiSetting.java           设置项（勾选框 / 可拖拽数值）
│   └── GuiTheme.java             配色、圆角绘制、动画曲线
└── mixin/
    ├── ChatCommandMixin.java     注入 sendChatMessage，拦下客户端指令
    ├── EntityFlagsMixin.java     打开 Entity.getFlag/setFlag 的访问器
    ├── EntityRendererMixin.java  注入 getAndUpdateRenderState，抑制原版名签
    ├── HandledScreenMixin.java   注入 HandledScreen.close，把关闭改成隐藏
    ├── InGameHudMixin.java      注入 renderScoreboardSidebar，让位给计分板面板
    ├── KeyboardInputMixin.java   注入 KeyboardInput.tick，改写 PlayerInput
    ├── MinecraftClientMixin.java 注入 MinecraftClient.tick，调度 itemUseCooldown
    ├── MinecraftClientSessionMixin.java  打开 MinecraftClient.session 的写访问
    ├── MouseMixin.java           注入 Mouse.updateMouse，向 cursorDelta 叠加一笔修正
    ├── MultiplayerScreenMixin.java  注入 MultiplayerScreen.init，追加账户按钮
    ├── PlayerListHudAccessor.java   打开 PlayerListHud 的 header/footer 读取
    └── PlayerListHudMixin.java   注入 PlayerListHud.render，让位给灵动岛的面板
```

模组总共**十二处 Mixin**，都只在客户端。

* InvChest 与 AutoArmour 完全不依赖 Mixin，它们走的是原版自己的交互 API。
* 右键提速几乎不依赖——它只是把原版那个私有计数器在正确的时刻放到正确的值。
* AimAssist 也几乎不依赖——它加的是原版本来就要消费的那笔鼠标位移，
  后面的角度换算、灵敏度曲线、视角应用全部是原版自己做的。
* 客户端指令是唯一「借」Mixin 干别的事的：它只是让聊天消息在变成数据包之前停下来。
* ESP 用了两处：一处打开 `getFlag`/`setFlag` 访问器（因为它们都是 `protected`），
  一处抑制原版名签；名字本身是我们自己画的。
* Account 用了一处：`session` 是 `private final`，写访问需要 `@Mutable @Accessor`。
* 账户按钮用了一处：只往多人游戏界面追加一个子控件，不碰原版布局。
* RemoteShop 用了一处：在 `HandledScreen.close()` 的开头把它改成隐藏，不发关闭包。
* Tab 面板用了两处：一处取消原版列表的渲染，一处只为读 header/footer 开个访问器。
* 计分板用了一处：取消原版侧边栏的两个渲染重载，改成自己画。

---

## 十五、命令行构建

不需要 IDE，一个 JDK 21 和一个终端就够。仓库自带 Gradle Wrapper，**不用预先安装 Gradle**。

### 1. 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| **JDK** | **21** | `build.gradle` 里是 `release = 21`，JDK 17 会在编译阶段直接报错 |
| **Git** | 任意较新版本 | 只用来 clone |
| 网络 | 仅首次构建需要 | 要下载 Gradle 本体、Fabric Loom、Minecraft 客户端与映射表 |

确认版本：

```bash
java -version      # 必须输出 21.x
```

机器上有多个 JDK 时，用 `JAVA_HOME` 指定比改 `PATH` 可靠：

```bash
# Linux / macOS
export JAVA_HOME=/path/to/jdk-21

# Windows PowerShell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
```

### 2. 克隆并构建

```bash
git clone https://github.com/Minecut/AssistSimulatingTheClient.git
cd AssistSimulatingTheClient

# Linux / macOS
./gradlew build

# Windows（cmd 或 PowerShell）
gradlew.bat build
```

首次构建会下载并反编译 Minecraft，**几分钟到十几分钟**，取决于网络。之后增量构建一般 30 秒内。

### 3. 产物

```
build/libs/eagle-<版本>.jar           ← 放进 mods 的就是这个
build/libs/eagle-<版本>-sources.jar   ← 源码包，不用管
```

版本号来自 `gradle.properties` 里的 `mod_version`，和文件名对一下就知道了。

装进游戏还需要：

* **Fabric Loader** ≥ 0.16，1.21.4 档案
* **Fabric API** 1.21.4 版

### 4. 开发运行

```bash
./gradlew runClient
```

会启动一个带本模组的客户端，使用仓库内独立的 `run/` 目录，**不碰你正式的存档和配置**。
Windows 上也可以直接双击根目录的 `launch.bat dev`。

本模组是纯客户端（`"environment": "client"`），`runServer` 没有意义。

### 5. 常用参数

| 命令 | 作用 |
| --- | --- |
| `./gradlew build --offline` | 依赖已缓存时不联网构建，适合 CI 和重复构建 |
| `./gradlew clean build` | 从零重建，怀疑产物过期时用 |
| `./gradlew build --console=plain` | 关掉进度条，日志更适合贴进 issue |
| `./gradlew build --warning-mode all` | 展开 Gradle 自身的弃用警告 |
| `./gradlew --stop` | 停掉后台的 Gradle 守护进程 |

### 6. 常见问题

**`invalid source release: 21` / `Unsupported class file major version`**
JDK 不是 21。`java -version` 确认，并检查 `JAVA_HOME` 指向的确实是它。

**一直卡在下载 / `Could not resolve ...`**
首次构建需要能访问 `maven.fabricmc.net`、`libraries.minecraft.net`、Maven Central。
代理环境下可以给 Gradle 配 `systemProp.https.proxyHost` 等参数到 `~/.gradle/gradle.properties`；
或者先在有网的机器上构建一次，再把 `~/.gradle/caches` 拷贝过来配合 `--offline`。

**`Failed to load native library 'native-platform.dll'`（Windows）**
`~/.gradle` 不可写。检查目录权限；被安全软件或「受控文件夹访问」拦住也会这样。

**构建成功但游戏里没有模组**
确认 `fabric.mod.json` 里的 `minecraft` 依赖是 `~1.21.4`，且启动的是 **1.21.4 的 Fabric 档案**。
日志里应该能看到 `eagle` 的加载记录。

**改了源码但行为没变**
`./gradlew clean build`。增量编译偶尔会留下旧 class。

### 7. CI（可选）

GitHub Actions 上自动构建，`.github/workflows/build.yml`：

```yaml
name: build

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - run: chmod +x gradlew
      - run: ./gradlew build --console=plain
      - uses: actions/upload-artifact@v4
        with:
          name: eagle-jar
          path: build/libs/*.jar
```

> `.gitignore` 已经排除了 `build/`、`run/`、`.gradle/`、`.idea/`，克隆下来直接构建即可。

---

## License

MIT
