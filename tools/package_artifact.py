# -*- coding: utf-8 -*-
"""Package a signed APK and a ZIP fallback into an isolated delivery directory."""
import hashlib
import shutil
import subprocess
import zipfile
from artifact_config import ROOT, VERSION, ARTIFACT, APK, ZIP

source = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
assert source.is_file(), "Build the APK before packaging"
aapt = ROOT / ".tools/android-sdk/build-tools/35.0.0/aapt"
if aapt.exists():
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(source)]).decode()
    assert "versionName='" + VERSION + "'" in badging, "APK version does not match source"
ARTIFACT.mkdir(parents=True, exist_ok=True)
target = ARTIFACT / APK
shutil.copy2(str(source), str(target))
digest = hashlib.sha256(target.read_bytes()).hexdigest()
archive = ARTIFACT / ZIP
with zipfile.ZipFile(str(archive), "w", compression=zipfile.ZIP_STORED) as bundle:
    bundle.write(str(target), "Orbit-" + VERSION + ".apk")
with zipfile.ZipFile(str(archive)) as bundle:
    assert bundle.testzip() is None
    assert hashlib.sha256(bundle.read(bundle.namelist()[0])).hexdigest() == digest
(ARTIFACT / "SHA256SUMS.txt").write_text(digest + "  " + APK + "\n" + hashlib.sha256(archive.read_bytes()).hexdigest() + "  " + ZIP + "\n")
page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Orbit VERSION</title><style>*{box-sizing:border-box}body{margin:0;background:#fbfcfa;color:#142a2c;font:16px/1.75 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}main{max-width:740px;margin:40px auto;padding:0 22px}h1{font-size:30px;margin:0}small,.muted{color:#6a7971}.hero{background:#142a2c;color:white;border-radius:24px;padding:28px;margin:26px 0}.hero p{color:#bdd2c4}.button{display:inline-block;padding:12px 20px;margin:5px 5px 0 0;border-radius:13px;background:#b2e3c0;color:#142a2c;text-decoration:none;font-weight:700}.section{background:white;border:1px solid #e7ebe3;border-radius:18px;padding:22px;margin:18px 0}h2{font-size:20px;margin:0 0 10px}li{margin:9px 0}code{background:#edf2e9;border-radius:6px;padding:2px 6px;overflow-wrap:anywhere}.hash{font:11px/1.8 monospace;overflow-wrap:anywhere}.note{font-size:13px;color:#6a7971}</style>
<main><h1>Orbit</h1><small>Android 原生客户端 · SSH · VERSION</small>
<section class="hero"><h2>会话、文件与代码审查</h2><p>任务、草稿与连接随身继续。多机工作、重启恢复、后台提醒、计划与代码审查。</p><a class="button" href="ZIP_NAME" download>下载 ZIP 安装包</a><a class="button" href="APK_NAME" download>直接下载 APK</a></section>
<section class="section"><h2>这一版改了什么</h2><ul><li>完善手机代码审查：仓库与分支、分组和行数统计、已读进度、文件筛选、代码高亮、自动换行和修改块导航；没有评论时收起底部工具栏。</li><li>评论发送支持选择会话、批量选择、默认排队、主动改为引导；发送前及队列实际发送前核对代码位置，变化时可重新定位，仍仅发送文件、行号和评论。</li><li>会话增加本轮变更入口，使用任务开始前的文件快照区分原有修改；工作区和本轮期间可切换查看，未记录起点时不伪造本轮统计。</li><li>评论发给 agent 时仅包含文件、行号和评论正文；移除额外提示词、代码摘录和 JSON 元数据。</li><li>运行中发送消息默认进入待发队列，支持编辑、改为引导、取消；当前轮正常完成后按顺序发送。队列按电脑和会话保存，断线、失败及重启恢复后暂停，需手动继续。</li><li>Git 读取会在对应电脑上验证并选择可用的 Git，兼容系统 xcrun 路径失效但已安装 Homebrew Git 的情况；变更页区分读取中、读取失败和工作区干净，并提供重试。</li><li>进一步对齐双层输入框：上层编辑文字、下层操作栏，移除输入后的项目与电脑定位行；收紧编辑区留白。项目与电脑仅在空输入框中作为提示显示。</li><li>输入框支持两种布局：空闲时收为单行胶囊，输入时展开为编辑区和工具栏；发送成功后收起。动态提示改为“在 项目 · 电脑 上工作”，不添加语音入口。</li><li>优化首次连接后的会话列表：按电脑与项目保存最近列表，先显示缓存再后台更新；上次项目尽早查询，同一列表合并重复请求，独立加载状态与超时重试，避免空白等待。</li><li>修复会话上下滚动时消息穿透标签栏与连接提示的问题：消息仅绘制在独立的滚动区域内，保留浅分隔线。</li><li>打开历史会话和重连时主动从对应电脑读取已保存的真实用量，无需发送新任务；不会覆盖读取期间收到的更新。用量面板区分读取中、无记录、读取失败，支持手动刷新。</li><li>修复用量偶尔消失：按电脑和会话保存最近一次真实用量，重启后恢复；暂无数据时保留可点击的“—%”入口。分支和压缩操作不可用时显示具体原因。</li><li>输入框空白时用当前项目与电脑名作为动态占位提示，输入后切换为小号定位行，避免重复；断线与重连状态继续显示，跨会话同步更新。</li><li>项目与电脑移入输入框内的固定定位行，输入文字后仍可见；会话顶部改为紧凑单行。Token 用量和窗口大小使用 K、M、B 十进制缩写，最多保留一位小数。</li><li>会话标签下方增加随主题适配的浅分隔线；输入框权限按钮旁显示当前会话的上下文使用百分比，可点击查看详细用量，接近上限时变色提醒。未同步到数据时不显示百分比。</li><li>精简会话顶部：一行标题、项目与电脑小字；数字标签移除边框，只有当前标签显示底色。上下文、token、SSH 状态收进会话详情，工作状态仅在消息过程中显示。</li><li>首页精简为电脑切换与项目列表，移除欢迎区域和连接大卡片；会话标签仅在会话页显示，新建等操作统一放入右上角三点菜单，设置入口仅保留在首页，删除顶部会话中心按钮。</li><li>移除底部导航栏，将“文件 / 变更”移到会话上方三点菜单；文件与变更页面保留三点切换入口，返回可直接回到会话。</li><li>输入框提示改为所连接电脑名称；移除运行中的“补充指令”和“设置从下一轮任务生效”提示，保持输入区域简洁。</li><li>修正发送按钮位置：固定在输入框底栏最右侧，箭头与停止图标居中，模型名称长度不再改变按钮位置。</li><li>输入框布局对齐原生 Codex：权限图标、模型与推理强度入口放到输入框底部；弹出菜单提供“默认权限 / 自动审查 / 只读 / 完整访问权限”，模型档位按电脑实际能力显示。</li><li>消息发送确认成功后退出输入焦点并收起键盘；失败保留文字与焦点，其他会话的发送不会干扰当前输入。</li><li>会话自动预览 HTML、SVG、Markdown 成果，支持源码切换与全屏；电脑上的文档与同目录资源可直接读取。</li><li>点击电脑 localhost 网页，通过会话所属电脑的 SSH 转接到手机；保留路径、查询和页面锚点，支持交互请求及 A→B 登录跳转。最终电脑需有 Python 3，预览依赖电脑服务和 SSH 在线。</li><li>Markdown 文件链接直接渲染，解析相对图片与链接；HTTPS 网站内置打开，一般 HTTP 网站可在手机浏览器打开。</li><li>重启后恢复跨电脑标签、名称、文字和图片草稿；关闭有草稿的标签前确认，发送结果不明时保留内容并提示核对历史。</li><li>支持项目与会话搜索、已加载消息查找。</li><li>设置可选后台连接与任务/审批提醒；显示常驻连接通知，可随时关闭。通知不展示消息正文，强制停止后不保证送达。</li><li>支持原生计划模式、任务计划清单、真实 token 用量与上下文估计；增加同步重命名、分支会话、确认后压缩上下文。</li><li>可编辑常用提示词、重用历史指令、分享与 Markdown 导出已加载消息；附件以占位说明导出。</li><li>外观支持跟随系统、浅色和深色，Markdown 和 diff 一并适配；按钮边界加深，增加连接排障建议和不含地址/凭据/正文的诊断摘要。</li><li>会话支持从相册选择最多 4 张图片，可预览、移除、单独发送图片；失败保留文字和附件，跨电脑切换不会串会话。图片经当前 SSH 路线上传至会话所属电脑，支持在进行中的任务中补充。</li><li>多台电脑同时维持独立 SSH 连接。开关打开立即连接并自动重连；关闭立即断开并取消重试，其他电脑保持连接。</li><li>项目与会话可跨电脑切换，每台电脑独立保存消息、草稿、权限和图片缓存。关闭开关保留标签与草稿。</li><li>SSH 支持导入 Host 别名与 ProxyJump、独立跳板凭据/指纹、A 上的 SSH 别名，以及 A 登录后免密自动跳转 B；文件与 Git 使用同一路径。</li><li>连接设置改成紧凑列表，显示状态和开关；长按编辑或手动连接，拖动左侧图标排序。</li><li>消息区域左右滑动切换相邻会话。</li><li>连接管理移到设置；每台电脑的开关直接控制连接和断开，可同时开启多台。断线逐步延长间隔重试，保留标签和草稿，指令不重发。</li><li>点击项目直接展开会话，支持同步归档、查看归档与恢复。</li><li>紧凑数字标签（32dp 高）：默认 1、2、3，长按打开统一会话管理菜单；右上角＋进入新会话，首次发送时创建；聊天工具提供搜索、刷新、文件、变更和提示词，导航页会话菜单提供置顶、命名、关闭、归档、详情、分享导出与高级操作。切换保留草稿、消息与阅读位置，后台持续更新。</li><li>权限对齐 Codex：请求审批、自动审批、完全访问，以及只读与自定义方案；读取电脑策略限制并等待服务确认。</li><li>重新调整项目、设置、会话输入框的留白、配色与层级。</li><li>SSH 连接支持 SOCKS5 代理，可通过 TailSocks 访问电脑的 Tailscale 地址，同时保留 FlClash VPN。每台电脑独立保存代理配置；旧连接默认保持直连，代理失败不会回退直连。</li><li>会话先加载最近 40 条消息，旧消息按需加载；缓存解码移到后台，重复进入复用会话连接。</li><li>图片附件、工具输出图片、Markdown 图片及图片文件链接直接渲染，不必展开工作过程；点击可全屏缩放。</li><li>输入框旁可选择模型、推理强度、权限模式；选项从电脑读取，等待服务确认生效，正在运行的任务保持原设置。</li><li>文件标签浏览目录和预览文件；消息中的代码文件链接在 App 内打开。可进入具体仓库目录后点击“审查此目录”。</li><li>Review 支持未暂存、已暂存、新增文件和删除行，带旧/新行号。点击代码行或文件评论按钮添加 comments。</li><li>评论可编辑、删除、汇总发送给 agent，附带文件、区段、行号与代码摘录；发送失败保留加密草稿，浏览和添加评论不会修改或提交代码。</li></ul><p class="note">发送图片最多 4 张，每张压缩至 2 MB；历史图片读取上限 8 MB，最长边预览 2048 像素，动画格式显示静态预览；没有可读取地址的云端图片会提示暂不可用。文本文件预览上限 2 MB。</p></section>
<section class="section"><h2>手机更新与使用</h2><ol><li>通过 SOCKS5 代理连接时，在“连接电脑”中填写目标电脑地址，并按实际网络配置填写代理地址和端口。支持无认证 SOCKS5。</li><li>推荐下载 ZIP，解压后安装里面的 APK。直接覆盖安装，无需卸载，保留原有 SSH 配置。</li><li>会话输入框上方点击权限、模型或推理强度切换选项，运行中的任务从下一轮使用新设置。</li><li>会话三点菜单中的“文件”浏览项目目录；如果仓库在子目录内，进入该目录后点“审查此目录”。</li><li>会话三点菜单中的“变更”打开文件 diff，点击具体行添加评论。点“评论”汇总检查，或点“发送给 agent”发送。</li><li>发送到当前工作目录相符的会话；没有合适会话时，会在当前目录新建会话。评论带的是添加时的代码位置，agent 会核对最新改动后处理。</li></ol><p class="note">Android 8.0 及以上。连接时填写你自己的电脑地址、SSH 端口和用户名；凭据在手机端输入。手机与电脑需要网络互通。</p></section>
<section class="section"><h2>对齐范围与验证</h2><p>本轮覆盖 SSH 远程会话、项目文件、图片展示、模型/推理/权限选择，以及未提交代码的 Review 评论流程。</p><p class="note">这是独立 SSH 客户端，尚未包含官方中继配对、普通 ChatGPT 聊天、语音、定时任务和进程被停止后的云端推送；不能视为官方客户端全部功能已复刻。</p><p class="note">自动化验证覆盖分页历史、Git diff、评论组装与发送、图片解析、设置切换；本机真实 Codex 服务已验证图片/文件读取、分页历史，以及临时会话中的模型/推理/权限生效。另用临时会话发送红蓝测试图，验证模型实际识图。安装包已生成；手机端需通过远程调试更新后验证。</p></section>
<p class="hash">APK SHA-256<br>DIGEST</p><a href="SHA256SUMS.txt">下载校验值</a><p class="note">Orbit VERSION · SSH 开发测试版</p></main></html>'''
page = page.replace("VERSION", VERSION).replace("APK_NAME", APK).replace("ZIP_NAME", ZIP).replace("DIGEST", digest)
(ARTIFACT / "index.html").write_text(page, encoding="utf-8")
print(str(ARTIFACT))
print("APK SHA-256: " + digest)
