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

- `start(instanceName, kiosk=false, headless=false)`：默认普通窗口，`kiosk=true` 全屏，`headless=true` 无窗口；两者不能同时为 true。
- 同名、同模式 `start` 幂等；切换模式需要先 `stop`。
- `stop` 关闭该实例所有标签页并保留资料；`purge` 先停止，再删除资料和元数据，实例不存在时返回 false。
- 名称为 1–64 个 ASCII 字母、数字、点、下划线或连字符，必须以字母或数字开头。
- `tab.new` 可选 `url`，默认 `about:blank`，同实例标签页名称不能重复。
- `tab.list` 返回名称、CDP target ID、URL、标题；弹窗及手动新建的标签页自动命名为 `tab-N`。
- 仅管理通过这些命令建立的实例；每个实例有自己的用户资料目录。
- 服务正常退出时关闭其浏览器，资料留存；重新启动实例后标签页重新建立。

## 页面操作

所有 `chrome.page.*` 都带 `instanceName`、`tabName`，并支持 `timeoutMs`（默认 30000，范围 1–120000）。
超时覆盖等待实例锁与整个操作。实例内操作串行，不同实例可以并行。

| 命令 | 其他参数 | 结果 |
| --- | --- | --- |
| `goto` | `url`, `waitUntil=domcontentloaded` | URL、标题 |
| `snapshot` | `maxNodes=1000`（最多 5000） | 可访问性树、frame ID、元素 ref、截断标记 |
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
快照节点 JSON 总量最多 768 KiB，达到数量或字节限制时 `truncated=true`。
多个 CSS 匹配会报错。点击和输入自动等待元素可见、启用、可操作；超过期限返回 `chrome_timeout`。

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
