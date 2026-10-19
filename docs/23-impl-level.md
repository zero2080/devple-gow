# 23. 구현: 레벨 씬 / 충돌 질의

포맷은 [12 문서](12-level-format.md). 패키지 `devple.gow.engine.level`.

```bash
./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD"              # 자유 카메라
./gradlew :engine:run --args="--level gow-rom/ARENA06.WAD --cam 0 --col" # 레벨 카메라 0 + 충돌/볼륨 와이어
./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD --cam auto"    # 시작 위치의 CMV 볼륨이 고른 카메라
```

## 1. LevelScene
- `enter`: `WadAssets` → `LevelData` (배치/충돌/볼륨/카메라) → 배치마다 `PlacedMesh.build` 로 월드 좌표 메시를 만들고
  `WadAssets.build(name, TriMesh)` 로 GPU 업로드 (정적이므로 CPU 변환 1회, 그릴 때 행렬 = 단위).
- 하늘 돔(이름/텍스처에 `sky` + 원점 중심 반지름 > 500): 카메라 위치로 옮겨 깊이 쓰기 없이 먼저 그린다.
- 조작: WASD 이동, 방향키 시선, Q/E(L1/R1) 하강/상승, Shift(L3) 빠르게, **1** 충돌 시트 와이어(초록 바닥/빨강 벽), **2** 볼륨 와이어(CMV 파랑, ENV 노랑, SEV 보라, CDV 주황), **Tab** 카메라 순환(자유 → 레벨 카메라 0..n-1), **3** CMV 자동 카메라.
- `--cam auto`: 자유 카메라 위치가 CMV 안이면 그 카메라, 아니면 시작 오브젝트 위치 기준, 그것도 없으면 첫 카메라.
- 공개 API: `data()` (`LevelData`), `collision()` (`LevelCollision`), `instances()`.

## 2. 캐릭터 컨트롤러용 충돌 API — `LevelCollision`
```java
LevelCollision col = new LevelCollision(levelData.sheets);
float y = col.groundHeight(x, footY + stepUp, z, maxDrop);   // 발밑 바닥 높이, NaN = 바닥 없음(낙하)
LevelCollision.Hit h = col.raycast(origin, dir, maxDist);    // 첫 면 (distance, point, tri{a,b,c,n,surface}), null = 없음
boolean pushed = col.collideSphere(center, radius);          // 벽(|n.y| < 0.7) 밀어내기, center 수정
// 캡슐: 발/허리/머리 높이의 구 2~3개로 collideSphere 를 부르고, 바닥은 groundHeight 로 붙인다
LevelData.PlacedCamera cam = levelData.activeCamera(x, y, z); // CMV 볼륨 → 카메라 (eye(), forward(), world())
boolean inZone = volume.contains(x, y, z);                    // ENV 트리거 (사망 존, 체크포인트 …)
```
- 색인: XZ 32 단위 균일 격자 (원작은 시트 kd 트리, 결과는 같다).
- 바닥/벽 구분: 법선 Y ≥ 0.7 (`FLOOR_NY`). 표면 속성 비트(Water/Climbable/Ladder/Death)는 아직 쓰지 않는다 (12 문서 §5).
- 동적 충돌(CDV: 항아리 ball, 함정문 상자)은 `LevelData.volumes` 에 kind 0 으로 있고 오브젝트 관절 월드에 붙어 있다 — 움직이는 물체와의 판정은 GO 시스템 몫.

## 3. 변경한 기존 코드 (추가만)
| 파일 | 변경 |
|------|------|
| `GowMain` | `--level <WAD> [--cam N|auto] [--col]` |
| `WadAssets` | `gowModel(name)`, `materialTexture(mat)`, `build(name, TriMesh)` (기존 `model()` 은 그대로) |
| `VifUnpack` / `VifParser` / `VertexBatch` | STMOD 1 로 더해진 STROW 를 기록 (`VertexBatch.posRow`) — 레벨 배치 시 빼기 위함. 기존 정점 값은 불변 |

## 4. 검증
- 테스트 `LevelCollisionTest` (4): ATHN01A 시작 위치 아래 바닥(오차 < 25), 아래 방향 레이 = groundHeight, 벽 위 구 밀어내기, CMV hull 중심 → 카메라.
- 스크린샷 (`work/level/`, 커밋 제외):
  - `arena06_cam0.png` / `arena06_col.png`: 레벨 카메라로 본 아레나, 하늘, 항아리. 충돌 바닥 부채꼴·난간 벽·항아리 ball 이 메시와 일치.
  - `athn01a_auto.png` / `athn01a_free_col.png`: 비 오는 배 갑판, 돛대, 바위. 충돌 시트가 갑판에 정확히 겹침.
  - `pand01a_cam0.png`: 판도라 신전 안뜰 (레벨 카메라), `pand01a_free.png` 전체 조감.
  - `hdes01a_free.png` / `hdes01a_cam0.png`: 하데스 용암 지대, 기둥 숲, 붉은 하늘.
- 환경/이펙트(안개, 하늘 규칙, 비, 물, 파티클, 머티리얼 블렌드/애니메이션)는 31 문서. 이전 기록의 남은 문제 중 바다·안개·파티클·PAND 어두운 방은 31 문서에서 다룸.
- 남은 문제(당시): 바다 표면(ATHN01A, `SCR_Water` 추정)·안개·파티클 미구현, PAND01A 어두운 방(베이크 색), 레일 카메라 이동, 카메라 FOV 미해석(60° 고정).
