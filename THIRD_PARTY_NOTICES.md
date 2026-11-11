# Third-party notices

このプロジェクトのアプリコードは、次の外部ライブラリを使用します。Appleの画像・音源・フォント・ロゴは同梱していません。アルバムアートと歌詞はユーザーがアクセスを許可したローカルファイルから読み取ります。

| ライブラリ | バージョン | ライセンス |
|---|---|---|
| AndroidX Compose BOM | 2026.09.00 | Apache-2.0 |
| AndroidX Activity | 1.13.0 | Apache-2.0 |
| AndroidX Lifecycle | 2.11.0 | Apache-2.0 |
| AndroidX Media3 | 1.11.1 | Apache-2.0 |
| AndroidX Room | 2.8.5 | Apache-2.0 |
| AndroidX DataStore | 1.3.0 | Apache-2.0 |
| AndroidX DocumentFile | 1.1.0 | Apache-2.0 |
| AndroidX Palette | 1.1.0 | Apache-2.0 |
| Material Icons Extended | 1.7.8 | Apache-2.0 |
| Coil | 3.6.3 | Apache-2.0 |
| Backdrop (Kyant0) | 2.0.1 | Apache-2.0 |
| Kotlin / kotlinx.coroutines | 2.4.21 / 1.11.0 | Apache-2.0 |
| Guava (Media3の依存関係) | Gradleが解決したAndroid向け版 | Apache-2.0 |
| DataStore external Protobuf | 1.3.0 | BSD-3-Clause |
| Noto Sans JP（Regular / Medium / Bold） | 2.004 | SIL Open Font License 1.1 |
| JUnit（テストのみ） | 4.13.2 | EPL-1.0 |
| AndroidX Test（テストのみ） | runner 1.7.0 / ext-junit 1.3.0 | Apache-2.0 |

AndroidXとKotlinはそれぞれの著作権者、CoilはCoil Contributors、GuavaはGoogle LLCおよびcontributorsによるものです。依存ライブラリの著作権表示は配布元のPOM/ソース/同梱メタデータに従います。Gradle WrapperとGradleはApache-2.0です。ビルド用JDKはAPKに同梱しません。

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [JUnit license](https://junit.org/junit4/license.html)
- [AndroidX source](https://android.googlesource.com/platform/frameworks/support/)
- [Media3 source](https://github.com/androidx/media)
- [Coil source/license](https://github.com/coil-kt/coil/blob/main/LICENSE.txt)
- [Backdrop source/license](https://github.com/Kyant0/AndroidLiquidGlass/tree/2.0.1)
- [Kotlin source/license](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)

完全な依存関係ツリーは `gradlew :app:dependencies --configuration debugRuntimeClasspath` で再現できます。

今回のdebugアーティファクトが解決したライブラリは `docs/DEPENDENCIES.csv` に記録しています。Guava系のライセンスは親POMで確認しました。APKの `assets/licenses/` に依存一覧とApache-2.0・Protobuf BSD-3-Clauseの本文を同梱しています。

AurioはNoto CJKプロジェクトが配布するNoto Sans JP 2.004のRegular、Medium、Boldを、未改変の静的OTFとして同梱します。Copyright © 2014–2021 Adobe (http://www.adobe.com/)。SIL Open Font License 1.1全文は `licenses/NotoSansJP-OFL.txt` とAPK内の `assets/licenses/NotoSansJP-OFL.txt` にあります。同梱ファイルのSHA-256は `docs/aurio-fonts.csv` に記録しています。

- [公式配布ファイル](https://github.com/notofonts/noto-cjk/tree/main/Sans/SubsetOTF/JP)
- [OFLライセンス原文](https://github.com/notofonts/noto-cjk/blob/main/Sans/LICENSE)
