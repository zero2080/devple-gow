package devple.gow.engine.boss;

import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.input.Input;
import devple.gow.engine.script.ScriptActor;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 히드라 보스 (ATHN01E 배 갑판, 37 문서 §2). 원작 흐름은 전부 데이터:
 * <pre>
 * 진입 볼륨 → a01Mp03 컷신 → 501 → Goto deckBossBegin → (전역 이벤트 301..) → spawnDaddyHydra → SpawnEnemy 250 (그룹 250)
 * 구역 볼륨 51..56 → LevelData28 → PlayerZoneTransmit 1054 → 플레이어 +0x88 → DTre_250 조건 26: 구역 1 포효/도발, 2 오른쪽 물기,
 *   3 왼쪽 물기, 4 가운데 물기 (자기 피해 SELF_HP 15×11)
 * 피격 1039(HP) → bossEnt: HP &lt; 250 → 1019 EvtForce 53/54 (내려찍기, HP 짝/홀) → 0.35– 영웅 ○(GRAB, 자세 200) → 806/802 동기
 *   → 807/803 연타(RepeatPress 251/250) → 809/805 성공 (돛대 단계 LevelData39 +1, 포효) / 808/804 실패 (영웅 피해 10)
 * 돛대 3단계 + HP &lt; 150 → 1019 123 STUN (또는 HP 0 → EV_DEATH → 123) → 120 STUNLOOP (자세 810, ○ 프롬프트)
 *   → 영웅 ○ = COMBO5A(800) 적중 → 810 BHABOSSKILL: TimedPress 251 [0.39–0.47] 실패 → 813
 *   → 811 연타(RepeatPress 253) → 812 KILLWIN (SELF_HP 1500 = 사망) / 816
 * 812 완료 1047 → trigHydras-EventHydraDie: 음악 정지, a01Mp01 컷신 → 501 → Goto afterHydraDeath
 * </pre>
 * 엔진 쪽 보정: HP 바닥 1 (피격으로는 죽지 않고 EV_DEATH 분기로 기절 — 원작 A170), 머리 위치 고정(바다에 붙은 목) [근사],
 * 스크립트가 스폰하므로 시야 판정 생략.
 */
public final class HydraFight extends BossFight {

	public static final String LEVEL = "ATHN01E";
	private final Vector3f anchor = new Vector3f();
	private float anchorYaw;
	private boolean killDone;
	private boolean demoPlaced, demoGrabbed, demoKill;
	private float demoWait;
	private boolean demoSpawn;

	HydraFight(Path rom, WadFile permk) throws IOException {
		super(LEVEL);
		Path lv = rom.resolve(LEVEL + ".WAD");
		List<Path> extra = new ArrayList<>();
		if (Files.exists(rom.resolve("R_AHA.WAD"))) {
			extra.add(rom.resolve("R_AHA.WAD"));
		}
		var big = BossKinds.load(lv, permk, extra, "bha", 250, 250);
		kinds.put(250, big);
		silent.addAll(java.util.List.of(254, 255, 256, 257, 259, 261));
		bossTemplates.add(250);
		// 작은 머리 (enc3 253 / 258): bhb, 세트 253 — 같은 레벨에서 갑판 아래 아기 히드라
		try {
			var baby = BossKinds.load(big.assets(), permk, List.of(), "bhb", 253, 253);
			kinds.put(253, baby);
			kinds.put(258, baby);
		} catch (RuntimeException ex) {
			System.out.println("아기 히드라 로드 실패: " + ex.getMessage());
		}
		sync = new BossSync(id -> id >= 802 && id <= 816, BossSync.Align.TARGET_SYNCH);
	}

	@Override
	protected void bind(EnemyFighter f, ScriptActor a) {
		f.hpFloor = 1;
		anchor.set(f.pos);
		anchorYaw = f.yaw;
		f.brain().engage();
	}

	@Override
	protected void tick(Input in, float dt) {
		EnemyFighter f = boss;
		if (f.hp > 0) {
			f.brain().engage();
		}
		// 목이 바다에 고정된 몸통: 위치·방향 모두 스폰 값 고정 [추정]. 공격 방향은 구역별 애니(51 왼쪽 / 52 오른쪽 / 62 가운데)가 맡고,
		// 잡기·처형 카메라(gobossleftfight 등)가 월드 고정 오브젝트라 동기 장면도 스폰 방향 기준으로 저작됨 (30 문서 §6).
		// 방향을 AI 에 맡기면 영웅 쪽으로 돌아 HERO_TO_BOSS 정렬된 크레토스가 망대 밖 허공 / 카메라 밖에 놓였다.
		f.pos.x = anchor.x;
		f.pos.z = anchor.z;
		f.yaw = anchorYaw;
		int m = f.combat().active() ? f.combat().move().id() : -1;
		if (m == 812 && f.hp <= 0 || killDone) {
			killDone = true;
			markDefeated();
			return;
		}
		switch (m) {
			case 53, 54 -> setPhase("내려찍기 (○ 잡기 기회)");
			case 802, 806, 803, 807 -> setPhase("잡기 연타");
			case 805, 809 -> setPhase("잡기 성공");
			case 804, 808 -> setPhase("잡기 실패");
			case 123, 120 -> setPhase("기절 (○ 처형)");
			case 810, 811 -> setPhase("처형");
			case 813, 816 -> setPhase("처형 실패");
			case 812 -> setPhase("처형 성공");
			default -> {
				if (m >= 0) {
					setPhase("구역 " + (arena.bridge() != null ? arena.bridge().playerZone() : 0));
				}
			}
		}
	}

	@Override
	protected void beforeSpawnDemo(float dt) {
		if (demoTime > 1 && !demoPlaced) {
			demoPlaced = true; // 아래 갑판 스폰 볼륨 (spawnDaddyHydra) — 보스가 나오면 demo() 가 구역 2 로 옮김
			placeHero(pointIn("spawnDaddyHydra-pCube4413-spawnDaddy", 606));
			demoSpawn = true;
		}
	}

	/**
	 * 데모: 구역 2 갑판에 영웅 → 6초 뒤 HP 240 (스크립트가 내려찍기 지시) → 잡기 창에서 ○ 잡기 → 자동 연타 → 돛대 단계,
	 * 3단계 뒤 HP &lt; 150 → 기절 → ○ 처형 (TimedPress·연타 자동) → KILLWIN → 컷신 a01Mp01.
	 */
	@Override
	protected void demo(float dt) {
		EnemyFighter f = boss;
		if (!demoPlaced || demoSpawn) {
			demoPlaced = true;
			demoSpawn = false;
			demoTime = 0;
			placeHero(pointIn("bossEnt-bossZoneEntry2", 900, "bossEnt-bossZoneEntry1", "bossEnt-bossZoneEntry3",
					"bossEnt-bossZoneEntry4"));
		}
		int m = f.combat().active() ? f.combat().move().id() : -1;
		var hc = arena.heroCombat();
		int hm = hc.active() ? hc.move().id() : -1;
		if ((demoWait -= dt) > 0 || killDone) {
			return;
		}
		Object stage = arena.world() != null ? arena.world().host().levelData().get(39) : null;
		int mast = stage instanceof Number n ? n.intValue() : 0;
		if ((m == 53 || m == 54) && f.combat().progress() >= 0.4f && !demoGrabbed) {
			demoGrabbed = true;
			contact(200, 0); // 영웅 ○ 잡기
			demoWait = 0.5f;
		} else if (m == 120 && !demoKill && hm != 800) {
			demoKill = true;
			contact(800, 0); // 영웅 ○ COMBO5A → 처형
			demoWait = 0.5f;
		} else if (demoTime > 6 && !sync.active() && (m < 0 || m < 800 && m != 53 && m != 54 && m != 120 && m != 123)) {
			demoGrabbed = false;
			if (mast >= 3) {
				f.hp = Math.min(f.hp, 140); // 1039 → lowHealthStunSensor → 123
			} else {
				f.hp = Math.min(f.hp - 1, 240); // 1039 → bossStunSensor → 53/54
			}
			demoWait = 3f;
		}
	}
}
