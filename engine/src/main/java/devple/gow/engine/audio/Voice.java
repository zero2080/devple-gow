package devple.gow.engine.audio;

import org.joml.Vector3f;

/**
 * 보이스 재생 파라미터 (가변). pos == null 이면 2D: pan(도, 0 = 정면, 90 = 오른쪽) 으로 좌우 배치.
 * 3D 감쇠: near 까지 1, far 에서 0 (선형, SEM 이미터 near/far 와 같은 의미로 가정).
 */
public final class Voice {

	public float gain = 1;
	public float pitch = 1;
	public Vector3f pos;
	public float pan;
	public float near = 10;
	public float far = 80;
	/** 설명 (헤드리스 로그·디버그용). */
	public String label = "";

	public Voice copy() {
		Voice v = new Voice();
		v.gain = gain;
		v.pitch = pitch;
		v.pos = pos != null ? new Vector3f(pos) : null;
		v.pan = pan;
		v.near = near;
		v.far = far;
		v.label = label;
		return v;
	}

	/** 리스너 위치에서의 거리 감쇠 (헤드리스 계산용, OpenAL 의 LINEAR_DISTANCE_CLAMPED 와 같음). */
	public float attenuation(Vector3f listener) {
		if (pos == null) {
			return 1;
		}
		float d = pos.distance(listener);
		if (d <= near) {
			return 1;
		}
		return d >= far ? 0 : 1 - (d - near) / (far - near);
	}
}
