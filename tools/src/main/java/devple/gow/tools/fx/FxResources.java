package devple.gow.tools.fx;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 파티클 리소스 (16 문서 §2): FXC_ 이미터/필드 (EffectsServer 0x1E) 와 PTC_ 파티클 모양 (renParticleSvr 0x13).
 * SEM_ (0x1A) 은 사운드 이미터라 여기서 다루지 않는다. 모든 길이·속도 값은 ×16 이 월드 단위.
 */
public final class FxResources {

	/**
	 * FXC_ 이미터 (서브타입 1..10, 228 바이트, def 생성자 0x121BA8). P 블록 = 파일 0x84.
	 *
	 * @param subtype  1 방향 원뿔, 2 전방향, 3 표면, 4 커브, 5 상자, 6 구, 7 원기둥, 8 원뿔 볼륨, 9 토러스, 10 관절
	 * @param header   0x08 s16 ×2 (관절?, 링크?) [추정]
	 * @param local    0x10 4x4 로컬 행렬 (행벡터, 이동 0x40)
	 * @param shape    0x54 파티클 모양 이름 (PTC_ +0x54 와 같다)
	 * @param dir      P+0x00 방향 (+Y 를 이 방향으로 돌린다, 0x122C88)
	 * @param spread   P+0x0C 원뿔각 = rand·spread·π/2
	 * @param speed    P+0x10, speedRnd P+0x14: v = (speed − sr/2 + rand·sr)·16
	 * @param minDist  P+0x18, maxDist P+0x1C 생성 거리 ×16
	 * @param rate     P+0x20 초당 생성 수
	 * @param sweep    P+0x38 volumeSweep, sectionRadius P+0x3C
	 * @param randomDir P+0x50, dirSpeed P+0x54
	 * @param lodNear  0xDC / lodFar 0xE0 (−1 = 끔): 생성률 × (far − d/16)/(far − near)
	 */
	public record Emitter(String name, int subtype, int[] header, float[] local, String shape, String geom, float[] dir, float spread,
						  float speed, float speedRnd, float minDist, float maxDist, float rate, float sweep, float sectionRadius,
						  float awayCenter, float awayAxis, float alongAxis, float aroundAxis, float randomDir, float dirSpeed,
						  float lodNear, float lodFar) {
	}

	/** FXC_ 서브타입 0xC 필드 (180 바이트): kind 3 중력, 6 균일, 7 소용돌이, 36 뉴턴 [이름 추정]. */
	public record Field(String name, float[] local, int kind, float magnitude, float[] dir, float attenuation, float minDist, float maxDist) {
	}

	/**
	 * PTC_ 파티클 모양 (def 생성자 0x15AD60).
	 *
	 * @param shape     0x54 모양 이름
	 * @param maxCount  0x6C 최대 개수 (−1 = 동적)
	 * @param lifespan  0x70 수명 (초)
	 * @param size      0x74 크기 [추정: |값|/16 = 월드]
	 * @param lodNear   0x78 / lodFar 0x7C
	 * @param flags     0x80 → 렌더 모드 (0x4040 → 6 줄기, 0x2000 → 5, flags&amp;3 …)
	 * @param vuType    0x98+4 VU 프로그램 종류 (불 3, 비 8, 파문 9, 조각 0)
	 * @param material  PTC 그룹 안의 MAT_
	 * @param colors    VU 상수 영역 qword (RGB 0..128 + 변화량 등, 의미 일부만 추정)
	 */
	public record Shape(String name, String shape, int maxCount, float lifespan, float size, float lodNear, float lodFar, int flags,
						int vuType, String material, float[][] constants) {

		/**
		 * VU 상수에서 색/알파 곡선 추정 [추정]: RGB 세 값(4..256, 0x80 = 1.0) 다음 한 칸 건너 변화량 RGB, 이어서 알파 계수 A, B
		 * (알파 = (A·u + B·u²)/128, u = 나이/수명). 예 Tfire (84.4,53.5,25.3) Δ(−31.7,−40.9,−22.6) A 2966 B −5932,
		 * rain (134.9,124.2,102) A 380.7 B −761.4. 못 찾으면 null.
		 * @return {r, g, b, dr, dg, db, A, B} (0..1 단위 색, A/B 는 원값)
		 */
		public float[] colorCurve() {
			float[] f = new float[constants.length * 4];
			for (int k = 0; k < constants.length; k++) {
				System.arraycopy(constants[k], 0, f, k * 4, 4);
			}
			for (int i = 0; i + 9 < f.length; i++) {
				if (in(f[i]) && in(f[i + 1]) && in(f[i + 2]) && f[i + 3] == 0 && !(f[i] == f[i + 1] && f[i + 1] == f[i + 2])) {
					float[] d = {f[i + 4], f[i + 5], f[i + 6]};
					boolean delta = Math.abs(d[0]) < 256 && Math.abs(d[1]) < 256 && Math.abs(d[2]) < 256;
					float a = 0, b = 0;
					for (int j = i + 3; j < Math.min(f.length - 1, i + 16); j++) {
						if (Math.abs(f[j]) > 64 && Math.abs(f[j]) < 1e5 && j > i + 6) {
							a = f[j];
							for (int q = j + 1; q < Math.min(f.length, j + 6); q++) {
								if (f[q] != 0) {
									b = f[q];
									break;
								}
							}
							break;
						}
					}
					return new float[]{f[i] / 128, f[i + 1] / 128, f[i + 2] / 128, delta ? d[0] / 128 : 0, delta ? d[1] / 128 : 0,
							delta ? d[2] / 128 : 0, a, b};
				}
			}
			return null;
		}

		private static boolean in(float v) {
			return v >= 4 && v <= 256;
		}

		public int renderMode() {
			if ((flags & 0x4040) != 0) {
				return 6;
			}
			if ((flags & 0x2000) != 0) {
				return 5;
			}
			return switch (flags & 3) {
				case 0 -> 1;
				case 1 -> 3;
				case 2 -> (flags & 0x40000) != 0 ? 4 : 2;
				default -> 0;
			};
		}
	}

	public final Map<String, Emitter> emitters = new LinkedHashMap<>();
	public final Map<String, Field> fields = new LinkedHashMap<>();
	/** 이름 → PTC. */
	public final Map<String, Shape> shapes = new LinkedHashMap<>();
	/** 모양 이름(+0x54) → PTC. */
	public final Map<String, Shape> byShape = new LinkedHashMap<>();

	public FxResources(WadFile wad) {
		List<WadTag> tags = wad.tags();
		for (int i = 0; i < tags.size(); i++) {
			WadTag t = tags.get(i);
			if (t.tag() != WadTagType.RESOURCE || !t.hasData() || t.size() < 0x58) {
				continue;
			}
			int m = t.magic();
			ByteBuffer d = t.data();
			if ((m & 0xFFFF) == 0x1E) {
				int sub = m >>> 16;
				if (sub >= 1 && sub <= 10 && t.size() >= 0xE4) {
					emitters.putIfAbsent(t.name(), emitter(t.name(), sub, d));
				} else if (sub == 0xC && t.size() >= 0x88) {
					fields.putIfAbsent(t.name(), new Field(t.name(), floats(d, 0x10, 16), d.getInt(0x54), d.getFloat(0x58), floats(d, 0x5C, 3),
							d.getFloat(0x68), d.getFloat(0x80), d.getFloat(0x84)));
				}
			} else if (m == 0x13 && t.size() >= 0xA0) {
				Shape s = shape(t.name(), d, material(tags, i));
				shapes.putIfAbsent(t.name(), s);
				byShape.putIfAbsent(s.shape(), s);
			}
		}
	}

	private static Emitter emitter(String name, int sub, ByteBuffer d) {
		int p = 0x84;
		return new Emitter(name, sub, new int[]{d.getShort(8), d.getShort(10)}, floats(d, 0x10, 16), EnvScript.cstr(d, 0x54, 24),
				EnvScript.cstr(d, 0x6C, 24), floats(d, p, 3), d.getFloat(p + 0x0C), d.getFloat(p + 0x10), d.getFloat(p + 0x14),
				d.getFloat(p + 0x18), d.getFloat(p + 0x1C), d.getFloat(p + 0x20), d.getFloat(p + 0x38), d.getFloat(p + 0x3C),
				d.getFloat(p + 0x40), d.getFloat(p + 0x44), d.getFloat(p + 0x48), d.getFloat(p + 0x4C), d.getFloat(p + 0x50),
				d.getFloat(p + 0x54), d.getFloat(0xDC), d.getFloat(0xE0));
	}

	private static Shape shape(String name, ByteBuffer d, String mat) {
		int vu = d.limit() >= 0xA0 ? d.getInt(0x9C) : -1;
		int nConst = (d.limit() - 0x128) / 16;
		float[][] c = new float[Math.max(nConst, 0)][];
		for (int k = 0; k < c.length; k++) {
			c[k] = floats(d, 0x128 + k * 16, 4);
		}
		return new Shape(name, EnvScript.cstr(d, 0x54, 24), d.getInt(0x6C), d.getFloat(0x70), d.getFloat(0x74), d.getFloat(0x78),
				d.getFloat(0x7C), d.getInt(0x80), vu, mat, c);
	}

	/** PTC 바로 뒤의 MAT_ 참조 ({@code GROUP{ PTC_x, MAT_y }}), 없으면 null. */
	private static String material(List<WadTag> tags, int i) {
		for (int k = i + 1; k < tags.size() && k <= i + 2; k++) {
			WadTag s = tags.get(k);
			if (s.tag() == WadTagType.RESOURCE && s.name().startsWith("MAT_")) {
				return s.name();
			}
			if (s.tag() != WadTagType.GROUP_START) {
				break;
			}
		}
		return null;
	}

	static float[] floats(ByteBuffer b, int off, int n) {
		float[] f = new float[n];
		for (int k = 0; k < n; k++) {
			f[k] = off + 4 * k + 4 <= b.limit() ? b.getFloat(off + 4 * k) : 0;
		}
		return f;
	}
}
