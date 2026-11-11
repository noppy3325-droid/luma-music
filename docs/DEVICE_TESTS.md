# Xiaomi 14T + Apple EarPods (USB-C) 実機テスト

## 今回確認できた端末

- Xiaomi 14T / au XIG07
- Android 16 / API 36
- HyperOS `OS3.0.7.0.WNEJPKD`
- システムの `notification_focus_protocol = 3`
- システムの `persist.sys.feature.island = 1`

これらはADBで取得した端末・OSの情報です。アプリのHyperIsland表示や操作が確認済みであることを意味しません。

初回は端末が `INSTALL_FAILED_USER_RESTRICTED` を返しました。ユーザーの承認後は同じ署名で上書きでき、v0.20をUSB ADBで検証しています。画面操作はアプリ内のアクセシビリティとテストから送ったタッチイベントで実施し、端末のセキュリティ設定や音量設定は維持しました。結果は [v0.20 検証記録](VERIFICATION-v0.20.md) に掲載しています。

EarPodsの実接続はPCとのUSB ADBと同時に試せなかったため未検証です。着脱と物理ボタンのテストには、ADBを別の経路で維持できる環境が必要です。

```powershell
adb install -r release/Aurio-0.20.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell content call --uri content://jp.noppy.lumamusic.test.bench --method reset
adb shell am instrument -w jp.noppy.lumamusic.test/androidx.test.runner.AndroidJUnitRunner
```

## 実施するチェック

| 条件 | 確認する結果 |
|---|---|
| EarPodsを接続 | USB Audioとして表示、製品名はOSの報告値、接続機器一覧に追加 |
| EarPodsの仕様 | AudioDeviceInfoのレート・チャンネル・エンコーディングを列挙。未列挙はそのまま表示 |
| 44.1kHz / 48kHz | それぞれ別のローカルファイルを再生。ソースとAudioTrack設定を混同しない |
| USB高品質モード | 列挙された完全一致形式だけ希望設定。設定の成功・読み戻しを確認 |
| ビットパーフェクト試行 | 音量警告、BIT_PERFECT列挙、既知の無変換PCM、音量/速度/無加工条件を確認 |
| 非対応形式・拒否・例外 | 希望設定解除、通常再生。ビットパーフェクト成功と表示しない |
| 中央ボタン | Androidが認識した再生/一時停止がMediaSessionに反映 |
| 音量ボタン | Androidのメディア音量操作が実際に認識されるか確認。機器依存の未認識を区別 |
| USB切断 | 自動一時停止。スピーカーへの経路変更が先行しても停止。再接続で勝手に再開しない |
| 一般のUSB DAC | 同じ標準APIで再生・検出。機種名の特別扱いをしない |
| Bluetooth | 標準出力、ヘッドセット操作、切断時の一時停止 |
| バックグラウンド/画面ロック | 再生継続、通知とロック画面の操作、キュー維持 |
| HyperIsland | 再生情報、アート、前後曲、再生/停止、アプリ復帰。実際の島表示を撮影して確認 |
| スタンバイ | 未待機時の通常回転で起動しない。待機後の横姿勢、縦復帰・戻る・終了、再生維持 |
| 点灯設定 | 前面のみ有効。バックグラウンドで輝度・KEEP_SCREEN_ONを解除 |

DAC/HALの実レートや改変の有無の確認には、端末が開示する診断情報、または外部での測定が必要です。元ファイルのレートやAudioTrack設定だけでビットパーフェクト成功とは判定しません。

## 公式の参照先

- [Preferred mixer attributes on USB devices](https://source.android.com/docs/core/audio/preferred-mixer-attr)
- [AudioManager](https://developer.android.com/reference/android/media/AudioManager)
- [AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo)
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
- [Xiaomi HyperOS 媒体通知适配说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2161)
- [小米超级岛 开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)

Xiaomi公式資料は標準MediaSession/MediaStyle通知への適合を案内しています。今回の実装はMedia3が提供する一つの通知を使い、別の島通知を重複発行しません。
