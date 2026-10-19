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
| ADSR | 완료 | §7 |
| 리버브 | 완료 (모드 근사) | §8 |
| LFO | 완료 | §9 |
| 수중 필터 | 미구현 | 14 §8 |
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

## 6. 연결 완료 (리드, 2026-10-19)
| 소스 | 연결 위치 |
|------|----------|
| 공유 SoundManager | `GameSession.sharedSound(rom)` — 앱 전체 하나, R_PERMK 상주 |
| 메뉴 효과음 | `ShellScene`: R_SHELLK 뱅크 로드, `PS2_Sound*` → play |
| 레벨/적 뱅크 | `GameScene.enter`: 레벨 WAD + 적 종류 WAD 로드, `exit` 에서 stopAll + unload |
| 전투 SOUND 이벤트 | `Combatant.onSound` (이벤트 종류 3/40) → `play(hash, 위치)` — 영웅·적 모두 |
| HUD 효과음 | `PS2_Sound*` 변수 리스너 |
| 스크립트 | `ScriptListener.music` (MusicGroupStart/Stop/Param, PlaySound), `sound` (SoundController → soundGroup) |
| 오브 획득 | 적 처치 드롭 시 `SND_PICKUPS_ORBS_E` |
| 리스너 | 매 틱 카메라 위치/방향, `update(dt)` |

실행 확인: `--game ATHN01A` 에서 OpenAL 백엔드, 활성 소리 2–3 (시작 지점 앰비언트). 발소리·패드 진동은 미연결.

## 7. SPU ADSR 엔벨로프 (`SpuEnvelope`)

톤(14 문서 §2.4) 의 ADSR1/ADSR2 를 PS1/PS2 SPU 규칙 그대로 48kHz 샘플 단위로 흉내 낸다.

| 비트 | ADSR1 | ADSR2 |
|------|-------|-------|
| 15 | 공격 지수 | 서스테인 지수 |
| 14 | 공격 shift (14-10) | 서스테인 감소 |
| 12-8 | | 서스테인 shift |
| 9-8 / 7-6 | 공격 step (+7..+4) | 서스테인 step (증가 +7..+4 / 감소 −8..−5) |
| 7-4 / 5 | 감쇠 shift (항상 지수 감소, step −8) | 릴리스 지수 |
| 3-0 / 4-0 | 서스테인 레벨 SL — 감쇠는 `(level>>11) ≤ SL` 까지 | 릴리스 shift (step −8) |

한 단계: `cycles = 1 << max(0, shift−11)`, `step = stepVal << max(0, 11−shift)`; 지수 증가는 레벨 > 0x6000 에서 cycles ×4,
지수 감소는 step × level / 0x8000 (정수 곱이 0 이 되는 저레벨 구간은 최소 −1 로 끝까지 — 근사).

- **연결**: `SoundInstance.tone` 이 보이스마다 엔벨로프를 만들고, 240Hz 틱마다 200 샘플 진행 → 보이스 gain = 기본 gain × 레벨.
  `KEY_OFF` / `KILL_VOICES` 그레인과 정지 요청(`stop` 의 루프 보이스)은 **릴리스** 로 넘어가고, 레벨 0 이 되면 보이스 정지.
  `kill()` (즉시 정지) 과 플러그인·스트림 보이스는 엔벨로프 없이 바로 끈다.
- **실데이터 분포** (`SpuEnvelopeTest.adsrDistribution`, 톤 8,195 / 305 종): 최다 `80FF/9FCD` ×3,153 = 즉시 공격·최대 유지·선형 릴리스
  shift 13 (≈ 342ms). `9FCE` 683ms, `9FCC` 171ms, `9FC0` 1ms. 공격 shift 가 큰 `A8FF`·`B2FF`·`B7FF` 는 페이드 인 (환경음·음성).
- 남은 것: 수중 필터 — 리버브는 §8, LFO 는 §9.

## 8. 리버브

### 8.1 원작 [확실: 데이터 흐름 / 추정: 가중치·슬루]
- 프리셋 표 (exe 0x29CFE8 이름 → 0x29D010 값, TWK_Sound `/System/Sound/`): 0 Custom(이미터 값), 1 Outdoor Small 2728, 2 Outdoor Medium 3432,
  3 Outdoor Large 4792, 4 Indoor Small 2080, 5 Indoor Medium 2608, 6 Indoor Large 3472, 7 Underwater 3752. `Base Reverb` 3500.0, `Override Reverb` 0.
- **리버브는 사운드 이미터(SEM)가 정한다**: SEM `+0xD0` u32 사용자 깊이, `+0xD5` u8 종류(0..7), `+0xD6` u16 flags (bit0 = 리버브 기여).
  `0x1706C0` 이 이 값을 이미터 상태(+0xBC/+0xC1/+0xC2)로 복사하고, 리스너 갱신 `0x171B28` 안의 `0x171F24` 가 bit0 이고 가중치 &gt; 0 인 이미터마다
  깊이(종류 ≠ 0 → 프리셋, 0 → 사용자 값)를 `0x172910` 에 넘긴다: `Σ += (깊이 − 현재 기준) × w`, `Σw += w` (0x29D030 / 0x29D034).
- 실데이터 (`SemStats`, 전 WAD): 리버브 이미터는 대부분 앰비언트 루프 — 예 ATHN01A `SNDAmbDeckofShip` 종류 3(Outdoor Large),
  ATHN01B `SNDAmbBallanceRoom` 종류 5(Indoor Medium), SEWR01 하수도 사용자 깊이 9000, PAND04E 절벽 3 / 동굴 5.

### 8.2 엔진
- `SoundEmitter` 에 `reverbType / flags / reverbDepth` 추가. `SoundManager.soundGroup` 이 bit0 이미터를 켜면 리버브 소스로 등록 (재생이 끝나면 제거).
- 매 프레임 `깊이 = Base + Σ w·(프리셋 − Base) / max(1, Σw)`, `w = 1 − 거리/max(near, far)` (0 이하 0, 2D 이미터 1) [추정: 가중치 식],
  초당 8000 단위로 따라감 [추정]. `GameSession` 이 TWK_Sound 값으로 프리셋을 덮는다.
- 출력: `AudioBackend.reverb(깊이/0x7FFF)` → OpenAL EFX 표준 리버브 하나 (보조 슬롯 이득 = 깊이 × 3 [청감 보정]).
  보이스마다 `Voice.dry` (음악 1, 메뉴 6, MPEG 8, 수중 음악 9, Dry Effects 14 그룹) 이면 보내지 않는다. 장치에 EFX 가 없으면 리버브 없이 재생.
- 원작 SPU2 리버브 모드(Room/Hall 등 지연선 설정)는 IRX 쪽이라 미확인 → EFX 기본(generic) 값 [근사].
- 검증: `ReverbTest` — 이미터 없음 3500, 갑판 이미터 켜면 &gt; 4500 (Outdoor Large), 멀어지면 3500 으로 복귀, 백엔드에 깊이/0x7FFF 전달.

## 9. LFO (`SoundLfo`, LFO_SETTINGS 그레인 4)

IRX 근거: 설정 `0x4F14`, 초기화 `0x22DC`, 대상별 깊이 `0x29D4`, 계산 `0x24D8` (점프표 0x1D618), 모양 표 `0x1E444`. [확실: 식 / 추정: PMOD 단위]

- 데이터 16B (`SoundBank.Lfo`): +0 슬롯(핸들러 +0x5C + 48·슬롯, 4개), +1 대상 (0 = 해제), +2 대상 보조(5/6 의 다른 슬롯 번호), +3 모양,
  +4 u16 duty, +6 u16 depth (1024 = 최대), +8 flags (0x1 반전, 0x2 시작 위상 무작위), +0xA u16 시작 위상, +0xC u32 step.
- 틱(240Hz, 그레인과 같음)마다 `위상 += step·2`, 주기 = 2048 &lt;&lt; 16 → **주파수 = step × 480 / 2^27 Hz** (279620 = 1.000Hz, 2796202 = 10Hz,
  55920 = 0.2Hz — 실데이터 값이 정확히 떨어진다). 값 = 모양(진행 전 위상 &gt;&gt; 16) ∈ ±0x7FFF.
- 모양: 1 사인(1/4 표 513개), 2 사각(위상 &lt; duty → +), 3 삼각(0 에서 시작), 4 톱니(0→+최대, −최대→0), 5 무작위(반 주기마다 새 값).
- 대상 (핸들러 필드 / 깊이 배율 / 적용):

| 대상 | 필드 | 깊이' | 변조 | 엔진 |
|---|---|---|---|---|
| 1 볼륨 | +0x38 | 소리 vol × depth / 1024 | depth' × (v − 0x7FFF) &gt;&gt; 16 (0..−depth') | 소리 볼륨에 더함 |
| 2 팬 | +0x3C | depth × 45 / 256 | depth' × v &gt;&gt; 15 (1024 → ±180°) | 팬(도)에 더함 |
| 3 PMOD | +0x44 | depth × 381 / 64 | 〃 | 1/128 반음 단위로 피치에 더함 [추정: 단위] |
| 4 PBEND | +0x40 | depth × 32767 / 1024 | 〃 | 벤드에 더함 (톤 PBLow/High 반음 범위) |
| 5 RATE | 다른 슬롯 +0x10 | – | 기본 step 에 ±(step·depth/1024) | 다른 LFO 속도 변조 |
| 6 DEPTH | 다른 슬롯 +0x6 | – | 기본 depth 에 ±(depth·depth/1024), 다시 0x29D4 | 다른 LFO 깊이 변조 |

- 실데이터 81 그레인 / 43 소리 (`SND_TORCH_LOOP`, `SND_BIGTORCH_LP`, `SND_FLIESBUZZ_LP`, `SND_WINDWHISTLES_LOOP`, `SND_TIMEWARP_LP` …):
  볼륨(사인·삼각 0.6–10Hz), 벤드(depth 1024, 0.02–2Hz 느린 피치 흔들림), 5 = 다른 슬롯 속도 변조.
- 검증 `SoundLfoTest`: 1Hz = 240 틱 한 주기, 삼각/사각/반전, `SND_TORCH_LOOP` 볼륨이 4초 동안 0.12–0.44 로 흔들림.
