# 내장 서포트 이미지와 faceX·faceY 검증

검증일: 2026-10-15. 원본 v3059와 동일하게 포함된 서번트 24종의 재림·영의 이미지 107장과 개념예장 28장을 전부 대조했습니다. 앱에 이미지 다운로드 기능을 추가한 작업은 아닙니다.

## 결론

**내장 이미지가 `faceX`·`faceY`로 생성되었다고 확인할 근거는 찾지 못했습니다.** 공개 뷰어에서 해당 좌표는 전신 그림에 표정 조각을 붙이는 데 사용합니다. 서포트 썸네일의 잘라내기 좌표로 해석하면 안 됩니다.

대신 서번트의 **`extraAssets.faces`**, 예장의 **`extraAssets.equipFace`**에서 내장 템플릿과 대응하는 전용 이미지 자산을 확인했습니다. 예장은 일반 `faces`보다 별도로 마련된 `equipFace`를 사용하는 것이 적합했습니다.

| 대상 | 내장 이미지 수 | 대응하는 공개 자산 | 이미지별 최적 비교 유사도 최저 / 중앙값 |
|---|---:|---|---|
| 서번트 | 107 | `Faces` 128×128 | 0.9533 / 0.9891 |
| 개념예장 | 28 | `EquipFaces` 150×68 | 0.9254 / 0.9862 |

이는 **파일 사이의 정규화 상관계수**입니다. 게임에서의 인식 성공률이나 모든 서번트에 대한 보장을 뜻하지 않습니다. 이 비교에서는 각 이미지의 배율과 위치를 개별적으로 탐색했습니다.

## faceX·faceY의 실제 역할

Atlas의 `charaScripts`는 원시 `mstSvtScript`의 `faceX`, `faceY`, `scale`, `offsetX`, `offsetY` 등을 제공합니다. [변환 코드](https://github.com/atlasacademy/fgo-game-data-api/blob/master/app/core/nice/svt/chara_script.py)

Atlas의 장면 렌더러와 Chaldea의 전신 이미지 합성 코드는 이 좌표에 표정 조각을 붙입니다. [Atlas 렌더러](https://github.com/atlasacademy/apps/blob/master/packages/db/src/Component/Scene.tsx#L108-L150), [Chaldea 렌더러](https://github.com/chaldea-center/chaldea/blob/main/lib/app/modules/tools/chara_figure/custom_chara_figure.dart#L629-L649)

반면 FGA의 서포트 이미지 메이커는 화면에서 일정한 영역을 자릅니다. 서번트는 현재 `(0, 0, 125, 44)`이고, 이 과정에서 `faceX`·`faceY`를 조회하지 않습니다. [FGA 코드](https://github.com/puruuun/FGA-Modified/blob/main/scripts/src/main/java/io/github/fate_grand_automata/scripts/entrypoints/SupportImageMaker.kt#L103-L116)

대조한 서번트 107장 중 대응하는 자산 ID와 정확히 같은 `charaScripts.id`의 좌표가 있는 경우는 81장이었습니다. 나머지 26장에는 그 직접 대응 행이 없었습니다. 이것만으로 게임의 상속 규칙까지 부정할 수는 없지만, 모든 이미지에 개별 `faceX`·`faceY`가 존재한다고 가정할 수는 없습니다.

좌표가 있는 81장에 대해, `Faces` 안에서 찾아낸 잘림 위치를 `faceX`·`faceY`의 공통 선형식으로 설명해 보았습니다. 세로 위치 오차는 상수 위치만 사용했을 때 평균 0.621픽셀, 두 좌표를 넣었을 때 0.616픽셀로 거의 줄지 않았고, 서번트 하나씩을 제외한 교차 검증에서는 0.652픽셀이었습니다. 이 단순 모델에서는 해당 좌표가 서포트 잘림 위치를 설명하는 데 유효하다는 근거가 없었습니다. 임의의 다른 변환까지 불가능하다는 증명은 아닙니다.

PNG만으로 제작자가 원래 어떤 절차를 사용했는지 단정할 수는 없습니다. 추가 이력에도 이 좌표로 자동 생성했다는 설명은 찾지 못했습니다. 내장 파일의 폭과 높이도 서로 달라, 동일한 픽셀 영역으로만 저장된 집합은 아닙니다.

## 예장에는 대응하는 좌표가 있는가

내장 예장 28종의 정리된 API 응답에는 `faceX`·`faceY`가 없었고, 예장별 `script`도 모두 비어 있었습니다. 원시 데이터 28종에서도 `mstSvtScript`, `mstSvtLimitImage`, `mstImagePartsGroup`은 모두 비어 있었습니다. 공개 API에서 예장별 위치 좌표를 찾지 못했다는 뜻이며, 공개 API 밖의 게임 UI 리소스에 어떤 값도 없다는 뜻은 아닙니다.

예장에는 이미 **`EquipFaces` 150×68** 이미지가 제공됩니다. 예를 들어 [Kaleidoscope 데이터](https://api.atlasacademy.io/nice/JP/equip/34)의 `extraAssets.equipFace`가 가리키는 [전용 이미지](https://static.atlasacademy.io/JP/EquipFaces/f_94003400.png)를 이용할 수 있습니다. 전체 카드 일러스트에서 예장별 얼굴 위치를 계산하는 방식보다, 이 준비된 자산을 사용하는 쪽이 내장 템플릿과 잘 대응했습니다.

## 일정한 규칙도 적용해 보았는가

이미지별 최적 탐색과 별도로, 종류마다 **한 가지 배율과 한 가지 작은 내부 영역**만 사용한 실험도 했습니다.

| 대상 | 공통 배율 | 원본 자산에서 지정한 내부 영역 `(x,y,w,h)` | 비교 결과 |
|---|---:|---|---|
| 서번트 | 1.235 | `(24,48,72,20)` | 107장 최저 0.8767, 중앙값 0.9761. 106장이 0.90 이상 |
| 예장 | 1.053 | `(16,24,100,10)` | 28장 최저 0.9458, 중앙값 0.9924. 28장 모두 0.90 이상 |

크기를 바꾼 전용 자산에서 이 공통 영역을 잘라, 기존 템플릿 안에서 대응 위치를 찾았습니다. 서번트는 실제 FGA와 같이 친구 잠금 표시가 생기는 왼쪽 15픽셀을 비교에서 제외했습니다.

이 작은 공통 영역은 데이터 점검 후 선택한 것이므로 독립적인 미관측 데이터 검증은 아닙니다. 또한 영역을 줄이면 다른 캐릭터와 혼동할 가능성도 따로 검증해야 합니다. **이번 실험은 전용 자산을 이용한 자동 생성 가능성을 뒷받침하지만, 이 숫자를 바로 앱에 넣어 모든 서포트에 배포할 수준의 검증은 아닙니다.**

다음 개발을 진행한다면 `Faces`와 `EquipFaces` 기반으로 템플릿을 만들고, KR·JP 실제 서포트 화면에서 같은 캐릭터 검출과 다른 캐릭터 오검출, 영의·특수 변화, 일반·그랜드 예장 표시를 함께 확인하는 것이 적절합니다. 이번 버전에서는 자동 다운로드 기능을 추가하지 않았습니다.

## 비교 자료

- [135장 전체의 개별 최적 비교 결과](research/support-template-correlations.csv)
- [135장 전체의 공통 내부 영역 비교 결과](research/support-template-fixed-patches.csv)
- 공개 데이터: [Atlas API](https://api.atlasacademy.io/docs), [스카디 예시](https://api.atlasacademy.io/nice/JP/servant/215), [예장 원시 데이터 예시](https://api.atlasacademy.io/raw/JP/equip/9400340)

다운로드한 공개 자산과 메타데이터는 앱에 추가하지 않았습니다. 비교 도구는 OpenCV의 회색조 `TM_CCOEFF_NORMED`를 사용했습니다. 게임의 실제 렌더링 코드나 사용자의 설치된 클라이언트 비공개 파일은 분석하지 않았습니다.
