# 29. 구현: 오디오 런타임 (SoundManager)

명세: [14-sound-bank-format](14-sound-bank-format.md), 코덱 [09-audio-format](09-audio-format.md).
패키지 `devple.gow.engine.audio` (파서는 도구 쪽 `devple.gow.tools.audio`: `SoundBank`, `VagPack`, `SoundEmitter`, `WadSounds`).

## 1. 구성
| 클래스 | 역할 |
|-------|------|
| `SoundManager` | 뱅크 등록(WAD 단위), 이름/해시 조회, 재생 핸들, 음악, SoundController 그룹, 240Hz 틱 |
| `SoundInstance` | 재생 중인 소리 = 989snd 블록 핸들러. 그레인 실행기(IRX 0x63A0 재현), 레지스터 4 + 전역 32, 자식 소리, 보이스 목록 |
| `AudioBackend` | 보이스 출력 인터페이스: `play(clip)`, `stream(pcm)`, `update`, `stop`, `listener`, `tick` |
| `OpenAlBackend` | OpenAL: 소스 풀 48, 클립 버퍼 캐시, 루프 인트로/몸통 2 버퍼, 스트림 0.25s × 4 버퍼 큐, `AL_LINEAR_DISTANCE_CLAMPED` |
| `NullAudioBackend` | 헤드리스: 길이/피치로 재생 시간 흉내, 시작 로그, 스트림은 실제 디코딩 |
| `VpkStream` | VPK 블록(8192B) 단위 스트리밍 디코딩, 4채널 → 2 층 믹스 (`layerGain`) |
| `Voice` | gain / pitch / pos(null = 2D) / pan / near / far |

기존 `AudioSystem` 은 그대로 두었다 (사용처 없음). 새 코드는 `SoundManager` 만 쓰면 된다.

## 2. API
```java
SoundManager snd = SoundManager.open(session.rom);      // OpenAL, 장치 없으면 자동 헤드리스. 테스트: SoundManager.headless(rom)
snd.load(permkWad);                                     // R_PERMK (GENERAL·GENERAL2·Hero 팩) — 게임 시작 시 1회
snd.load(levelWad);                                     // 레벨 (SBK_<level> + SEM 이미터 + GO 그룹), 적 WAD(R_MIN …)도 load
snd.unload("ATHN01A.WAD");                              // 레벨 교체 시
snd.update(dt);                                         // 매 프레임 (그레인 240Hz 틱 + 스트림 보충 + 정리)
snd.listener(camPos, camForward, camUp);                // 매 프레임 (카메라)

int h = snd.play("SND_IG_POPUP");                       // 2D
int h = snd.play("SND_FOOTSTEP_DIRT", heroPos, 1);      // 3D + 레지스터0 = 1 (WALK)
int h = snd.play(event.hash(), owner.position());       // 전투 이벤트 TWK 해시 (없는 해시 → 0)
snd.stop(h);       // ON_STOP_MARKER 가 있으면 루프 보이스를 끄고 꼬리 그레인 실행, 없으면 즉시
snd.kill(h);       // 즉시
snd.playing(h); snd.setPosition(h, pos); snd.setRange(h, near, far); snd.setRegister(h, reg, v);

snd.music("SND_M_ATH1C1_4", fadeSec);                   // 이전 음악 정지 후 음악 그룹(볼륨 그룹 1)으로 재생
snd.stopMusic(fadeSec);  snd.musicParam(a, b, sec);  snd.currentMusic();
snd.soundGroup("SNDGrpAmbDeckofShip", op, origin);      // SoundController: op 0 끄기, 1..4 SEM 슬롯 소리 켜기

snd.setGroupVolume(SoundManager.GROUPS 인덱스, v); snd.setMasterVolume(v);
snd.setGlobalRegister(i, v); snd.onRumble((motor, value) -> pad.rumble(...));
snd.has(name); snd.find(name); snd.nameOf(hash);
```
볼륨 = 핸들러 vol × Sound.vol × Tone.vol (각 /127) × 볼륨 그룹 × 마스터.
3D 감쇠는 near 까지 1, far 에서 0 (선형). 기본 near 10 / far 80, SoundController 는 SEM 의 near/far.
피치 = 톤 재생 레이트(버퍼 샘플레이트) × 2^(벤드 반음/12).

## 3. 연결 가이드 (GameScene — 리드 작업)
`GameScene` / `HeroRig` / `Combatant` 는 수정하지 않았다. 아래처럼 연결하면 된다.

| 소스 | 지점 | 호출 |
|------|------|------|
| 세션 시작 | `GameSession` 생성 | `snd = SoundManager.open(rom); snd.load(session.permk);` (R_PERMK) |
| 레벨 진입 | `GameScene` 생성자, `LevelScene` 로드 직후 | `snd.load(level.data().wad)`; 적 종류 로드(`loadEnemyKinds`) 시 적 WAD 도 `snd.load(...)` |
| 레벨 이탈 | 씬 종료 / 워프 | `snd.stopAll(); snd.unload(levelWadName)` |
| 프레임 | `update(dt)` 끝 | `snd.listener(cam.pos, cam.forward, up); snd.update(dt);` |
| 전투 이벤트 | `Combatant.runEvents` 의 `switch (e.type())` | `case 3, 40 -> sound.play(e.hash(), owner.position())` — `IntConsumer`/콜백을 Combatant 에 하나 두고 GameScene 이 `snd` 로 연결. 한 번만(`once`) 규칙은 기존 firedEvents 그대로 |
| UI | `UiVariables` 리스너 (GameScene 105행, 셸 메뉴도 동일) | `if (name.regionMatches(true, 0, "PS2_Sound", 0, 9) && value.startsWith("SND_")) snd.play(value);` |
| 스크립트 음악 | `ScriptListener.music(func, args)` | `MusicGroupStart` → `snd.music((String) args.get(0), num(args.get(1)))`; `MusicGroupStop` → `snd.stopMusic(num(args.get(0)))`; `MusicGroupParam` → `snd.musicParam(int a0, int a1, num a2)`; `PlaySound` → `snd.play((String) args.get(0))` (문자열일 때) |
| SoundController | `ScriptListener.sound(source, group, op)` | `snd.soundGroup(group, op, new Vector3f(source.position()))` |
| 발소리 | 애니 이벤트(미구현) 또는 이동 속도 | `snd.play("SND_FOOTSTEP_" + 표면, heroPos, 0=STEP/1=WALK/2=RUN/3=LAND…)`. 표면 = 충돌 Sheet 속성(12 문서, 미상) — 우선 STONE |
| 오브 획득·마법 부족 | Rewards / Magic | `SND_PICKUPS_ORBS_E`, `SND_IG_MAGICOUT`, 신들의 분노 준비 `SND_MI_RAGEMIME` |
| 패드 진동 | `onRumble` | GENERAL2 `SND_SHAKE_*`/`SND_RUMBLE_*` 재생 시 호출 |

주의: ESC `SoundController` 의 그룹 이름은 엔티티 이름 접두사(`SNDGrp…`, `MusicGrp…`)다. `MusicGrp…` 그룹은 SEM 이 없고
핸들러 10 에서 MusicGroupStart 를 직접 부르므로 `soundGroup` 은 아무 것도 하지 않는다(정상).

## 4. 구현 결정
| 항목 | 결정 | 근거 |
|------|------|------|
| 틱 | 240Hz 고정, `update(dt)` 누적 | IRX 0x7828 = 240 |
| 첫 그레인 | `play` 시 즉시 1 틱 실행 | 원작은 다음 틱 (≤4ms) |
| 루프 | 샘플 ADPCM 플래그(6 시작 / 3 끝) → 클립 loopStart, OpenAL 인트로+몸통 | 09/14 문서 §2.5 |
| 인스턴스 제한 | Sound.instanceLimit 초과 시 가장 오래된 것 kill | 989snd InstanceLimit |
| RAND_PLAY 반복 회피 | (뱅크, 그레인 위치) 별 직전 선택을 관리자 공유 맵에 | 원작은 그레인 데이터 arg2 에 기록(전역 공유) |
| DPMS kind 0/1/2/3 | 메모리 팩 / `NAME.VAG` 전체 디코딩 / `NAME.VPK` 스트림 / 4채널 스트림 | 14 §4 |
| 4채널 VPK | 채널 (0,1)+(2,3) 믹스, `musicParam(a, b)` = 층 a 볼륨 b/127 | [추정] |
| 음악 페이드 | 미구현 (즉시 전환) | |
| ADSR / LFO / 리버브 / 수중 필터 | 미구현 | 14 §8 |
| 피치 상한 | 192kHz (SPU2 0x3FFF) | 9 개 톤이 초과 |

## 5. 테스트 (`engine/src/test/.../audio`, 헤드리스, gow-rom 필요)
| 테스트 | 검증 |
|-------|------|
| `SoundBankTest.decodesAllBanksOfAllWads` | 214 WAD: 뱅크 157, VAG 팩 48, 톤 8,185(루프 994) 디코딩, 톤 범위·레이트·클리핑 |
| `SoundBankTest.signalChecks` | GEN1 223 소리, DIRTSTEP 톤 cn −84 → 12,016Hz, 0.35s, RMS/영교차/인접상관 |
| `SoundManagerTest.nameAndHashLookup` | SND_PICKUPS_ORBS_E / SND_MINO_SNORT / SND_TRANSFORM_LP / SND_IG_POPUP 이름·해시, 해시 재생 → MIN 뱅크 16,040Hz 톤 |
| `…uiAndPickupSounds` | UI·픽업 소리 보이스 시작 |
| `…footstepRegisterSelectsSurfaceVariant` | reg0 0/1/2 → DIRTSTEP / DIRTWALK / DIRTRUN (TEST_REGISTER + BRANCH) |
| `…loopingSoundStopsOnRequest` | SND_TRANSFORM_LP 2초 유지 → stop → 종료 |
| `…vagPackDialogueViaPlugin` | SND_MINO_VODIE → DPMS kind 0 → `min` 팩 MINDIE_1 16kHz |
| `…musicGroupStreamsVpk` | SND_M_ATH1C1_4 → ATH1C1_4.VPK 스트림 디코딩, stopMusic |
| `…soundControllerGroupUsesEmitterSlots` | SNDGrpAmbDeckofShip Op1/Op2 → 다른 소리, Op0 → 전부 종료 |

OpenAL 경로는 로컬에서 수동 확인 (재생 60 프레임, `alGetError` 0).
