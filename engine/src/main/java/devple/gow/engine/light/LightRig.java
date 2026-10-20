package devple.gow.engine.light;

import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 물체 하나의 라이트 묶음 = 0x1521B0 출력 (0x70 바이트, VU1 qw 70–75 로 올라감, 0x167A98 → 0x13F928(블록, 6, ·, 0x46)) [확실: 디스어셈블].
 * <pre>
 * qw 70–72  방향 행렬: 열 x = 슬롯 0 방향, 열 y = 슬롯 1 방향 (카메라 회전 공간 — 내적이라 월드와 같음)
 * qw 73     슬롯 0 색 (채널별 최댓값, w = max(r,g,b))
 * qw 74     슬롯 1 색
 * qw 75     주변광 (주변광 라이트 색·세기 평균, w = 0)
 * +0x60     VU 프로그램 플래그 8 (= 조명 커널)
 * </pre>
 * VU1 조명 커널 (0x2AE000 라이브러리 0x0B5…): 정점 색 = M·(주변 + 색0·max(n·L0, 0) + 색1·max(n·L1, 0)), 2^23 편향 정수 → 255 로 자름 (128 = 1 배),
 * M = 머티리얼 색 (qw 76) — GS MODULATE 로 텍스처에 곱해진다. 정점 색 (메시) 은 쓰지 않는다.
 */
public final class LightRig {

	public final Vector3f dir0 = new Vector3f(), dir1 = new Vector3f();
	public final Vector4f col0 = new Vector4f(), col1 = new Vector4f();
	public final Vector3f ambient = new Vector3f();
	/** 그림자 방향 (종류 비트 4 라이트의 세기 가중 방향 합, 정규화 — 0x1521B0 a1 출력). 없으면 0. */
	public final Vector3f shadowDir = new Vector3f();
	/** 슬롯별·주변광 라이트 수 (디버그·테스트). */
	public int count0, count1, countAmbient;

	/** VU 정점 색 상한 255 / 128 (minii 8388863 = 2^23 + 255, GS 128 = 1 배). */
	public static final float MAX = 255f / 128f;

	/** 법선 n (정규화) 의 조명 배율 (VU 커널과 같은 식, 채널별 255/128 로 자름). */
	public Vector3f shade(Vector3f n, Vector3f out) {
		float d0 = Math.max(0f, n.dot(dir0)), d1 = Math.max(0f, n.dot(dir1));
		out.set(ambient).add(col0.x * d0 + col1.x * d1, col0.y * d0 + col1.y * d1, col0.z * d0 + col1.z * d1);
		return out.set(Math.min(out.x, MAX), Math.min(out.y, MAX), Math.min(out.z, MAX));
	}

	/** VU 편향 1/128 (0x0E8: vf29 += 8388609 = 2²³ + 1). */
	public static final float BIAS = 1f / 128f;

	/**
	 * 셰이더와 같은 최종 배율: min(A + M·(주변 + 색0·d0 + 색1·d1) + 1/128, 255/128), M = 머티리얼 색 (qw 76 = 헤더 색 × 레이어 rgb × 물체 색).
	 * 주 프로그램 0x099–0x0A4: vf7 = qw76 × 128 (VU 플래그 4 일 때, 아니면 128), vf27/28 = 색 × vf7, vf29 = qw77 + 주변 × vf7, A = qw77 / 128 (MAT 더할 색, 대부분 0) [확실].
	 */
	public Vector3f shade(Vector3f n, Vector3f m, Vector3f out) {
		return shade(n, m, new Vector3f(), out);
	}

	/** add = 더할 색 qw 77 / 128 (MAT 파일 0x18, 대부분 0 — 적 발광 12.8 / 64 / 128). */
	public Vector3f shade(Vector3f n, Vector3f m, Vector3f add, Vector3f out) {
		float d0 = Math.max(0f, n.dot(dir0)), d1 = Math.max(0f, n.dot(dir1));
		out.set(ambient).add(col0.x * d0 + col1.x * d1, col0.y * d0 + col1.y * d1, col0.z * d0 + col1.z * d1).mul(m).add(add).add(BIAS, BIAS, BIAS);
		return out.set(Math.min(out.x, MAX), Math.min(out.y, MAX), Math.min(out.z, MAX));
	}

	@Override
	public String toString() {
		return String.format(java.util.Locale.ROOT, "amb(%.2f,%.2f,%.2f)×%d L0(%.2f,%.2f,%.2f)·(%.2f,%.2f,%.2f)×%d L1(%.2f,%.2f,%.2f)·(%.2f,%.2f,%.2f)×%d",
				ambient.x, ambient.y, ambient.z, countAmbient, dir0.x, dir0.y, dir0.z, col0.x, col0.y, col0.z, count0,
				dir1.x, dir1.y, dir1.z, col1.x, col1.y, col1.z, count1);
	}
}
