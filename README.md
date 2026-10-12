# FGA (Card Count) 개인 수정판

특정 서번트의 사용 가능한 일반 카드가 설정한 개수 이상 나올 때까지 일반 공격으로 기다린 뒤, 기존 커맨드를 처음부터 시작하는 개인용 수정판입니다.

- **APK 다운로드:** [이 저장소의 Releases](https://github.com/puruuun/FGA-card-count/releases)
- **설치·설정 안내:** [한국어 카드 개수 모드 안내](wiki/scripts/Card-Count-Start-KR.md)
- **원본:** [Fate-Grand-Automata/FGA](https://github.com/Fate-Grand-Automata/FGA), 공식 v3059 / `e138a15273dee9cac342833ee2d8aee93d8ae700`
- **앱 구분:** `FGA (Card Count)`, 초록색 Quick 아이콘, 패키지 `io.github.fate_grand_automata.cardcount`. 원본 공식판 및 원본 CI판과 별도 설치됩니다.
- **검증:** 실행 로직 41개 + 설정 파일 7개 테스트 통과, 앱 컴파일 및 APK 서명 확인. 실제 FGO 기기 동작은 아직 검증하지 않았습니다.

기본 모드는 기존 동작을 유지합니다. 카드 개수 모드에서는 대기 중 스킬·보구·Spam·자동 셔플을 보류하고, 조건 충족 위치부터 커맨드 웨이브·턴을 이동시켜 실행합니다. 시작 뒤에는 조건 검사를 생략하고 다음 주회에서 다시 초기화합니다.

비공개 저장소이므로 릴리스 다운로드에는 접근 권한이 있는 GitHub 계정의 로그인이 필요합니다. 아래는 원본 프로젝트 안내입니다. 수정판 APK는 위 Releases에서 받으세요.

---

# Fate/Grand Automata

[![CI](https://github.com/Fate-Grand-Automata/FGA/actions/workflows/ci.yml/badge.svg)](https://github.com/Fate-Grand-Automata/FGA/actions/workflows/ci.yml)

[![Discord Banner 2](https://discordapp.com/api/guilds/1117873862500163684/widget.png?style=banner2)](https://tinyurl.com/2z2d6wuz)

Auto-battle app for FGO (Android 7 or later, no need for root on phones).

Download from our [website](https://fate-grand-automata.github.io)

This is a **Kotlin** port of [FGO-Lua][FGOLua] as an Android app with UI for configuration and without a time-limit on use.  
It doesn't tamper with the game in anyway and works by looking at the screen and tapping things just like a normal user would do.  
It's not made to do the story for you, but to automate the mundane farming.

Having Trouble? See the [Troubleshooting Guide](https://github.com/Fate-Grand-Automata/FGA/wiki/Troubleshooting) first.

## Video Guide by @reconman

[![Watch the video guide](https://img.youtube.com/vi/JOwupZ4W8AQ/sddefault.jpg)](https://youtu.be/JOwupZ4W8AQ)

## How to Use?

1. Install from the link given above and launch the app.
2. Click on `Start Service` and give all the permissions it asks for.
3. Open FGO. Now, you can see a button with play icon on it floating on screen.
4. Go to the node you want to farm.
5. Press Play to start. The same button can be pressed to pause/stop later.

Check the [Troubleshooting Guide](https://github.com/Fate-Grand-Automata/FGA/wiki/Troubleshooting) first if you face any problems.

## How to make/use images of Servant/CE/Friend?

See the wiki page for [Support Image Maker](https://github.com/Fate-Grand-Automata/FGA/wiki/Support-Image-Maker).

## What about other scripts like Lottery and Friend Gacha?

When you click on the PLAY button, the app detects which script can be run on the current screen and presents it to you.

## How does it work?

This is a native Android app written in Kotlin.
We use [OpenCV](https://opencv.org/) for image recognition,
[Media Projection](https://developer.android.com/reference/android/media/projection/MediaProjection) for taking screenshots
and [Accessibility Service](https://developer.android.com/guide/topics/ui/accessibility) for clicking/swiping.

## Contributing

If you want to contribute, read the [Contribution Guide](CONTRIBUTING.md).

## Acknowledgements

- [FGO-Lua][FGOLua] developers are the real deal. Without them this app won't exist.
- The icons are from https://materialdesignicons.com/

[FGOLua]: https://github.com/29988122/Fate-Grand-Order_Lua

## Like the project? Want to support us?

<a href='https://ko-fi.com/W7W0F7D9T' target='_blank'><img height='36' style='border:0px;height:36px;' src='https://storage.ko-fi.com/cdn/kofi2.png?v=3' border='0' alt='Buy Me a Coffee at ko-fi.com' /></a>

Code/doc contributions are surely welcome!

Translations should be edited via https://poeditor.com/join/project/67PXOyBGI0
