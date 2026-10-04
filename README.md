# Eagle —— Fabric 客户端辅助套件

五个模块，各自独立开关：

| 模块 | 行为 | 开关 | 默认 |
| --- | --- | --- | --- |
| **Eagle · 潜行** | 快踩空时替你按 Shift，用原版潜行挡住你 | `V` | 开 |
| **Eagle · 右键提速** | 长按右键时把放置节奏提到 6~9 CPS | 同 `V` | 开 |
| **SafePad** | 快踩空时在脚下补一块方块，让你继续走 | `B` | **关** |
| **AimAssist** | 往目标方向修正鼠标向量，镜头真的转过去 | `R` | **关** |
| **InvChest** | 打开箱子后每 0.85 秒取一件，高级物品优先 | `N` | **关** |

聊天框里的客户端指令（`.inv speed <秒>`）可以随时调 InvChest 的节奏，详见第四节。

Eagle 只改输入记录和原版的放置冷却计数，**永远不动世界**；SafePad 会真的放置方块，
AimAssist 会真的转动镜头，InvChest 会真的取走箱子里的东西，所以后三个默认关闭，需要你手动开。

SafePad 与 Eagle 同时开启时 **SafePad 优先**：它成功放下方块的那一 tick，Eagle 会让开，
让你保持全速前进；一旦 SafePad 放不下去（手上没方块、够不着、被挡住），Eagle 立刻接管把你拦在边缘。
这就是「兜底的兜底」。

AimAssist 和 InvChest 与前三个都无关，各自独立，`enabled`（V 键）不影响它们。

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

同一套探针判定被 SafePad 复用，两个模块对「危险」的定义完全一致。

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

---

## 二、SafePad：垫块兜底

只取神桥里**和放置有关**的那部分，不做自动跳跃、不做自动后退/对角线移动控制。

### 1. 两种触发

* **预测型（没路了）**——站在地面上，Eagle 用的同一根探针报告前方地面断了。
  就在你**正前方、与你脚下那块同一层**的位置补一块。
* **补救型（要摔了）**——已经在空中且向下速度超过 `safePadRescueMinFallSpeed`。
  直接在你**正下方**补一块。

预测型做的是「沿移动方向的主轴走整整一格」，而不是你碰撞箱正前方那个点。
这样目标方块永远和你脚下那块**共面相邻**，才能找到可以点击的面——
对角线方向没有「对角面」可点，所以斜着走时会退化成先补主轴那一格。

### 2. 静默旋转

这是神桥最核心的机制，也是这个模块不需要拽你镜头的原因。
放置只需要服务端认为你在看着目标面，客户端镜头完全不用动：

```java
network.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, false));
mc.interactionManager.interactBlock(player, hand, hit);   // 由原版负责发包和 sequence
network.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(realYaw, realPitch, onGround, false));
```

两个细节是踩过的坑：

* `LookAndOnGround` 在 1.21.4 是 **4 参构造** `(yaw, pitch, onGround, horizontalCollision)`。
  第 4 个是**水平碰撞标志**，不是 `changeLook`——`changePosition=false` / `changeLook=true`
  是构造器里硬编码的。
* **恢复包不是可选项**。客户端只在自己视角变化时才重发朝向，
  所以如果只发欺骗包不恢复，服务端会一直卡在「低头看着脚」的状态。

### 3. 放置本身完全是原版

没有手搓 `PlayerInteractBlockC2SPacket`。构造一个 `BlockHitResult` 交给
`ClientPlayerInteractionManager.interactBlock(...)`——这正是你右键点方块时走的那条路。
客户端预测、sequence 计数、发包全部由游戏自己的代码处理。

目标面的挑选顺序是「下方邻居的顶面 → 四个水平邻居 → 上方邻居的底面」，
所以平地边缘、竖直墙面、天花板下面都能找到支撑。

### 4. 与 Eagle 的优先级

`EagleLogic` 在判定前先看 `SafePadLogic.isPlacing()`。SafePad 刚放下方块的这一 tick，
Eagle 直接让开，你保持全速前进；SafePad 放不下去时，Eagle 按原样拦你。

---

## 三、AimAssist

只做「辅助瞄准」，**不自动攻击**。打不打、什么时候打完全由你控制。

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

## 五、安装

1. 装好 **Fabric Loader**（≥ 0.16）和一个 **1.21.4** 的档案
2. 下载 **Fabric API**（1.21.4 版）放进 `mods`
3. 把 `eagle-1.5.0.jar` 放进 `mods`
4. 启动游戏，进世界后按 **V** 开关 Eagle，**B** 开关 SafePad，**R** 开关 AimAssist，**N** 开关 InvChest

左上角显示 `Eagle ON [sneak]  CPS ON [hold]  Pad OFF  Aim OFF  Inv OFF` 之类的实时状态。
聊天框里输入 `.inv speed 0.85` 可以直接调 InvChest 的取物间隔。

---

## 六、配置

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

  "cpsBoostEnabled": true,
  "cpsMin": 6.0,
  "cpsMax": 9.0,
  "cpsRequireBlock": true,

  "safePadEnabled": false,
  "safePadCooldownTicks": 2,
  "safePadFallRescue": true,
  "safePadRescueMinFallSpeed": 0.08,

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

  "invChestEnabled": false,
  "invChestDelayMs": 850,
  "invChestSkipExisting": true,
  "invChestMinTier": 1,
  "invChestCloseWhenDone": false
}
```

### Eagle

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | Eagle 总开关，V 键切换 |
| `edgeOffset` | `0.08` | 探针在身体半宽之外再前移的距离。**调大 = 更早触发 = 更安全，但更不像人**；钳制在 0 ~ 0.30。SafePad 共用这一个值 |
| `releaseMargin` | `0.06` | 迟滞量，防止潜行状态抖动 |
| `keepSneakWhileIdle` | `true` | 停下不动时仍保持潜行。在边缘上放方块很有用 |
| `sneakDelayMs` | `0` | 检测到边缘后延迟多久才潜行。**不建议调大**，见下方余量换算 |
| `releaseDelayMs` | `40` | 边缘消失后延迟多久才解除潜行 |
| `randomizeDelay` | `true` | 把两个延迟随机化到 `0..设定值`，打散固定节奏 |
| `onlyWhileBridging` | `false` | 只在手持可放置方块时生效 |
| `showHud` | `true` | 左上角状态显示 |

### Eagle · 右键提速

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `cpsBoostEnabled` | `true` | 右键提速子开关。**仍然受 `enabled` 约束**——按 V 关掉 Eagle 会把提速一起关掉 |
| `cpsMin` | `6.0` | 目标速率下限（次/秒），钳制在 1 ~ 20 |
| `cpsMax` | `9.0` | 目标速率上限（次/秒），钳制在 1 ~ 20 |
| `cpsRequireBlock` | `true` | 只在主手拿着可放置方块时提速，也就是「搭路的时候」 |

### SafePad

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `safePadEnabled` | `false` | SafePad 总开关，B 键切换 |
| `safePadCooldownTicks` | `2` | 两次放置之间的最小间隔（刻）。调太低会在放置反复失败时刷包 |
| `safePadFallRescue` | `true` | 是否启用「已经在空中」的补救型触发 |
| `safePadRescueMinFallSpeed` | `0.08` | 补救型触发所需的最小下落速度（格/刻） |

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

### InvChest

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `invChestEnabled` | `false` | InvChest 总开关，N 键切换。独立模块，不受 `enabled` 约束 |
| `invChestDelayMs` | `850` | 两次取物之间的间隔（毫秒），钳制在 50 ~ 10000。可用 `.inv speed <秒>` 在线修改 |
| `invChestSkipExisting` | `true` | 背包里已经有同种物品就跳过 |
| `invChestMinTier` | `1` | 最低取用等级 1~5。调到 2 就会把泥土圆石这类垃圾留下 |
| `invChestCloseWhenDone` | `false` | 没有可取的物品时自动关闭容器 |

### 关于安全余量

原版行走 4.317 格/秒 = **每刻 0.216 格**，检测每刻做一次。

探针位于身体中心前方 `0.3 + edgeOffset`，因此探针刚离开方块时，
身体前沿距离边缘还有 `edgeOffset` 格。潜行会在**同一刻**生效，
所以这一格余量只被消耗一次、且以潜行速度（约 0.065 格/刻）消耗。

注意玩家碰撞箱 0.6 宽，**整个箱子离开方块才会真的掉下去**。
探针触发时你离真正坠落还有约 `0.6 + edgeOffset` 格的缓冲，大约 3 刻。
SafePad 就是吃这个窗口的——所以它每次都能赶在坠落之前把块放下去。

如果调大 `sneakDelayMs`，每 50ms 就吃掉约 0.216 格的余量，**很容易直接摔下去**。

---

## 七、行为边界

**会生效**：站在地面正常行走、搭桥、跑搭、倒退搭、侧向搭。

**两个模块都会让开**：

* 创造飞行中、鞘翅滑翔中
* 游泳 / 水中 / 爬梯 / 骑乘
* 打开任何 GUI 时
* 手上没有 `BlockItem`（SafePad 与右键提速专用；Eagle 潜行只在开了 `onlyWhileBridging` 时看这个）

**SafePad 额外不做**：不自动跳跃、不自动后退、不控制你的移动输入、不自动切换快捷栏。
它只在你已经因为自己的操作走到危险的瞬间补一块。

**右键提速额外不做**：玩家不按右键时一次都不点。它只是把原版的 5 CPS 提到 6~9，
放哪个方块、朝哪个方向、什么时候放，仍然全部由你自己决定。

**AimAssist 额外不做**：不自动攻击（没有 KillAura）。它只往你当前正在瞄的方向拉，
你的鼠标输入始终是叠加的基底，辅助只是那一点「磁吸」。关掉它的方式是把
`aimHorizontalSpeed` / `aimVerticalSpeed` 设为 0，或者按 R。

**InvChest 额外不做**：不自动走去开箱子、不自动打开容器、不碰末影箱之外的特殊容器界面
（工作台、熔炉、漏斗这些一律不动）。它只在你**自己打开了**箱子之后才开始取，
而且每 0.85 秒才取一件。

**五个模块都不做**：不防摔落伤害、不改位置、不自动攻击、不自动移动。

> 关于服务器规则：这五个模块都会向服务端发送自动化输入，在多数服务器的规则下属于作弊。
> AimAssist 会显著改变 PvP 对抗的公平性，InvChest 在共享箱子的服务器上等同于快速搬空公共物资。
> 请只在你自己开的存档或允许的环境里使用。

---

## 八、自行构建

```bash
./gradlew build
```

产物在 `build/libs/eagle-1.5.0.jar`（`-sources.jar` 是源码包，不用丢进 mods）。

开发环境（`./gradlew runClient`）需要联网下载 Minecraft 资源与依赖。

---

## 九、项目结构

```
src/main/java/com/dmod/eagle/
├── EagleClient.java              入口：按键、HUD、tick 注册、开关逻辑
├── EagleConfig.java              JSON 配置读写与数值钳制
├── EagleLogic.java               Eagle：边缘预测 + 潜行判定 + 延迟状态机 + 共享探针
├── CpsBoostLogic.java            Eagle：右键提速的节奏调度
├── SafePadLogic.java             SafePad：目标选择 + 支撑面选择 + 静默旋转放置
├── AimAssistLogic.java           AimAssist：目标筛选 + 指数逼近 + 度数换算 + 抖动
├── InvChestLogic.java            InvChest：容器识别 + 等级评分 + 取物节奏
├── EagleCommands.java            聊天框客户端指令（.inv speed）
├── EagleHud.java                 左上角状态显示
└── mixin/
    ├── ChatCommandMixin.java     注入 sendChatMessage，拦下客户端指令
    ├── KeyboardInputMixin.java   注入 KeyboardInput.tick，改写 PlayerInput
    ├── MinecraftClientMixin.java 注入 MinecraftClient.tick，调度 itemUseCooldown
    └── MouseMixin.java           注入 Mouse.updateMouse，向 cursorDelta 叠加一笔修正
```

模组总共**四处 Mixin**，都只在客户端。

* SafePad 与 InvChest 完全不依赖 Mixin，它们走的是原版自己的交互 API。
* 右键提速几乎不依赖——它只是把原版那个私有计数器在正确的时刻放到正确的值。
* AimAssist 也几乎不依赖——它加的是原版本来就要消费的那笔鼠标位移，
  后面的角度换算、灵敏度曲线、视角应用全部是原版自己做的。
* 客户端指令这个是唯一「借」Mixin 干别的事的：它只是让聊天消息在变成数据包之前停下来。

---

## License

MIT
