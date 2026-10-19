package devple.gow.engine.boss;

import devple.gow.engine.anim.Animator;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 동기 애니 정렬 검사: 영웅 손목(lwrist/rwrist) ↔ 상대 골격 관절 최소 거리 평균 (보조 관절 *Joint·main 제외). */
final class BossSyncContact {

	private BossSyncContact() {
	}

	/** zeroJoint 차이 규칙: 영웅 = 상대 원점 + R(상대 요)·(zero_영웅 − zero_상대) (애니 로컬, y 포함). */
	static Matrix4f zeroRule(Animator hero, Animator other, Vector3f otherPos, float otherYaw) {
		float[] zh = zero(hero), zo = zero(other);
		return new Matrix4f().translation(otherPos).rotateY(otherYaw).translate(zh[0] - zo[0], zh[1] - zo[1], zh[2] - zo[2]);
	}

	static float[] zero(Animator a) {
		int j = a.skeleton().indexOf("zeroJoint");
		if (j < 0 || a.current() == null) {
			return new float[3];
		}
		float[] p = a.current().sample(a.skeleton(), a.time(), a.current().looping()).pos[j];
		return new float[]{p[0], p[1], p[2]};
	}

	static float handGap(Animator hero, Matrix4f heroWorld, Animator other, Matrix4f otherWorld) {
		float sum = 0;
		int n = 0;
		var sk = other.skeleton();
		float[][] pose = other.pose().world(sk);
		for (String hn : new String[]{"lwrist", "rwrist"}) {
			Vector3f h = BossFight.jointWorld(hero, heroWorld, hn);
			if (h == null) {
				continue;
			}
			float best = Float.MAX_VALUE;
			for (int i = 0; i < sk.size(); i++) {
				String name = sk.joints.get(i).name();
				if (name.endsWith("Joint") || name.equals("main")) {
					continue;
				}
				best = Math.min(best, otherWorld.transformPosition(new Vector3f(pose[i][12], pose[i][13], pose[i][14])).distance(h));
			}
			sum += best;
			n++;
		}
		return n == 0 ? Float.MAX_VALUE : sum / n;
	}
}
