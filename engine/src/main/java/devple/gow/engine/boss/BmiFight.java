package devple.gow.engine.boss;

import devple.gow.engine.combat.CombatInput;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.fx.EffectSystem;
import devple.gow.engine.fx.MagicFx;
import devple.gow.engine.input.Input;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptListener;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * PAND03G 판도라 수호자 (BMI, AI1 200, CombatFile 200–205). 원작은 <b>전용 Enemy1 하위 클래스</b>(팩토리 클래스 1, 객체 0x400, vtable 0x2BB3D8)
 * 이고 결정 트리가 없다 — 기술 선택은 갱신 Think(0x1C0A48)와 피해 재정의 TakeDamage(0x1C1838)가 한다 (37 문서 §10). 수치는 {@code /GlobGame/BMI}
 * (R_PERMK, 거리 ×16, 각도는 tan).
 * <pre>
 * Think (매 프레임, 기술이 없을 때만 고름 — 같은 프레임에 나중 요청이 이김):
 *   영웅이 뒤(fz &lt; Behind Close)·걷는 중 Close 안·발판 위에서 영웅이 내려옴 → CHARGED (대기/걷기/도발의 0x800 AUTO 로 끝냄)
 *   영웅 HP 0 → Victory 5 (발판 13) / 앵커에서 Retreat Dist 넘게 나옴 → Retreat 40 / 멀거나(Far Walk) 높음(96) → Walk Forward 1
 *   / 뒤 → Leap Back 2 / 그 밖: 단계표 S0..S3 [Close|Mid|Far][Left|Center|Right] (Idle Time 대기). 발판 위: Platform L/C/R/Back 표
 *   앵커 선(정면) 위 [0, Platform Dist] 로 고정 (HP &gt; 99 일 때), Platform Dist 에서 걷기 → Platform Mount 10 (발판)
 * TakeDamage: ≥ 1000 (통나무) → 단계 +1 / 999 (스크립트 1039, 통나무 쏨) → NoDodge 아니면 Dodge 220 / 잠금(기절 연쇄) 중 무시
 *   단계 0·1 배출구: 피해 100 마다 배출구 하나 (5 개째 = Stunned 201 → 202 → 203 거북 기절, 첫 번째 = 250 STEAMVENT, 나머지 200)
 *   단계 2 갑옷: 150 마다 230, 5 번째 = 232 갑옷 파괴 (→ 단계 3) / 단계 3: HP 감소, 99 바닥 → 240 → 242 → 243 통나무 기절
 * 올라타기: 203 의 EV_HIT_REACT 태그 200 (영웅 ○) → 450 → 영웅 451/452/453 (TimedPress 206/207, 실패 → 454) → 455 → 208 통나무 기절 (22초)
 *   → 레버(과제 351) LevelData0 = 99 → 통나무 Arrow/060 (피해 1000, Context 456) → 단계 +1 · 210 LOGHIT / 단계 3 (HP ≤ 1000) 245 → ExplodeDeath
 * </pre>
 * 위치는 공용 루트 모션 (원작 0x1F7110 → 0x1EBD78, 37 문서 §11.4). 통나무 = 레버 logAim 투사체 (Arrow/060 golog), 배출구 증기 GO,
 * 갑옷 BRK 조각, 모델 변형 (+0x120), HUD EnemyState, 카메라 대상 슬롯은 원작 해독 (37 문서 §10.1). 피한 통나무가 빗나가는 판정만 근사.
 */
public final class BmiFight extends BossFight {

	public static final String LEVEL = "PAND03G";
	public static final int TEMPLATE = 200;
	static final String NODE = "/GlobGame/BMI/";
	public static final int SCR_NO_DODGE = 0x4ca9cf75, SCR_TURN_OFF_VENTS = 0xd4451ecd, SCR_BROKEN_PIECES = 0x27c3c4db,
			SCR_ON_PLATFORM = 0xf84955d3;
	/** 통나무 Arrow/060 "BMILogLauncher": Damage 1000, Context 456, Velocity 150 (×16/초). */
	public static final int LOG_DAMAGE = 1000, LOG_TAG = 456, RIDE_MOUNT = 450, RIDE_FAIL = 454, RIDE_WIN = 455, LOG_STUN = 208,
			FINAL_LOG_STUN = 243, DEATH = 245,
			/** 발판 잡아 던지기: 202 의 104 BMIPLATFORMGRAB 적중 → BMI 205 / 영웅 002 의 460 BMIPLATFORMGRABTOSS (37 문서 §11.6). */
			PLATFORM_TOSS = 460;
	static final float HIGH = 96f;

	// ---- /GlobGame/BMI ----
	final float ventDamage, armorDamage, noArmorHp;
	final int walkForward, leapBack, dodge, staggerFirst, stagger, stunned, armorStagger, armorStunned, noArmorStunned, platStaggerFirst,
			platStagger, platStunned, platArmorStagger, platArmorStunned, platNoArmorStunned, platMount, platDismount, victory, platVictory,
			retreat;
	final float behindClose, closeMid, midFar, farWalk, centerTan, retreatDist, platDist, platBackDist, platCenterTan, cameraWeight;
	/** 단계표 [단계][Close L,C,R, Mid L,C,R, Far L,C,R, Platform L,C,R,Back]. */
	final int[][] table = new int[4][13];
	final float[] idle = new float[4], platIdle = new float[4];

	// ---- 상태 (객체 0x360..0x3F0) ----
	private final Vector3f anchor = new Vector3f();
	private int stage, count, brokenPieces = 4;
	private float accum, timer;
	private boolean onPlatform, firstVent = true, lock;
	/** 날고 있는 통나무 (Arrow/060 "golog", 없으면 null). */
	private Log log;
	/** 기록 (테스트): 통나무 적중 / 빗나감, 마지막 통나무가 멈춘 곳·이유. */
	public int logHits, logMisses;
	public final Vector3f lastLogEnd = new Vector3f();
	public String lastLogEndReason = "";
	/** 통나무 튜닝 (/TweakTemplates/Arrow/060 "BMILogLauncher" — 레벨 WAD). */
	final float logSpeed, logLife;
	final String logGo, logHitGo;
	/** HUD EnemyState (객체 +0x380, −1 = 변화 없음) / 켜짐 (HUD +0x1590). */
	private int meterState = -1;
	private boolean meterOn;
	/** 기록 (테스트): HUD 로 보낸 EnemyState 값들. */
	public final java.util.List<Integer> meterLog = new java.util.ArrayList<>();
	/** 배출구 증기 GO 8 (goSteamVent01 @ steamAttach01..08) / 갑옷 조각 BRK GO 8, 깨졌는지. */
	private final EffectSystem.Effect[] vents = new EffectSystem.Effect[8];
	private final EffectSystem.Effect[] pieces = new EffectSystem.Effect[8];
	private final boolean[] pieceBroken = new boolean[8];
	/** 기록 (테스트): 깨진 갑옷 조각 수 (원작 표 0x2B7550 / +0x37C). */
	public int piecesBroken;
	private int demoStep;
	private float demoWait;

	BmiFight(Path rom, WadFile permk) throws IOException {
		super(LEVEL);
		var kind = BossKinds.load(rom.resolve(LEVEL + ".WAD"), permk, List.of(), "bmi", TEMPLATE, TEMPLATE);
		kinds.put(TEMPLATE, kind);
		silent.addAll(List.of(202, 257)); // AB2/AMISoundbankLoad
		bossTemplates.add(TEMPLATE);
		// 동기 쌍 = 같은 ID 기술 (0x2097A8: 플래그 0x20 기술 시작 때 +0x84 = 공격 대상). 올라타기 450–455 와 발판 잡아 던지기 460
		// (BMI 205 의 460 플래그 0x130 · 영웅 002 의 460 플래그 0x140, BMI 460 이벤트 19 [0–0.4] → BMI 가 영웅을 끈다, 37 문서 §11.6) [확실: 데이터]
		sync = new BossSync(id -> (id >= RIDE_MOUNT && id <= RIDE_WIN) || id == PLATFORM_TOSS);
		Tweaks tw = kind.tweaks();
		ventDamage = tw.f(NODE, "Damage Per Vent Stage", 100);
		armorDamage = tw.f(NODE, "Damage Per Armor Stage", 150);
		noArmorHp = tw.f(NODE, "No Armor Health Threshold", 99);
		walkForward = s(tw, "Walk Forward", 1);
		leapBack = s(tw, "Leap Back", 2);
		dodge = s(tw, "Dodge", 220);
		staggerFirst = s(tw, "Stagger First Time", 250);
		stagger = s(tw, "Stagger", 200);
		stunned = s(tw, "Stunned", 201);
		armorStagger = s(tw, "Armor Stagger", 230);
		armorStunned = s(tw, "Armor Stunned", 232);
		noArmorStunned = s(tw, "No Armor Stunned", 240);
		platStaggerFirst = s(tw, "Platform Stagger First Time", 251);
		platStagger = s(tw, "Platform Stagger", 205);
		platStunned = s(tw, "Platform Stunned", 206);
		platArmorStagger = s(tw, "Platform Armor Stagger", 231);
		platArmorStunned = s(tw, "Platform Armor Stunned", 232);
		platNoArmorStunned = s(tw, "Platform No Armor Stunned", 241);
		platMount = s(tw, "Platform Mount", 10);
		platDismount = s(tw, "Platform Dismount", 2);
		victory = s(tw, "Victory", 5);
		platVictory = s(tw, "Platform Victory", 13);
		retreat = s(tw, "Retreat", 40);
		behindClose = tw.f(NODE, "Behind Close Dist", 2.5f) * 16;
		closeMid = tw.f(NODE, "Close Mid Dist", 12) * 16;
		midFar = tw.f(NODE, "Mid Far Dist", 18) * 16;
		farWalk = tw.f(NODE, "Far Walk Dist", 34) * 16;
		centerTan = (float) Math.tan(Math.toRadians(tw.f(NODE, "Center Angle", 30)));
		retreatDist = tw.f(NODE, "Retreat Dist", 45) * 16;
		platDist = tw.f(NODE, "Platform Dist", 53) * 16;
		platBackDist = tw.f(NODE, "Platform Back Dist", 75) * 16;
		platCenterTan = (float) Math.tan(Math.toRadians(tw.f(NODE, "Platform Center Angle", 55)));
		cameraWeight = tw.f(NODE, "Camera Target Weight", 0.35f);
		String[] keys = {"Close Left", "Close Center", "Close Right", "Mid Left", "Mid Center", "Mid Right", "Far Left", "Far Center",
				"Far Right", "Platform Left", "Platform Center", "Platform Right", "Platform Back"};
		for (int st = 0; st < 4; st++) {
			for (int k = 0; k < keys.length; k++) {
				table[st][k] = s(tw, "S" + st + " " + keys[k], 0);
			}
			idle[st] = tw.f(NODE, "S" + st + " Idle Time", 0);
			platIdle[st] = tw.f(NODE, "S" + st + " Platform Idle Time", 0);
		}
		int arrow = tw.i16(EMITTER, "Arrow Template", 60);
		String an = String.format("/TweakTemplates/Arrow/%03d/", arrow);
		logSpeed = tw.f(an, "Velocity", 150) * 16;
		var life = tw.rawHash(an, 0x117c872e);
		logLife = life.isPresent() ? Float.intBitsToFloat(life.getAsInt()) : 10f;
		logGo = tw.s(an, "GO Name", "golog");
		logHitGo = tw.s(an, "Hit Effect", "gologShatter");
	}

	/** ArrowEmitter/100 "BMILogLauncher" (레버 gologlever 의 Arrow Template 60, Aim Joint logAim). */
	static final String EMITTER = "/TweakTemplates/ArrowEmitter/100/";
	/** 갑옷 조각: BMI 관절 (표 0x2B7400) ↔ BRK GO (표 0x2B74B0) — 생성자 0x1C0378. */
	static final String[] PIECE_JOINTS = {"BMI01JOLULN1", "BMI01JOLHUM1", "BMI01JORULN1", "BMI01JORHUM1", "BMI01JOCVEB1", "BMI01JOCVEC1",
			"BMI01JOCHEA1", "BMI01JOCNEC1"};
	static final String[] PIECE_GOS = {"goLeftArmShatterBRK", "goLeftShouldBRK", "goRArm2BRK", "goRightShouldBRK", "goAbdomenBRK",
			"goChestBRK", "goHelmetBRK", "goCollarBRK"};
	/** 카메라 대상 슬롯 (생성자: 관절 문자열 0x2B7360, 가중 "Camera Target Weight" 0x29DBDC, 반지름 ×16 "Camera Target Radius"). */
	static final String CAMERA_JOINT = "BMI01JOCHEA1";
	/** 통나무 반지름 (MDL_log 단면 ±8 [근사]) — Arrow 판정 반지름 미해석. */
	static final float LOG_RADIUS = 8f;

	private static int s(Tweaks tw, String key, int def) {
		return (short) tw.i16(NODE, key, def);
	}

	/** 순회 검증(HeadlessBoss)은 아직 맡기지 않음 (검증 하네스 쪽 PAND03G 경로를 바꾸지 않음) — 데모·BmiFightTest 로 결말까지 확인. */
	@Override
	public boolean headless() {
		return false;
	}

	public int stage() {
		return stage;
	}

	public int vents() {
		return count;
	}

	public boolean onPlatform() {
		return onPlatform;
	}

	public Vector3f anchor() {
		return anchor;
	}

	@Override
	protected void bind(EnemyFighter f, ScriptActor a) {
		// 원작 Think 첫 갱신 (플래그 0x10): 앵커 +0x360 = 부모 로케이터 (sub_1A8ED0 → sub_1A9E98 행렬 이동) — 스폰한 SpawnEnemy 엔티티의
		// 소유 GO (minoBoss-minoBossAI → gominoboss, 관절 0 (0, 0, 1920)) [추정: 부모 = 스포너 소유 GO]. 없으면 스폰 위치
		anchor.set(f.pos);
		if (a != null && a.spawner != null && a.spawner.owner != null) {
			float[] m = a.spawner.owner.jointWorld()[0];
			anchor.set(m[12], f.pos.y, m[14]);
		}
		f.damageHook = this::takeDamage;
		// Think 0x1C0AD0–0x1C0AE8: 매 프레임 owner+0x2D8 |= 0x80000000 → 자기 이동에서 생물 밀어내기 없음 (영웅에게는 단단함, 32 문서) [확실]
		f.nav().setNoPush(true);
		meterState(true); // 생성자 sub_1C1958(this, 1): 단계 0 → 1
		EffectSystem fx = arena.effects();
		if (fx != null) {
			// 생성자: steamAttach01..08 에 goSteamVent01 부착, 배율 0 (꺼짐). 갑옷 BRK GO 는 숨김 (단계 2 부터 0x1C1B10)
			for (int i = 0; i < 8; i++) {
				vents[i] = fx.play("goSteamVent01", jointAnchor(String.format("steamAttach%02d", i + 1)));
				if (vents[i] != null) {
					vents[i].rateOverride = 0;
				}
			}
		}
	}

	/** BMI 관절 월드 (보스가 없어지면 false → 이펙트 종료). */
	private EffectSystem.Anchor jointAnchor(String joint) {
		EnemyFighter f = boss;
		MagicFx.Joints j = MagicFx.joints(f);
		return out -> boss == f && f.hp > 0 && j.world(joint, out);
	}

	// ---------------- Think 0x1C0A48 ----------------

	@Override
	protected void tick(Input in, float dt) {
		EnemyFighter f = boss;
		// 위치: 공용 루트 모션 (EnemyFighter, 원작 0x1F7110 → 0x1EBD78 — 기술 중 zeroJoint 델타, 회전 포함)
		if (log != null) {
			flyLog(f, dt);
		}
		int m = f.combat().active() ? f.combat().move().id() : -1;
		f.modelVariant = stage <= 0 ? 2 : stage == 1 ? 1 : 0; // 0x1C1AA8: 모델 컴포넌트 +0x120
		ventsAndPieces(f);
		Vector3f head = f.hp > 0 ? jointWorld(f.animator(), f.world(new Matrix4f(), 1f), CAMERA_JOINT) : null;
		arena.cameraTarget(head, cameraWeight);
		if (f.hp <= 0) {
			markDefeated();
			return;
		}
		if (m == DEATH) {
			setPhase("통나무 — 죽음");
		}
		if (sync.active() || (arena.heroCombat().active() && syncMove(arena.heroCombat().move().id()))) {
			line(f);
			setPhase("올라타기 " + (sync.pairedMove() >= 0 ? sync.pairedMove() : m));
			return; // 동기 중: 보스 기술은 영웅을 따라간다 (BossSync)
		}
		think(f, dt);
		setPhase(m == LOG_STUN || m == FINAL_LOG_STUN ? "통나무 기절" : lock ? "기절" : onPlatform ? "발판 단계 " + stage : "단계 " + stage);
	}

	private static boolean syncMove(int id) {
		return id >= RIDE_MOUNT && id <= RIDE_WIN;
	}

	private void think(EnemyFighter f, float dt) {
		Combatant c = f.combat();
		Vector3f fwd = new Vector3f((float) -Math.sin(f.yaw), 0, (float) -Math.cos(f.yaw));
		Vector3f left = new Vector3f((float) -Math.cos(f.yaw), 0, (float) Math.sin(f.yaw));
		Vector3f hero = arena.heroCtl().position;
		Vector3f d = new Vector3f(hero).sub(f.pos);
		float fz = fwd.dot(d), fx = left.dot(d), hy = d.y;
		float bA = fwd.dot(new Vector3f(f.pos).sub(anchor));
		float hA = fwd.dot(new Vector3f(hero).sub(anchor));
		float tan = onPlatform ? platCenterTan : centerTan;
		int[] tbl = table[Math.max(0, Math.min(3, stage))];
		int st = Math.max(0, Math.min(3, stage));
		if (!lock) {
			if (c.active()) {
				if (fz < behindClose || (isMove(c, walkForward) && fz < closeMid && hy < HIGH) || (onPlatform && hy < HIGH)) {
					c.setStateBit(Combatant.STATE_CHARGED, true); // 0x1E1AB0: 대기·걷기·도발의 AUTO (0x800) 로 끝냄
					timer = 0;
				}
			} else {
				c.setStateBit(Combatant.STATE_CHARGED, false);
			}
		}
		if (!c.active()) {
			timer -= dt;
			float heroHp = arena.hero().hp();
			int want = -1;
			float wantIdle = 0;
			if (onPlatform) {
				if (heroHp <= 0) {
					want = platVictory;
				}
				if (fz < behindClose || hy < HIGH) {
					want = platDismount;
					onPlatform = false;
				}
			} else {
				if (heroHp <= 0) {
					want = victory;
				}
				if (bA > retreatDist && hy < HIGH) {
					want = retreat;
				}
				if (fz > farWalk || hy > HIGH) {
					want = walkForward;
				} else if (fz < behindClose) {
					want = leapBack;
				}
			}
			if (want < 0) {
				if (onPlatform) {
					wantIdle = platIdle[st];
					if (hA > platBackDist) {
						want = tbl[12];
					} else if (Math.abs(fx) < Math.abs(hA - 1056) * tan) {
						want = tbl[10];
					} else {
						want = fx > 0 ? tbl[9] : tbl[11];
					}
				} else {
					int side = Math.abs(fx) < Math.abs(fz) * tan ? 1 : fx > 0 ? 0 : 2;
					int band = fz < closeMid ? 0 : fz < midFar ? 1 : 2;
					want = tbl[band * 3 + side];
					wantIdle = idle[st];
				}
			}
			req(want, wantIdle);
			if (!c.active()) {
				f.animator().play(bossKind.idle(), false); // 대기 시간 동안 (원작 Enemy1 기본 대기)
			}
		}
		float b2 = line(f);
		if (b2 >= platDist) {
			if (isMove(c, walkForward) && !onPlatform) {
				c.forceMove(platMount); // EndMove + Req(Platform Mount)
				onPlatform = true;
				lock = false;
				timer = 0;
			}
		} else if (b2 < platDist - 0.2f * 16) {
			onPlatform = false;
		}
	}

	/** 앵커 선 고정 (Think 끝, 항상): HP &gt; 99 면 앵커 정면 선 [0, Platform Dist] 안으로. 반환 = 고정 뒤 bA. */
	private float line(EnemyFighter f) {
		Vector3f fwd = new Vector3f((float) -Math.sin(f.yaw), 0, (float) -Math.cos(f.yaw));
		float bA = fwd.dot(new Vector3f(f.pos).sub(anchor));
		if (f.hp > noArmorHp && (bA < 0 || bA > platDist)) {
			bA = Math.max(0, Math.min(platDist, bA));
			f.pos.set(anchor.x + fwd.x * bA, f.pos.y, anchor.z + fwd.z * bA);
		}
		return bA;
	}

	private static boolean isMove(Combatant c, int id) {
		return c.active() && c.move().id() == id;
	}

	/** Req 0x1C0888: 잠금 해제, 대기 시간이 끝났고 살아 있으면 즉시 시작 (요청 큐 비움). */
	private void req(int id, float idleTime) {
		lock = false;
		if (id >= 0 && timer <= 0 && boss.hp > 0) {
			Combatant c = boss.combat();
			if (!c.request(id)) {
				c.forceMove(id);
			}
			timer = idleTime;
		}
	}

	// ---------------- TakeDamage 0x1C1838 ----------------

	/** vtable +0x40 재정의. 일반 피격(0x204690)과 스크립트 1039 둘 다 여기로. */
	void takeDamage(float dmg) {
		if (dmg >= LOG_DAMAGE) {
			stage++; // StageUp 0x1C1430
			accum = 0;
			count = 0;
			meterState(true);
			return;
		}
		if (dmg == 999) {
			if (!noDodge()) {
				timer = 0;
				req(dodge, 0);
				onPlatform = false;
			}
			return;
		}
		if (lock) {
			return;
		}
		accum += dmg;
		switch (stage) {
			case 0, 1 -> vent();
			case 2 -> armor();
			default -> noArmor(dmg);
		}
	}

	/** 0x1C1518: 배출구 단계. */
	private void vent() {
		if (accum < ventDamage) {
			meterState(false); // 0x1C1654: 문턱 전에도 (a1 = 0)
			return;
		}
		if (count < 5) {
			arena.sound("SND_MB_VENTSHOT"); // 0x1C1560 (+ SND_MB_VENT%d_LP 반복음은 미연결)
		}
		count++;
		int id;
		boolean stun = false;
		if (count >= 5) {
			id = onPlatform ? platStunned : stunned;
			stun = true;
		} else if (firstVent) {
			id = onPlatform ? platStaggerFirst : staggerFirst;
			firstVent = false;
		} else {
			id = onPlatform ? platStagger : stagger;
		}
		timer = 0;
		req(id, 0);
		onPlatform = false;
		lock = stun;
		accum = 0;
		meterState(false);
	}

	/** 0x1C1678: 갑옷 단계 (5 번째 = 갑옷 파괴 232, 단계 3). */
	private void armor() {
		if (accum < armorDamage) {
			meterState(false); // 0x1C1770 (a1 = 0): 흔들림/반짝임 5, 7, … 13
			return;
		}
		count++;
		int id;
		boolean stun = false;
		if (count >= 5) {
			id = onPlatform ? platArmorStunned : armorStunned;
			stage++;
			accum = 0;
			count = 0;
			stun = true;
		} else {
			id = onPlatform ? platArmorStagger : armorStagger;
		}
		timer = 0;
		req(id, 0);
		onPlatform = false;
		lock = stun;
		accum = 0;
		meterState(true); // 0x1C175C (a1 = 1): 4 + 2·조각 / 단계 3 → 14
	}

	/**
	 * HUD EnemyState 값 (0x1C1958 → 객체 +0x380, Think 가 HUD +0x28 "EnemyState_Value1" / +0x238 "EnemyState_Event1" 로 보냄).
	 * set (a1 = 1): 단계 0 → 1, 1 → 3, 2 → 4 + 2·조각, 3 → 14 / 아니면: 0 → 2, 1 → 4, 2 → 5 + 2·조각, 3 → 변화 없음.
	 * FLP_Pand03G SetValue: 1 LargeArmor 켬, 2·4 BMI_Glow, 3 조각 1–5 켬 + LargeArmor 끔, 5.. 조각 흔들림/깨짐.
	 */
	private void meterState(boolean set) {
		int v = switch (Math.max(0, Math.min(3, stage))) {
			case 0 -> set ? 1 : 2;
			case 1 -> set ? 3 : 4;
			case 2 -> count < 5 ? (set ? 4 : 5) + 2 * count : -1;
			default -> set ? 14 : -1;
		};
		if (v >= 0) {
			meterState = v;
		}
	}

	/** Think 0x1C0AF0..0x1C0B9C: 처음엔 Event 1 (켬 + SetValueStart), 그 뒤 값이 바뀔 때마다 Event 2 (SetValue). */
	@Override
	public void hud(devple.gow.engine.ui.UiVariables v) {
		if (boss == null || boss.hp <= 0) {
			return;
		}
		if (!meterOn || meterState >= 0) {
			int st = meterState >= 0 ? meterState : lastMeter;
			v.set("PS2_EnemyState_Value1", st);
			v.event("PS2_EnemyState_Event1", meterOn ? 2 : 1);
			meterOn = true;
			lastMeter = st;
			meterLog.add(st);
			meterState = -1;
		}
	}

	private int lastMeter = 1;

	/** 0x1C1798: 갑옷 없음 — HP 감소, No Armor Health Threshold(99) 바닥에서 기절 240 → 242 → 243. */
	private void noArmor(float dmg) {
		EnemyFighter f = boss;
		if (f.hp - dmg < noArmorHp) {
			f.hp = Math.min(f.hp, noArmorHp);
			req(onPlatform ? platNoArmorStunned : noArmorStunned, 0);
			onPlatform = false;
			lock = true;
		} else {
			f.hp -= dmg;
		}
	}

	private boolean noDodge() {
		return boss.combat().activeScript(SCR_NO_DODGE) != null;
	}

	// ---------------- 스크립트 이벤트 / 통나무 ----------------

	@Override
	protected void script(EnemyFighter f, CombatFile.Event e) {
		if (f != boss) {
			return;
		}
		int h = e.hash();
		if (h == SCR_TURN_OFF_VENTS) {
			count = Math.max(0, count - (int) e.b()); // 0x1C1448
			accum = 0;
		} else if (h == SCR_ON_PLATFORM) {
			onPlatform = true;
		} else if (h == SCR_BROKEN_PIECES) {
			brokenPieces = (int) e.b();
		}
	}

	@Override
	public boolean objectEvent(ScriptListener.ObjectEvent e) {
		// logLever-Anim-logArrow: 1020 인자1 1000 "fire log arrow" → ArrowEmitter/100 BMILogLauncher
		if (e.event() == 1020 && e.arg1() == LOG_DAMAGE) {
			fireLog();
			return true;
		}
		return super.objectEvent(e);
	}

	/** 날고 있는 통나무. */
	private static final class Log {
		final Vector3f pos = new Vector3f(), prev = new Vector3f(), vel = new Vector3f();
		final Matrix4f rot = new Matrix4f();
		float age;
		EffectSystem.Effect fx;
	}

	/**
	 * 통나무 발사 (logLever-Anim-logArrow 1020(1000) → 레버 GO 의 ArrowEmitter/100 → Arrow/060, 0x222710):
	 * 레버 관절 logAim 월드에서 그 +Z 로 Velocity·16 /초 직선 (Gravity Flag 꺼짐), GO "golog" (MDL_log + 불 FWemit1) 이 날아간다.
	 * logAim = (1184, 8, 1912), +Z = (−1, 0, 0) → BMI 앵커 선을 따라 문 쪽으로. 판정: 이전→현재 선분이 BMI 피격 구 (CDV_BMI) 에 닿으면
	 * 적중 (Arrow "Ignore Invulnerable" — 무적 무시), 레벨 면에 닿으면 빗나감, 수명 #117c872e (10초). 명중·충돌 지점에 Hit Effect gologShatter.
	 * 피하기 (999 → Dodge 220 BMIJUMPBACK → 221 TURTLESTUNIDLE) 는 몸을 낮춰 통나무 높이(바닥 + 104) 아래로 숙인다.
	 */
	public void fireLog() {
		if (boss == null || log != null) {
			return;
		}
		float[] aim = logAim();
		Log l = new Log();
		if (aim != null) {
			l.pos.set(aim[12], aim[13], aim[14]);
			l.vel.set(aim[8], aim[9], aim[10]).normalize().mul(logSpeed);
			l.rot.set(aim).setTranslation(0, 0, 0);
			l.rot.normalize3x3();
		} else { // 레버 없음 (다른 데이터): 앵커 선 정면에서 BMI 쪽으로
			Vector3f fwd = new Vector3f((float) -Math.sin(boss.yaw), 0, (float) -Math.cos(boss.yaw));
			l.pos.set(anchor).fma(platBackDist + 300, fwd).add(0, 104, 0);
			l.vel.set(fwd).negate().mul(logSpeed);
			l.rot.rotationY(boss.yaw);
		}
		l.prev.set(l.pos);
		EffectSystem fx = arena.effects();
		if (fx != null) {
			l.fx = fx.play(logGo, out -> {
				out.set(l.rot).setTranslation(l.pos);
				return true;
			});
		}
		log = l;
	}

	/** 레버 GO (gologlever) 관절 logAim 의 월드 행렬 (행벡터 배열, 없으면 null). */
	private float[] logAim() {
		var w = arena.world();
		if (w == null || w.level == null) {
			return null;
		}
		String joint = "logAim";
		for (var p : w.level.layout.placements()) {
			var sk = p.object().skeleton();
			for (int j = 0; j < sk.joints.size(); j++) {
				if (sk.joints.get(j).name().equalsIgnoreCase(joint)) {
					return p.jointWorld()[j];
				}
			}
		}
		return null;
	}

	private void flyLog(EnemyFighter f, float dt) {
		Log l = log;
		l.age += dt;
		l.prev.set(l.pos);
		l.pos.fma(dt, l.vel);
		Vector3f hitAt = null;
		String why = null;
		if (f.hp > 0 && !dodging(f)) {
			for (float[] sp : f.hitVolumes().spheres(-1)) {
				Vector3f c = new Vector3f(sp[0], sp[1], sp[2]);
				if (segDist(l.prev, l.pos, c) <= sp[3] + LOG_RADIUS) {
					hitAt = closest(l.prev, l.pos, c);
					why = "BMI";
					break;
				}
			}
		}
		if (hitAt == null && arena.collision() != null && l.age > 0.05f) {
			Vector3f d = new Vector3f(l.pos).sub(l.prev);
			float len = d.length();
			var h = len > 1e-4f ? arena.collision().raycast(l.prev, d.div(len), len) : null;
			if (h != null) {
				hitAt = new Vector3f(h.point());
				why = "벽";
			}
		}
		if (hitAt == null && l.age >= logLife) {
			hitAt = new Vector3f(l.pos);
			why = "수명";
		}
		if (hitAt == null) {
			return;
		}
		log = null;
		if (l.fx != null) {
			l.fx.stop();
		}
		lastLogEnd.set(hitAt);
		lastLogEndReason = why;
		EffectSystem fx = arena.effects();
		if (fx != null && !why.equals("수명")) {
			Matrix4f m = new Matrix4f(l.rot).setTranslation(hitAt);
			fx.play(logHitGo, EffectSystem.Anchor.of(m)); // gologShatter: 쪼개지는 통나무 (ANM_logshatter 1회) + 불·파편
		}
		if (!why.equals("BMI")) {
			logMisses++;
			return;
		}
		// 적중: 피해 1000 (TakeDamage → 단계 +1) + EV_HIT_REACT 태그 456 → A210 LOGHIT (HP 1001..) / A245 (HP ≤ 1000)
		logHits++;
		takeDamage(LOG_DAMAGE);
		f.combat().fireFrom(CombatInput.EV_HIT, 0, 1, -1, LOG_TAG);
	}

	/**
	 * 피하는 중 (999 → Dodge 220 BMIJUMPBACK → AUTO 221 TURTLESTUNIDLE, 발판 225 → 226): 통나무가 지나간다 [근사 — 원작에서 피한 통나무가 왜
	 * 몸에 닿지 않는지(Arrow 충돌 0x223920 의 대상 거르기)는 미해석. 스크립트는 이때 LogMissCam → 2.5초 뒤 DrHitCam (문에 맞는 통나무) 으로 빗나감을 연출한다].
	 */
	private boolean dodging(EnemyFighter f) {
		if (!f.combat().active()) {
			return false;
		}
		int m = f.combat().move().id();
		return m == dodge || m == 221 || m == 225 || m == 226;
	}

	static float segDist(Vector3f a, Vector3f b, Vector3f q) {
		return closest(a, b, q).distance(q);
	}

	static Vector3f closest(Vector3f a, Vector3f b, Vector3f q) {
		Vector3f ab = new Vector3f(b).sub(a);
		float l2 = ab.lengthSquared();
		float t = l2 < 1e-8f ? 0 : Math.max(0, Math.min(1, new Vector3f(q).sub(a).dot(ab) / l2));
		return new Vector3f(a).fma(t, ab);
	}

	/** 증기를 뿜는 배출구 GO 수 (테스트). −1 = 이펙트 없음. */
	public int ventsEmitting() {
		int n = 0;
		for (EffectSystem.Effect e : vents) {
			if (e == null) {
				return -1;
			}
			n += e.rateOverride < 0 && !e.done() ? 1 : 0;
		}
		return n;
	}

	/** 관절에 붙어 보이는(깨지기 전) 갑옷 조각 GO 수 (테스트). */
	public int piecesShown() {
		int n = 0;
		for (EffectSystem.Effect e : pieces) {
			n += e != null && !e.done() ? 1 : 0;
		}
		return n;
	}

	/** 날고 있는 통나무 위치 (테스트·화면 확인, 없으면 null). */
	public Vector3f logPosition() {
		return log != null ? new Vector3f(log.pos) : null;
	}

	// ---------------- 배출구 증기 / 갑옷 조각 ----------------

	/** 열린 배출구 수 (0x1C1E40): 단계 0·1 에서 개수 0..5 → 0, 1, 2, 3, 5, 8 / 단계 2+ → 0. */
	int openVents() {
		if (stage >= 2) {
			return 0;
		}
		return switch (Math.max(0, Math.min(5, count))) {
			case 0 -> 0;
			case 1 -> 1;
			case 2 -> 2;
			case 3 -> 3;
			case 4 -> 5;
			default -> 8;
		};
	}

	/** 깨진 갑옷 조각 수 (0x1C1D00): 단계 2 에서 개수 0..4 → 같은 수, 5 이상·단계 3 → SCR_BMIBrokenPieces 값 (기본 4, 232 에서 6 → 8). */
	int brokenCount() {
		if (stage < 2) {
			return 0;
		}
		return stage == 2 && count < 5 ? count : brokenPieces;
	}

	/**
	 * 0x1C1D70 (Think): 배출구 i &lt; 열린 수 → 증기 GO 배율 1, 아니면 0 (0x1C09F8: 파티클 이미터 +0xB4).
	 * 0x1C1B10 (갱신, 단계 2+): 조각 i ≥ 깨진 수 → BRK GO 애니 0 초 + BMI 관절 행렬을 그대로 (보임), i &lt; 깨진 수 → 그 자리에서 애니 재생, 끝나면 숨김.
	 */
	private void ventsAndPieces(EnemyFighter f) {
		int open = f.hp > 0 ? openVents() : 0;
		for (int i = 0; i < 8; i++) {
			if (vents[i] != null) {
				vents[i].rateOverride = i < open ? -1 : 0;
			}
		}
		EffectSystem fx = arena.effects();
		if (stage < 2 || f.hp <= 0) {
			return;
		}
		int broken = brokenCount();
		for (int i = 0; i < 8; i++) {
			if (i >= broken) {
				if (pieces[i] == null && !pieceBroken[i] && fx != null) {
					pieces[i] = fx.play(PIECE_GOS[i], jointAnchor(PIECE_JOINTS[i]), 1e9f);
					if (pieces[i] != null) {
						pieces[i].hold = true;
						pieces[i].rateOverride = 0;
					}
				}
			} else if (!pieceBroken[i]) {
				pieceBroken[i] = true;
				piecesBroken++;
				EffectSystem.Effect e = pieces[i];
				Matrix4f at = new Matrix4f();
				if (MagicFx.joints(f).world(PIECE_JOINTS[i], at)) {
					if (e == null && fx != null) {
						e = fx.play(PIECE_GOS[i], EffectSystem.Anchor.of(at));
					}
					if (e != null) {
						e.setAnchor(EffectSystem.Anchor.of(at)); // 깨진 순간의 관절 행렬에서 멈춤 (0x1C1B10: 행렬 복사 중단)
						e.hold = false;
						e.rateOverride = -1;
						float dur = e.def.anim() != null ? Math.max(e.def.anim().duration, 0.1f) : 1f;
						e.setLife(e.time() + dur);
					}
				}
				pieces[i] = null;
			}
		}
	}

	// ---------------- 데모 ----------------

	/**
	 * 데모 (--boss-demo): 영웅을 BMI 정면 근처에 두고 데이터 피해로 단계를 진행한다 — 배출구 5 → 거북 기절 → ○ 올라타기(TimedPress 자동)
	 * → 통나무 기절 → 레버 과제 351 (LevelData0 = 99, 1023) → 통나무 → … 단계 2 갑옷 → 232 → 단계 3 HP 99 → 243 → 통나무 → 245 죽음.
	 */
	@Override
	protected void demo(float dt) {
		EnemyFighter f = boss;
		if (f.hp <= 0 || (demoWait -= dt) > 0) {
			return;
		}
		int m = f.combat().active() ? f.combat().move().id() : -1;
		var c = arena.heroCtl();
		if (demoStep == 0) {
			demoStep = 1;
			placeFront(c, f, 120);
			return;
		}
		if (sync.active() || m == RIDE_FAIL || m == RIDE_WIN) {
			return;
		}
		if (m == 203 || m == 226) {
			placeFront(c, f, 60);
			contact(200, 0); // ○ GRAB → 450 올라타기
			demoWait = 1;
			return;
		}
		if (m == LOG_STUN || m == FINAL_LOG_STUN) {
			if (log == null) {
				pullLever();
				demoWait = 3;
			}
			return;
		}
		if (lock || m == 232 || m == 242 || m == 210 || m == 250 || m == 220) {
			return;
		}
		// 영웅 공격 한 번 = 단계 피해 (배출구 100 / 갑옷 150 / HP 400) — 일반 피격 경로 (무적 기술 중이면 무시됨)
		float hit = stage <= 1 ? ventDamage : stage == 2 ? armorDamage : 400;
		placeFront(c, f, 90);
		contact(DEMO_ATTACK, hit);
		demoWait = 1.2f;
	}

	/** 데모 공격 기술 (영웅 CombatFile 71: 판정 하나). */
	static final int DEMO_ATTACK = 71;
	private boolean introDone;

	/** 데모: 문 앞 볼륨(CloseToDoor) 으로 → 컷신 P03MP06 → Goto BossSpawnPoint → 231 → 보스 스폰. */
	@Override
	protected void beforeSpawnDemo(float dt) {
		if (!introDone) {
			introDone = true;
			placeHero(pointIn("playCutScene-CloseToDoorVol-CloseToDoorEntry", arena.heroCtl().position.y));
		}
	}

	/** 레버 과제 351 완료 = 1023 (99 %, 과제 351) — logLoadGRP-LogEvtSens → LevelData0 = 99 → 통나무 발사·카메라. */
	void pullLever() {
		var w = arena.world();
		if (w != null) {
			w.postEvent(1023, 99, 351, 0, w.player());
		}
	}

	private static void placeFront(devple.gow.engine.anim.HeroController c, EnemyFighter f, float dist) {
		c.position.set(f.pos.x - (float) Math.sin(f.yaw) * dist, f.pos.y, f.pos.z - (float) Math.cos(f.yaw) * dist);
		c.prevPosition.set(c.position);
		c.yaw = f.yaw + (float) Math.PI;
	}

	/** 그대로 보관 (조각 수, HUD 용). */
	public int brokenPieces() {
		return brokenPieces;
	}
}
