# CastLink v0.1.41 DLNA DMR 真机验收清单

> 目标：按 DLNA-DMR-INTEGRATION-PLAN.md 四个阶段验收。沙箱已做：JVM 单元测试 78/78、
> 全量编译、APK 打包验证（dex 含全部 DLNA 类 + Media3）。以下需真机验证。

## 前置
- [ ] TV 盒子安装 CastLink-v0.1.41.apk（**干净安装**：先卸载旧版再装，避免状态残留）
- [ ] 手机与 TV 连**同一 Wi-Fi**
- [ ] 打开 CastLink 待机页，确认前台服务通知文案为"CastLink 接收服务运行中：支持隔空播放与流媒体投屏"

## 阶段 1：发现（SSDP / description.xml）
- [ ] 手机打开腾讯视频 → 投屏列表能看到 **CastLink-TV**（等待约 5 秒，SSDP 响应+NOTIFY）
- [ ] 爱奇艺 / 哔哩哔哩投屏列表同样能看到
- [ ] （可选）电脑浏览器访问 `http://<TV-IP>:8758/description.xml`，能看到 friendlyName=CastLink-TV、
      三个 service（AVTransport / RenderingControl / ConnectionManager）

## 阶段 2：投屏发起（SOAP）
- [ ] 在腾讯视频选一部影片点投屏 → TV 自动跳到 DLNA 播放页，**顶部显示片名**
- [ ] 播放页中央 Loading 圈转几秒后出画面、有声音
- [ ] 投屏后 1 秒内手机端不弹"投屏失败"（SetAVTransportURI → TRANSITIONING 即时反馈）

## 阶段 3：播放内核（ExoPlayer）
- [ ] HLS（m3u8）影片播放流畅、无黑边（竖屏片应等比铺满不拉伸）
- [ ] 手机端拖进度条 → TV 同步 Seek
- [ ] 手机端暂停/播放 → TV 同步；TV 遥控器**确定键**暂停 → 手机端状态变暂停
- [ ] 遥控器**左/右键**快退/快进 10 秒，OSD 进度条呼出
- [ ] OSD（片名+进度条）5 秒无操作自动隐藏；按任意方向键重新呼出
- [ ] 手机端调音量 → TV 系统媒体音量跟随变化

## 阶段 4：退出与异常
- [ ] 遥控器**返回键** → 停止投屏、手机端显示已停止、TV 回到待机页
- [ ] 手机端点"退出投屏" → TV 回待机页
- [ ] 播完一部影片 → TV 回待机页（STATE_ENDED → STOPPED）
- [ ] 切后台再回待机页：若投屏中，播放页应仍在（StandbyActivity.onResume 补拉逻辑）

## 日志（出问题时）
- 设置 → 查看日志，搜 `DlnaServer` / `DlnaBridge` / `DlnaPlayer` 看：
  - `DLNA SSDP answered M-SEARCH`（发现阶段）
  - `DLNA SOAP /control/avt#SetAVTransportURI -> 200`（控制阶段）
  - `setUri:` 打印的 URL（确认拿到的是 http(s) 链接）
  - `player error:`（播放失败原因）
