package devple.gow.engine.render;

import java.util.Arrays;

/** 디버그/프로시저 지오메트리 생성기. */
public final class MeshBuilder {

	private float[] v = new float[Mesh.STRIDE * 64];
	private int n;

	public MeshBuilder vertex(float x, float y, float z, float nx, float ny, float nz, float u, float t,
							  float r, float g, float b, float a) {
		if (n + Mesh.STRIDE > v.length) {
			v = Arrays.copyOf(v, v.length * 2);
		}
		float[] src = {x, y, z, nx, ny, nz, u, t, r, g, b, a};
		System.arraycopy(src, 0, v, n, Mesh.STRIDE);
		n += Mesh.STRIDE;
		return this;
	}

	public MeshBuilder line(float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b) {
		vertex(x0, y0, z0, 0, 1, 0, 0, 0, r, g, b, 1);
		return vertex(x1, y1, z1, 0, 1, 0, 0, 0, r, g, b, 1);
	}

	/** 축 정렬 박스 (GL_TRIANGLES). */
	public MeshBuilder box(float cx, float cy, float cz, float hx, float hy, float hz, float r, float g, float b) {
		float[][] faces = {
				{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (float[] f : faces) {
			float[] u = f[1] != 0 ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
			float[] w = {f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]};
			float[][] q = new float[4][3];
			int[][] s = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
			for (int i = 0; i < 4; i++) {
				for (int k = 0; k < 3; k++) {
					q[i][k] = f[k] + u[k] * s[i][0] + w[k] * s[i][1];
				}
			}
			int[] order = {0, 1, 2, 0, 2, 3};
			for (int i : order) {
				vertex(cx + q[i][0] * hx, cy + q[i][1] * hy, cz + q[i][2] * hz, f[0], f[1], f[2], 0, 0, r, g, b, 1);
			}
		}
		return this;
	}

	public Mesh build(int glMode) {
		return new Mesh(Arrays.copyOf(v, n), null, glMode);
	}
}
