# Chrome 自动化

Linux 服务端直接启动本机 Chromium，通过一个实例级 CDP WebSocket 复用标签页连接。
命令由同一份 Scala 声明生成 HTTP、CLI、Code Mode 和 GraalJS SDK。

## 实例与标签页

```sh
groot-cli chrome instance start --instanceName demo --headless true
groot-cli chrome instance list
groot-cli chrome tab new --instanceName demo --tabName main
groot-cli chrome tab list --instanceName demo
groot-cli chrome tab activate --instanceName demo --tabName main
groot-cli chrome tab delete --instanceName demo --tabName main
groot-cli chrome instance stop --instanceName demo
groot-cli chrome instance purge --instanceName demo
```

- `start(instanceName, kiosk=false, headless=false, extraArgs=[])`：默认普通窗口，`kiosk=true` 全屏，`headless=true` 无窗口；两者不能同时为 true。
- 同名、同模式、同 `extraArgs` 的 `start` 幂等；更改模式或参数需要先 `stop`。
- `stop` 关闭该实例所有标签页并保留资料；`purge` 先停止，再删除资料和元数据，实例不存在时返回 false。
- 名称为 1–64 个 ASCII 字母、数字、点、下划线或连字符，必须以字母或数字开头。
- `tab.new` 可选 `url`，默认 `about:blank`，同实例标签页名称不能重复。
- `tab.list` 返回名称、CDP target ID、URL、标题；弹窗及手动新建的标签页自动命名为 `tab-N`。
- 仅管理通过这些命令建立的实例；每个实例有自己的用户资料目录。
- 服务正常退出时关闭其浏览器，资料留存；重新启动实例后标签页重新建立。

`extraArgs` 是 Chrome 启动参数数组，每项为 `--flag` 或 `--flag=value`（值含空格也放在同一个字符串中）。
参数按原顺序追加到默认参数之后，通过 `ProcessBuilder` 原样传递，不经过 shell；例如可以覆盖默认窗口大小：

```js
await tools.chrome.instance.start({
  instanceName: "demo", headless: true,
  extraArgs: ["--window-size=1920,1080", "--autoplay-policy=no-user-gesture-required"],
});
```

```sh
groot-cli chrome instance start --instanceName demo --headless true \
  --extraArgs=--window-size=1920,1080 \
  --extraArgs=--autoplay-policy=no-user-gesture-required
```

`--user-data-dir`、`--remote-debugging-*` 由实例/CDP 管理，`--headless`、`--kiosk` 使用独立参数指定，
不能通过 `extraArgs` 覆盖。最多 128 项，每项最多 4096 字符，总共最多 65536 字符。
实例元数据和 `start/list/stop` 结果包含 `extraArgs`；旧实例默认空数组。
每次 `start` 都以本次参数为准，省略 `extraArgs` 等同于空数组，不会隐式沿用上次参数。

## 页面操作

所有 `chrome.page.*` 都带 `instanceName`、`tabName`，并支持 `timeoutMs`（默认 30000，范围 1–120000）。
超时覆盖等待实例锁与整个操作。实例内操作串行，不同实例可以并行。

| 命令 | 其他参数 | 结果 |
| --- | --- | --- |
| `goto` | `url`, `waitUntil=domcontentloaded` | URL、标题 |
| `snapshot` | `maxNodes=1000`（最多 5000）, `format=json`, `interactiveOnly=false` | 带层级和点击提示的 JSON 节点或紧凑文本树 |
| `click`, `hover` | `target`, `frameId?` | 成功无正文 |
| `input` | `target`, `text`, `clear=true`, `frameId?` | 清空/追加输入 |
| `select` | `target`, `value`, `frameId?` | 按 value 选择 HTML select 选项 |
| `check` | `target`, `checked`, `frameId?` | 设置 checkbox/radio 状态 |
| `press` | `key` | 发送按键，如 Enter、Control+A、Shift+Tab |
| `scroll` | `target?`, `deltaX=0`, `deltaY=600`, `frameId?` | 有 target 时滚动到元素，否则发送滚轮事件 |
| `wait` | `target?`, `text?`, `url?`, `state=visible`, `frameId?` | 等待所有指定条件成立 |
| `screenshot` | `fullPage=false` | PNG 绝对路径、MIME、宽高 |
| `evaluate` | 纯文本请求体 | 页面 JavaScript 的 JSON 结果 |
| `reload` | `waitUntil=domcontentloaded` | URL、标题 |
| `back`, `forward` | 无 | URL、标题 |

`waitUntil` 为 `commit`、`domcontentloaded` 或 `load`。`wait.state` 为 `visible`、`hidden`、`attached`、`detached`。
`wait.text` 检查 frame body 的可见文本子串，`wait.url` 为精确 URL；至少指定一种条件。

### 定位和引用

`target` 可以是 `snapshot` 返回的 `ref:...`，也可以是唯一 CSS selector。
CSS 查询会遍历开放的 Shadow DOM；iframe 使用快照中的 `frameId`，ref 自带 frame 信息。
每次新快照替换旧 ref，页面导航或节点删除后 ref 失效，返回 `chrome_stale_ref`，需要重新 snapshot。
多个 CSS 匹配会报错。点击和输入自动等待元素可见、启用、可操作；超过期限返回 `chrome_timeout`。
`click` / `hover` 收到 DOM 文本节点（`nodeType=3`）的 ref 时，先取它的直接父元素，
再执行原有的可见性、启用状态和遮挡检查、滚动及真实鼠标事件。事件按浏览器规则冒泡；
不会在失败后逐层改点祖先。输入、选择等其他操作仍要求对应的元素 ref。

### 快照层级和交互信息

默认 `format=json` 返回 `nodes`，每个节点含以下字段：

- `ref`：可用于后续操作；无对应 DOM 节点的 AX 内部节点可能为 null。
- `parentRef`：**同一 frame 内，本次输出中最近的带 ref 的 AX 祖先**；根节点为 null。
  忽略的 AX 节点、无 ref 的中间节点及被输出模式过滤的容器会被跳过，因此不等同于直接 DOM 父元素。
- `depth`：相对于输出中 `parentRef` 的深度，根为 0。节点按前序排列，父节点一定先于子节点；
  每个 frame 是独立的树，可按 `frameId` 分组。
- `nodeType` / `tagName`：DOM 类型与元素标签，如 `1` / `DIV`、`3` / null；无 DOM 元数据时为 null。
- `clickable`：Chromium `DOMSnapshot.isClickable` 提示，覆盖原生点击行为和直接注册的点击监听器。
  文本节点为 false；这不是可见、启用或不被遮挡的保证，false 也不排除祖先上的事件委托。
- `role` / `name` / `value`：AX 语义信息。可点击元素没有 AX 名称时，用后代的可访问文本补充名称。

例如外层 DIV 的 ref 可直接用于点击，文本 ref 也可通过父元素归一化完成点击：

```json
[
  {"ref":"ref:example:1","role":"generic","name":"请点击刷新","nodeType":1,"tagName":"DIV","clickable":true,"parentRef":null,"depth":0},
  {"ref":"ref:example:2","role":"StaticText","name":"请点击刷新","nodeType":3,"tagName":null,"clickable":false,"parentRef":"ref:example:1","depth":1}
]
```

### 紧凑文本快照

`format=text` 将紧凑树放在响应的 `text` 字段，`nodes=[]`，避免重复传输。
它省略无名称、无点击行为的 generic 容器及重复的 InlineTextBox，保留可直接操作的 ref。
`interactiveOnly=true` 可用于两种格式，只返回 Chromium 标记可点击的元素和 AX 控件（输入框、按钮等）。

```js
const {body} = await tools.chrome.page.snapshot({
  instanceName: "demo", tabName: "main", format: "text", interactiveOnly: true,
});
console.log(body.text);
```

```text
frame "FRAME_ID"
- div "二维码已过期 请点击刷新" [ref=ref:example:1] [clickable]
- textbox "请输入账号" [ref=ref:example:2]
```

`maxNodes` 在格式/交互过滤后计数；输出有 768 KiB 的节点/文本预算，达到数量或字节限制时
`truncated=true`，文本末尾同时显示 `[truncated]`。截断不会留下指向未返回节点的 `parentRef`。
仅本次输出中的 ref 可用，两种格式的 ref 生命周期相同。

```js
await tools.chrome.instance.start({instanceName: "demo", headless: true});
await tools.chrome.tab.new({instanceName: "demo", tabName: "main"});
await tools.chrome.page.goto({instanceName: "demo", tabName: "main", url: "https://example.com"});
const snapshot = (await tools.chrome.page.snapshot({instanceName: "demo", tabName: "main"})).body;
console.log(snapshot);
const shot = (await tools.chrome.page.screenshot({instanceName: "demo", tabName: "main"})).body;
console.log(shot.path);
```

`evaluate` 在浏览器页面主 world 执行，可访问页面 JavaScript，等待 Promise，`undefined` 返回 null。
返回值必须可 JSON 序列化；这里没有 GraalJS 的 `tools.*`。

```sh
groot-cli chrome page evaluate --instanceName demo --tabName main --body 'document.title'
```

```js
const title = await tools.chrome.page.evaluate({instanceName: "demo", tabName: "main", body: "document.title"});
```

截图最多 3200 万像素，保存在 `$CHROME_HOME/screenshots/`，由调用者按需读取、归档或删除。

## Cookies

```sh
groot-cli chrome cookies --instanceName demo --domain example.com
```

通过 CDP 获取包含 HttpOnly 的 Cookie。domain 为纯主机名，大小写不敏感，匹配该域及其子域，
`example.com` 不会匹配 `badexample.com`。返回 path、expires、secure、sameSite、partitionKey 等属性。

## 配置与交付

`config.env` 中的环境变量由 `server-deploy` 写入 user service：

- `CHROME_BINARY`：可选 Chromium/Chrome 可执行文件绝对路径，默认自动发现。
- `CHROME_HOME`：默认 `~/groot-chrome`，使用非隐藏目录以兼容 Ubuntu Chromium snap。
- `CHROME_WAYLAND_DISPLAY` / `CHROME_DISPLAY`：可选图形会话覆盖。
  未配置时使用继承的显示环境（优先 DISPLAY），或发现 X11/XWayland `:0`、当前用户运行目录中的 Wayland socket。
  多桌面环境建议显式指定目标会话。

CDP 使用随机端口并绑定本机回环地址。浏览器 stdout/stderr 写入实例目录的 `chrome.log`。
实例资料、元数据和命名映射保存在 `$CHROME_HOME/instances/<instanceName>/`。

```sh
groot-cli jobs --job chrome-test   # 使用真实 headless Chromium 和本地页面验证
CHROME_KIOSK_TEST=1 groot-cli jobs --job chrome-test  # 同时验证真实桌面的 kiosk 启动
groot-cli jobs --job server-deploy
```

`chrome-test` 在用户 home 下建立独立临时资料目录，完成后关闭并删除测试实例。
