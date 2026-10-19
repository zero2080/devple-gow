# 24. 구현: 이벤트 / 레벨 스크립트 VM (엔진)

명세: [07-ui-script-text](07-ui-script-text.md) §5 (ESC 포맷·opcode), [11-progression-save-flow](11-progression-save-flow.md) §6.2 (GameMgr 메시지).
패키지 `devple.gow.engine.script`.

## 1. 구성
| 클래스 | 역할 |
|-------|------|
| `EscVm` | 스택 머신 인터프리터. 핸들러 하나를 exit 까지 실행, `return` 값 반환. 무한 루프 방지 10만 스텝 |
| `EscHost` | VM → 엔진 인터페이스 (함수 호출, 변수 get/set). **인자 수는 호스트가 함수별로 pop** |
| `EscContext` | 실행 중 엔티티 |
| `GameScriptHost` | Internal 스코프 = GameMgr 메시지 → `GameFlow` / `PlayerState` / 메시지 표시 리스너. GlobalData·LevelData 변수 저장 |

`EscScript`(tools) 에 VM 용으로 `Entity.bytecode`, `Handler.start` 를 추가했다.

## 2. opcode 의미 결정 사항
| op | 구현 | 근거/가정 |
|----|------|----------|
| 0F jump_if | pop 이 참이면 점프. 오프셋은 **다음 명령 기준** | 07 문서 |
| 0A–0D call | 호스트 반환값 push (null 이면 반환형 기본값) | 스택 pop 명령이 없으므로 호출 결과는 항상 push 되고 핸들러 종료 시 버려진다고 판단 |
| 28–33 | lt/le/gt/ge × (int, float, string) | 07 문서 표의 3개 묶음 |
| 34–37 | eq × (int, float, bool, string) | |
| 38 return | 결과 레지스터 ← pop, 실행 계속 | 상수 파라미터가 `push; return; exit` 형태 |
| ≥3A | exit | |

## 3. Internal 함수 인자 표 (호스트가 pop)
| N | 이름 | 인자 (push 순서) |
|---|------|------------------|
| 0 | CheckPoint | – |
| 1 | GetPlayTime | – → float |
| 2 | Load | wad, entry |
| 3 / 4 | LoadWad / LoadForWarp | wad |
| 5 | Warp | – |
| 6 | LoadCheck | wad → bool |
| 7 | Goto | 엔티티 이름 |
| 0xA | ShowMessage | msgId:int, sec:float |
| 0xC / 0x10 / 0x2F / 0x3A | Idle / ZoneReport / CompleteGame / SetEndGame | 1개 |
| 0x1F | ShowItemCard | msg, sec, kind |

→ 전 함수 인자 수 표와 검증은 [13 문서](13-script-entities.md) §5·§6, 구현은 `ScriptHost` ([28 문서](28-impl-script-world.md)).
`GameScriptHost` 단독 사용 시 미확인 함수는 스택을 건드리지 않는다. 핸들러가 끝나면 스택은 버려지므로 같은 핸들러 안의 이후 연산에만 영향이 있다 → 함수별 인자 수는 사용 패턴을 보고 계속 보강.

## 4. 검증 (`EscVmTest`)
- ARENA06 `Start` 엔티티의 파라미터 핸들러 → `"Hero"`
- ATHN01A 튜토리얼 → `ShowMessage(269, 5.0)` ("[SquareButton] 보통 공격. [TriangleButton] 강한 공격.")
- **전 레벨 WAD 의 모든 핸들러**가 예외 없이 실행 완료

## 5. 남은 일
- (완료 → 13 / 28 문서) 엔티티 타입별 발동 규칙 (EntrySensor = 볼륨 진입 시 핸들러 N, Creation/Destruction/Event 센서, EventTransmitter → 대상 엔티티) 은 레벨 런타임(23 문서)에서 연결
- Entity 스코프 함수(0x9 등) 의미 분석
