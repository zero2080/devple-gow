package devple.gow.engine.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 법선 없는 레벨 메시 (조명이 정점색에 구워짐, 16 문서 §3) 의 부드러운 정점 법선 — 노멀맵 (44 문서) 의 기준 법선용.
 * <pre>
 * 같은 위치 (UV 이음과 무관, 위치 양자화) 정점끼리 면적 가중 면 법선을 모은다.
 * 원작 스트립은 감김이 섞여 있어 (04 문서) 면 법선 부호가 제각각이다 → 그룹 합과 반대 방향이면 뒤집어 더한다 (부호 무관 평균).
 * 결과 부호는 임의 — 셰이더가 화면 미분 면 법선 쪽으로 맞춘다 (원작은 레벨 메시 뒷면 제거를 안 함, 43 문서 §8).
 * </pre>
 */
public final class SmoothNormals {

	/** 위치 양자화 (월드 단위, 1 m ≈ 16). */
	private static final float QUANT = 1f / 64f;

	private SmoothNormals() {
	}

	/** @return 정점마다 단위 법선 (면이 없는 정점은 0) */
	public static float[][] compute(List<float[]> pos, List<int[]> tris) {
		int n = pos.size();
		int[] group = new int[n];
		Map<Long, Integer> ids = new HashMap<>();
		for (int i = 0; i < n; i++) {
			float[] p = pos.get(i);
			long key = (Math.round(p[0] / QUANT) * 73856093L) ^ (Math.round(p[1] / QUANT) * 19349663L) ^ (Math.round(p[2] / QUANT) * 83492791L);
			group[i] = ids.computeIfAbsent(key, k -> ids.size());
		}
		float[] acc = new float[ids.size() * 3];
		for (int[] t : tris) {
			float[] a = pos.get(t[0]), b = pos.get(t[1]), c = pos.get(t[2]);
			float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
			float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
			float fx = uy * vz - uz * vy, fy = uz * vx - ux * vz, fz = ux * vy - uy * vx;
			for (int k = 0; k < 3; k++) {
				int g = group[t[k]] * 3;
				float s = acc[g] * fx + acc[g + 1] * fy + acc[g + 2] * fz < 0 ? -1 : 1;
				acc[g] += s * fx;
				acc[g + 1] += s * fy;
				acc[g + 2] += s * fz;
			}
		}
		float[][] out = new float[n][3];
		for (int i = 0; i < n; i++) {
			int g = group[i] * 3;
			float l = (float) Math.sqrt(acc[g] * acc[g] + acc[g + 1] * acc[g + 1] + acc[g + 2] * acc[g + 2]);
			if (l > 0) {
				out[i][0] = acc[g] / l;
				out[i][1] = acc[g + 1] / l;
				out[i][2] = acc[g + 2] / l;
			}
		}
		return out;
	}
}
