package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.tools.combat.CombatFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 원작 동기 정렬 0x204BF0 — 이벤트 22 구간 동안 <b>그 기술을 가진 쪽이 동기 상대를 자기 쪽으로 끌어 붙인다</b> (37 문서 §4.2, §11) [확실: 코드].
 * <ul>
 *   <li>이벤트 22 (덤프 FLAG_24, 0x206BA0): 창 안 프레임마다 자기 combatant +0x1AC |= 0x24, 동기 상대 +0x1AC |= 0x4000.
 *       0x206030 이 매 프레임 +0x1AC 를 0x5400 으로 지우고 창 안 이벤트만 다시 켜므로 <b>창 [start, end] 동안만</b> 끈다.</li>
 *   <li>이벤트 19 (FLAG_4, 0x206B44): 자기 |= 0x4 → 0x206BA8 (이벤트 22 와 같은 꼬리) 상대 |= 0x4000. 아래 검사 마스크 0x24 가 0x4 를 포함하므로
 *       <b>이벤트 19 창도 끈다</b> [확실: 코드 — 예전 문서의 "위치는 건드리지 않는다" 는 틀림]. 사이클롭스 240 MAICYWMINI02 [0–0.86] (적이 영웅을),
 *       영웅 270 MAIHARWINGRIP [0–1] (영웅이 하피를) 등 일반 적 처형 대부분 (37 문서 §11.5).</li>
 *   <li>0x205968 → 0x2441E0(+0x1AC, 0x24) → 0x204BF0: 동기 상대 (+0x84, 0x207B38 — 서로 연결된 경우만) 오브젝트를
 *       목표 = inv(S_상대) · S_자기 · W_자기 (행벡터) 로. S = zeroJoint 기준 synchJoint (0x1F7110 이 [synchJoint] = S · Z⁻¹ 로 덮어씀),
 *       위치 × 오브젝트 배율. 회전 slerp · 위치 lerp, 비율 t = +0x1BC: 동기 연결이 새로 생길 때 (0x209830, 기술 플래그 0x20 이고 +0x84 가 비어 있을 때)
 *       0.1, 끄는 프레임마다 ×1.1, 최대 1 (0x205098).</li>
 * </ul>
 * 이벤트 22 를 가진 기술 (디스크 전체 CombatFile 조사, 37 문서 §11): 히드라 252 의 802/806/810, 아레스 604 의 600 [0–0.35]·608 [0–0.52],
 * 영웅 002 의 221 MAISKB02 [0–0.76]·421 MAISIRKILL [0.44–0.91] (영웅이 적을 끈다), 아기 히드라 254 의 821–825, ATHN01F 침대 305 의 846–853/880–887.
 */
public final class SyncPull {

	/** CombatFile 이벤트 22 (FLAG_24): 동기 상대를 끌어 붙임. */
	public static final int EV_SYNC_TARGET = 22;
	/** CombatFile 이벤트 19 (FLAG_4): +0x1AC |= 0x4 — 0x24 검사에 걸리므로 이벤트 22 처럼 끈다 (0x206B44 → 0x206BA8, 0x205B28). */
	public static final int EV_SYNC_HOLD = 19;

	/** +0x1BC 섞기 비율 (음수 = 연결 없음). */
	private float blend = -1;

	/** 동기 연결이 새로 생김 (0x209830: 비율 0.1). */
	public void link() {
		blend = 0.1f;
	}

	/** 동기 연결 끊김. */
	public void unlink() {
		blend = -1;
	}

	public boolean linked() {
		return blend >= 0;
	}

	public float blend() {
		return blend;
	}

	/**
	 * 서로 연결된 동기 (결합) 에서 끄는 창: 이벤트 22 또는 19 (0x2441E0(+0x1AC, 0x24) — 0x20 은 22, 0x4 는 19 와 22 가 켬). 없으면 null.
	 */
	public static CombatFile.Event linkedPullWindow(Combatant c) {
		return c.active() ? linkedPullWindow(c.move(), c.progress()) : null;
	}

	public static CombatFile.Event linkedPullWindow(CombatFile.Move m, float progress) {
		for (CombatFile.Event e : m.events()) {
			if ((e.type() == EV_SYNC_TARGET || e.type() == EV_SYNC_HOLD) && progress >= e.start() && progress <= e.end()) {
				return e;
			}
		}
		return null;
	}

	/** 지금 기술 진행률이 이벤트 22 창 안이면 그 이벤트 (없으면 null). */
	public static CombatFile.Event pullWindow(Combatant c) {
		return c.active() ? pullWindow(c.move(), c.progress()) : null;
	}

	public static CombatFile.Event pullWindow(CombatFile.Move m, float progress) {
		for (CombatFile.Event e : m.events()) {
			if (e.type() == EV_SYNC_TARGET && progress >= e.start() && progress <= e.end()) {
				return e;
			}
		}
		return null;
	}

	/** 기술에 이벤트 22 가 (창과 무관하게) 있는지. */
	public static boolean hasPull(CombatFile.Move m) {
		return m.events().stream().anyMatch(e -> e.type() == EV_SYNC_TARGET);
	}

	/**
	 * 끄는 쪽(자기) 기준 상대의 목표 월드 행렬 = W_자기 · S_자기 · S_상대⁻¹ (열벡터).
	 */
	public static Matrix4f target(Vector3f selfPos, float selfYaw, Animator self, float selfScale, Animator partner, float partnerScale) {
		return new Matrix4f().translation(selfPos).rotateY(selfYaw)
				.mul(synchRel(self, selfScale)).mul(synchRel(partner, partnerScale).invert());
	}

	/**
	 * 0x204BF0 한 프레임: 상대 위치·요를 목표 쪽으로 비율 t 만큼 (위치 lerp, 요 slerp). 비율은 뒤에 ×1.1 (최대 1).
	 * @param yaw [0] 상대 요 (입출력)
	 */
	public void step(Matrix4f target, Vector3f pos, float[] yaw) {
		if (blend < 0) {
			blend = 0.1f;
		}
		float t = Math.min(1f, blend);
		Vector3f p = target.getTranslation(new Vector3f());
		Vector3f f = target.transformDirection(new Vector3f(0, 0, -1));
		float ty = (float) Math.atan2(-f.x, -f.z);
		pos.lerp(p, t);
		float d = ty - yaw[0];
		d = (float) Math.atan2(Math.sin(d), Math.cos(d));
		yaw[0] += d * t;
		blend = Math.min(1f, blend * 1.1f);
	}

	/** zeroJoint 기준 synchJoint 행렬 (열벡터: Z⁻¹ · S), 위치 × 배율. 관절이 없으면 단위. */
	public static Matrix4f synchRel(Animator an, float scale) {
		var sk = an.skeleton();
		int zj = sk.indexOf("zeroJoint"), sj = sk.indexOf("synchJoint");
		if (sj < 0 || an.current() == null) {
			return new Matrix4f();
		}
		float[][] w = an.pose().world(sk);
		Matrix4f s = new Matrix4f().set(w[sj]);
		if (zj >= 0) {
			s = new Matrix4f().set(w[zj]).invert().mul(s);
		}
		Vector3f tr = s.getTranslation(new Vector3f()).mul(scale);
		return s.setTranslation(tr);
	}
}
