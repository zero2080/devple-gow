package devple.gow.tools.level;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;

import java.util.List;

/**
 * 배치 오브젝트의 시작 자세 = 인스턴스의 시작 act 를 시작 시간에 샘플한 관절 월드 + u8 가시성 트랙의 숨김 관절.
 * <p>
 * 원작 GO 리셋 (0x1309C0) [확실: 코드]: 오브젝트에 애니 컴포넌트가 있으면 act = 인스턴스 0x54 (act 수 이상이면 0) 를 걸고 (0x1007C8),
 * 시작 시간 (0x50) ≠ 0 이면 그 시간으로 옮긴다 (0x1005D0). 그래서 ANM 이 있는 레벨 오브젝트는 CXT 바인드 자세가 아니라 이 act 의 자세로 보인다.
 * PAND03A {@code godmcdog2} (DMC 결계): act 0 "DMC" 의 byte 트랙이 관절 1 (dmcSquare) · 3 (dogHead) = 0 → 결계 전체 숨김,
 * 스크립트 "dogIn" 이 1 로 바꿔 나타나고 "dogOut" 끝에서 다시 0 (41 문서 §1.3 byte 트랙 = 관절 가시성).
 *
 * @param act       시작 act (ANM 이 없거나 act 가 없으면 null → 바인드 자세)
 * @param time      시작 시간 (초, act 길이로 감은 값이 아니라 원래 값)
 * @param jointWorld 관절 월드 · 인스턴스 행렬
 * @param hidden    관절별 숨김 (가시성 트랙이 없으면 null)
 */
public record StartPose(Animation act, float time, float[][] jointWorld, boolean[] hidden) {

	/** 원작 규칙으로 고른 시작 act: 번호가 act 수 이상이면 0 (0x1309C0: sltu / movz). */
	public static Animation startAct(AnimationSet set, int index) {
		if (set == null) {
			return null;
		}
		List<Animation> all = set.all();
		if (all.isEmpty()) {
			return null;
		}
		return all.get(index >= 0 && index < all.size() ? index : 0);
	}

	/**
	 * @param set 오브젝트의 ANM 세트 (없으면 null)
	 */
	public static StartPose of(LevelLayout.Placement p, AnimationSet set) {
		Skeleton sk = p.object().skeleton();
		Animation a = startAct(set, p.instance().startAct());
		if (a == null || sk == null) {
			return new StartPose(null, 0, p.jointWorld(), null);
		}
		float t = p.instance().startTime();
		float[][] lw = a.sample(sk, t, true).world(sk);
		float[] inst = p.instance().matrix();
		float[][] w = new float[lw.length][];
		for (int i = 0; i < lw.length; i++) {
			w[i] = Mat4.mul(lw[i], inst);
		}
		float len = a.duration;
		float ht = len > 0 ? ((t % len) + len) % len : 0;
		return new StartPose(a, t, w, a.hiddenJoints(sk, ht));
	}

	/** 바인드 자세와 다른가 (관절 위치 차 > tol 또는 숨김 관절이 있음). */
	public boolean differsFromBind(float[][] bindWorld, float tol) {
		if (hidden != null) {
			return true;
		}
		for (int i = 0; i < jointWorld.length && i < bindWorld.length; i++) {
			for (int k = 0; k < 15; k++) {
				float s = k >= 12 ? 1 : 100; // 회전·스케일 성분은 단위가 작으므로 크게 본다
				if (Math.abs(jointWorld[i][k] - bindWorld[i][k]) * s > tol) {
					return true;
				}
			}
		}
		return false;
	}
}
