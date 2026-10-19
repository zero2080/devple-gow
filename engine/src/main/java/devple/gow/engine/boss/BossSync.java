package devple.gow.engine.boss;

import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.GrabSync;
import devple.gow.engine.combat.RepeatPressQte;
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
 *   <li>정렬: {@link Align#BOSS_TO_HERO} = 사람 크기 보스(아레스)를 영웅 기준점에 (GrabSync 와 같은 규칙),
 *       {@link Align#NONE} = 고정 보스(히드라, 바다에 고정) — 동기 애니는 각자 원점 기준 [추정]</li>
 * </ul>
 */
public final class BossSync {

	/**
	 * 동기 애니 정렬 = 두 synchJoint 의 <b>위치</b>를 겹치고 <b>방향은 같게</b> ({@link #synchOffset}). 회전 성분(아레스 보스 180° / 영웅 0°)은
	 * 정렬에 쓰지 않는다 [추정: 상대 방향 표식]. 근거 [데이터: 관절 접촉 검사, 37 문서 §11] — 영웅 손목 ↔ 상대 관절 평균 거리:
	 * 아레스 MAIBARGrab* (synch 둘 다 (0,0,−32) → 같은 원점) 7.0, synchJoint 행렬을 통째로 맞춘 "64 앞 마주 보기" 25;
	 * 포옹 MAIEVIHug* (가족 (0,0,−16), 영웅 (0,0,−32) → 영웅이 16 뒤·같은 방향) 6.4, 예전 "정면 48 마주 봄" 약 25.
	 * BOSS_TO_HERO = 보스를 영웅 쪽에 (아레스), SYNCH = 영웅을 고정 보스 쪽에 (가족 포옹), HERO_TO_BOSS = 히드라 (zeroJoint 규칙),
	 * ZERO = 영웅 = 보스 + R(요)·(zero_영웅 − zero_보스) (높이 포함, BMI 올라타기 BMIRide*: 영웅 zeroJoint 가 높이 0 → 212 로 등에 기어오름,
	 * 몸 관절은 제자리 — 같은 원점이면 크레토스가 BMI 다리 사이 바닥에 남는다) [추정: 화면 확인].
	 */
	public enum Align { NONE, BOSS_TO_HERO, HERO_TO_BOSS, SYNCH, ZERO }

	private final IntPredicate syncId;
	private final Align align;
	private int heroSerial = -1, bossSerial = -1;
	private RepeatPressQte qte;
	private int qteSerial = -1;
	private RepeatPressQte.State lastResult;
	private GrabSync timedHelper;
	/** 마지막 쌍 (기술 ID, 없으면 -1). */
	private int paired = -1;
	private org.joml.Vector3f anchor;

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
		if (paired < 0) {
			qte = null;
			if (anchor != null) {
				// 동기 끝: 원래 서 있던 곳으로 (HERO_TO_BOSS — 동기 애니는 보스 원점 기준)
				ctl.position.set(anchor);
				ctl.prevPosition.set(anchor);
				anchor = null;
			}
			return;
		}
		if (align == Align.BOSS_TO_HERO) {
			boss.yaw = ctl.yaw;
			org.joml.Vector3f v = synchOffset(boss, ctl.animator());
			boss.pos.set(ctl.position).sub(v);
			boss.prevPos.set(ctl.prevPosition).sub(v);
		} else if (align == Align.HERO_TO_BOSS) {
			if (anchor == null) {
				anchor = new org.joml.Vector3f(ctl.position);
			}
			// 영웅 동기 애니의 zeroJoint = 보스 원점 기준 영웅 위치 (예 BHAbossGrabLeft (−40, 682, 432): 높이 682 = 갑판 위 단상) [추정]
			var sk = ctl.skeleton();
			int zj = sk.indexOf("zeroJoint");
			org.joml.Vector3f off = new org.joml.Vector3f();
			if (zj >= 0 && ctl.animator().current() != null) {
				float[] w = ctl.animator().pose().world(sk)[zj];
				off.set(w[12], w[13], w[14]);
			}
			// 영웅 zeroJoint 는 보스 프레임에서 z 만 뒤집힌 위치다: 보스 물기 애니의 synchJoint(물기가 떨어지는 단상 지점)와 대응 —
			// BHAbossBiteMiddle (1, 679, -382) <-> 영웅 BHAbossKill zero (1, 682, 383), BiteLeft (-74, 679, -449) <-> BHAbossGrabLeft
			// zero (-40, 682, 432), BiteRight (69, 679, -451) <-> GrabRight (52, 682, 436). 전엔 180° 회전이라 좌우(x)가 뒤바뀌어 잡기 카메라가
			// 머리 쪽으로 돌면 크레토스가 화면 밖(카메라 축에서 45-57°)이었다 [추정: 대응 관계는 데이터]. 영웅은 보스를 마주 본다 (요 + π).
			float yaw = boss.yaw + (float) Math.PI;
			off.z = -off.z;
			off.rotateY(boss.yaw).add(boss.pos);
			ctl.position.set(off);
			ctl.prevPosition.set(off);
			ctl.yaw = yaw;
			ctl.vy = 0;
		}
		if (align == Align.ZERO) {
			float[] zh = zero(ctl.animator()), zb = zero(boss.animator());
			org.joml.Vector3f v = new org.joml.Vector3f(zh[0] - zb[0], zh[1] - zb[1], zh[2] - zb[2]).mul(boss.scale).rotateY(boss.yaw);
			ctl.position.set(boss.pos).add(v);
			ctl.position.y = Math.max(boss.pos.y, ctl.position.y); // 실패 애니(454)는 zeroJoint 가 바닥 아래로 내려감
			ctl.prevPosition.set(ctl.position);
			ctl.yaw = boss.yaw;
			ctl.vy = 0;
		}
		if (align == Align.SYNCH) {
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
