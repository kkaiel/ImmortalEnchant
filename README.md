# ImmortalEnchant 1.1.0

Paper 26.3 / Java 25 전용 불멸 장비 테스트 플러그인입니다.

## 명령어
- `/immortal give <sword|axe|pickaxe|crossbow|helmet|chestplate|leggings|boots>`: 테스트 장비 지급 (관리자)
- `/immortal enchant`: 최초 옵션 생성 GUI
- `/immortal anvil`: 강화 GUI
- `/immortal stats`: 현재 플레이어에게 적용된 주요 속성값 확인
- `/immortal reload`: 설정 다시 불러오기 (관리자)

## 1.1.0 변경 사항
- 무기 공격력·공격 속도 속성값을 플레이어 기본 속성 기준으로 다시 계산
- 곡괭이의 기본 채굴 효율 및 채굴 효율 옵션을 `MINING_EFFICIENCY` 속성으로 적용
- 석궁에 Quick Charge II 부여
- 커스텀 석궁에서 발사한 발사체에 기본 1.5배 피해 배율 및 옵션 배율 기록
- 방어구의 방어력·방어 강도·넉백 저항을 슬롯별 속성으로 재구성
- 공격력·공격 속도·채굴 효율·체력·방어 옵션을 실제 AttributeModifier로 적용
- 경험치 획득 옵션을 자연 경험치 획득 이벤트에서 처리
- 기존 장비의 속성 데이터 버전 마이그레이션
- 강화 GUI 대상 아이템 고정, 옵션 데이터 검증, 설정의 활성화/확률/강화 횟수/재료값 반영
- `/immortal stats` 테스트 명령어 추가

## 빌드
GitHub Actions의 Java 25 워크플로에서 `gradle build`를 실행합니다. 빌드 산출물은 `build/libs/`에 생성됩니다.

## 주의
이 버전은 소스 수정본입니다. 실제 Paper 26.3 서버에서 피해량, 공격 속도, 채굴 시간, 방어력, 재접속 및 GUI 악용 테스트를 통과하기 전까지 운영 서버에 바로 적용하지 마세요. 기존 JAR과 월드/플레이어 데이터를 백업한 뒤 별도 테스트 서버에서 검증해야 합니다.
