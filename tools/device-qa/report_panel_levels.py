"""Summarize a real skillLevelPanel device report (not a host classifier result)."""
from pathlib import Path
import argparse
import collections
import hashlib
import json
import math
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def percentile(values, q):
    values = sorted(values)
    point = (len(values)-1)*q
    lo = math.floor(point)
    hi = math.ceil(point)
    return values[lo]*(hi-point)+values[hi]*(point-lo) if lo != hi else values[lo]


def summarize(report_path: Path):
    fixtures = json.loads((ROOT/'docs/reference-images/cases/skillLevelPanel.json').read_text(encoding="utf-8"))["cases"]
    cases = {c["name"]: c for c in fixtures}
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["passed"] and len(report["results"]) == len(fixtures)
    rows = []
    for result in report["results"]:
        assert result["passed"], result
        c = cases[result["name"]]
        a = result["actual"]
        expected = c["expected"]["level"]
        assert a["level"] is None or a["level"] == expected
        assert expected != 0 or a["locked"]
        assert c["mode"] != "Skill" or a["level"] == expected
        rows.append(dict(name=c["name"], server=c["server"], mode=c["mode"], screen=c["screen"],
                         layout=c.get("stress", "native-wide"), slot=c["slot"]+1, reference=c["file"],
                         expected=expected, **a))
    assert len(rows) == len({r["name"] for r in rows})
    output = ROOT/'docs/qa/panel-level-reader-phase1-cases.json'
    output.write_text(json.dumps(dict(device="127.0.0.1:5555", report=report_path.relative_to(ROOT).as_posix(),
                                     cases=rows), ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    counts = collections.defaultdict(collections.Counter)
    for r in rows:
        counts[(r["layout"], r["mode"], r["server"])][r["status"]] += 1
    totals = ["| 레이아웃 | 모드 | 서버 | 정답 / 열린 수 | 기권 | 오독 | 잠금 정답 |", "|---|---|---|---:|---:|---:|---:|"]
    for key, n in sorted(counts.items()):
        opened = n["correct"]+n["abstained"]+n["wrong"]
        totals.append(f"| {' | '.join(key)} | {n['correct']}/{opened} | {n['abstained']} | {n['wrong']} | {n['locked']} |")
    slots = ["| 레이아웃 | 서버 | 슬롯 | 읽음 / 열린 수 | 기권 | 오독 |", "|---|---|---:|---:|---:|---:|"]
    for layout in ("native-wide", "16:9", "16:10"):
        for server in ("Kr", "Jp"):
            for slot in range(1, 6):
                rr=[r for r in rows if r["mode"]=="Append" and r["expected"]>0 and r["layout"]==layout and r["server"]==server and r["slot"]==slot]
                n=collections.Counter(r["status"] for r in rr)
                slots.append(f"| {layout} | {server} | {slot} | {n['correct']}/{len(rr)} | {n['abstained']} | {n['wrong']} |")
    distributions=["| 레이아웃 / 모드 / 서버 | 지표 | min | p05 | median | p95 | max |", "|---|---|---:|---:|---:|---:|---:|"]
    for key in sorted(counts):
        rr=[r for r in rows if (r["layout"],r["mode"],r["server"])==key and r["expected"]>0]
        for field in ("score", "margin"):
            values=[r[field] for r in rr]
            qs=[percentile(values,q) for q in (0,.05,.5,.95,1)]
            distributions.append(f"| {' / '.join(key)} | {field} | "+" | ".join(f"{x:.4f}" for x in qs)+" |")
    def worst_table(candidates):
        lines=["| 사례 | 기대 | 출력 | 후보 | score | margin |", "|---|---:|---:|---:|---:|---:|"]
        for r in sorted(candidates,key=lambda r:r["margin"])[:10]:
            lines.append(f"| `{r['name']}` | {r['expected']} | {r['level'] if r['level'] is not None else 'null'} | {r['candidate']} | {r['score']:.4f} | {r['margin']:.4f} |")
        return "\n".join(lines)
    native_worst=worst_table([r for r in rows if r["mode"]=="Append" and r["expected"]>0 and r["layout"]=="native-wide"])
    slot2_worst=worst_table([r for r in rows if r["mode"]=="Append" and r["expected"]>0 and r["slot"]==2])
    real=[r for r in rows if r["screen"]=="append-real" and r["layout"]=="native-wide"]
    real_lines=["| 실제 강화 화면 | 슬롯 | 기대 | 출력 | 잠금 |", "|---|---:|---:|---:|---|"]
    for r in real:real_lines.append(f"| {r['server']} | {r['slot']} | {r['expected']} | {r['level']} | {r['locked']} |")
    unit=[]
    for module,variant in (("app","testCiUnitTest"),("prefs","testCiUnitTest"),("scripts","test"),("libautomata","test")):
        roots=[ET.parse(p).getroot() for p in (ROOT/module/'build/test-results'/variant).glob('TEST-*.xml')]
        unit.append((module,sum(int(r.get("tests",0)) for r in roots),sum(int(r.get("failures",0))+int(r.get("errors",0)) for r in roots)))
    text="""# 패널 레벨 판독기 — Phase 1 측정

기준 main `139b2123`, 브랜치 `feat/panel-level-reader`. **강화 흐름에는 연결하지 않았고 version.properties도 변경하지 않았습니다.** AutoSkillLevel, SkillLevelFlow, SkillLevelDigits의 동작은 그대로입니다.

## 자료와 좌표

초기 56장 측정 중 입력에 `jp/Screenshot_2026.10.25_00.53.47.870.png`(스킬 5/4/4)가 추가되었습니다. 최종은 **57장**, JP 스킬 Lv.5도 포함합니다. 기획자 라벨을 수정하지 않았으며 눈으로 본 글자 및 교차 슬롯 판독에서 라벨 모순은 발견하지 못했습니다.

- 네이티브 2560×1080에서 제품의 21:9 중앙 게임 영역 2520×1080(x=20)을 잘라 1680×720으로 저장했습니다. 57개 원본 매핑은 [source manifest](../reference-images/panel-0021-sources.json)에 있습니다.
- 네이티브 슬롯 261개: 스킬 36개(KR 15, JP 21), 어펜드 열린 슬롯 152개(KR 70, JP 82), 잠금 73개(KR 60, JP 13). 어펜드 각 슬롯의 레벨 1–10과 양 서버 스킬 1–10을 포함합니다.
- 실제 강화 KR/JP 2장으로 보정했습니다. 교환 화면과 실제 강화 화면의 아이콘 행·레벨 열은 동일합니다. **교환 화면 오프셋은 0**이며 제품 코드에 교환 화면 분기를 추가하지 않았습니다. wide 아이콘 중심 x=673/850/1028/1205/1383, 레벨 크롭 y=273입니다(720p).
- 새 SkillLevelLocations.panelLevel은 기존 slotX/slotRise를 사용합니다. 1440p 기준 Skill: 아이콘 중심 x+130, y=584-slotRise, 290×68; Append: x-10, y=590-slotRise, 66×42. 따라서 16:9 게임 영역에서는 각각 y=292/295, wide에서는 y=270/273(720p)입니다. 16:10은 제품의 레터박스 제거 후 동일 16:9 게임 좌표를 씁니다.

## 판독과 보수적 기권

- 스킬: 전체 `Lv. N/10` 행을 Tesseract eng로 읽습니다. scale 2/3 × 이진화 0.6/원본 grayscale, 패딩 8의 네 프로필이며 whitelist를 쓰지 않습니다. 문법은 Lv. 접두사(선택), 1–10, `/10` 전체 일치를 요구합니다. 신뢰도 80 이상의 판독이 최소 3개이고 그 판독들이 전부 같은 유효 값일 때만 인정합니다. 신뢰도 0을 합의만으로 승격하지 않습니다.
- 초기 whitelist 실험은 문자열이 맞아도 JP 또는 Lv. 포함 행의 신뢰도가 0이었습니다. 제한 없는 OCR은 정상 신뢰도를 보였고, 최종 **실제 제품 판독기 경로**로 다시 측정했습니다.
- 어펜드: 기존 SkillLevelScreens.slotLocked/slotLock과 유사도 0.85를 먼저 사용합니다. 잠금이면 level=null, locked=true이며 숫자 매칭/OCR을 하지 않습니다.
- 열린 어펜드는 33×21 현재 숫자 열을 Laplacian으로 고주파/윤곽 특징화한 뒤 정규화 NCC를 계산합니다. **최고 점수 ≥0.70, 서로 다른 레벨의 1·2위 차이 ≥0.10**일 때만 반환합니다. 별도의 Tesseract 추정값으로 빈 결과를 채우지 않습니다.
- KR/JP별 40개 원 크기의 셀을 132×210 sprite에 보관합니다(레벨 1–10 × 출처 슬롯 1/3/4/5). **2번 슬롯은 학습 출처에서 제외**했습니다. 판독 대상과 같은 출처 슬롯의 템플릿도 항상 제외하므로 자신의 보정 크롭과 그대로 일치시켜 통과하지 않습니다. 80개 출처와 위치는 [template manifest](../reference-images/panel-0021-template-sources.json)입니다. 템플릿은 native grayscale→720p 스케일→크롭 경로이며 컬러 reference를 나중에 grayscale로 바꾼 값과 반올림 1단계 차이가 있을 수 있습니다.
- 누락 에셋, 지원하지 않는 서버, 빈/저대비 입력, 낮은 점수/마진, OCR 충돌은 null과 원인 증거를 반환합니다. 클릭이나 강화/해방 호출은 없습니다.

## 기기 측정 결과

BlueStacks **127.0.0.1:5555**, 실제 ImageLoader/OpenCV/Tesseract 경로입니다. 아래 수치는 호스트 NN 수치를 대신 사용한 것이 아닙니다. 최종 strict QA는 오독, 잠금 오인식, 스킬 기권을 실패로 처리합니다. **711건 전부 통과, 실패 0, skip 0**이며 잠금은 별도 열로 분리했습니다.

"""+"\n".join(totals)+"""

네이티브 어펜드 읽기율은 KR **58/70=82.9%**, JP **75/82=91.5%**입니다. 스킬은 양쪽 모두 **100%**입니다. 잠금은 각 레이아웃에서 KR 60/60, JP 13/13을 정확히 분류했습니다.

### 어펜드 슬롯별

"""+"\n".join(slots)+"""

2번 슬롯은 네이티브 **KR 14/14, JP 18/18**을 모두 읽었습니다. 가로 압축 후에는 KR 4/14, JP 15/18로 줄었지만 잘못된 값은 반환하지 않았습니다. 네이티브 기권은 4번 슬롯에 집중됩니다.

### score / margin 분포

잠금은 숫자 점수 분포에서 제외하고, 열린 슬롯의 정답·기권은 모두 포함했습니다. 스킬 score는 유효 후보 그룹의 최소 OCR 신뢰도/100이며 margin은 2위 그룹과의 차이(없으면 0 기준)입니다. 어펜드는 NCC와 서로 다른 레벨 간 차이입니다. 서로 다른 지표이며 확률로 해석하지 않습니다. 모든 사례의 원문/증거는 [per-case JSON](panel-level-reader-phase1-cases.json)에 있습니다.

"""+"\n".join(distributions)+"""

### 네이티브 최저 마진 사례

"""+native_worst+"""

### 2번 슬롯 최저 마진 사례(스트레스 포함)

"""+slot2_worst+"""

### 실제 강화 화면 보정 결과

"""+"\n".join(real_lines)+"""

## 재표본화 내성의 범위

16:9 스트레스는 1680×720 **전체를** OpenCV로 1280×720 가로 압축합니다. 16:10은 그 결과에 상하 40px 검은 막대를 붙여 1280×800을 만들고 FgoGameAreaManager의 (0,40,1280,720) 게임 영역과 일치하는지 확인합니다. QA 전용 아핀 어댑터가 16:9 논리 ROI를 해당 이미지로 옮기며, 잠금 템플릿에는 동일한 렌더 폭 축척을 적용합니다. 숫자 관측은 열화된 픽셀 그대로 읽어 33×21로 정규화합니다. 이 오프셋·렌더 코드는 QA에만 있습니다.

두 입력 모두 **오독 0**입니다. 읽기율은 KR 20/70=28.6%, JP 62/82=75.6%로 떨어집니다. 두 레이아웃의 숫자 픽셀이 같으므로 결과도 같습니다. 실제 FGO 16:9 UI 재배치가 아니라 **재표본화 스트레스**이며, 별도 네이티브 16:9/16:10 기기 검증을 했다고 주장하지 않습니다.

## 재현·제약과 Phase 2 권고

```text
python tools/device-qa/prepare_panel_levels.py
set ANDROID_HOME=<Android SDK>
gradlew -PfgaDeviceTests=true :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
python tools/device-qa/run.py --serial 127.0.0.1:5555 --suite skillLevelPanel
python tools/device-qa/report_panel_levels.py --report build/device-qa/<run>/report-skillLevelPanel.json
gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
```

첫 측정의 ADB 연결/설치 실패 결과는 최종 수치에 포함하지 않았습니다. 이후 동일한 ADB 실행 파일과 완성된 QA APK를 확인하고 재실행했습니다. 최종 보고 경로: `"""+report_path.relative_to(ROOT).as_posix()+"""`. 전체 단위 테스트 결과는 다음과 같습니다.

"""+"\n".join(f"- {m}: {n} tests, {f} failures" for m,n,f in unit)+"""

실험 자료에서의 0 wrong은 미지 화면에 대한 보장이 아닙니다. 템플릿은 이 자료에서 잘랐고 같은 슬롯 출처를 제외했지만, 독립 기기·새 서번트의 완전한 holdout 검증은 아닙니다. 여러 OCR 프로필도 독립 샘플로 간주할 수 없습니다. 원래 기획자 라벨은 그대로이며 추가 JP Lv.5를 포함했습니다.

**Phase 2 권고:** 현재 문턱과 null을 유지한 채 패널·다이얼로그 결과와 score/margin/reason을 함께 기록하는 제한적 결합부터 권합니다. 둘 다 확정됐는데 다르면 즉시 중단하고, 잠금은 해방하지 않습니다. 패널 단독 허용 범위를 넓히기 전에 4번 슬롯과 네이티브 16:9/16:10 독립 캡처를 보충해야 합니다. 이번 변경에서는 AutoSkillLevel/SkillLevelFlow에 연결하지 않았습니다. PR Main CI 결과는 PR checks 및 최종 전달 보고에서 확인합니다.
"""
    (ROOT/'docs/qa/panel-level-reader-phase1.md').write_text(text, encoding="utf-8")
    print(json.dumps({"cases":len(rows),"counts":{str(k):dict(v) for k,v in counts.items()},"unit":unit}))


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    args=parser.parse_args()
    summarize(args.report.resolve())
