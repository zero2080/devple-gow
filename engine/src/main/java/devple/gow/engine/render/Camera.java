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

	private final Matrix4f viewProj = new Matrix4f();

	public Matrix4f viewProj(float aspect) {
		return viewProj.setPerspective((float) Math.toRadians(fovDeg), aspect, near, far)
				.lookAt(eye, target, new Vector3f(0, 1, 0));
	}
}
