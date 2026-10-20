# UnzipKeylist

安卓批量解压工具，支持 ZIP / 7z / RAR 与分卷压缩，内置「一行一个密码」的可自定义密码本，逐个尝试密码直到解压成功。密码本主要用于放置用户常用的密码。
An Android batch extraction tool for ZIP / 7z / RAR and split archives, with a built-in "one password per line" book that tries every password until extraction succeeds. The password book is primarily used to store frequently used passwords by users.

下载见Release.
Go to "Release" to download.

## 功能特性 Features
- 支持 ZIP（AES 与传统加密）、7z（LZMA / LZMA2）、RAR（RAR4 / RAR5，含加密文件名）。

  Supports ZIP (AES and legacy encryption), 7z (LZMA / LZMA2) and RAR (RAR4 / RAR5, including encrypted headers).

- 支持分卷压缩包：`xxx.z01/z02…xxx.zip`、`xxx.part1.rar`、`xxx.7z.001`、`xxx.zip.001`。

  Supports split archives: `xxx.z01/z02…xxx.zip`, `xxx.part1.rar`, `xxx.7z.001`, `xxx.zip.001`.

- 密码本为纯文本，一行一个密码，可在应用内编辑、导入、导出。

  The password book is a plain text file, one password per line, and can be edited, imported and exported inside the app.

- 也可手动输入密码；若手动输入的密码生效，会询问是否加入密码本；未手动输入时完全依赖密码本逐条尝试。

  A manual password can be entered; if it works the app asks whether to save it into the book; when nothing is entered manually, the book is used exclusively.

- 可选择单个压缩包，也可选择整个文件夹。

  Pick individual archives, or pick a whole folder (with an option to include subfolders) and extract every archive inside at once.

- 输出位置可选：解压到压缩包所在目录（不创建子文件夹），或解压到指定目录。

  Output location: extract next to the archive (no extra subfolder), or into a folder you choose.

- 可选「解压成功后删除原压缩包」。

  Optionally delete the original archive after a successful extraction.

- 实时进度条与逐条目状态提示。

  Live progress bar with per-entry status text.

- 中文 / 英文界面，跟随系统语言，支持深色模式。

  Chinese / English UI that follows the system language, with dark mode support.

## 技术栈 Tech stack
| 项目 / Item | 版本 / Version |
| --- | --- |
| 语言 / Language | Kotlin |
| 构建 / Build | Gradle 9.6.0 + AGP 9.4.0 |
| minSdk / targetSdk | 26 (Android 8.0) / 37 |
| 界面 / UI | XML + View + Material Components 1.10.0 |
| ZIP | zip4j 2.11.6 |
| 7z | Apache Commons Compress 1.28.0 + XZ 1.10 |
| RAR | junrar 8.1.2 |

---

## 项目结构 Project structure
```text
app/src/main/java/com/example/unzipkeylist/
├── MainActivity.kt            主界面 / main screen
├── PasswordBookActivity.kt    密码本编辑 / password book editor
├── archive/
│   ├── ArchiveEngine.kt       引擎接口与错误类型 / engine interface and error types
│   ├── ArchiveModels.kt       格式识别与分卷定位 / format detection and split-part locating
│   ├── ZipEngine.kt           ZIP
│   ├── SevenZEngine.kt        7z
│   ├── RarEngine.kt           RAR
│   └── ExtractionRunner.kt    批量调度与密码遍历 / batch runner and password loop
├── password/PasswordBook.kt   密码本读写与导入导出 / read, write, import, export
└── util/StorageUtils.kt       存储权限与路径解析 / storage permissions and path resolving
```

## 许可证 License
本项目采用 MIT 协议。
This project is licensed under the MIT License.
