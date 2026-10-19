package devple.gow.engine.boss;

import devple.gow.engine.combat.CombatInput;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptListener;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
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
 * 엔진 쪽 근사: 위치는 애니 zeroJoint 변화량(영웅과 같은 규칙), 통나무는 1020(1000) 뒤 비행 시간 후 무적이 아니면 적중 [근사: 궤적 미구현],
 * 배출구 증기·갑옷 조각·HUD EnemyState·카메라 대상 슬롯은 미구현.
 */
public final class BmiFight extends BossFight {

	public static final String LEVEL = "PAND03G";
	public static final int TEMPLATE = 200;
	static final String NODE = "/GlobGame/BMI/";
	public static final int SCR_NO_DODGE = 0x4ca9cf75, SCR_TURN_OFF_VENTS = 0xd4451ecd, SCR_BROKEN_PIECES = 0x27c3c4db,
			SCR_ON_PLATFORM = 0xf84955d3;
	/** 통나무 Arrow/060 "BMILogLauncher": Damage 1000, Context 456, Velocity 150 (×16/초). */
	public static final int LOG_DAMAGE = 1000, LOG_TAG = 456, RIDE_MOUNT = 450, RIDE_FAIL = 454, RIDE_WIN = 455, LOG_STUN = 208,
			FINAL_LOG_STUN = 243, DEATH = 245;
	static final float HIGH = 96f;

	// ---- /GlobGame/BMI ----
	final float ventDamage, armorDamage, noArmorHp;
	final int walkForward, leapBack, dodge, staggerFirst, stagger, stunned, armorStagger, armorStunned, noArmorStunned, platStaggerFirst,
			platStagger, platStunned, platArmorStagger, platArmorStunned, platNoArmorStunned, platMount, platDismount, victory, platVictory,
			retreat;
	final float behindClose, closeMid, midFar, farWalk, centerTan, retreatDist, platDist, platBackDist, platCenterTan;
	/** 단계표 [단계][Close L,C,R, Mid L,C,R, Far L,C,R, Platform L,C,R,Back]. */
	final int[][] table = new int[4][13];
	final float[] idle = new float[4], platIdle = new float[4];

	// ---- 상태 (객체 0x360..0x3F0) ----
	private final Vector3f anchor = new Vector3f();
	private int stage, count, brokenPieces = 4;
	private float accum, timer;
	private boolean onPlatform, firstVent = true, lock;
	private Animation lastClip;
	private float lastTime;
	/** 통나무 비행 남은 시간 (&lt; 0 = 없음). */
	private float logFlight = -1;
	/** 이번 레버 주기에 999 로 피했음 (Dodge 220 점프백 — 통나무 궤적 밖). */
	private boolean dodged;
	/** 기록 (테스트): 통나무 적중 / 빗나감. */
	public int logHits, logMisses;
	private int demoStep;
	private float demoWait;

	BmiFight(Path rom, WadFile permk) throws IOException {
		super(LEVEL);
		var kind = BossKinds.load(rom.resolve(LEVEL + ".WAD"), permk, List.of(), "bmi", TEMPLATE, TEMPLATE);
		kinds.put(TEMPLATE, kind);
		silent.addAll(List.of(202, 257)); // AB2/AMISoundbankLoad
		bossTemplates.add(TEMPLATE);
		sync = new BossSync(id -> id >= RIDE_MOUNT && id <= RIDE_WIN, BossSync.Align.ZERO);
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
		String[] keys = {"Close Left", "Close Center", "Close Right", "Mid Left", "Mid Center", "Mid Right", "Far Left", "Far Center",
				"Far Right", "Platform Left", "Platform Center", "Platform Right", "Platform Back"};
		for (int st = 0; st < 4; st++) {
			for (int k = 0; k < keys.length; k++) {
				table[st][k] = s(tw, "S" + st + " " + keys[k], 0);
			}
			idle[st] = tw.f(NODE, "S" + st + " Idle Time", 0);
			platIdle[st] = tw.f(NODE, "S" + st + " Platform Idle Time", 0);
		}
	}

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
		anchor.set(f.pos); // 원작은 첫 갱신에 GO 부모 로케이터 위치 (플래그 0x10) — 스폰 위치로 근사
		f.damageHook = this::takeDamage;
		lastClip = null;
	}

	// ---------------- Think 0x1C0A48 ----------------

	@Override
	protected void tick(Input in, float dt) {
		EnemyFighter f = boss;
		rootMotion(f);
		if (logFlight >= 0 && (logFlight -= dt) < 0) {
			logArrives(f);
		}
		int m = f.combat().active() ? f.combat().move().id() : -1;
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

	/** 기술 애니 zeroJoint 변화량 = 몸 이동 (영웅과 같은 규칙, 25 문서). 클립이 바뀐 프레임은 이동 없음 (BMI 애니는 zeroJoint 가 절대 위치). */
	private void rootMotion(EnemyFighter f) {
		var an = f.animator();
		Animation clip = an.current();
		float t = an.time();
		var sk = an.skeleton();
		int zj = sk.indexOf("zeroJoint");
		if (clip != null && clip == lastClip && zj >= 0 && clip.hasSkeletalData()) {
			float z0 = zero(clip, sk, lastTime, zj), z1 = zero(clip, sk, t, zj);
			float dz = t >= lastTime ? z1 - z0 : (zero(clip, sk, clip.duration, zj) - z0) + (z1 - zero(clip, sk, 0, zj));
			float x0 = zeroX(clip, sk, lastTime, zj), x1 = zeroX(clip, sk, t, zj);
			float dx = t >= lastTime ? x1 - x0 : 0;
			Vector3f v = new Vector3f(dx, 0, dz).mul(f.scale).rotateY(f.yaw);
			f.pos.add(v);
		}
		lastClip = clip;
		lastTime = t;
	}

	private static float zero(Animation a, devple.gow.tools.anim.Skeleton sk, float t, int zj) {
		return a.sample(sk, Math.min(t, a.duration), false).pos[zj][2];
	}

	private static float zeroX(Animation a, devple.gow.tools.anim.Skeleton sk, float t, int zj) {
		return a.sample(sk, Math.min(t, a.duration), false).pos[zj][0];
	}

	// ---------------- TakeDamage 0x1C1838 ----------------

	/** vtable +0x40 재정의. 일반 피격(0x204690)과 스크립트 1039 둘 다 여기로. */
	void takeDamage(float dmg) {
		if (dmg >= LOG_DAMAGE) {
			stage++; // StageUp 0x1C1430
			accum = 0;
			count = 0;
			return;
		}
		if (dmg == 999) {
			if (!noDodge()) {
				timer = 0;
				req(dodge, 0);
				onPlatform = false;
				dodged = true;
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
			return;
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
	}

	/** 0x1C1678: 갑옷 단계 (5 번째 = 갑옷 파괴 232, 단계 3). */
	private void armor() {
		if (accum < armorDamage) {
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
	}

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

	/**
	 * 통나무 발사: 비행 시간 뒤 적중 판정 [근사: 원작 Arrow 궤적(Velocity 150·16/초, Aim Joint logAim) 대신 — 레버 99 % 0.5초 뒤 스크립트
	 * 1039(999) 로 피했으면(점프백 220) 빗나감, NoDodge(기절 연쇄·통나무 기절) 중이면 적중. Arrow "Ignore Invulnerable" 이라 무적은 안 봄].
	 */
	public void fireLog() {
		if (boss == null || logFlight >= 0) {
			return;
		}
		float dist = boss.pos.distance(anchor) + 300f;
		logFlight = dist / (150f * 16f);
	}

	/** 통나무 도착: 피하지 않았으면 피해 1000 (단계 +1) + EV_HIT_REACT 태그 456 → A210 LOGHIT / A245 (HP ≤ 1000). */
	private void logArrives(EnemyFighter f) {
		logFlight = -1;
		boolean miss = dodged;
		dodged = false;
		if (f.hp <= 0 || miss) {
			logMisses++;
			return;
		}
		logHits++;
		takeDamage(LOG_DAMAGE);
		f.combat().fireFrom(CombatInput.EV_HIT, 0, 1, -1, LOG_TAG);
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
			if (logFlight < 0) {
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
