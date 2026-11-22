# ImmortalEnchant 1.3.3

Paper 26.3 / Java 25 전용 불멸 장비 테스트 플러그인입니다.

## 명령어
- `/immortal give <sword|axe|pickaxe|crossbow|helmet|chestplate|leggings|boots>`: 테스트 장비 지급 (관리자)
- `/immortal perfect`: OP 전용 최종 장비 명령어 도움말 및 옵션 설명 표시
- `/immortal perfect help`: 위와 동일한 도움말 표시
- `/immortal perfect <type> <option=value> x4`: 옵션 구조/수치 검증이 적용된 OP 전용 최종 장비 지급
- `/immortal enchant`: 최초 옵션 생성 GUI (불멸 장비를 주 손에 들고 있어야 함)
- `/immortal anvil`: 강화 GUI (옵션 생성이 끝난 불멸 장비를 주 손에 들어야 함)
- `/immortal stats`: 현재 플레이어에게 적용된 주요 속성값 확인
- `/immortal reload`: 설정 다시 불러오기 (관리자)

## 1.3.3 변경 사항
- `/immortal perfect`만 입력해도 게임 내에서 장비별 옵션 ID, 설명, 허용 후보 및 수치 한도를 확인 가능
- 최초 옵션 생성과 동일하게 첫 두 줄은 장비별 고정 긍정, 세 번째는 필수 부정, 네 번째는 긍정/부정 중 선택하도록 검증
- 네 번째 옵션에서 중복 옵션을 지정할 수 있지만, 동일 ID의 합산 수치가 전체 강화 시도에서 가능한 이론상 최대치를 넘으면 지급 거부
- README 및 테스트 절차를 실제 코드 검증 방식과 일치하도록 정리

## 1.3.0 변경 사항
- 초과 방어력 1당 추가 피해 감소를 2%로 설정하고, 추가 피해 감소 상한을 80%로 제한
- `config.yml`에서 `armor-mitigation.excess-per-point` 및 `armor-mitigation.max-reduction` 값을 직접 조정 가능
- 인챈트 테이블 우클릭 시 미개방 불멸 장비에 한해 최초 인챈트 GUI 실행
- 모루 우클릭 시 옵션 생성이 끝난 불멸 장비에 한해 강화 GUI 실행
- 불멸 장비의 바닐라 인챈트 및 모루 합성 차단
- 곡괭이의 첫 번째 긍정 채굴 옵션을 전용 수치로 분리: 강함은 일반 돌 즉시 채굴을 목표로 설정
- 불멸 방어구 풀세트의 설계 합산 방어력 33 / 방어 강도 20을 `/immortal stats`에 별도 표시
- 바닐라 방어력 상한으로 30을 초과한 방어력이 잘리는 경우, 일반 근접·투사체·폭발 피해에 한해 초과분을 1당 2% 추가 피해 감소로 보정하며, 추가 감소율은 최대 80%로 제한
- 석궁 기본 투사체 피해 배율을 1.5배에서 1.0배로 변경하고 빠른 장전 II 및 옵션 피해 증감은 유지
- 기존 불멸 장비 속성 데이터를 버전 3으로 마이그레이션

## 빌드
GitHub Actions의 Java 25 워크플로에서 `gradle build`를 실행합니다. 빌드 산출물은 `build/libs/`에 생성됩니다.

## 주의
실제 Paper 26.3 서버에서 채굴 시간, 방어력, 피해량, GUI 악용 방지 테스트를 진행한 뒤 운영 서버에 적용하세요. 기존 JAR과 플레이어 데이터 백업 후 별도 테스트 서버에서 먼저 검증하는 것을 권장합니다.

### 초과 방어력 디버그
- `/immortal debug on`: 풀세트 착용자가 피해를 받을 때 계산 내역을 서버 콘솔에 출력합니다.
- `/immortal debug off`: 디버그 출력을 중지합니다.
- `/immortal debug status`: 현재 상태를 확인합니다.
- 출력에는 피해 유형, 원 피해, 장비/속성 방어력과 방어 강도, 초과 방어력, 추가 감소율, 바닐라 예상 최종 피해, 불멸 방어력 적용 예상 최종 피해가 포함됩니다.
- 테스트가 끝나면 반드시 `off`로 꺼주세요.


## 초과 방어력 추가 피해 감소 설정
`config.yml`의 `armor-mitigation.excess-per-point` 값으로 조정합니다. 기본값은 `excess-per-point: 0.02`(초과 방어력 1당 2%), `max-reduction: 0.80`(추가 피해 감소 최대 80%)입니다. 두 값은 `config.yml`에서 변경할 수 있으며, 변경 후 `/immortal reload` 또는 서버 재시작으로 적용합니다. 상한은 초과 방어력에 의한 추가 감소에만 적용되며 바닐라 방어력 감소와는 별개입니다.


## OP 전용 최종 장비 지급 (1.3.3)

OP 권한으로 `/immortal perfect` 또는 `/immortal perfect help`를 입력하면 게임 채팅창에 사용 가능한 장비 종류, 옵션 ID, 한글 설명, 옵션 순서, 수치 한도가 표시됩니다.

```text
/immortal perfect <type> <positive-1=value> <positive-2=value> <negative=value> <fourth=value>
```

장비 종류는 `sword`, `axe`, `pickaxe`, `crossbow`, `helmet`, `chestplate`, `leggings`, `boots`입니다.

옵션 구성은 최초 인챈트 규칙과 동일합니다.
- 1번째: 장비별 고정 긍정 옵션 1
- 2번째: 장비별 고정 긍정 옵션 2
- 3번째: 반드시 부정 옵션 1
- 4번째: 긍정/부정 중 하나 (일반 추첨에서는 각각 50%, OP 명령어에서는 직접 선택)

고정 긍정 옵션:
- sword: `attack-damage`, `attack-speed`
- axe: `attack-damage`, `mining-speed`
- pickaxe: `mining-speed`, `attack-speed`
- crossbow: `projectile-damage`, `experience-gain`
- armor: `max-health`, `armor`

방어구의 네 번째 긍정 후보에는 `armor-toughness`도 포함됩니다. 부정 후보는 장비별 활성화된 부정 옵션 목록을 따릅니다. 발광 저주는 해당 장비를 주 손에 들고 있을 때 발동합니다.

**중복 옵션 수치 제한:** 네 번째 옵션은 앞선 옵션과 같은 ID를 선택할 수 있습니다. 이때 각 줄을 따로 최대치까지 허용하지 않습니다. 동일 ID의 값을 합산하고, 그 옵션이 여러 줄에 기본으로 생성된 상태에서 남은 전체 강화 시도 횟수가 한 옵션에 집중되었을 때의 최대 합계까지만 허용합니다. 공격력은 다섯 번째 성공 보너스도 포함합니다. 발광 저주는 설정된 지속시간을 사용합니다.

현재 기본 설정에서 단일 옵션 줄의 최대 예시는 공격력 +7, 공격속도 +0.22, 도끼 채굴 속도 +4.5, 곡괭이 채굴 효율 +81.5, 투사체 피해 +0.45, 경험치 획득 +2.25, 최대 체력/방어력/방어 강도 +8입니다. 부정 옵션은 공격력 -5.5, 공격속도 -0.25, 최대 체력 -8, 투사체 피해 -0.45까지 허용됩니다. 중복 옵션의 합산 최대치는 게임 내 도움말 및 실제 입력 검증이 적용합니다.

예시:
```text
/immortal perfect sword attack-damage=7 attack-speed=0.22 negative-max-health=-8 attack-damage=4
/immortal perfect helmet max-health=8 armor=8 negative-max-health=-8 armor-toughness=8
```

지급 장비는 옵션 생성 완료 상태이며 강화 단계가 최대치로 설정되어 추가 강화할 수 없습니다. 일반 랜덤 인챈트 및 강화 흐름에는 영향을 주지 않습니다.

