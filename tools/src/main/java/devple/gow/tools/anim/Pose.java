package devple.gow.tools.anim;

/**
 * 관절별 로컬 성분 (애니메이션 샘플 결과).
 * rot: 쿼터니언 관절은 (x,y,z,w) 비정규 값, 오일러 관절은 (x,y,z) 16384=1회전. pos/scale: 부모 기준.
 */
public final class Pose {

	public final float[][] rot;
	public final float[][] pos;
	public final float[][] scale;

	public Pose(int joints) {
		rot = new float[joints][4];
		pos = new float[joints][3];
		scale = new float[joints][3];
	}

	/** 스켈레톤 아이들(바인드) 포즈. */
	public static Pose idle(Skeleton sk) {
		Pose p = new Pose(sk.size());
		for (int j = 0; j < sk.size(); j++) {
			for (int c = 0; c < 4; c++) {
				p.rot[j][c] = sk.idleRot[j][c];
			}
			p.pos[j] = sk.idlePos[j].clone();
			p.scale[j] = sk.idleScale[j].clone();
		}
		return p;
	}

	public float[][] local(Skeleton sk) {
		float[][] l = new float[sk.size()][];
		for (int j = 0; j < sk.size(); j++) {
			l[j] = sk.localMatrix(j, rot[j], pos[j], scale[j]);
		}
		return l;
	}

	/** 관절 월드 행렬 (모델 공간). */
	public float[][] world(Skeleton sk) {
		return sk.world(local(sk));
	}

	/** 스키닝 행렬 (정점 · skin[j] = 포즈된 위치). */
	public float[][] skin(Skeleton sk) {
		return sk.skin(world(sk));
	}
}
