package devple.gow.engine.fx;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Pose;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.fx.FxResources;
import devple.gow.tools.fx.PtcVu;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 48 문서 §9 의 할당·연산 줄이기가 값을 바꾸지 않았는지: 예전 코드 (이 시험 안의 참조 구현) 와 비트 단위로 같다.
 * <ul>
 *   <li>{@link PtcVu#eval} — 상수 슬롯 부분 복사·연산 분기 끌어올리기·항력 exp 생략. 같은 작업 공간을 계속 쓰며 (실제 그리기처럼) 비교</li>
 *   <li>{@link Pose#world} — 관절마다 로컬·회전 float[16] 을 만들지 않는 경로</li>
 * </ul>
 */
class HotPathEquivalenceTest {

	private static Path wad(String name) {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return p;
	}

	@Test
	void ptcVuEvalMatchesReferenceBitForBit() throws Exception {
		int programs = 0;
		long evals = 0;
		for (String w : new String[]{"R_PERMK", "PAND03G", "PAND01A", "ATHN01A", "DEST01", "HDES01A"}) {
			FxResources fx = new FxResources(WadFile.load(wad(w)));
			for (FxResources.Shape sh : fx.byShape.values()) {
				PtcVu vu = sh.vu();
				if (vu == null) {
					continue;
				}
				programs++;
				PtcVu.Eval e = vu.newEval();
				Ref ref = new Ref(vu);
				java.util.Random r = new java.util.Random(sh.name().hashCode());
				for (int n = 0; n < 40; n++) {
					float[][] rec = new float[6][4];
					for (float[] q : rec) {
						for (int c = 0; c < 4; c++) {
							q[c] = (r.nextFloat() - 0.5f) * 200;
						}
					}
					long seed = r.nextLong();
					float age = r.nextFloat() * 3;
					float dt = n % 7 == 0 ? 0 : 1 / 60f;
					float[][] use = n % 11 == 3 ? null : rec;
					vu.eval(e, use, seed, age, dt);
					ref.eval(use, seed, age, dt);
					for (int k = 0; k < 8; k++) {
						assertArrayEquals(ref.out[k], e.out[k], sh.name() + " #" + n + " out " + k);
					}
					assertEquals(ref.uvSet, e.uvSet, sh.name());
					if (ref.uvSet) {
						assertArrayEquals(ref.uv, e.uv, sh.name());
					}
					evals++;
				}
			}
		}
		assertTrue(programs > 50, "VU 프로그램 " + programs);
		assertTrue(evals > 2000);
	}

	@Test
	void poseWorldMatchesAllocatingReference() throws Exception {
		WadFile w = WadFile.load(wad("R_HERO0"));
		Skeleton sk = Skeleton.loadAll(w).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(w, "ANM_hero");
		int clips = 0;
		for (AnimationSet.Group g : set.groups) {
			for (Animation a : g.acts) {
				if (!a.hasSkeletalData() || clips >= 60) {
					continue;
				}
				clips++;
				for (float t : new float[]{0, 0.13f, a.duration * 0.5f, a.duration}) {
					Pose p = a.sample(sk, t, a.looping());
					float[][] got = p.world(sk);
					float[][] want = refWorld(sk, p);
					for (int j = 0; j < sk.size(); j++) {
						assertArrayEquals(want[j], got[j], a.name + " t=" + t + " 관절 " + j);
					}
				}
			}
		}
		assertTrue(clips > 10);
		// 아이들 로컬 (할당 경로 localMatrix) 도 같은 식
		float[][] il = sk.idleLocal();
		for (int j = 0; j < sk.size(); j++) {
			int[] ir = sk.idleRot[j];
			assertArrayEquals(refLocal(sk, j, new float[]{ir[0], ir[1], ir[2], ir[3]}, sk.idlePos[j], sk.idleScale[j]), il[j]);
		}
	}

	// ---- 참조: 48 §9 이전 코드 그대로 ----

	private static float[][] refWorld(Skeleton sk, Pose p) {
		float[][] l = new float[sk.size()][];
		for (int j = 0; j < sk.size(); j++) {
			l[j] = refLocal(sk, j, p.rot[j], p.pos[j], p.scale[j]);
		}
		float[][] w = new float[sk.size()][];
		for (int i = 0; i < sk.size(); i++) {
			refWorldOf(sk, i, l, w);
		}
		return w;
	}

	private static float[] refWorldOf(Skeleton sk, int i, float[][] local, float[][] w) {
		if (w[i] == null) {
			int p = sk.joints.get(i).parent();
			w[i] = p < 0 ? local[i].clone() : mul(local[i], refWorldOf(sk, p, local, w));
		}
		return w[i];
	}

	private static float[] refLocal(Skeleton sk, int j, float[] rot, float[] pos, float[] scale) {
		Skeleton.Joint jt = sk.joints.get(j);
		float[] r = jt.quaternion() ? fromQuat(rot[0], rot[1], rot[2], rot[3])
				: fromEuler(rot[0] * Skeleton.EULER_UNIT, rot[1] * Skeleton.EULER_UNIT, rot[2] * Skeleton.EULER_UNIT);
		if ((jt.flags() & Skeleton.F_EXT_ROT) != 0 && jt.extIndex() >= 0 && jt.extIndex() < sk.ext.length) {
			r = mul3(r, sk.ext[jt.extIndex()]);
		}
		if (scale != null) {
			for (int row = 0; row < 3; row++) {
				for (int c = 0; c < 3; c++) {
					r[row * 4 + c] *= scale[row];
				}
			}
		}
		r[12] = pos[0];
		r[13] = pos[1];
		r[14] = pos[2];
		return r;
	}

	private static float[] identity() {
		float[] m = new float[16];
		m[0] = m[5] = m[10] = m[15] = 1;
		return m;
	}

	private static float[] mul(float[] a, float[] b) {
		float[] r = new float[16];
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				float s = 0;
				for (int k = 0; k < 4; k++) {
					s += a[i * 4 + k] * b[k * 4 + j];
				}
				r[i * 4 + j] = s;
			}
		}
		return r;
	}

	private static float[] mul3(float[] a, float[] b) {
		float[] r = identity();
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j];
			}
		}
		return r;
	}

	private static float[] fromQuat(float x, float y, float z, float w) {
		float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		if (n == 0) {
			return identity();
		}
		x /= n;
		y /= n;
		z /= n;
		w /= n;
		float[] m = identity();
		m[0] = 1 - 2 * (y * y + z * z);
		m[1] = 2 * (x * y + z * w);
		m[2] = 2 * (x * z - y * w);
		m[4] = 2 * (x * y - z * w);
		m[5] = 1 - 2 * (x * x + z * z);
		m[6] = 2 * (y * z + x * w);
		m[8] = 2 * (x * z + y * w);
		m[9] = 2 * (y * z - x * w);
		m[10] = 1 - 2 * (x * x + y * y);
		return m;
	}

	private static float[] fromEuler(float ax, float ay, float az) {
		float cx = (float) Math.cos(ax), sx = (float) Math.sin(ax);
		float cy = (float) Math.cos(ay), sy = (float) Math.sin(ay);
		float cz = (float) Math.cos(az), sz = (float) Math.sin(az);
		float[] rx = identity();
		rx[5] = cx;
		rx[6] = sx;
		rx[9] = -sx;
		rx[10] = cx;
		float[] ry = identity();
		ry[0] = cy;
		ry[2] = -sy;
		ry[8] = sy;
		ry[10] = cy;
		float[] rz = identity();
		rz[0] = cz;
		rz[1] = sz;
		rz[4] = -sz;
		rz[5] = cz;
		return mul(mul(rx, ry), rz);
	}

	/** 예전 PtcVu.eval + Eval 작업 공간 (매번 모든 상수 슬롯 복사, 성분마다 연산 분기, exp 항상). */
	private static final class Ref {
		final PtcVu vu;
		final float[][] s;
		final float[][] res;
		final float[][] out = new float[8][4];
		final int[] uv = new int[4];
		final int[] targets;
		boolean uvSet;
		long rnd;

		Ref(PtcVu vu) {
			this.vu = vu;
			s = new float[vu.slots.length + 8][4];
			targets = vu.resultTargets();
			res = new float[Math.max(targets.length, 1)][4];
		}

		float next() {
			rnd ^= rnd << 13;
			rnd ^= rnd >>> 7;
			rnd ^= rnd << 17;
			return (rnd >>> 40) / (float) (1L << 24);
		}

		static final float[] ZERO = new float[4];

		float[] at(int k) {
			return k >= 0 && k < s.length ? s[k] : ZERO;
		}

		void put(int k, float[] v, boolean all) {
			if (k < 0 || k >= s.length || v == null) {
				return;
			}
			if (all) {
				System.arraycopy(v, 0, s[k], 0, 4);
			} else {
				s[k][3] = v[3];
			}
		}

		static float clamp(float v) {
			return Math.max(0, Math.min(255, v));
		}

		void eval(float[][] record, long seed, float age, float dt) {
			int[][] tables = vu.tables;
			for (int k = 0; k < vu.slots.length; k++) {
				System.arraycopy(vu.slots[k], 0, s[k], 0, 4);
			}
			if (record != null) {
				for (int k = 0; k < tables[0].length && k < record.length; k++) {
					put(tables[0][k], record[k], true);
				}
				for (int k = 0; k < tables[1].length && k < record.length; k++) {
					put(tables[1][k], record[k], false);
				}
			}
			rnd = seed * 0x9E3779B97F4A7C15L | 1;
			int src = 1;
			for (int a : tables[2]) {
				float[] lo = at(src), w = at(src + 1);
				src += 2;
				if (a >= 0 && a < s.length) {
					for (int i = 0; i < 4; i++) {
						s[a][i] = lo[i] + w[i] * next();
					}
				}
			}
			for (int a : tables[3]) {
				float[] lo = at(src), w = at(src + 1);
				src += 2;
				float v = lo[3] + w[3] * next();
				if (a >= 0 && a < s.length) {
					s[a][3] = v;
				}
			}
			float t = age, t2 = age * age / 2, tp = age - dt, tp2 = tp * tp / 2;
			float[] s0 = at(0);
			float drag = (float) Math.exp(-s0[2] * t);
			uvSet = false;
			int r = 0;
			for (int k = 0; k < tables[6].length && k < tables[7].length; k++) {
				int op = tables[6][k], a = tables[7][k];
				float[] c0 = at(a), c1 = at(a + 1), c2 = at(a + 2), c3 = at(a + 3);
				if (PtcVu.outputs(op) == 2 && r < res.length) {
					float[] p = res[r++];
					for (int i = 0; i < 4; i++) {
						p[i] = switch (op) {
							case 1 -> c0[i] - c1[i] * tp;
							case 11 -> c0[i] + c1[i] * tp;
							case 12 -> c0[i] + c1[i] * tp + c2[i] * tp2;
							case 19 -> c0[i] - c1[i] * dt;
							default -> c0[i];
						};
					}
				}
				if (r >= res.length) {
					break;
				}
				float[] v = res[r++];
				for (int i = 0; i < 4; i++) {
					v[i] = switch (op) {
						case 2, 11 -> c0[i] + c1[i] * t;
						case 3 -> clamp(c0[i] + c1[i] * t);
						case 4, 12 -> c0[i] + c1[i] * t + c2[i] * t2;
						case 5 -> clamp(c0[i] + c1[i] * t + c2[i] * t2);
						case 16 -> c1[i] * c0[i];
						case 17 -> (c1[i] + c2[i] * t) * c0[i];
						case 18 -> (c1[i] + c2[i] * t + c3[i] * t2) * c0[i];
						case 8 -> c0[i] + c2[i] * s0[0] * t + (c1[i] - c2[i] * s0[0]) * s0[0] * (1 - drag);
						default -> c0[i];
					};
				}
				if (op == 9) {
					PtcVu.plane1(c0, c1, c2, t, v);
				} else if (op == 10) {
					PtcVu.planeBounce(c0, c1, c2, c3, t, 0, v);
				} else if (op == 13 || op == 14) {
					PtcVu.planeBounce(c0, c1, c2, c3, t, dt, res[r - 2]);
					PtcVu.planeBounce(c0, c1, c2, c3, t, 0, v);
				}
				if (op == 15) {
					float f = c0[3] * t;
					f -= (float) Math.floor(f);
					int idx = 1 + (int) (c0[1] * f);
					int si = a + idx;
					if (si >= 0 && si < vu.bits.length) {
						System.arraycopy(vu.bits[si], 0, uv, 0, 4);
						uvSet = true;
					}
				}
			}
			for (int k = 0; k < r && k < targets.length; k++) {
				int a = targets[k];
				if (a < 0) {
					continue;
				}
				int o = a & 0xFF;
				if (o < out.length) {
					if ((a & 0x100) != 0) {
						out[o][3] = res[k][3];
					} else {
						System.arraycopy(res[k], 0, out[o], 0, 4);
					}
				}
			}
			assertTrue(Arrays.stream(out).allMatch(q -> q.length == 4));
		}
	}
}
