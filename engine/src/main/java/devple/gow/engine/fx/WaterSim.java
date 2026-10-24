package devple.gow.engine.fx;

import devple.gow.tools.fx.EnvParams;

import java.util.Random;

/**
 * SCR_Water 파문 높이장 (GL 무관 — {@link WaterSurface} 가 그린다). 16 문서 §1.6.
 * <ul>
 *   <li>격자 원점 = 소유 GO 위치 (0x219D88) 가 <b>최소 모서리</b> — 격자는 +X·+Z 로 nx·nz 칸 [확실: 거리 감쇠의 중심 = 위치 + (nx·cell·8, 0, nz·cell·8)
 *       (0x219E24–0x219E8C, VADD), 충격 칸 = (점 − 위치) / (cell·16) (0x21A608, VSUB)]. 예전 엔진은 위치를 격자 한가운데로 두어 크기의 절반만큼
 *       밀려 있었다 (ATHN06A 팔각 마당 물 −160, −96 · ATHN05D 물 방 −128, −192, 43 §21).</li>
 *   <li>물방울: 위치 + (난수[0,1)·크기 + 오프셋) × 16 (X·Z 따로, 0x21A09C–0x21A128), 초당 개수 × dt 를 쌓아 1 마다 하나 (0x21A12C–0x21A190) [확실].</li>
 *   <li>충격 0x21A608: 세기 s 를 둘레 네 칸에 쌍선형 가중치로 <b>이전</b> 높이 배열 (+0x18) 에 더한다 — 다음 단계에서 −s 로 나타난다 [확실].
 *       예전 엔진은 현재 배열에서 4s (가운데) + 2s × 4 를 빼 12 배로 깊었고, ATHN06A (세기 1, 초당 30) 에서 높이가 쌓여 흰 뾰족한 산이 솟았다.</li>
 *   <li>단계 0x21A758: k = (2·dt/cell)², h' = (k·(N+S+E+W) + (2 − 4k)·h − h_prev) · 0.99, dt = min(프레임, 1/60) — 경계 한 줄은 고정 [확실].</li>
 *   <li>그리기 0x21A888: 꼭짓점 y = 위치 y + h (정수 ×16 고정소수 = 월드 단위 h), 법선으로 환경맵 UV [확실: ×16 저장].</li>
 * </ul>
 */
public final class WaterSim {

	public final int nx, nz;
	/** 월드 칸 크기 (cell × 16). */
	public final float cellW;
	/** 격자 최소 모서리 (소유 GO 위치). */
	public final float x0, y0, z0;
	private final EnvParams.Water def;
	private float[] h, prev;
	private final Random rnd;
	private float dropAcc;

	public WaterSim(EnvParams.Water def, float[] corner, long seed) {
		this.def = def;
		this.nx = def.nx();
		this.nz = def.nz();
		this.cellW = def.cell() * 16;
		this.x0 = corner[0];
		this.y0 = corner[1];
		this.z0 = corner[2];
		h = new float[nx * nz];
		prev = new float[nx * nz];
		rnd = new Random(seed);
	}

	/** 거리 감쇠 기준 중심 (0x219E24: 위치 + (nx·cell·8, 0, nz·cell·8)). */
	public float centreX() {
		return x0 + nx * cellW / 2;
	}

	public float centreZ() {
		return z0 + nz * cellW / 2;
	}

	/** 감쇠 반경 R = √(nx² + nz²) · 8 (0x219EC4–0x219F04). */
	public float radius() {
		return (float) Math.sqrt(nx * nx + nz * nz) * 8;
	}

	/** 한 단계 (dt = 1/60): 물방울 → 파동. */
	public void step(float dt) {
		dropAcc += def.dropRate() * dt;
		while (dropAcc >= 1) {
			dropAcc -= 1;
			float px = x0 + (rnd.nextFloat() * def.dropSize()[0] + def.dropOff()[0]) * 16;
			float pz = z0 + (rnd.nextFloat() * def.dropSize()[1] + def.dropOff()[1]) * 16;
			impulse(px, pz, def.strength());
		}
		wave(Math.min(dt, 1 / 60f));
	}

	/** 0x21A608: 점 (px, pz) 의 칸에 세기 s 를 쌍선형으로 이전 배열에 (경계 칸·밖은 무시). */
	public void impulse(float px, float pz, float s) {
		float fx = (px - x0) / cellW, fz = (pz - z0) / cellW;
		int ix = (int) fx, iz = (int) fz; // cvt.w.s (0 쪽으로 자름)
		if (ix <= 0 || ix >= nx - 2 || iz <= 0 || iz >= nz - 2) {
			return;
		}
		float ax = fx - ix, az = fz - iz;
		int i = iz * nx + ix;
		prev[i] += (1 - ax) * (1 - az) * s;
		prev[i + 1] += ax * (1 - az) * s;
		prev[i + nx] += (1 - ax) * az * s;
		prev[i + nx + 1] += ax * az * s;
	}

	/** 파동 한 단계 (0x21A758) — 물방울 없이 (시험용으로 패키지 공개). */
	void wave(float dt) {
		float k = (2 * dt / def.cell()) * (2 * dt / def.cell());
		float[] next = prev;
		for (int z = 1; z + 1 < nz; z++) {
			for (int x = 1; x + 1 < nx; x++) {
				int i = z * nx + x;
				next[i] = (k * (h[i - 1] + h[i + 1] + h[i - nx] + h[i + nx]) + (2 - 4 * k) * h[i] - prev[i]) * 0.99f;
			}
		}
		prev = h;
		h = next;
	}

	/** 현재 높이 (월드 단위, 꼭짓점 y 오프셋). */
	public float height(int x, int z) {
		return h[z * nx + x];
	}

	/** 최대 |높이| (시험용). */
	public float maxAbs() {
		float m = 0;
		for (float v : h) {
			m = Math.max(m, Math.abs(v));
		}
		return m;
	}
}
