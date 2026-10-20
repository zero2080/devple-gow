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
 *   <li>정렬: 이벤트 22 창 안이면 원작 0x204BF0 ({@link SyncPull}) — 그 기술을 가진 쪽이 상대를 끈다 (보스 → 영웅: 히드라 802/806/810,
 *       아레스 600/608; 영웅 → 보스도 같은 규칙). 창 밖은 원작이 위치를 건드리지 않는다 (이벤트 19 FLAG_4 = 동기 유지만).
 *       그 구간에 쓰는 {@link Align} 은 원작에 없는 엔진 대체 규칙이다 (37 문서 §11)</li>
 * </ul>
 */
public final class BossSync {

	/**
	 * 이벤트 22 창 <b>밖</b>의 엔진 대체 정렬 [근사]. 원작은 창 밖에서 위치를 정렬하지 않는다 (이벤트 19 = 동기 유지만, 0x206B44) —
	 * 잡기 적중 위치 + 두 쪽 루트 모션(이동·회전, 엔진은 영웅 수평 이동만)이 관계를 만든다. 디스크 전체 CombatFile 에서 이벤트 22 가 없는
	 * 동기 (가족 포옹 865–867, 분신 잡기 860–863, BMI 올라타기 450–455) 는 엔진이 그 관계를 대신 맞춘다:
	 * <ul>
	 *   <li>{@link #NONE}: 정렬 없음 (원작 그대로 — 히드라 807/809…, 아레스 605–607/609)</li>
	 *   <li>{@link #BOSS_TO_HERO}: 상대를 영웅 쪽에, synchJoint 위치 겹침·같은 방향 ({@link #synchOffset}, 분신 잡기)</li>
	 *   <li>{@link #SYNCH}: 영웅을 고정 상대 쪽에, 같은 규칙 (가족 포옹)</li>
	 *   <li>{@link #ZERO}: 영웅 = 상대 + R(요)·(zero_영웅 − zero_상대) (높이 포함, BMI 올라타기: 영웅 zeroJoint 가 등 높이 212 로 기어오름 — 엔진에 수직 루트 모션이 없어서)</li>
	 * </ul>
	 */
	public enum Align { NONE, BOSS_TO_HERO, SYNCH, ZERO }

	/** CombatFile 이벤트 22 (덤프 이름 FLAG_24, 0x206BA0) — {@link SyncPull#EV_SYNC_TARGET}. */
	public static final int EV_SYNC_TARGET = SyncPull.EV_SYNC_TARGET;

	private final IntPredicate syncId;
	private final Align align;
	private int heroSerial = -1, bossSerial = -1;
	private RepeatPressQte qte;
	private int qteSerial = -1;
	private RepeatPressQte.State lastResult;
	private GrabSync timedHelper;
	/** 마지막 쌍 (기술 ID, 없으면 -1). */
	private int paired = -1;
	/** 원작 0x204BF0 끌기 (섞기 비율 +0x1BC: 동기 연결 시작 0.1, 끄는 프레임마다 ×1.1). */
	private final SyncPull pull = new SyncPull();
	/** 마지막 틱에 끈 쪽: 1 = 보스가 영웅을, 2 = 영웅이 보스를, 0 = 없음 (테스트). */
	private int pulling;
	/** 정렬 없는 구간의 영웅 수직 루트 모션 (zeroJoint y 변화량): 직전 클립·y. */
	private Object rootClip;
	private float rootY;

	public BossSync(IntPredicate syncId, Align align) {
		this.syncId = syncId;
		this.align = align;
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
			pull.unlink();
			rootClip = null;
			return;
		}
		if (!pull.linked()) {
			pull.link(); // 0x209830: 동기 연결이 새로 생길 때 +0x1BC = 0.1 (자기·상대)
		}
		var hc = ctl.animator().current();
		float zy = zero(ctl.animator())[1];
		boolean sameClip = hc != null && hc == rootClip;
		float dy = sameClip ? zy - rootY : 0;
		rootClip = hc;
		rootY = zy;
		if (SyncPull.pullWindow(bc) != null) {
			// 보스 기술의 이벤트 22 창: 보스가 영웅을 끈다 (히드라 802/806/810, 아레스 600/608)
			float[] y = {ctl.yaw};
			pull.step(SyncPull.target(boss.pos, boss.yaw, boss.animator(), boss.scale, ctl.animator(), 1f), ctl.position, y);
			ctl.yaw = y[0];
			ctl.prevPosition.set(ctl.position);
			ctl.vy = 0;
			pulling = 1;
		} else if (SyncPull.pullWindow(hero) != null) {
			// 영웅 기술의 이벤트 22 창: 영웅이 보스를 끈다 (같은 0x204BF0, 자기 = 영웅)
			float[] y = {boss.yaw};
			pull.step(SyncPull.target(ctl.position, ctl.yaw, ctl.animator(), 1f, boss.animator(), boss.scale), boss.pos, y);
			boss.yaw = y[0];
			boss.prevPos.set(boss.pos);
			pulling = 2;
		} else if (align == Align.NONE) {
			// 원작: 정렬 없음 — 영웅은 자기 루트 모션 (0x1F7110 zeroJoint 변화량). 수평은 HeroController, 수직은 여기서
			// (동기 중 물리가 꺼져 있어 608 창이 끝난 뒤 크레토스가 들린 높이 +30 에 떠 있었다 — zeroJoint y 62 → 0 로 내려옴)
			ctl.position.y += dy;
			ctl.prevPosition.y = ctl.position.y;
		} else if (align == Align.BOSS_TO_HERO) {
			boss.yaw = ctl.yaw;
			org.joml.Vector3f v = synchOffset(boss, ctl.animator());
			boss.pos.set(ctl.position).sub(v);
			boss.prevPos.set(ctl.prevPosition).sub(v);
		} else if (align == Align.ZERO) {
			float[] zh = zero(ctl.animator()), zb = zero(boss.animator());
			org.joml.Vector3f v = new org.joml.Vector3f(zh[0] - zb[0], zh[1] - zb[1], zh[2] - zb[2]).mul(boss.scale).rotateY(boss.yaw);
			ctl.position.set(boss.pos).add(v);
			ctl.position.y = Math.max(boss.pos.y, ctl.position.y); // 실패 애니(454)는 zeroJoint 가 바닥 아래로 내려감
			ctl.prevPosition.set(ctl.position);
			ctl.yaw = boss.yaw;
			ctl.vy = 0;
		} else if (align == Align.SYNCH) {
			// 영웅 = 보스 · T(S_보스 위치) · T(−S_영웅 위치), 같은 방향 (고정 보스 쪽으로 영웅) [데이터: 접촉 검사, 37 문서 §11]
			org.joml.Vector3f v = synchOffset(boss, ctl.animator());
			ctl.position.set(boss.pos).add(v);
			ctl.prevPosition.set(ctl.position);
			ctl.yaw = boss.yaw;
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
