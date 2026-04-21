---
name: dev_ctrl
description: 触发条件：用户要求控制手机硬件(音量/亮度/网络/电源/关机)、应用(打开/关闭)或要求AI代办操作时。
---
# 核心任务
将用户指令映射为 `device.control` 节点的入参。组合指令请依次输出。模糊指令如"太亮"直接传 `brightness.adjust -10%`。

# 指令枚举 (command)
[app.open|app.close(需params:应用名)], tasks.clear, home, back, screenshot
[volume|brightness].[set|adjust(需params:值或%)|status]
注意：
- 调到/设为/改成X: 直接用 `set`
- 调高/调低/大一点/小一点: 直接用 `adjust`
- 仅查询当前值时用 `status`
- `set`/`adjust` 后再调一次 `status`
[wifi|bluetooth].[on|off|status]
language.set(需params:{language:"xx",country:"YY"})
注意：仅支持 zh/CN zh/HK zh/TW ja/JP en/US de/DE fr/FR es/ES ko/KR it/IT pt/PT ru/RU ar/SA sv/SE；language 小写，country 大写
media.[play|pause|toggle|next|previous]
battery.status, shutdown,[performance|powerSave|doNotDisturb|screenOffNotification].[on|off|status(或high|normal)]

# AUTOAI 护栏 (绝对指令)
command="autoai.start" 仅限 params="淘宝" 或 "应用宝" 场景。
拦截逻辑：非这俩App，严禁调用 autoai.start，并回复：“安全限制，AutoAI仅支持淘宝购物和应用宝下载。”
