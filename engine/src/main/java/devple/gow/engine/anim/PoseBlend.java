package devple.gow.engine.anim;

import devple.gow.tools.anim.Pose;
import devple.gow.tools.anim.Skeleton;

/** 두 포즈 보간. 쿼터니언 관절 = 정규화 후 nlerp(최단 경로), 오일러 관절 = 16384 주기 각도 보간. */
public final class PoseBlend {

	private PoseBlend() {
	}

	public static Pose lerp(Skeleton sk, Pose a, Pose b, float t) {
		Pose out = new Pose(sk.size());
		for (int j = 0; j < sk.size(); j++) {
			if (sk.joints.get(j).quaternion()) {
				float na = norm(a.rot[j]), nb = norm(b.rot[j]);
				float dot = 0;
				for (int c = 0; c < 4; c++) {
					dot += a.rot[j][c] / na * b.rot[j][c] / nb;
				}
				float s = dot < 0 ? -1 : 1;
				for (int c = 0; c < 4; c++) {
					out.rot[j][c] = a.rot[j][c] / na * (1 - t) + b.rot[j][c] / nb * s * t;
				}
			} else {
				for (int c = 0; c < 3; c++) {
					float d = b.rot[j][c] - a.rot[j][c];
					d -= Math.round(d / 16384f) * 16384f;
					out.rot[j][c] = a.rot[j][c] + d * t;
				}
			}
			for (int c = 0; c < 3; c++) {
				out.pos[j][c] = a.pos[j][c] + (b.pos[j][c] - a.pos[j][c]) * t;
				out.scale[j][c] = a.scale[j][c] + (b.scale[j][c] - a.scale[j][c]) * t;
			}
		}
		return out;
	}

	private static float norm(float[] q) {
		float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
		return n == 0 ? 1 : n;
	}
}
