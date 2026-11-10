# 0.4.1 图标与界面

用户指定使用 imagegen 生成图标素材，原生 Android Compose 实现界面；不使用整屏生成图冒充可操作 UI。方向：北欧简洁、紧凑层级、暖白纸色、松绿、少量暖灰金。仅提供记录和采集状态，不编造消费总额。

生成方式：内置 imagegen，透明背景。最终资源：
- `app/src/main/res/drawable-nodpi/pw_brand.png`：票据与信封组合，用于应用、自适应桌面图标和系统通知图标。
- `app/src/main/res/drawable-nodpi/pw_debit.png`：通用银行建筑，用于借记卡来源；不是民生官方商标。
- `app/src/main/res/drawable-nodpi/pw_credit.png`：通用银行卡，用于信用卡来源；不是民生官方商标。

导航图标为原生矢量资源，保持小尺寸可读性。生成图保留透明通道；Android 自行缩放。系统通知使用标准模板和单色着色，实际背景、圆角及折叠布局由手机系统控制。

## 生成提示词

三张的共同提示词：

> Use case: logo-brand. Production Android app raster icon asset for PayWhy, a quiet elegant personal transaction journal. Scandinavian editorial graphic design, highly distinctive restrained geometry, strong readable silhouette at small sizes. Flat shapes, warm understated colors, tasteful optical balance, no gloss, no bevels, no 3D, no mockup scene, no text, no gradient, no watermark. Genuinely transparent background, generous empty outer margin so full artwork fits within central 62% square safe zone. Large square image.

主图标追加：

> Main app emblem: a single ingenious dark pine-green geometric mark combining a folded receipt and a sealed envelope. A small folded upper corner and a single precise negative-space envelope diagonal. One solid ink color #25483F only, use transparent negative spaces, no background plate. Refined Nordic stationery brand. Avoid any floating parts, excessive detail, or decorative leaves.

借记卡追加：

> Debit account icon: a compact minimal bank building, three thick pillars and a flat triangular roof, rounded subtle corners. Dark pine green #25483F with one restrained warm taupe #C8B89B accent at base. A unified simple symbol, no outline hairlines. No circle or square container behind it.

信用卡初稿追加：

> Credit account icon: a single elegant payment card with rounded corners, small square chip and two short horizontal bars, one offset taupe paper edge peeking from behind it. Main card dark pine green #25483F, chip warm taupe #C8B89B, cutout bars transparent. Refined crisp planar graphic. No circle or square container behind it.

信用卡最终编辑提示词（去除初稿光晕）：

> Edit this icon asset. Preserve the front credit-card silhouette, chip, and offset back card, but remove ALL glow, haze, shadows, texture and gradients completely. STRICTLY FLAT VECTOR-LIKE GRAPHIC. Crisp hard edges with minimal antialiasing only. Front card solid #25483F, chip and rear card solid #C8B89B. Add two short transparent horizontal cutout marks near lower-left of front card. No background of any color: genuine fully transparent RGBA outside the solid shapes. Center the compact symbol on a SQUARE canvas, symbol occupying central 60% so it matches companion app icons. All corners modestly rounded. This is a small 32dp Android UI icon; pixel-perfect clarity, absolutely no lighting effects.


## 0.4.4 日常界面收敛

- 界面改为暖白、石墨灰、浅灰紫；原有生成图标保留原素材，应用内使用统一石墨灰着色。
- 投递页只显示日常邮件，长主题／尝试次数／原文入口按需展开；测试发送和测试历史移入设置底部折叠的辅助工具。
- 常驻通知使用金额大字与一行状态，展开后显示预授权、退款及待核对信息。去除 CNY / USD 文字，保留“积分”“游戏币”；其他适配币种使用独立符号，不混加金额。
- 通知采用系统装饰的自定义内容布局与系统文字颜色，支持明暗通知面板。参考 [Android 自定义通知布局](https://developer.android.com/develop/ui/views/notifications/custom-notification)；系统仍决定通知外框及操作区。
