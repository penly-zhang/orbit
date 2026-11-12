package dev.codexpocket.core

import kotlinx.serialization.json.JsonPrimitive

/** Source is JSON data, never HTML or executable JavaScript. The renderer is bundled in the APK. */
fun mermaidDocument(source: String, dark: Boolean = false): String {
    val payload = JsonPrimitive(source.take(50001)).toString().replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    val background = if (dark) "#101719" else "#ffffff"
    val foreground = if (dark) "#e2ece9" else "#142a2c"
    val theme = if (dark) "dark" else "default"
    return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'">
<style>body{margin:0;padding:12px;background:$background;color:$foreground;font:14px/1.6 sans-serif}#diagram{min-height:120px;overflow:auto}svg{display:block;max-width:100%;height:auto;margin:auto}#status{white-space:pre-wrap;overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}</style>
<script src="/__orbit_mermaid__/mermaid.min.js"></script></head><body>
<div id="status" role="status">正在绘制图表…</div><div id="diagram"></div>
<script>
const source=$payload;
const status=document.getElementById('status'), diagram=document.getElementById('diagram');
window.orbitMermaidState='loading';
function fail(message){window.orbitMermaidState='error';diagram.replaceChildren();status.textContent='图表暂时无法绘制，请查看源码。\n'+message;const pre=document.createElement('pre');pre.textContent=source;diagram.appendChild(pre);}
const timeout=setTimeout(()=>fail('图表较复杂，请简化后重试。'),12000);
(async()=>{try{
if(source.length>50000)throw new Error('图表源码超过 50,000 字符。');
mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'$theme',fontFamily:'sans-serif',maxTextSize:50000,maxEdges:500,suppressErrorRendering:true,flowchart:{htmlLabels:false}});
const result=await mermaid.render('orbit-diagram',source);
if(window.orbitMermaidState==='error')return;
diagram.innerHTML=result.svg;status.textContent='';window.orbitMermaidState='ready';
}catch(error){fail(String(error.message||error));}finally{clearTimeout(timeout);}})();
</script></body></html>"""
}
