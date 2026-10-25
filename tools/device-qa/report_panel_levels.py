"""Summarize native panel fixtures measured by the real on-device reader.

Requires numpy/opencv-python for the selection-border pixel audit only.
Neither host OCR nor a host classifier supplies acceptance results.
"""
from pathlib import Path
import argparse
import collections
import json
import math
import xml.etree.ElementTree as ET
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
LAYOUTS = ("native-16:9", "native-wide")


def percentile(values, q):
    values = sorted(values)
    point = (len(values)-1)*q
    lo, hi = math.floor(point), math.ceil(point)
    return values[lo]*(hi-point)+values[hi]*(point-lo) if lo != hi else values[lo]


def table(headers, rows):
    return "\n".join(["| "+" | ".join(headers)+" |", "|"+"---|"*len(headers)] +
                     ["| "+" | ".join(str(x) for x in row)+" |" for row in rows])


def stats(rows):
    n = collections.Counter(r["status"] for r in rows)
    return [n[k] for k in ("correct", "abstained", "wrong", "locked")]


def border_audit(rows):
    """Pixel geometry only; scores below come from the device report, not this audit."""
    images = {}
    def image(r):
        path = r["reference"]
        if path not in images:
            images[path] = cv2.imread(str(ROOT/path))
            assert images[path] is not None, path
        return images[path]
    def crop(r):
        x,y,w,h = r["region720"]
        return cv2.cvtColor(image(r)[y:y+h,x:x+w], cv2.COLOR_BGR2GRAY)
    output = []
    for r in rows:
        if not r["selected"] or r["mode"] != "Append":
            continue
        a = image(r)
        x,y,w,h = r["region720"]
        center = x+5
        cy = 239 if r["layout"] == "native-wide" else 261
        b,g,red = cv2.split(a.astype(np.int16))
        cyan = (b > red+45) & (g > red+40) & (b > 145) & (g > 140)
        in_crop = int(cyan[y:y+h,x:x+w].sum())
        # Only the icon's outer ring: do not mistake blue icon artwork for selection UI.
        ring = cyan[cy-66:cy+66,center-68:center+68].copy()
        ring[12:-12,12:-12] = False
        ys,xs = np.where(ring)
        assert len(xs) > 0, r["name"]
        xs,ys = xs+center-68,ys+cy-66
        dx = np.maximum(np.maximum(x-xs,0),xs-(x+w-1))
        dy = np.maximum(np.maximum(y-ys,0),ys-(y+h-1))
        distance = int(np.minimum.reduce(np.maximum(dx,dy)))
        item = dict(name=r["name"],region720=r["region720"],cyanPixelsInCrop=in_crop,
                    cyanRowsInCrop=np.flatnonzero(cyan[y:y+h,x:x+w].any(axis=1)).tolist(),
                    nearestCyanRingDistance720=distance, score=r["score"],margin=r["margin"])
        controls = [c for c in rows if c["layout"] == r["layout"] == "native-wide" and
                    c["server"] == r["server"] and c["mode"] == "Append" and
                    c["slot"] == r["slot"] and c["expected"] == r["expected"] and not c["selected"]]
        if controls:
            control = min(controls,key=lambda c: np.abs(crop(c).astype(int)-crop(r).astype(int)).max())
            cb,cg,cr = cv2.split(image(control).astype(np.int16))
            cc = (cb > cr+45) & (cg > cr+40) & (cb > 145) & (cg > 140)
            item.update(control=control["name"], maxGrayDifference=int(np.abs(crop(control).astype(int)-crop(r).astype(int)).max()),
                        controlCyanPixelsInCrop=int(cc[y:y+h,x:x+w].sum()),
                        controlScore=control["score"],scoreDelta=r["score"]-control["score"],
                        controlMargin=control["margin"],marginDelta=r["margin"]-control["margin"])
        output.append(item)
    return output


def summarize(report_path, baseline_path, before_path):
    fixtures = json.loads((ROOT/'docs/reference-images/cases/skillLevelPanel.json').read_text(encoding="utf-8"))["cases"]
    cases = {c["name"]: c for c in fixtures}
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["passed"] and len(report["results"]) == len(fixtures)
    rows = []
    for result in report["results"]:
        assert result["passed"], result
        c,a = cases[result["name"]],result["actual"]
        expected = c["expected"]["level"]
        assert c["layout"] in LAYOUTS and "stress" not in c
        assert a["level"] is None or a["level"] == expected
        assert expected != 0 or a["locked"]
        assert c["mode"] != "Skill" or a["level"] == expected
        rows.append(dict(name=c["name"],server=c["server"],mode=c["mode"],screen=c["screen"],
                         layout=c["layout"],slot=c["slot"]+1,reference=c["file"],selected=c["selected"],
                         selectedSlot=c["selectedSlot"],expected=expected,**a))
    assert len(rows) == len({r["name"] for r in rows}) == 282
    baseline = {r["name"]:r["actual"] for r in json.loads(baseline_path.read_text(encoding="utf-8"))["results"]}
    wide = [r for r in rows if r["layout"] == "native-wide"]
    for r in wide:
        old = baseline[r["name"]]
        assert all(r[k] == old[k] for k in ("level","locked","candidate","status","reason"))
        assert all(abs(r[k]-old[k]) < 1e-12 for k in ("score","margin")),r["name"]
    before = {r["name"]:r["actual"] for r in json.loads(before_path.read_text(encoding="utf-8"))["results"]}
    borders = border_audit(rows)
    selected = [r for r in rows if r["selected"]]
    assert len(wide) == 261 and len(selected) == 19 and len(borders) == 5
    output = dict(device="127.0.0.1:5555",report=report_path.relative_to(ROOT).as_posix(),
                  baseline=baseline_path.relative_to(ROOT).as_posix(),unchangedWideCases=len(wide),
                  borderAudit=borders,cases=rows)
    (ROOT/'docs/qa/panel-level-reader-phase1-cases.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    def totals(source):
        return table(["레이아웃","모드","서버","정답","기권","오독","잠금 정답"],
                     [[layout,mode,server,*stats(rr)] for layout in LAYOUTS for mode in ("Skill","Append") for server in ("Kr","Jp")
                      if (rr:=[r for r in source if (r["layout"],r["mode"],r["server"]) == (layout,mode,server)])])
    slots = table(["레이아웃","서버","슬롯","정답 / 열린 수","기권","오독"],
                  [[layout,server,slot,f"{stats(rr)[0]}/{len(rr)}",*stats(rr)[1:3]]
                   for layout in LAYOUTS for server in ("Kr","Jp") for slot in range(1,6)
                   if (rr:=[r for r in rows if r["layout"]==layout and r["server"]==server and r["mode"]=="Append" and r["slot"]==slot and r["expected"]>0])])
    distributions = []
    for layout in LAYOUTS:
        for mode in ("Skill","Append"):
            for server in ("Kr","Jp"):
                rr = [r for r in rows if (r["layout"],r["mode"],r["server"]) == (layout,mode,server) and r["expected"]>0]
                for field in ("score","margin"):
                    distributions.append([layout,mode,server,field,*[f"{percentile([r[field] for r in rr],q):.4f}" for q in (0,.05,.5,.95,1)]])
    def value(r):
        return r["level"] if r["level"] is not None else "null"
    def case_table(source):
        return table(["케이스","기대","출력","후보","score","margin"],
                     [[f"`{r['name']}`",r["expected"],value(r),r["candidate"],f"{r['score']:.4f}",f"{r['margin']:.4f}"] for r in source])
    worst = sorted([r for r in rows if r["mode"] == "Append" and r["expected"]>0],key=lambda r:r["margin"])[:10]
    slot2 = sorted([r for r in rows if r["mode"] == "Append" and r["expected"]>0 and r["slot"] == 2],key=lambda r:r["margin"])[:10]
    changes = table(["16:9 어펜드","기대","수정 전 출력","전 score","수정 후 출력","후 score","후 margin"],
                    [[f"`{r['name']}`",r["expected"],value(before[r["name"]]),f"{before[r['name']]['score']:.4f}",value(r),f"{r['score']:.4f}",f"{r['margin']:.4f}"]
                     for r in rows if r["layout"] == "native-16:9" and r["mode"] == "Append" and r["expected"]>0])
    border_table = table(["선택 어펜드","크롭 안 청색 픽셀","해당 크롭 행(0부터)","외곽 테두리 거리(px)","score","margin"],
                         [[f"`{b['name']}`",b["cyanPixelsInCrop"],','.join(map(str,b['cyanRowsInCrop'])) or '없음',b["nearestCyanRingDistance720"],f"{b['score']:.4f}",f"{b['margin']:.4f}"] for b in borders])
    controls = table(["선택 / 비선택 대조","크롭 최대 명도차","score 차이","margin 차이"],
                     [[f"`{b['name']}` / `{b['control']}`",b["maxGrayDifference"],f"{b['scoreDelta']:+.6f}",f"{b['marginDelta']:+.6f}"] for b in borders if "control" in b])
    selected_details = table(["레이아웃","선택 케이스","기대","출력","score","margin"],
                             [[r["layout"],f"`{r['name']}`",r["expected"],value(r),f"{r['score']:.4f}",f"{r['margin']:.4f}"] for r in selected])
    unit = []
    for module,variant in (("app","testCiUnitTest"),("prefs","testCiUnitTest"),("scripts","test"),("libautomata","test")):
        roots = [ET.parse(p).getroot() for p in (ROOT/module/'build/test-results'/variant).glob('TEST-*.xml')]
        unit.append([module,sum(int(r.get("tests",0)) for r in roots),sum(int(r.get("failures",0))+int(r.get("errors",0)) for r in roots)])
    qa = []
    for suite in ("support","bond","questItems","noblePhantasm","fpSell","skillLevel","weeklyRoute","rankUp","skillLevelPanel"):
        d = json.loads((report_path.parent/f'report-{suite}.json').read_text(encoding="utf-8"))
        assert d["passed"] and all(r["passed"] for r in d["results"]),suite
        registered = json.loads((ROOT/f'docs/reference-images/cases/{suite}.json').read_text(encoding="utf-8"))["cases"]
        skipped = sum("skip" in c for c in registered)
        assert len(registered) == len(d["results"])+skipped,suite
        qa.append([suite,len(d["results"]),0,skipped])
    text = f'''# 패널 레벨 판독기 — Phase 1 측정

기준 main `139b2123`, 브랜치 `feat/panel-level-reader`, [PR #18](https://github.com/puruuun/FGA-Modified/pull/18). **강화 흐름에 연결하지 않았으며 version.properties도 변경하지 않았습니다.** AutoSkillLevel/SkillLevelFlow/SkillLevelDigits의 동작은 그대로입니다.

## 측정 정정 및 입력

이전 가로 압축 실험은 wide 1680×720을 1280×720으로 눌러 글자 폭까지 바꾸었으므로 실제 16:9/16:10 성능을 검증하지 못합니다. 해당 450개 합성 케이스, QA 좌표 어댑터, acceptance 수치와 개별 결과를 삭제했습니다. 아래 표에는 **네이티브 캡처만** 포함합니다. 실제 16:10 캡처는 없어 **미검증**입니다.

- 총 **62장 / 282슬롯**: 기존 wide 57장 / 261슬롯 + 새 16:9 5장 / 21슬롯입니다. 원본 levels 라벨은 바꾸지 않았고 눈으로 확인한 라벨 모순도 없습니다. 초기 56장 이후 추가된 JP 스킬 Lv.5도 유지했습니다.
- wide 2560×1080은 중앙 게임 영역 2520×1080(x=20)을 잘라 1680×720으로, 실제 16:9 1920×1080은 가로·세로 동일 배율로 1280×720으로 저장했습니다. 글자 비율을 바꾸지 않습니다. [source manifest](../reference-images/panel-0021-sources.json)에 원본·레이아웃·selectedSlot을 기록했습니다.
- 새 프레임: JP 스킬 7/6/5, JP 실제 어펜드 6/7/8/4/5(모두 강화 가능), JP 어펜드 10/10/잠금/10/10, KR 스킬 8/6/4, KR 실제 어펜드 잠금/잠금/잠금/9/잠금입니다.
- 새 16:9는 **검증 전용**입니다. KR/JP 각 40개 셀의 기존 템플릿 뱅크(132×210), 점수 0.70·마진 0.10 기준을 바꾸거나 새 입력으로 재학습하지 않았습니다. 준비 도구는 동결된 PNG와 바이트가 달라지면 중단합니다. [템플릿 출처 80개](../reference-images/panel-0021-template-sources.json)를 유지했습니다.

## 제품 좌표와 16:9 수정

좌표는 SkillLevelLocations.panelLevel에 있고 xFromCenter/기존 slotX를 사용합니다. 교환 화면과 실제 강화 화면의 아이콘 위치는 같아 교환 화면 전용 오프셋은 **0**입니다. 별도 화면 분기를 넣지 않았습니다.

- 스킬: 1440p 기준 아이콘 중심 x+130, y=584-slotRise, 290×68. 720p 크롭 y는 wide 270, 비와이드 292이며 변경하지 않았습니다.
- 어펜드: 1440p 기준 아이콘 중심 x−10, 66×42. wide y=546(720p **273**)은 유지했습니다. **비와이드 y만 590→588(720p 295→294), 1px 위로 수정**했습니다. KR·JP의 새 실제 16:9 숫자 윤곽이 기존 wide 셀과 세로로 1px 어긋나는 것을 확인했습니다. 아이콘 행의 wide 상승은 22px이나 숫자 행의 대응은 21px입니다. x, 폭, 높이, 잠금 영역은 그대로입니다.
- 수정 전 실제 기기에서 열린 16:9 어펜드 10개 모두 기권했습니다. 수정 후 JP 7개를 읽고 JP 2개·KR 1개는 여전히 보수적으로 기권합니다. 후보는 모두 기대 레벨이나 score<0.70이며, 임계값 완화·슬롯별 미세 이동으로 통과시키지 않았습니다. 잔여 기권은 모두 4번 아이콘이며 wide에서도 이 슬롯에 기권이 집중됩니다. 배경과 서브픽셀 정렬의 영향을 분리하지 못했으므로 원인을 배경 하나로 단정하지 않습니다.
- 기존 **wide 261개**의 출력·잠금·후보·상태·원인·score·margin은 이전 최종 기기 보고서 `{baseline_path.relative_to(ROOT).as_posix()}`와 모두 동일합니다(점수/마진 차이 <1e−12).

### 수정 전후 — 실제 16:9 제품 경로

{changes}

## 판독과 안전한 기권

스킬은 `Lv. N/10` 전체 행을 실제 Tesseract eng로 읽습니다. scale 2/3 × 이진화 0.6/원본 grayscale, padding 8의 네 프로필이며 whitelist는 사용하지 않습니다. Lv. 접두사(선택), 1–10, `/10`의 전체 문법을 검사합니다. 신뢰도 80 이상 판독이 최소 3개이고 모두 같은 유효 값일 때만 반환합니다. 신뢰도 0을 합의만으로 승격하지 않습니다.

어펜드는 기존 SkillLevelScreens.slotLocked/slotLock(0.85)을 먼저 확인합니다. 잠금은 level=null, locked=true이며 숫자를 읽지 않습니다. 열린 슬롯은 33×21 숫자 열의 Laplacian 윤곽 특징과 동결된 셀의 NCC를 계산합니다. 최고 점수 ≥0.70, 서로 다른 레벨의 1·2위 차이 ≥0.10일 때만 반환합니다. 2번 슬롯은 템플릿 출처에서 제외했고 대상과 같은 출처 슬롯의 셀도 항상 제외합니다. 누락 에셋·저대비·낮은 점수/마진·OCR 충돌은 증거와 함께 null을 반환합니다. 클릭이나 강화 호출은 없습니다.

## 실제 기기 acceptance

BlueStacks **127.0.0.1:5555**, 실제 ImageLoader/OpenCV/Tesseract 및 제품 좌표를 사용했습니다. 호스트 분류기 수치를 acceptance로 쓰지 않았습니다. strict QA는 오독·잠금 오인식·스킬 기권을 실패로 처리합니다. **282건 통과, 실패 0, skip 0**입니다. 아래 정답/기권/오독은 열린 슬롯만 세며 잠금 정답은 별도 열입니다.

{totals(rows)}

스킬은 두 레이아웃·양 서버 모두 100%입니다. wide 어펜드는 KR 58/70(82.9%), JP 75/82(91.5%); 실제 16:9는 KR 0/1, JP 7/9입니다. 16:9 KR 열린 표본은 선택된 4번 Lv.9 하나뿐이므로 서버 전체 읽기율로 일반화하지 않습니다. 잠금은 총 78/78 정확합니다.

### 어펜드 슬롯별

{slots}

2번 슬롯은 템플릿 출처에 포함하지 않았으며 wide KR 14/14·JP 18/18, 실제 16:9 JP 2/2를 읽었습니다. 실제 16:9 KR 2번은 잠금만 있어 열린 레벨 읽기는 미검증입니다.

### score / margin 분포

열린 슬롯의 정답·기권을 모두 포함하고 잠금은 제외합니다. 스킬 score는 유효 후보 그룹의 최소 OCR 신뢰도/100, margin은 차순위 후보 그룹과의 차이(차순위가 없으면 0 기준)입니다. 어펜드는 NCC와 서로 다른 레벨 간 차이입니다. 서로 다른 지표를 확률로 해석하지 않습니다. 모든 슬롯의 개별 점수·마진·원문·좌표·선택 여부는 [per-case JSON](panel-level-reader-phase1-cases.json)에 있습니다.

{table(["레이아웃","모드","서버","지표","min","p05","median","p95","max"],distributions)}

### 최저 마진 어펜드 사례

{case_table(worst)}

### 2번 슬롯 최저 마진 사례

{case_table(slot2)}

## 선택 테두리

wide 스킬 18–23·50–54·56의 선택 슬롯 12개와 실제 어펜드 24(2번)·55(1번), 새 16:9 프레임 5개의 selectedSlot을 별도로 집계했습니다. 기존 wide 선택은 [선택 위치 감사 기록](../reference-images/panel-0021-selection-audit.json), 새 16:9 선택은 제공된 labels.json을 따릅니다.

{totals(selected)}

{selected_details}

선택 스킬 **14/14**와 선택 wide 어펜드 **2/2**는 모두 정답입니다. 선택된 실제 16:9 어펜드는 JP Lv.6 정답, JP Lv.10·KR Lv.9 기권이며 오독은 없습니다.

### 어펜드 크롭의 테두리 유입과 판독 영향

실제 제품 QA가 보고한 region720으로 컬러 참조 이미지를 잘랐습니다. 청색 픽셀 조건은 B−R>45, G−R>40, B>145, G>140이며 거리 계산은 아이콘 바깥 링(중심 ±68×66 중 안쪽 제외)에 한정해 파란 아이콘 아트와 구분했습니다. 아래 거리는 크롭의 마지막 포함 픽셀부터 링까지의 Chebyshev 거리입니다. **wide JP 24의 2번 슬롯은 테두리의 청색 번짐이 크롭 마지막 3행(y=291–293)의 68픽셀에 들어옵니다.** 나머지 네 선택 어펜드 크롭은 청색 픽셀이 0이고 테두리까지 2px 떨어져 있습니다. 이 색상 조건은 관찰용 감사이며 제품 판독 조건이 아닙니다.

{border_table}

wide에는 같은 서버·슬롯·레벨의 비선택 대조가 있어 실제 기기 점수도 직접 비교했습니다.

{controls}

번짐이 들어온 JP 2번도 비선택 대조보다 score +0.018210, margin +0.005198이며 읽기 결과가 유지됐습니다. KR 선택 1번은 score +0.017092, margin −0.000021로 판정 기준에서 충분히 떨어져 있습니다. 따라서 제공된 wide 선택 표본에서 **점수 저하나 판독 실패를 유발하는 테두리 영향은 관찰하지 못했습니다.** 선택/비선택 캡처에는 강화 가능 오버레이 등 다른 차이도 있어 이 차이를 테두리만의 인과 효과라고 단정하지 않습니다. 크롭을 바꾸지 않았으며 기존 wide 전 케이스 결과가 동일합니다.

16:9는 같은 레벨·슬롯의 비선택 대조 캡처가 없어 테두리의 score 영향에 대한 A/B 단정은 하지 않습니다. 세 선택 크롭 모두 테두리 유입이 없으며, 기권한 두 선택 슬롯은 기존에도 어려웠던 4번 아이콘입니다. 테두리를 없앤 캡처 없이 잔여 차이의 원인을 테두리로 돌리지 않습니다.

## 전체 검증과 재현

전체 단위 테스트는 다음 명령으로 통과했습니다.

```text
gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
```

{table(["모듈","테스트","실패"],unit)}

전체 기기 QA는 수정 좌표를 포함한 QA 전용 APK로 실행했습니다. ANDROID_HOME을 설정하고 실행 중인 서버와 같은 ADB 바이너리, BlueStacks 127.0.0.1:5555만 사용했습니다. 최초 전체 실행은 support 통과 후 ADB 연결 중단으로 끝났으며 최종 통계에 합산하지 않았습니다. 서버 명령 없이 현재 실행 중인 서버 바이너리를 확인하고 전체를 재실행했습니다. 게임 조작은 하지 않았습니다.

{table(["suite","통과","실패","기존 명시 skip"],qa)}

총 **{sum(r[1] for r in qa)} 통과 / 0 실패 / {sum(r[3] for r in qa)} 기존 명시 skip**입니다. skip은 기존 support/questItems의 원본 범위 불일치 사례이며 새 panel 282개에는 skip이 없습니다. 최종 보고 디렉터리: `{report_path.parent.relative_to(ROOT).as_posix()}`. PR Main CI 결과는 [PR checks](https://github.com/puruuun/FGA-Modified/pull/18/checks)에서 확인합니다.

```text
python tools/device-qa/prepare_panel_levels.py
set ANDROID_HOME=<Android SDK>
gradlew -PfgaDeviceTests=true :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
python tools/device-qa/run.py --serial 127.0.0.1:5555 --suite support,bond,questItems,noblePhantasm,fpSell,skillLevel,weeklyRoute,rankUp,skillLevelPanel
python tools/device-qa/report_panel_levels.py --report build/device-qa/<run>/report-skillLevelPanel.json
```

재측정 보고서 생성에는 비교용 이전 최종 wide 보고서와 수정 전 16:9 보고서도 필요합니다. 경로는 `--baseline`, `--before`로 지정할 수 있습니다. 원본 captures는 입력 디렉터리에 두고, 저장소에는 720p 참조만 보관합니다.

## Phase 2 권고

현재 기권 기준을 유지하며 패널·다이얼로그의 값과 score/margin/reason을 함께 기록하는 제한된 결합부터 권합니다. 두 확정값이 다르면 즉시 중단하고 잠금은 해방하지 않아야 합니다. 실제 16:9는 이번에 검증했지만 열린 KR 표본과 4번 아이콘 배경, 실제 16:10 캡처를 보강해야 합니다. 특히 현재 16:9 선택 4번은 기권하므로 패널 단독 진행 범위를 넓히기에는 이릅니다. 템플릿은 wide 자료에서 뽑았으므로 동일 슬롯 출처를 제외했어도 완전한 독립 holdout은 아닙니다. 이번 변경에는 흐름 연결·버전 변경이 없습니다.
'''
    (ROOT/'docs/qa/panel-level-reader-phase1.md').write_text(text,encoding="utf-8")
    print(json.dumps(dict(cases=len(rows),native16=stats([r for r in rows if r["layout"]=="native-16:9"]),
                          selected=stats(selected),borders=borders,unit=unit,qa=qa),ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report",type=Path,required=True)
    parser.add_argument("--baseline",type=Path,default=ROOT/'build/device-qa/20261025-011230/report-skillLevelPanel.json')
    parser.add_argument("--before",type=Path,default=ROOT/'build/device-qa/20261025-014839/report-skillLevelPanel.json')
    args = parser.parse_args()
    summarize(args.report.resolve(),args.baseline.resolve(),args.before.resolve())
