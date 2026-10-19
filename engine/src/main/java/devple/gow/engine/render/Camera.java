package devple.gow.engine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 원작식 3인칭 고정/추적 카메라의 기반: eye → target 룩앳. */
public final class Camera {

	public final Vector3f eye = new Vector3f(0, 3, 8);
	public final Vector3f target = new Vector3f();
	public float fovDeg = 55f;
	public float near = 0.1f;
	public float far = 2000f;
	/** SCR_Mirror 반사 패스: 월드 평면 반사 R (VP·R, 원작 0x12A4E8). null = 보통 카메라. */
	public Matrix4f mirror;
	/** 반사 패스의 월드 자름 평면 (a, b, c, d) — 스키닝 셰이더 uClipPlane (GL_CLIP_DISTANCE0 켤 때만). null = 없음. */
	public float[] clipPlane;

	private final Matrix4f viewProj = new Matrix4f();

	public Matrix4f viewProj(float aspect) {
		viewProj.setPerspective((float) Math.toRadians(fovDeg), aspect, near, far)
				.lookAt(eye, target, new Vector3f(0, 1, 0));
		return mirror != null ? viewProj.mul(mirror) : viewProj;
	}
}
