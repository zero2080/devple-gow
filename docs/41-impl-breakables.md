# 41. 구현: 부서지는 물체 (Breakable / Brkb)

## 1. 데이터 [확실]
- GO 비헤이비어 리소스(0x17) 클래스 `"Breakable"` (`GoBehavior`, 0x24 바이트) → 템플릿 번호 = `/TweakTemplates/Brkb/<n>/`.
  - ARENA06 항아리 `gopotlgdirtyabrk*` 36 개: Brkb 000 "No Orbs", `Hit Points` ≈ 1 (덤프 0.9999991 — 하위 비트 잡음, 타수로 반올림), `Fade Time` 3.
  - 그 밖: R_PERMK Brkb 500 "MAI StoneStatue"(석화 석상, 35 문서), ATHN01B/02A/02C/03A Brkb 910 "HydraBreakSmall" 등 레벨별 템플릿.
- 각 물체 GO 에는 작은 `ESC_<오브젝트>` 가 붙어 **Marker(Id 101)** 를 만든다. 스크립트는 이 오브젝트들을 그룹 101 로 센다:
  ARENA06 `TimerControl-DestructionSensor1` (mode 3, Filter 101) → LevelData0 += 1 → 36 이면 `SuccessTransmitter` → 메시지 2008 → 1040 → Warp.
  ATHN03A 철문 `SwingMetalGate*-DestructionSensor` 도 Filter 101.
- 파편: 오브젝트마다 `<이름>_0/_1` 메시·`ANM_<이름>` (부서지는 애니) — 미사용.

## 2. 엔진 (`game/Breakables`)
- 레벨 적재 시 Breakable GO 마다 중심 = 인스턴스 경계 중심, 반지름 = `CDV_<오브젝트>` 공(없으면 24), 타수 = Brkb Hit Points.
- 매 프레임 `Combatant.strikes(중심, 반지름)`: 진행 중 공격(피해 > 0 창)의 부위 히트박스 구(34 문서) 와 겹치면 1 타 (같은 기술·공격 번호는 한 번).
- 타수 0 → 렌더에서 숨김(`LevelScene.hideInstance`) + 그 GO 의 Marker 오브젝트 파괴 → DestructionSensor 발동.
- [근사/미구현] 파편 애니·Fade Time, 오브 방출(OrbEmitter "large obj break"), 부서지는 소리·이펙트, 마법·발차기 판정.
- 검증 `BreakablesTest`: ARENA06 36 개 로드, 영웅 □ 로 항아리 파괴 → Marker 101 → LevelData0 ≥ 1.
- 하네스(36 문서 §7): 걷기 뒤 Breakable 을 모두 파괴(플레이어 대신) → ARENA06 도 강제 없이 ARENA09 로.
