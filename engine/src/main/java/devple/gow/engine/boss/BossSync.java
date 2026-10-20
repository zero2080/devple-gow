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
	public enum Align { NONE, BOSS_TO_HERO, HERO_TO_BOSS, SYNCH, ZERO, TARGET_SYNCH }

	/** CombatFile 이벤트 22 (덤프 이름 FLAG_24, 0x206BA0): 자기 +0x1AC |= 0x24, 락온 대상 +0x1AC |= 0x4000 → 0x205B28 에서 0x204BF0 정렬. */
	public static final int EV_SYNC_TARGET = 22;

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
	/** 0x204BF0 섞기 비율 (대상 combatant +0x1BC): 동기 기술 시작 0.1 (0x209830), 프레임마다 ×1.1, 최대 1. */
	private float syncBlend = -1;
	private int syncBlendSerial = -1;

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
			syncBlend = -1;
			if (anchor != null && align != Align.TARGET_SYNCH) {
				// 동기 끝: 원래 서 있던 곳으로 (HERO_TO_BOSS — 동기 애니는 보스 원점 기준). TARGET_SYNCH 는 원작처럼 되돌리지 않는다
				// (0x204BF0 이 대상 오브젝트 자체를 옮기고, 뒤는 영웅 루트 모션·내비게이션 몫)
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
		if (align == Align.TARGET_SYNCH) {
			targetSynch(ctl, boss, bc);
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

	/**
	 * 원작 0x204BF0 (0x205B28: 자기 +0x1AC 에 0x24 가 모두 있으면 = 이벤트 22 구간): <b>보스가 락온 대상 (영웅) 을 자기 쪽으로 붙인다</b>.
	 * 목표 = inv(S_대상) · S_자기 · W_자기 (행벡터), S = synchJoint 행렬을 zeroJoint 기준으로 (0x1F7110 이 모델 행렬 [synchJoint] = S · Z⁻¹,
	 * [zeroJoint] = 단위로 덮어씀), 위치에 오브젝트 배율 +0x168 · 내비 +0xEC 를 곱한다. 현재 → 목표를 회전 slerp · 위치 lerp 로 비율 t 만큼 (t = +0x1BC:
	 * 시작 0.1, 프레임마다 ×1.1, 최대 1). 이벤트 22 가 없는 동기 기술 (807 연타 · 809 성공 …) 에는 정렬이 없고 영웅 루트 모션만 [확실: 코드].
	 * 히드라 806/802/810 (CombatFile 252) 만 이벤트 22 — 영웅은 머리 쪽 synchJoint 에 붙어 높이 682 단상 위 (예전 HERO_TO_BOSS 의 z 뒤집기 [추정] 대체).
	 */
	private void targetSynch(HeroController ctl, EnemyFighter boss, Combatant bc) {
		if (!bc.active() || bc.move().events().stream().noneMatch(e -> e.type() == EV_SYNC_TARGET)) {
			return;
		}
		if (syncBlendSerial != bc.moveSerial()) {
			syncBlendSerial = bc.moveSerial();
			syncBlend = 0.1f;
		}
		org.joml.Matrix4f des = new org.joml.Matrix4f().translation(boss.pos).rotateY(boss.yaw)
				.mul(synchRel(boss.animator(), boss.scale)).mul(synchRel(ctl.animator(), 1f).invert());
		org.joml.Vector3f p = des.getTranslation(new org.joml.Vector3f());
		org.joml.Vector3f f = des.transformDirection(new org.joml.Vector3f(0, 0, -1));
		float yaw = (float) Math.atan2(-f.x, -f.z);
		float t = Math.min(1f, syncBlend);
		ctl.position.lerp(p, t);
		ctl.prevPosition.set(ctl.position);
		float d = yaw - ctl.yaw;
		d = (float) Math.atan2(Math.sin(d), Math.cos(d));
		ctl.yaw += d * t;
		ctl.vy = 0;
		syncBlend = Math.min(1f, syncBlend * 1.1f);
	}

	/** zeroJoint 기준 synchJoint 행렬 (열벡터: Z⁻¹ · S), 위치 × 배율. 관절이 없으면 단위. */
	static org.joml.Matrix4f synchRel(devple.gow.engine.anim.Animator an, float scale) {
		var sk = an.skeleton();
		int zj = sk.indexOf("zeroJoint"), sj = sk.indexOf("synchJoint");
		if (sj < 0 || an.current() == null) {
			return new org.joml.Matrix4f();
		}
		float[][] w = an.pose().world(sk);
		org.joml.Matrix4f s = new org.joml.Matrix4f().set(w[sj]);
		if (zj >= 0) {
			s = new org.joml.Matrix4f().set(w[zj]).invert().mul(s);
		}
		org.joml.Vector3f tr = s.getTranslation(new org.joml.Vector3f()).mul(scale);
		return s.setTranslation(tr);
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
