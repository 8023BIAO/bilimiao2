# 自动点赞 (Auto Like)

> 参考: Bilibili-Evolved autoLike, BiliRoaming Xposed hook
> 创建: 2025-06-24

## 功能
进入视频详情页后自动点赞，免去手动操作。

## 实现要点

### 1. 设置开关
- **文件**: `bilimiao-comm/.../SettingPreferences.kt` — 加 `AutoLikeEnabled`
- **文件**: `bilimiao-compose/.../FlagsSettingPage.kt` — 在"网络测试"区域加开关

### 2. 触发逻辑
- **文件**: `bilimiao-compose/.../VideoDetailViewModel.kt`
- 视频详情加载完成 → 检查 `reqUser.like == 0`（未点赞）
- 开关开启 → 延迟 2 秒 → 调 `requestLike(arc, reqUser)`
- 已点赞（`reqUser.like != 0`）→ 跳过

### 3. 防风控
- **不秒点**：2 秒延迟模拟真实用户行为
- **不重试**：用户手动取消点赞后不自动再点
- Bilibili-Evolved 走 DOM 按钮点击（风控友好），bilimiao 直接调 API，延迟足够可规避

### 4. 可选增强（后续）
- 黑名单（不自动点赞某些 UP 主）
- 动态自动点赞（参考 Bilibili-Evolved 的 feed like）

## 改动文件清单
| 文件 | 改动 |
|------|------|
| `SettingPreferences.kt` | +1 行：`AutoLikeEnabled` key |
| `SettingConstants.kt` | 无需改动 |
| `FlagsSettingPage.kt` | +10 行：开关 UI（实验性功能→网络测试） |
| `VideoDetailViewModel.kt` | +8 行：自动点赞逻辑 |

## 参考资料
- Bilibili-Evolved: `registry/dist/components/utils/auto-like.js`
  - name: autoLike, displayName: 自动点赞
  - options: video (默认true), feed (默认true), manualFeed, users (黑名单)
  - entry: 视频页找 `.video-like` 按钮点击; 动态页遍历 `.bili-dyn-action.like`
- bilibili-api (Nemo2011): `video.py`
  - `has_liked()` → bool
  - `like(status=True)` → dict
  - `triple()` → 一键三连
