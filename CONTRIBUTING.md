# Contributing to mumdroid

Thanks for taking the time to contribute! This document explains how to report
issues, propose changes, and get your work merged. It applies to everyone —
please read it before opening a pull request (PR).

[简体中文](#为本项目做出贡献)

---

## Contributor License Agreement (required)

Before your contribution can be accepted, you must agree to the project's
[Contributor License Agreement](CLA.md).

**By opening a pull request, you confirm that you have read the CLA and agree
to its terms for your present and future contributions.** You do not need to
sign any separate document. A PR that does not satisfy the CLA requirement will
not be merged.

In short, the CLA lets the maintainers and administrators of the project — now
and in the future — relicense your contributions under any
[OSI-approved license](https://opensource.org/licenses). Relicensing your
contribution under a non-OSI (for example proprietary) license requires your
separate explicit authorization.

## Developer Certificate of Origin (required)

Every commit in your pull request must be signed off to certify the
[Developer Certificate of Origin](https://developercertificate.org/) (DCO).
Add a `Signed-off-by` line with your name (the name or handle you go by) and an
email address as the last line of the commit message:

```
feat: add channel descriptions

Signed-off-by: Jane Doe <jane@example.com>
```

`git commit -s` adds the line automatically, using the name and email from your
Git configuration (set them with `git config user.name` and
`git config user.email`). To add the line to commits that are missing it, run
`git rebase --signoff master`.

The sign-off only needs to identify you — a name or handle together with an
email address is enough; it does not have to be your legal name. By signing
off, you certify that you wrote the contribution or otherwise have the right to
submit it under the project's license. The DCO is a per-commit confirmation and
is required **in addition to** the CLA above; a PR whose commits are not signed
off will not be merged.

## Reporting issues

- Search the existing issues first to avoid duplicates.
- For a bug, include: the app version, Android version and device/ABI, the
  server (Murmur) version, and clear steps to reproduce.
- For a feature request, describe the use case and the behaviour you expect.
- Attach logs or screenshots where they help. Do not report security issues in
  a public issue — contact the maintainer privately instead.

## Development setup

The build instructions live in the [README](README.md#build-from-source).
The short version:

```bash
git clone --recursive https://cnb.cool/womsxd/mumdroid.git
cd mumdroid
./gradlew :app:assembleDebug
```

The DSP libraries (libopus, RNNoise, speexdsp) are git submodules — clone with
`--recursive`, or run `git submodule update --init --recursive` afterwards if
CMake reports a missing directory.

Requirements: Android 8.0 (API 26)+, JDK 11, Android SDK with NDK 30 and CMake
3.22. The full toolchain is listed in the README.

## Making changes

1. Fork the repository and create a branch off `master`, for example
   `fix/jitter-buffer-preroll` or `feat/channel-description`.
2. Keep the change focused. One logical change per PR is much easier to review.
3. Match the existing code style (Kotlin, Jetpack Compose, Material 3). Add
   code comments only where the logic is not self-evident.
4. Add or update tests for behaviour you change. The JVM test suite lives in
   `app/src/test/`.
5. Update the documentation (both `README.md` and `README.zh-CN.md`, and the
   in-app strings in `res/values` / `res/values-zh`) when your change affects
   user-visible behaviour. Documentation must describe what the code actually
   does — do not claim capabilities or protections that are not implemented.

## Before you open a pull request

Run the same checks CI runs, and make sure they pass:

```bash
./gradlew :app:test    # JVM unit tests
./gradlew :app:lint    # static analysis
./gradlew :app:assembleDebug
```

If tests or lint fail, fix them before requesting review.

## Pull request process

- Open the PR against the `master` branch.
- Use a clear title and describe **what** changed and **why**. Link the issue
  it fixes (`Fixes #123`) where applicable.
- Use Conventional Commit style for the PR title/commits (`feat:`, `fix:`,
  `docs:`, `refactor:`, `test:`, `ci:`, `chore:`), optionally with a scope,
  e.g. `fix(ci): …`.
- Confirm in the PR description that you agree to the CLA.
- Sign off every commit with a `Signed-off-by` line (see above).
- Keep the branch up to date with `master`; resolve conflicts yourself.
- Respond to review feedback. Reviewers may request changes, and a maintainer
  makes the final decision on whether a PR is merged.

---

# 为本项目做出贡献

感谢您愿意花时间做出贡献！本文档说明如何报告问题、提交改动，以及如何让您的改动被合并。
它适用于所有人——在发起 Pull Request（PR）之前，请先阅读本文档。

[English](#contributing-to-mumdroid)

---

## 贡献者许可协议（必需）

在接受您的贡献之前，您必须同意本项目的[贡献者许可协议](CLA.md)。

**发起 Pull Request 即表示您确认已阅读 CLA，并同意其条款适用于您当前及未来的贡献。**
无需另行签署任何单独文件。不满足 CLA 要求的 PR 将不会被合并。

简言之，CLA 允许本项目的维护者与管理者（当前及未来）将您的贡献内容按任意
[OSI 批准许可证](https://opensource.org/licenses)进行再许可；若要以非 OSI（例如专有）
许可证再许可您的贡献，须另行取得您的明确授权。

## 开发者原创声明（DCO，必需）

Pull Request 中的每一个提交都必须签署，以证明遵守
[开发者原创声明](https://developercertificate.org/)（Developer Certificate of
Origin，DCO）。请在提交信息（commit message）的最后一行加上 `Signed-off-by`，内容为
您的称呼（您使用的名字或网名）与邮箱：

```
feat: add channel descriptions

Signed-off-by: 张三 <zhangsan@example.com>
```

`git commit -s` 会自动添加该行，姓名与邮箱取自 Git 配置（请先用 `git config user.name`
与 `git config user.email` 设置）。若已有提交缺少该行，可运行
`git rebase --signoff master` 补上。

签署行只需能标识您本人——一个称呼或网名加上邮箱即可，无需使用法定姓名。签署即表示
您证明该贡献由您本人创作，或您有权按本项目许可证提交它。DCO 是对每个提交的确认，与
上述 CLA **同时**适用；未签署的 PR 将不会被合并。

## 报告问题

- 请先搜索已有 issue，避免重复。
- 报告 bug 时请提供：应用版本、Android 版本与设备/ABI、服务端（Murmur）版本，以及
  清晰的可复现步骤。
- 功能建议请描述使用场景与期望行为。
- 如有帮助，请附上日志或截图。安全相关问题请勿在公开 issue 中报告，请私下联系维护者。

## 开发环境

构建步骤见 [README](README.zh-CN.md)。简要流程：

```bash
git clone --recursive https://cnb.cool/womsxd/mumdroid.git
cd mumdroid
./gradlew :app:assembleDebug
```

DSP 库（libopus、RNNoise、speexdsp）为 git 子模块——请以 `--recursive` 克隆，或在 CMake
报错提示目录缺失后执行 `git submodule update --init --recursive`。

环境要求：Android 8.0（API 26）及以上、JDK 11、带 NDK 30 与 CMake 3.22 的 Android SDK。
完整工具链见 README。

## 提交改动

1. Fork 仓库并从 `master` 创建分支，例如 `fix/jitter-buffer-preroll` 或
   `feat/channel-description`。
2. 保持改动聚焦。一个 PR 只做一件事，更易于评审。
3. 遵循既有代码风格（Kotlin、Jetpack Compose、Material 3）。仅在逻辑不自明处添加注释。
4. 对您改动的行为补充或更新测试。JVM 测试位于 `app/src/test/`。
5. 当改动影响用户可见行为时，请同步更新文档（`README.md` 与 `README.zh-CN.md`，以及
   `res/values` / `res/values-zh` 中的应用文案）。文档必须如实描述代码的实际行为，不得
   宣称尚未实现的能力或保护措施。

## 发起 Pull Request 之前

请运行与 CI 相同的检查，并确保全部通过：

```bash
./gradlew :app:test    # JVM 单元测试
./gradlew :app:lint    # 静态分析
./gradlew :app:assembleDebug
```

若测试或 lint 失败，请先修复再请求评审。

## Pull Request 流程

- 向 `master` 分支发起 PR。
- 使用清晰的标题，并说明**改了什么**以及**为什么**。若相关，请关联对应 issue
  （`Fixes #123`）。
- PR 标题/提交信息请使用 Conventional Commits 风格（`feat:`、`fix:`、`docs:`、
  `refactor:`、`test:`、`ci:`、`chore:`），可附带作用域，例如 `fix(ci): …`。
- 在 PR 描述中确认您同意 CLA。
- 为每个提交添加 `Signed-off-by` 签署行（见上）。
- 保持分支与 `master` 同步，并自行解决冲突。
- 回应评审意见。评审者可能要求修改，最终是否合并由维护者决定。
