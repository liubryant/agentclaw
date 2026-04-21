// hook.js
const originalFetch = global.fetch;

global.fetch = async function(...args) {
    const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || String(args[0]));
    const opts = args[1] || {};

    // 打印请求头和请求体
    console.log('\n\x1b[36m==========[OpenClaw 发出请求] ==========\x1b[0m');
    console.log('\x1b[33mURL:\x1b[0m', url);
    
    if (opts.body) {
        try {
            // 尝试把请求体格式化为美观的 JSON，方便你排查 tool_calls 的格式
            const parsedBody = JSON.parse(opts.body);
            console.log('\x1b[33mBody:\x1b[0m', JSON.stringify(parsedBody, null, 2));
        } catch {
            console.log('\x1b[33mBody:\x1b[0m', opts.body);
        }
    }

    // 调用原始的 fetch，完全不影响 OpenClaw 的正常运行
    const res = await originalFetch(...args);
    
    // 【关键】克隆一份响应流。这样我们可以打印日志，而不会导致 OpenClaw 后续读不到数据
    const clone = res.clone(); 

    console.log('\n\x1b[32m========== [OpenClaw 收到响应] ==========\x1b[0m');
    console.log('\x1b[33mStatus:\x1b[0m', clone.status);
    
    // 异步读取流式数据，不阻塞主程序
    (async () => {
        try {
            if (clone.body) {
                const reader = clone.body.getReader();
                const decoder = new TextDecoder('utf-8');
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    // 原汁原味打印 SSE 数据块 (data: {"choices": [...]})
                    process.stdout.write(decoder.decode(value, { stream: true }));
                }
                console.log('\n\x1b[32m========== [响应流结束] ==========\x1b[0m\n');
            } else {
                // 如果不是流式响应，直接打印文本
                const text = await clone.text();
                console.log(text);
                console.log('\n\x1b[32m========== [响应结束] ==========\x1b[0m\n');
            }
        } catch (err) {
            console.error('\x1b[31m[日志读取错误]\x1b[0m', err);
        }
    })();

    // 必须把原始响应返回给 OpenClaw
    return res;
};