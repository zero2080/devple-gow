package devple.gow.engine.boss;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.input.Input;
import devple.gow.engine.script.ScriptActor;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * ATHN08B — 크레토스의 집: 분신(악한 크레토스) 웨이브로부터 가족을 지키는 싸움 (37 문서 §9). 원작은 전용 클래스가 아니라
 * 일반 Enemy1 + CombatFile + 레벨 스크립트다 [확실: 데이터]:
 * <pre>
 *  가족 660 "family" (스켈레톤 xmc, CombatFileSet 660 = 660/661/662, AI Mode 1, DTre 001 비어 있음, 이동 애니 없음)
 *    familyEnt-family60 SpawnAtStart (그룹 60) → 0.1초 뒤 1019(0) EvtForce → M0 XMCSTANDIDLE(무적) → AUTO → M1 XMCTAUNT (잡기 원)
 *    분신(공격자 class 666)에게 맞으면 661 A130/A131 → XMCHITMEDIUM/HARD, 영웅 공격은 반응 태그 0..20 전부 (block)
 *    HP 0 → A150 EV_DEATH → M150 XMCDEATH2 → 1035(150) → familyEnt-move150: 카메라 camWifeDeatho, A07MP04 → 실패 화면
 *    영웅 ○ GRAB(자세 200) → 가족 M1 EV_HIT_REACT tag 200 → 865, 영웅 HIT_CONFIRM tgt=660 → 865 MAIEVIHUGSTART:
 *    포옹 = 영웅 SELF_HP +2 ×12, 가족 SELF_HP −2 ×12 → 866 루프(○ 유지, 영웅 HP ≥ 11, 가족 HP &lt; 99: 프레임마다 1씩 옮김) → 867
 *    1035(865) → hugMsg: 메시지 454
 *  분신 666/667/668 (evi, 세트 666, AI Mode 3, DTre "토큰 있으면 칼날 공격 / 없으면 back off"), 궁수 669 (세트 669, 번개 20)
 *    evilsWAVE1..4: SpawnEnemy MaximumCount·Respawn·Condition(LevelData 카운터) → DestructionSensor 가 LevelData 를 올림
 *    웨이브 4 처치 25(LevelData30 == LevelData31) → winEnt: A07MP05 → Warp (giveAthenaSword → LoadForWarp athn08c)
 *  SNDFakeEnemyCreatorEvilKratos 670 = 소리 뱅크 전용
 * </pre>
 * 엔진 쪽: 가족을 보호 대상(본체)으로, 분신 공격이 가족에게도 판정(Combatant 휘말림), 영웅 공격은 가족에게 피해 없음(잡기 자세 200 제외),
 * 가족 위치·방향 고정 (이동 애니가 없음), 포옹 동기 = synchJoint 정렬 ({@link BossSync.Align#SYNCH}).
 */
public final class EvilKratosFight extends BossFight {

	public static final String LEVEL = "ATHN08B";
	public static final int FAMILY = 660, HUG_START = 865, HUG_LOOP = 866, HUG_EXIT = 867, FAMILY_DEATH = 150;
	/** 분신이 크레토스를 잡음: 분신 M0 EVIGRAB 적중 → 영웅 A860 (EV_HIT_REACT 태그 860) / 분신 HIT_CONFIRM → 860 → 861, 영웅 861 은
	 * "AUTO fall&gt;2" (스틱 흔들기 게이지 &gt; 2) → 862 탈출, 아니면 창 끝 → 863 (영웅 SELF_HP 10). */
	public static final int CLONE_GRAB_START = 860, CLONE_GRAB_LOOP = 861, CLONE_GRAB_WIN = 862, CLONE_GRAB_LOSE = 863;
	static final int[] CLONES = {666, 667, 668, 669};
	/** 영웅 잡기 자세 (GRAB SET_1B0 idx=200). */
	static final int GRAB_STANCE = 200;

	private final Vector3f familyPos = new Vector3f();
	private float familyYaw;
	private boolean familyDead;
	private int hugs;
	private int demoStep;
	private float demoWait;
	/** 분신 잡기 동기 (860–863, 같은 ID 쌍): 분신을 영웅 원점에 (synchJoint 둘 다 (0,0,−32) → 같은 원점). */
	private final BossSync cloneSync = new BossSync(id -> id >= CLONE_GRAB_START && id <= CLONE_GRAB_LOSE);
	private EnemyFighter grabber;
	private int heroSerial = -1;
	/** 분신 잡기 결과 기록 (테스트): 탈출 / 실패. */
	public int grabEscapes, grabLosses;

	EvilKratosFight(Path rom, WadFile permk) throws IOException {
		super(LEVEL);
		var assets = new WadAssets(rom.resolve(LEVEL + ".WAD"));
		kinds.put(FAMILY, BossKinds.load(assets, permk, List.of(), "xmc", FAMILY, FAMILY));
		for (int t : CLONES) {
			kinds.put(t, BossKinds.load(assets, permk, List.of(), "evi", t, t == 669 ? 669 : 666));
		}
		silent.add(670);
		bossTemplates.add(FAMILY);
		sync = new BossSync(id -> id >= HUG_START && id <= HUG_EXIT);
	}

	/** 가족 (보호 대상). */
	public EnemyFighter family() {
		return boss;
	}

	public int hugs() {
		return hugs;
	}

	/** 격파할 본체가 없다 (가족은 지킬 대상) — 순회 검증은 분신 웨이브를 일반 적처럼 처치해 LevelData30 = 25 로 이긴다. */
	@Override
	public boolean headless() {
		return false;
	}

	@Override
	public void onSpawn(EnemyFighter f, ScriptActor a, int template, BossArena arena) {
		super.onSpawn(f, a, template, arena);
		if (template != FAMILY) {
			// 분신의 칼날·번개가 가족에게도 닿는다 (661 A130/A131 tgt=666 피격 반응)
			f.combat().setBystanders(() -> boss != null && boss.hp > 0 ? List.of(boss) : List.of());
		}
	}

	@Override
	protected void bind(EnemyFighter f, ScriptActor a) {
		familyPos.set(f.pos);
		familyYaw = f.yaw;
		var hero = arena.heroCombat();
		f.damageFrom = at -> at != hero || at.stanceTag() == GRAB_STANCE;
	}

	/** 크레토스를 잡고 있는 분신 (없으면 null). */
	public EnemyFighter grabber() {
		return grabber;
	}

	public BossSync cloneSync() {
		return cloneSync;
	}

	@Override
	public boolean heroLocked() {
		return super.heroLocked() || cloneSync.active();
	}

	@Override
	public boolean claimsGrab(EnemyFighter target) {
		return super.claimsGrab(target) || (target != null && target == grabber);
	}

	private static boolean cloneGrab(devple.gow.engine.combat.Combatant c) {
		return c.active() && c.move().id() >= CLONE_GRAB_START && c.move().id() <= CLONE_GRAB_LOSE;
	}

	/** 분신 잡기 동기: 잡은 분신을 찾아 BossSync (같은 ID 쌍) 로 영웅과 묶는다. */
	private void cloneGrabSync(Input in, float dt) {
		var hero = arena.heroCombat();
		if (grabber == null) {
			for (EnemyFighter m : minions.keySet()) {
				if (!m.dead() && cloneGrab(m.combat())) {
					grabber = m;
					break;
				}
			}
		}
		if (grabber == null) {
			return;
		}
		cloneSync.update(in, arena.heroCtl(), hero, grabber, kindFor(grabber.ai1()).tweaks(), dt);
		int serial = hero.moveSerial(), nm = hero.active() ? hero.move().id() : -1;
		if (serial != heroSerial) {
			heroSerial = serial;
			if (nm == CLONE_GRAB_WIN) {
				grabEscapes++;
			} else if (nm == CLONE_GRAB_LOSE) {
				grabLosses++;
			}
		}
		if (!cloneSync.active() && !cloneGrab(grabber.combat()) && !cloneGrab(hero)) {
			grabber = null;
		}
	}

	@Override
	protected void tick(Input in, float dt) {
		cloneGrabSync(in, dt);
		EnemyFighter f = boss;
		// 가족은 제자리 (xmc 애니 세트에 이동 클립이 없고 DTre 001 이 비어 있음)
		f.pos.set(familyPos);
		f.prevPos.set(familyPos);
		f.yaw = familyYaw;
		int m = f.combat().active() ? f.combat().move().id() : -1;
		if (m == HUG_START && sync.active() && !phase().equals("포옹")) {
			hugs++;
		}
		if (f.hp <= 0 && m != FAMILY_DEATH && !familyDead) {
			// 대기 순환 M0/M1 은 EV_DEATH 가 (block) — 엔진은 기술 분기를 루트 액션보다 먼저 보므로 661 A150(EV_DEATH → M150
			// XMCDEATH2) 이 안 걸린다. 원작 우선순위 미확인 [추정] → HP 0 이면 A150 의 사망 기술로.
			f.combat().forceMove(FAMILY_DEATH);
			m = FAMILY_DEATH;
		}
		if (f.hp <= 0 || m == FAMILY_DEATH) {
			if (!familyDead) {
				familyDead = true;
				setPhase("가족 사망");
			}
			return;
		}
		if (!f.combat().active()) {
			// 포옹 뒤 등: 원작은 865 SYNC → M0 과 스크립트 1019(0) 로 대기 순환(M0 → M1 잡기 원)으로 돌아감
			f.combat().request(0);
		}
		Object a = arena.world() != null ? arena.world().host().levelData().get(30) : null;
		Object b = arena.world() != null ? arena.world().host().levelData().get(31) : null;
		if (a instanceof Number x && b instanceof Number y && x.intValue() >= y.intValue() && y.intValue() > 0) {
			markDefeated(); // 웨이브 4 처치 수 = 25 → winEnt
			return;
		}
		if (sync.active()) {
			setPhase("포옹");
		} else {
			setPhase(String.format("가족 보호 (HP %.0f)", f.hp));
		}
	}

	/** 데모: 영웅을 가족 앞에, 8초 뒤 가족 HP 60 (맞은 셈) → ○ 잡기 → 포옹 (865 만, ○ 유지 입력은 플레이어 몫). */
	@Override
	protected void demo(float dt) {
		EnemyFighter f = boss;
		var c = arena.heroCtl();
		if (demoStep == 0) {
			demoStep = 1;
			c.position.set(familyPos.x - (float) Math.sin(familyYaw) * 48, c.position.y, familyPos.z - (float) Math.cos(familyYaw) * 48);
			c.prevPosition.set(c.position);
		}
		if ((demoWait -= dt) > 0 || sync.active() || f.hp <= 0) {
			return;
		}
		if (demoTime > 8 && hugs == 0) {
			f.hp = Math.min(f.hp, 60); // 데모: 분신에게 맞은 셈 치고 포옹을 보여 줌
		}
		if (f.hp < 70 && f.combat().active() && f.combat().move().id() == 1) {
			contact(GRAB_STANCE, 0);
			demoWait = 2;
		}
	}
}
