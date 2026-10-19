package devple.gow.tools.fx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PTC_ 의 VU1 블록 (파일 0x98..끝) = 파티클 마이크로코드(DVP 오버레이 0x2A7700, VU 0x000..0x581)가 해석하는 <b>식 목록</b> (16 문서 §2.4).
 *
 * <p>def 생성자 0x15AD60 이 블록을 복사하며 표를 VU 주소로 바꾼다: 헤더 +4 종류 → 진입점 표 0x2AA398, +8 상수 바이트 → (n&gt;&gt;4)+0x1F,
 * 표 8개 (+0x10..+0x8F, u16×8, 논리 순서 = 슬롯 0,2,4,6,1,3,5,7, −1 = 끝): T0..T3·T7 은 +0x1F (상수 슬롯 = VU vi12+0x1F+k),
 * T4/T5 는 +0x222 (절대 출력 주소), T6 은 연산 번호 → 서브루틴 표 0x2AA348.
 *
 * <p>파티클마다 VU 가 하는 일 (0x011..0x095) [확실: 마이크로코드]:
 * <ol>
 * <li>T0: 파티클 레코드 qword k → 슬롯 T0[k] (xyzw), T1: 레코드 qword k 의 w → 슬롯 T1[k].w</li>
 * <li>T2: 슬롯 1,2 / 3,4 … 의 (하한, 폭) 쌍으로 난수 lo + 폭·r → 슬롯 T2[k], T3: 이어서 w 만 (난수 씨앗 = 배치 헤더 q20, 파티클마다 고정)</li>
 * <li>vf31 = (t, t²/2, t−dt, (t−dt)²/2), t = 현재 − 슬롯0.w (출생 시각, <b>초 단위 — 수명으로 정규화하지 않는다</b>),
 *     P = exp(−슬롯0.z·t) (항력)</li>
 * <li>T6/T7: 연산 (아래 {@link #OPS}) 결과를 0x109 부터 차례로 (2출력 연산은 이전 프레임 값을 먼저)</li>
 * <li>T4: 결과 k → 절대 주소 T4[k] (xyzw), T5: 이어지는 결과의 w → T5[k].w</li>
 * <li>종류별 정점 생성: 0x222 = 색 RGBA (0..255, 0x80 = 1.0), 0x223.xyz 위치 / .w 크기 (월드 반폭), 0x224 … (종류마다)</li>
 * </ol>
 */
public final class PtcVu {

	/** 연산 번호 → 이름 (서브루틴 VU 주소). C0.. = 피연산자 슬롯 s, s+1 …; t = 나이 (초). */
	public static final String[] OPS = {
			"C0",                         // 0  0x0A6
			"C0 ; prev C0-C1*(t-dt)",     // 1  0x0A9
			"C0+C1*t",                    // 2  0x0B4
			"clamp(C0+C1*t,0,255)",       // 3  0x0C0
			"C0+C1*t+C2*t²/2",            // 4  0x0CD
			"clamp(C0+C1*t+C2*t²/2,0,255)", // 5 0x0DD
			"mem[C0.x]",                  // 6  0x0ED (절대 VU 주소, 디스크의 PTC 8951 개 중 사용 0)
			"lerp(mem[C0.x],mem[C0.x+1])", // 7 0x0F3 (사용 0)
			"drag C0,C1,C2",              // 8  0x0FB
			"plane C0: C1+C2*t, 1 bounce", // 9  0x10A (사용 0)
			"plane C0: C1+C2*t+C3*t²/2, bounces e=C2.w", // 10 0x12C (사용 0)
			"C0+C1*t ; prev",             // 11 0x0B8
			"C0+C1*t+C2*t²/2 ; prev",     // 12 0x0D3
			"plane bounces ; prev",       // 13 0x161 (사용 0)
			"plane bounces ; prev",       // 14 0x161 (사용 0)
			"flipbook UV",                // 15 0x1A2
			"C1*C0",                      // 16 0x0B0
			"(C1+C2*t)*C0",               // 17 0x0C7
			"(C1+C2*t+C3*t²/2)*C0",       // 18 0x0E5
			"C0 ; prev C0-C1*dt",         // 19 0x1B1
	};

	/** 연산이 차례로 읽는 슬롯 수. */
	private static final int[] ARGS = {1, 2, 2, 2, 3, 3, 1, 1, 3, 3, 4, 2, 3, 4, 4, 1, 2, 3, 4, 2};

	/** 헤더 +0 (런타임에 종류 진입점이 정점 루틴 주소로 덮는다), +4 종류, +8 상수 영역 바이트, +0xC 레코드 보폭 (qword). */
	public final int word0, kind, constBytes, stride;
	/** 표 8개 (논리 순서, 원값: 슬롯 번호 / 출력 오프셋 / 연산 번호). */
	public final int[][] tables = new int[8][];
	/** 상수 슬롯 (블록 +0x90 부터, 슬롯 k = VU vi12+0x1F+k). 꼬리 qword (GIF 태그, UV, 모서리 계수) 포함. */
	public final float[][] slots;
	/** 같은 슬롯의 원래 비트 (UV 등 정수 값). */
	public final int[][] bits;
	/** 꼬리 슬롯 = 상수 영역 끝 (VU 22.z = constBytes/16 + 0x1F): +0 GIF 태그, +1/+2 UV (u0,v0)/(u1,v1) 또는 원반 단계, +3/+4 모서리 계수. */
	public final int tail;
	/** 결과 → 출력 주소 (0x222 기준, w 만이면 0x100|k, 없음 −1). */
	private final int[] targets;
	private final int nResults;

	public PtcVu(ByteBuffer ptc) {
		ByteBuffer d = ptc.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int b = 0x98;
		word0 = d.getInt(b);
		kind = d.getInt(b + 4);
		constBytes = d.getInt(b + 8);
		stride = d.getInt(b + 12);
		for (int t = 0; t < 8; t++) {
			List<Integer> l = new ArrayList<>();
			for (int k = 0; k < 8; k++) {
				int slot = ((k & 3) << 1) | (k >> 2);
				int v = d.getShort(b + 0x10 + t * 16 + slot * 2);
				if (v == -1) {
					break;
				}
				l.add(v & 0x7FFF);
			}
			tables[t] = l.stream().mapToInt(Integer::intValue).toArray();
		}
		int n = Math.max(0, (d.limit() - (b + 0x90)) / 16);
		slots = new float[n][4];
		bits = new int[n][4];
		for (int k = 0; k < n; k++) {
			for (int c = 0; c < 4; c++) {
				bits[k][c] = d.getInt(b + 0x90 + k * 16 + c * 4);
				slots[k][c] = Float.intBitsToFloat(bits[k][c]);
			}
		}
		tail = constBytes / 16;
		int nr = 0;
		for (int i = 0; i < tables[6].length && i < tables[7].length; i++) {
			nr += outputs(tables[6][i]);
		}
		nResults = nr;
		targets = new int[nr];
		java.util.Arrays.fill(targets, -1);
		int r = 0;
		for (int a : tables[4]) {
			if (r < nr) {
				targets[r] = a;
			}
			r++;
		}
		// T5 는 T4 가 끝난 결과부터 w 만 (0x07B: vi2 − 1)
		for (int a : tables[5]) {
			if (r < nr) {
				targets[r] = 0x100 | a;
			}
			r++;
		}
	}

	/** 연산 k 의 결과 개수 (이전 프레임 값을 함께 내는 연산은 2). */
	public static int outputs(int op) {
		return switch (op) {
			case 1, 11, 12, 13, 14, 19 -> 2;
			default -> 1;
		};
	}

	/** 결과 번호(0x109 기준) → 출력 주소 (0x222 기준, xyzw 이면 0..; w 만이면 0x100+). 없으면 −1. */
	public int[] resultTargets() {
		return targets.clone();
	}

	/** 꼬리 슬롯 k 의 정수 비트 (없으면 0). */
	public int tailBits(int k, int c) {
		int i = tail + k;
		return i >= 0 && i < bits.length ? bits[i][c] : 0;
	}

	/** 꼬리 슬롯 k 의 float (없으면 0). */
	public float tailFloat(int k, int c) {
		int i = tail + k;
		return i >= 0 && i < slots.length ? slots[i][c] : 0;
	}

	/** 종류 4 (원반) 의 부채꼴 수 = 2 × 꼬리+2 .z (0x3BF mtir vi5 → 루프마다 삼각형 2개), 단계각 = atan2(꼬리+2 .y, .x). */
	public int fanSegments() {
		int z = tailBits(2, 2);
		return z > 0 && z <= 32 ? 2 * z : 16;
	}

	/** 한 파티클 계산용 작업 공간 (재사용, 단일 스레드). */
	public final class Eval {
		final float[][] s = new float[slots.length + 8][4];
		final float[][] res = new float[Math.max(nResults, 1)][4];
		/** 출력 0x222..0x229 (xyzw). */
		public final float[][] out = new float[8][4];
		/** 연산 15 (UV 플립북) 가 고른 GS UV (u0, v0, u1, v1, 텍셀×16), 없으면 uvSet = false. */
		public final int[] uv = new int[4];
		public boolean uvSet;
		long rnd;

		float next() {
			rnd ^= rnd << 13;
			rnd ^= rnd >>> 7;
			rnd ^= rnd << 17;
			return (rnd >>> 40) / (float) (1L << 24);
		}
	}

	public Eval newEval() {
		return new Eval();
	}

	/**
	 * 한 파티클의 출력 계산 (마이크로코드 0x011..0x086 재현). 결과는 {@code e.out[k]} = 출력 주소 0x222+k (xyzw).
	 *
	 * @param e       작업 공간 ({@link #newEval()})
	 * @param record  레코드 qword (T0/T1 이 슬롯으로 복사; null 이면 생략). 일반 배치 = {(위치, 출생 시각), (속도, 0)}
	 * @param seed    파티클 고정 난수 씨앗 (원작은 배치 헤더 q20 씨앗 → 파티클마다 같은 수열)
	 * @param age     나이 (초)
	 * @param dt      이전 프레임 간격 (원작 q21.z = 1/60)
	 */
	public void eval(Eval e, float[][] record, long seed, float age, float dt) {
		float[][] s = e.s;
		for (int k = 0; k < slots.length; k++) {
			System.arraycopy(slots[k], 0, s[k], 0, 4);
		}
		if (record != null) {
			for (int k = 0; k < tables[0].length && k < record.length; k++) {
				put(s, tables[0][k], record[k], true);
			}
			for (int k = 0; k < tables[1].length && k < record.length; k++) {
				put(s, tables[1][k], record[k], false);
			}
		}
		e.rnd = seed * 0x9E3779B97F4A7C15L | 1;
		int src = 1;
		for (int a : tables[2]) {
			float[] lo = at(s, src), w = at(s, src + 1);
			src += 2;
			if (a >= 0 && a < s.length) {
				for (int i = 0; i < 4; i++) {
					s[a][i] = lo[i] + w[i] * e.next();
				}
			}
		}
		for (int a : tables[3]) {
			float[] lo = at(s, src), w = at(s, src + 1);
			src += 2;
			float v = lo[3] + w[3] * e.next();
			if (a >= 0 && a < s.length) {
				s[a][3] = v;
			}
		}
		float t = age, t2 = age * age / 2, tp = age - dt, tp2 = tp * tp / 2;
		float[] s0 = at(s, 0);
		float drag = (float) Math.exp(-s0[2] * t);
		e.uvSet = false;
		int r = 0;
		for (int k = 0; k < tables[6].length && k < tables[7].length; k++) {
			int op = tables[6][k], a = tables[7][k];
			float[] c0 = at(s, a), c1 = at(s, a + 1), c2 = at(s, a + 2), c3 = at(s, a + 3);
			if (outputs(op) == 2 && r < e.res.length) {
				float[] p = e.res[r++];
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
			if (r >= e.res.length) {
				break;
			}
			float[] v = e.res[r++];
			for (int i = 0; i < 4; i++) {
				v[i] = switch (op) {
					case 2, 11 -> c0[i] + c1[i] * t;
					case 3 -> clamp(c0[i] + c1[i] * t);
					case 4, 12 -> c0[i] + c1[i] * t + c2[i] * t2;
					case 5 -> clamp(c0[i] + c1[i] * t + c2[i] * t2);
					case 16 -> c1[i] * c0[i];
					case 17 -> (c1[i] + c2[i] * t) * c0[i];
					case 18 -> (c1[i] + c2[i] * t + c3[i] * t2) * c0[i];
					// 0x0FB: 선형 항력 해 p0 + g·x·t + (v0 − g·x)·x·(1 − e^(−k·t)), x = 슬롯0.x (= 1/k), k = 슬롯0.z
					case 8 -> c0[i] + c2[i] * s0[0] * t + (c1[i] - c2[i] * s0[0]) * s0[0] * (1 - drag);
					// 0, 1, 19 = C0; 6/7 (절대 VU 메모리 참조 — 디스크의 PTC 가 쓰지 않음) 는 C0 로
					default -> c0[i];
				};
			}
			if (op == 9) {
				plane1(c0, c1, c2, t, v);
			} else if (op == 10) {
				planeBounce(c0, c1, c2, c3, t, 0, v);
			} else if (op == 13 || op == 14) {
				planeBounce(c0, c1, c2, c3, t, dt, e.res[r - 2]);
				planeBounce(c0, c1, c2, c3, t, 0, v);
			}
			if (op == 15) {
				// 0x1A2: 프레임 = floor(C0.y · frac(C0.w · t)), 표 = 슬롯 a+1 .. (u0, v0, u1, v1) 정수
				float f = c0[3] * t;
				f -= (float) Math.floor(f);
				int idx = 1 + (int) (c0[1] * f);
				int si = a + idx;
				if (si >= 0 && si < bits.length) {
					System.arraycopy(bits[si], 0, e.uv, 0, 4);
					e.uvSet = true;
				}
			}
		}
		for (int k = 0; k < r && k < targets.length; k++) {
			int a = targets[k];
			if (a < 0) {
				continue;
			}
			int o = a & 0xFF;
			if (o < e.out.length) {
				if ((a & 0x100) != 0) {
					e.out[o][3] = e.res[k][3];
				} else {
					System.arraycopy(e.res[k], 0, e.out[o], 0, 4);
				}
			}
		}
	}

	/** 편의: 새 작업 공간으로 계산해 출력 [8][4] 를 돌려준다 (테스트·도구용). */
	public float[][] eval(float[][] record, long seed, float age, float dt) {
		Eval e = newEval();
		eval(e, record, seed, age, dt);
		return e.out;
	}

	/**
	 * 연산 9 (0x10A) [확실: 마이크로코드]: 평면 C0 = (n, w) (n 쪽이 빈 공간, h = n·p + w), 직선 운동 p = C1 + C2·t.
	 * B = n·v &lt; 0 (다가감) 이고 t &gt; tc = −h0/B 이면 충돌점에서 v' = v − 2B·n 으로 반사 (반발 계수 없음, 1회). 아니면 C1 + C2·t
	 * (분기 0x0B6 = 연산 2 코드 재사용).
	 */
	public static void plane1(float[] n, float[] p, float[] v, float t, float[] out) {
		float b = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
		float d = n[0] * p[0] + n[1] * p[1] + n[2] * p[2] + n[3];
		for (int i = 0; i < 4; i++) {
			out[i] = p[i] + v[i] * t;
		}
		if (!(b < 0)) {
			return;
		}
		float tc = -d / b;
		if (!(tc - t < 0)) {
			return;
		}
		for (int i = 0; i < 3; i++) {
			float hit = p[i] + v[i] * tc;
			out[i] = hit + (v[i] - 2 * b * n[i]) * (t - tc);
		}
	}

	/**
	 * 연산 10 (0x12C) / 13·14 (0x161, 이전 프레임 값 함께) [확실: 마이크로코드]: 평면 C0, 포물선 p = C1 + C2·t + C3·t²/2.
	 * 법선 높이 h(t) = D + B·t + A·t²/2 (D = n·C1 + w, B = n·C2, A = n·C3), 판별식 B² − 2AD ≤ 0 이면 충돌 없음 (0x0D0/0x0D6 = 연산 4/12).
	 * 아니면 sq = √(B² − 2AD), t₀ = (sq − B)/A (빈 공간 쪽으로 떠나는 근), 비행 시간 2·s/|A| 마다 법선 속력 s ← s·e (e = C2.w)
	 * 로 최대 10 번 튕기고 (vi5 = 10), 그 뒤는 평면 위 정지 (법선 성분 0). 접선 성분은 p − n·D + (v − n·B)·t + (a − n·A)·t²/2.
	 *
	 * @param back 0 이면 현재, dt 면 이전 프레임 (같은 호에서 τ − dt, 접선 t − dt)
	 */
	public static void planeBounce(float[] n, float[] p, float[] v, float[] a, float t, float back, float[] out) {
		float d = n[0] * p[0] + n[1] * p[1] + n[2] * p[2] + n[3];
		float b = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
		float ac = n[0] * a[0] + n[1] * a[1] + n[2] * a[2];
		float disc = b * b - 2 * ac * d;
		float tt = t - back;
		if (!(disc > 0) || ac == 0) {
			for (int i = 0; i < 4; i++) {
				out[i] = p[i] + v[i] * tt + a[i] * tt * tt / 2;
			}
			return;
		}
		float sq = (float) Math.sqrt(disc), inv = 1 / ac;
		float x = t - (sq - b) * inv; // ACC.x = 마지막 이륙 후 경과
		float sv = sq, tau = 0, s = 0, e = v[3];
		boolean rest = true;
		for (int k = 0; k < 10; k++) {
			float prev = x, cur = sv;
			x += sv * 2 * inv;
			sv *= e;
			if (k == 9) {
				break;
			}
			if (x <= 0) {
				tau = prev;
				s = cur;
				rest = false;
				break;
			}
		}
		float tb = tau - back;
		float off = rest ? 0 : s * tb + ac * tb * tb / 2;
		for (int i = 0; i < 3; i++) {
			out[i] = p[i] - n[i] * d + n[i] * off + (v[i] - n[i] * b) * tt + (a[i] - n[i] * ac) * tt * tt / 2;
		}
		out[3] = p[3];
	}

	private static float clamp(float v) {
		return Math.max(0, Math.min(255, v));
	}

	private static float[] at(float[][] s, int k) {
		return k >= 0 && k < s.length ? s[k] : ZERO;
	}

	private static final float[] ZERO = new float[4];

	private static void put(float[][] s, int k, float[] v, boolean all) {
		if (k < 0 || k >= s.length || v == null) {
			return;
		}
		if (all) {
			System.arraycopy(v, 0, s[k], 0, 4);
		} else {
			s[k][3] = v[3];
		}
	}

	/** 사람이 읽는 식 목록. */
	public String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "  kind=%d w0=%x constBytes=%d stride=%d%n", kind, word0, constBytes, stride));
		String[] tn = {"T0 rec→slot", "T1 rec.w→slot", "T2 rnd→slot", "T3 rnd.w→slot", "T4 res→out", "T5 res.w→out", "T6 ops", "T7 args"};
		for (int t = 0; t < 8; t++) {
			sb.append("  ").append(tn[t]).append(':');
			for (int v : tables[t]) {
				sb.append(' ').append(t == 4 || t == 5 ? String.format("0x%x", 0x222 + v) : Integer.toString(v));
			}
			sb.append('\n');
		}
		int[] tgt = targets;
		int r = 0;
		for (int k = 0; k < tables[6].length && k < tables[7].length; k++) {
			int op = tables[6][k], a = tables[7][k];
			StringBuilder args = new StringBuilder();
			for (int i = 0; i < ARGS[op < ARGS.length ? op : 0]; i++) {
				float[] c = at(slots, a + i);
				args.append(String.format(Locale.ROOT, " s%d(%.4g,%.4g,%.4g,%.4g)", a + i, c[0], c[1], c[2], c[3]));
			}
			StringBuilder to = new StringBuilder();
			for (int o = 0; o < outputs(op); o++, r++) {
				int x = r < tgt.length ? tgt[r] : -1;
				to.append(x < 0 ? " -" : (x & 0x100) != 0 ? String.format(" 0x%x.w", 0x222 + (x & 0xFF)) : String.format(" 0x%x", 0x222 + x));
			}
			sb.append(String.format(Locale.ROOT, "  op%-2d %-30s →%s :%s%n", op, op < OPS.length ? OPS[op] : "?", to, args));
		}
		for (int k = 0; k < slots.length; k++) {
			float[] c = slots[k];
			sb.append(String.format(Locale.ROOT, "    s%-2d (%.5g, %.5g, %.5g, %.5g)%n", k, c[0], c[1], c[2], c[3]));
		}
		return sb.toString();
	}
}
