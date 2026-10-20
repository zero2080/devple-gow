package devple.gow.engine.boss;

import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.GrabSync;
import devple.gow.engine.combat.RepeatPressQte;
import devple.gow.engine.combat.SyncPull;
import devple.gow.engine.combat.TimedPressQte;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;

import java.util.function.IntPredicate;

/**
 * 보스 동기 기술 (원작 SYNC(18) 분기 + 같은 ID 기술 쌍). 영웅 CombatFile 006(800번대: 히드라 BHA*)·002(605–623: 아레스 MAIBAR*)와
 * 보스 CombatFile 이 같은 기술 ID 를 가진다 — 한쪽이 동기 ID 기술을 시작하면 다른 쪽도 같은 ID 로 들어간다.
 * <ul>
 *   <li>RepeatPress(SCRIPT 0xE513E9CB, idx = 템플릿) 가 있는 기술: {@link RepeatPressQte} 게이지가 두 쪽 진행률. 결과 Pass/Fail Move
 *       는 보스에 요청되고 영웅은 다음 틱에 같은 ID 로 따라간다 (히드라 807 → 809/808, 811 → 812/816, 아레스 606 → 608/607)</li>
 *   <li>TimedPress(0xF980EF69): 이벤트 창 = 프롬프트, 실패 → 같은 창의 REQUEST0 실패 분기 (히드라 810 [0.39–0.47] → 813)</li>
 *   <li>정렬: 원작 0x204BF0 ({@link SyncPull}) — 이벤트 22 또는 19 창을 가진 쪽이 상대를 끈다 (0x205B28 의 0x24 검사, 37 문서 §11.6).
 *       보스 → 영웅: 히드라 802–816, 아레스 600·605–609·621–623, 가족 포옹 865–867, 분신 잡기 860/861, BMI 올라타기 450–455·발판 잡아 던지기 460.
 *       영웅 → 보스: 아레스 620 (영웅 기술의 이벤트 19). 창 밖 (아레스 608 [0.52–1], 607 [0.6–1] …) 은 두 쪽 루트 모션만
 *       ({@link devple.gow.engine.anim.RootMotion}). 끌리는 쪽은 루트 모션 없음 (37 문서 §11.4)</li>
 * </ul>
 */
public final class BossSync {

	/** CombatFile 이벤트 22 (덤프 이름 FLAG_24, 0x206BA0) — {@link SyncPull#EV_SYNC_TARGET}. */
	public static final int EV_SYNC_TARGET = SyncPull.EV_SYNC_TARGET;

	private final IntPredicate syncId;
	private int heroSerial = -1, bossSerial = -1;
	private RepeatPressQte qte;
	private int qteSerial = -1;
	private RepeatPressQte.State lastResult;
	private GrabSync timedHelper;
	/** 마지막 쌍 (기술 ID, 없으면 -1). */
	private int paired = -1;
	/** 원작 0x204BF0 끌기. 섞기 비율 +0x1BC 는 <b>끌리는 쪽</b> combatant 의 것 (0x204EEC 읽기·0x2050A0 쓰기): 동기 연결 시작 때 두 쪽 모두 0.1
	 * (0x209830), 끌리는 프레임마다 ×1.1 — 영웅이 끌릴 때 / 보스가 끌릴 때 따로. */
	private final SyncPull heroPull = new SyncPull(), bossPull = new SyncPull();
	/** 마지막 틱에 끈 쪽: 1 = 보스가 영웅을, 2 = 영웅이 보스를, 0 = 없음 (테스트). */
	private int pulling;
	/** 동기 시작 때 영웅 높이 (수직 루트 모션 하한). */
	private float linkFloor;

	public BossSync(IntPredicate syncId) {
		this.syncId = syncId;
	}

	/** 지금 영웅과 보스가 같은 동기 기술 중인지. */
	public boolean active() {
		return paired >= 0;
	}

	public int pairedMove() {
		return paired;
	}

	public RepeatPressQte qte() {
		return qte;
	}

	public RepeatPressQte.State lastResult() {
		return lastResult;
	}

	/** 진행 중인 타이밍 프롬프트 (HUD). */
	public TimedPressQte timed() {
		return timedHelper != null ? timedHelper.timed() : null;
	}

	public int timedPassed() {
		return timedHelper != null ? timedHelper.timedPassed : 0;
	}

	public int timedFailed() {
		return timedHelper != null ? timedHelper.timedFailed : 0;
	}

	/** 두 쪽 전투 갱신 뒤 매 틱. */
	public void update(Input in, HeroController ctl, Combatant hero, EnemyFighter boss, Tweaks tw, float dt) {
		if (timedHelper == null) {
			timedHelper = new GrabSync(ctl, hero);
		}
		Combatant bc = boss.combat();
		int hm = hero.active() ? hero.move().id() : -1;
		int bm = bc.active() ? bc.move().id() : -1;
		boolean heroNew = hero.moveSerial() != heroSerial, bossNew = bc.moveSerial() != bossSerial;
		if (bossNew && syncId.test(bm) && hm != bm) {
			follow(hero, bm);
		} else if (heroNew && syncId.test(hm) && hm != bm && !boss.dead()) {
			follow(bc, hm);
		}
		hm = hero.active() ? hero.move().id() : -1;
		bm = bc.active() ? bc.move().id() : -1;
		heroSerial = hero.moveSerial();
		bossSerial = bc.moveSerial();
		paired = syncId.test(bm) && hm == bm ? bm : -1;
		pulling = 0;
		if (paired < 0) {
			qte = null;
			if (heroPull.linked()) {
				ctl.rootHold = false;
				boss.rootHold = false;
			}
			heroPull.unlink();
			bossPull.unlink();
			return;
		}
		if (!heroPull.linked()) {
			heroPull.link(); // 0x209830: 동기 연결이 새로 생길 때 +0x1BC = 0.1 (자기·상대)
			bossPull.link();
			linkFloor = ctl.position.y;
		}
		// 원작 0x205B28: 0x2441E0(+0x1AC, 0x24) ≠ 0 → 0x204BF0. 이벤트 22 (0x20) 와 이벤트 19 (0x4) 창 모두 그 기술을 가진 쪽이 상대를 끈다
		// (37 문서 §11.1, §11.6). 보스 데이터: 히드라 802/806/810 (22)·803–805/807–809/811–816 (19), 아레스 600/608 (22)·605/606/607/609 (19)·
		// 621–623 (19) 은 보스가, 영웅 620 MAIBARSHOWDOWN (19) 은 영웅이 아레스를, 가족 865–867·분신 860/861·BMI 450–455·460 (19) 는 보스 쪽이 영웅을 끈다.
		// 두 쪽이 같은 프레임에 창을 가진 동기는 보스 데이터에 없다 — 엔진은 보스 쪽 우선 [근사: 원작은 서로 회전만 끌고 위치는 둘 다 자기 루트 모션]
		boolean bossPulls = SyncPull.linkedPullWindow(bc) != null;
		boolean heroPulls = !bossPulls && SyncPull.linkedPullWindow(hero) != null;
		// 끌리는 쪽은 루트 모션 없음 (0x1EC058). 원작은 상대 이벤트 창을 같은 프레임에 갱신해 (0x1EBF8C → 0x206030) 끌리는 첫 프레임부터
		// D = 단위 — 엔진은 두 쪽 갱신이 끝난 뒤라 이번 틱 루트 모션을 되돌리고 다음 틱부터는 rootHold 로 막는다.
		// 끄는 쪽은 자기 루트 모션 그대로 (0x1EBFB8: 0x20 이면 변위 0 + D, 0x4 만이면 변위 = 상대 combatant +0x10 + D — +0x10 은 상대가 지난 갱신의
		// 자유 이동 0x1ECD60 에서 충돌이 깎은 양 (0x1ECE48) 이라 끌린 상대가 벽에 막히면 끄는 쪽도 그만큼 따라간다. 엔진의 끌기는 충돌 없이 위치를 정하므로 0)
		if (bossPulls && !ctl.rootHold) {
			ctl.undoRoot();
		}
		if (heroPulls && !boss.rootHold) {
			boss.undoRootMotion();
		}
		ctl.rootHold = bossPulls;
		boss.rootHold = heroPulls;
		if (bossPulls) {
			// 보스 기술의 이벤트 22/19 창: 보스가 영웅을 끈다 (비율 = 끌리는 쪽 +0x1BC, 0x2050A0)
			float[] y = {ctl.yaw};
			heroPull.step(SyncPull.target(boss.pos, boss.yaw, boss.animator(), boss.scale, ctl.animator(), 1f), ctl.position, y);
			ctl.yaw = y[0];
			ctl.prevPosition.set(ctl.position);
			ctl.vy = 0;
			pulling = 1;
		} else if (heroPulls) {
			// 영웅 기술의 이벤트 22/19 창: 영웅이 보스를 끈다 (같은 0x204BF0, 자기 = 영웅 — 아레스 620)
			float[] y = {boss.yaw};
			bossPull.step(SyncPull.target(ctl.position, ctl.yaw, ctl.animator(), 1f, boss.animator(), boss.scale), boss.pos, y);
			boss.yaw = y[0];
			boss.prevPos.set(boss.pos);
			pulling = 2;
		}
		if (!bossPulls) {
			// 영웅이 끌리지 않는 프레임: 영웅 루트 모션 (0x1F7110 → 0x1EBD78). 수평·회전은 HeroController, 수직은 여기서 (동기 중 물리가 꺼져 있다 —
			// 608 창 뒤 zeroJoint y 62 → 0 로 내려옴). 동기 시작 높이 아래로는 내려가지 않게 [근사: 원작은 지상 이동이 바닥에 붙임]
			ctl.position.y = Math.max(linkFloor, ctl.position.y + ctl.rootDy());
			ctl.prevPosition.y = ctl.position.y;
			ctl.vy = 0;
		}
		// 연타: 보스 기술(또는 영웅 기술)의 SCR_RepeatPress
		var rp = bc.activeScript(RepeatPressQte.SCR_REPEAT_PRESS);
		if (rp == null) {
			rp = hero.activeScript(RepeatPressQte.SCR_REPEAT_PRESS);
		}
		if (rp != null && (qte == null || qteSerial != bc.moveSerial())) {
			qte = new RepeatPressQte(boss, hero, ctl.animator(), tw, rp.index());
			qteSerial = bc.moveSerial();
		}
		if (qte != null && qteSerial == bc.moveSerial()) {
			RepeatPressQte.State s = qte.update(in, dt);
			if (s == RepeatPressQte.State.RUNNING) {
				hero.setProgress(qte.meter());
			} else {
				lastResult = s;
			}
		}
		// 타이밍 버튼
		// TimedPress 는 보통 보스 기술에, BMI 올라타기(451–453)처럼 영웅 기술에만 있으면 영웅 쪽 (실패 → 영웅 REQUEST0 → 454)
		timedHelper.updateTimed(hasTimed(bc) || !hasTimed(hero) ? bc : hero, tw, in);
	}

	/** 마지막 틱의 끌기: 1 = 보스가 영웅을, 2 = 영웅이 보스를 (원작 0x204BF0), 0 = 없음. */
	public int pulling() {
		return pulling;
	}

	/** zeroJoint 기준 synchJoint 행렬 — {@link SyncPull#synchRel}. */
	static org.joml.Matrix4f synchRel(devple.gow.engine.anim.Animator an, float scale) {
		return SyncPull.synchRel(an, scale);
	}

	/** 현재 자세의 zeroJoint 로컬 위치 (없으면 0). */
	static float[] zero(devple.gow.engine.anim.Animator an) {
		int j = an.skeleton().indexOf("zeroJoint");
		if (j < 0 || an.current() == null) {
			return new float[3];
		}
		float[] p = an.current().sample(an.skeleton(), an.time(), an.current().looping()).pos[j];
		return new float[]{p[0], p[1], p[2]};
	}

	private static boolean hasTimed(Combatant c) {
		return c.active() && c.move().events().stream().anyMatch(e -> e.type() == 8 && e.hash() == TimedPressQte.SCR_TIMED_PRESS);
	}

	/** 현재 자세의 synchJoint 행렬 (없으면 null). */
	private static float[] synch(devple.gow.engine.anim.Animator an) {
		int j = an.skeleton().indexOf("synchJoint");
		return j < 0 || an.current() == null ? null : an.pose().world(an.skeleton())[j];
	}

	/**
	 * 보스 원점 → 영웅 원점 (월드, 수평): R(보스 요)·(S_보스 − S_영웅) (애니 로컬, 보스 배율 무시). 두 synchJoint 의 <b>위치</b>가 겹치고 방향은 같다.
	 * 아레스 MAIBARGrab* 는 둘 다 (0,0,−32) → 같은 원점, 포옹 MAIEVIHug* 는 가족 (0,0,−16) / 영웅 (0,0,−32) → 영웅이 16 뒤.
	 */
	static org.joml.Vector3f synchOffset(EnemyFighter boss, devple.gow.engine.anim.Animator hero) {
		float[] sb = synch(boss.animator()), sh = synch(hero);
		org.joml.Vector3f v = new org.joml.Vector3f();
		if (sb != null && sh != null) {
			v.set(sb[12] - sh[12], 0, sb[14] - sh[14]).rotateY(boss.yaw); // 배율 무시: 아레스(1.15)는 배율을 곱하면 손목 간격 7.0 → 9.9 로 나빠짐
		}
		return v;
	}

	private static void follow(Combatant c, int move) {
		if (!c.request(move)) {
			c.forceMove(move);
		}
	}
}
