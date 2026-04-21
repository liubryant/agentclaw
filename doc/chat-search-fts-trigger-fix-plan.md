# Chat Search FTS Trigger Fix Plan

## Summary

聊天搜索当前查不到工具前后的中间说明文本，根因不是消息下发或搜索输入，而是本地缓存刷新事务在删除旧消息时触发了错误的 SQLite external-content FTS4 trigger，导致 `cacheMessages()` 事务失败，最终 assistant 文本没有稳定写入本地缓存和搜索数据源。

日志已经证明：

- 网关确实下发了完整 assistant 文本，包含 `上海`、`北京`、`城市`、`具体位置` 等可搜索关键词。
- 在 `cacheMessages session=..., messageCount=8` 之后立刻出现：
  - `Silent history refresh failed: SQL logic error (code 1 SQLITE_ERROR)`
- 并且没有继续打印每条消息的 `cacheMessage[...]` 日志，说明事务大概率在 `deleteBySession(sessionKey)` 或紧随其后的消息写入阶段就失败了。

## Root Cause

当前 `fts_synced_messages` 使用的是 SQLite FTS4 external-content 模式，但同步 trigger 的时机不符合 SQLite 官方要求：

- 现状：
  - `synced_messages_ai`: `AFTER INSERT`
  - `synced_messages_ad`: `AFTER DELETE`
  - `synced_messages_au`: `AFTER UPDATE`
- 问题：
  - external-content FTS4 在删除旧行索引时，必须在源表真实删除之前执行。
  - `AFTER DELETE` / 单段 `AFTER UPDATE` 会导致 FTS 内部无法正确读取旧内容，从而触发 `SQL logic error (code 1 SQLITE_ERROR)`。

这与现象完全一致：

1. `cacheMessages()` 的第一步就是 `deleteBySession(sessionKey)`。
2. 删除旧 `synced_messages` 时触发错误的 FTS delete/update trigger。
3. 整个事务中断，新的 assistant 文本没有被落到 `synced_messages` / `synced_segments`。
4. 搜索页继续查询时只能得到 `rawMessages=0`。

## Implementation Changes

### 1. Rewrite `fts_synced_messages` triggers

把 `fts_synced_messages` 的同步方式改成符合 SQLite 官方要求的 external-content FTS4 trigger 组合：

- `AFTER INSERT ON synced_messages`
  - 插入新索引内容
- `BEFORE DELETE ON synced_messages`
  - 先删除旧索引内容
- `BEFORE UPDATE ON synced_messages`
  - 先删除旧索引内容
- `AFTER UPDATE ON synced_messages`
  - 再写入新索引内容

不要再使用：

- `AFTER DELETE`
- 单个 `AFTER UPDATE` 内同时做 delete + insert

### 2. Rebuild FTS using official rebuild command

`rebuildSearchFtsIndexes()` 不再手动：

- `DELETE FROM fts_synced_messages`
- `INSERT INTO fts_synced_messages(...) SELECT ...`

改为使用 SQLite 官方 rebuild 命令：

```sql
INSERT INTO fts_synced_messages(fts_synced_messages) VALUES('rebuild');
INSERT INTO fts_synced_tool_calls(fts_synced_tool_calls) VALUES('rebuild');
```

这样能避免 external-content FTS 在手工清空/重填时出现内部状态不一致。

### 3. Keep segment search simple

`synced_segments.textContent` 保持普通表查询方案，不再单独建立 external-content FTS：

- 普通消息正文：继续使用 `fts_synced_messages`
- 工具调用：继续使用 `fts_synced_tool_calls`
- 工具前后中间说明文本：通过 `synced_segments.textContent LIKE '%' || :query || '%'`

这样可以满足搜索需求，同时避免给 `synced_segments` 再引入复杂 trigger。

### 4. Keep temporary diagnostics until verified

在验证通过前，保留以下调试日志：

- `cacheMessages` 入口日志
- `deleteBySession` 前后
- `upsertAll(messages)` 前后
- `insertAll(segments)` 前后
- `insertAll(toolCalls)` 前后
- 搜索执行日志：
  - `query`
  - `ftsQuery`
  - `rawMessages`
  - `messageHit[...]`

验证完成后可视情况降级或移除。

## Files To Update

主要涉及以下位置：

- `app/src/main/java/ai/inmo/openclaw/data/local/db/AppDatabase.kt`
- `app/src/main/java/ai/inmo/openclaw/data/local/db/SearchDao.kt`
- `app/src/main/java/ai/inmo/openclaw/data/repository/SyncedChatCacheRepository.kt`

## Test Plan

### Cache stability

- 复现天气追问场景，确认不再出现：
  - `Session switch refresh failed: SQL logic error (code 1 SQLITE_ERROR)`
  - `Silent history refresh failed: SQL logic error (code 1 SQLITE_ERROR)`
- `cacheMessages()` 应完整执行到消息、segment、tool call 插入完成。

### Search correctness

- 搜索以下关键字应返回 message hit：
  - `上海`
  - `北京`
  - `城市`
  - `具体位置`
- 用户消息搜索仍正常。
- 工具调用搜索仍正常。
- 冷启动后再次搜索相同关键字仍能命中。

### Regression

- 会话切换后搜索仍正常。
- 历史静默刷新后搜索仍正常。
- 不引入新的会话缓存丢失或搜索崩溃。

## Assumptions

- 当前 blocker 是 `synced_messages` external-content FTS4 trigger 时机错误。
- 网关消息内容和本地段落提取本身没有问题。
- 搜索页 UI 和跳转逻辑本轮不需要调整。

## References

- SQLite FTS3/FTS4 official docs:
  - https://www.sqlite.org/fts3.html
- Relevant note from the official docs:
  - For external-content FTS4 tables, delete synchronization must occur before the underlying row is actually deleted.
