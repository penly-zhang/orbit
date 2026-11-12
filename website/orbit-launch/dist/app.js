const demos = {
 chat: '<div class="demo-date">今天 · Codex</div><div class="demo-user">帮我做一个更适合手机浏览的首页。</div><div class="demo-agent"><strong>✳ Codex</strong><p>我会先梳理页面结构，再调整移动端布局，让内容在小屏幕上也清晰易读。</p><div class="demo-tool"><span>✓</span>已查看项目文件</div><p style="margin-top:12px">首页已更新，你可以预览效果，再告诉我想调整的地方。</p></div>',
 tasks: '<p class="demo-title">任务中心</p><div class="demo-date" style="text-align:left">已关注的对话 · 跨电脑汇总</div><div class="demo-task"><span class="task-dot orange"></span><div><b>确认首页设计方向</b><small>Studio / website</small></div><span>待处理</span></div><div class="demo-task"><span class="task-dot blue-bg"></span><div><b>优化图片加载</b><small>Studio / website</small></div><span>运行中</span></div><div class="demo-task"><span class="task-dot green"></span><div><b>调整移动端布局</b><small>Dev / storefront</small></div><span>待验收</span></div><span class="demo-label">打开任务，查看结果或继续反馈。</span>',
 review: '<p class="demo-title">查看变更</p><div class="demo-date" style="text-align:left">website / styles.css</div><div class="demo-diff">.page {<br>&nbsp; display: grid;<br><span style="color:#b7776a">− padding: 48px;</span><b>+ padding: 20px;</b>}</div><div class="demo-agent"><strong>↳ 行评论</strong><p>手机端的间距更合适了。请保留桌面端的宽松布局。</p><div class="demo-tool">评论可作为反馈发送到对话</div></div>'
};
const tabs = [...document.querySelectorAll('[data-demo]')];
function selectTab(tab, focus = false) {
 for (const item of tabs) { const selected = item === tab; item.setAttribute('aria-selected', String(selected)); item.tabIndex = selected ? 0 : -1; }
 const panel = document.getElementById('demo-panel'); panel.innerHTML = demos[tab.dataset.demo]; panel.setAttribute('aria-labelledby', tab.id); if(focus) tab.focus();
}
for (const [index, tab] of tabs.entries()) {
 tab.addEventListener('click', () => selectTab(tab));
 tab.addEventListener('keydown', event => { let next; if(event.key === 'ArrowRight') next = (index + 1) % tabs.length; if(event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length; if(event.key === 'Home') next = 0; if(event.key === 'End') next = tabs.length - 1; if(next !== undefined) { event.preventDefault(); selectTab(tabs[next], true); } });
}
selectTab(tabs[0]);
