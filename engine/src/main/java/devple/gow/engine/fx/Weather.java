package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.fx.FxResources;

import java.util.function.Supplier;

/**
 * SCR_Weather (16 문서 §1.5, 31 문서 §4.2). 원작 흐름 [확실: 디스어셈블]:
 * <ul>
 * <li>생성자 0x21CBE0: 종류 표 0x29F388 (0x18 바이트: 초기화 fn, 갱신 fn, GO 이름, 셀, 카메라 추적) 의 GO (gorain …) 를 복제하고
 *     그 파티클 시스템 +0x180 = 종류 갱신 fn, +0x184 = 날씨 객체 (0x15C46C 가 일반 생성·이동 대신 이것만 부른다).
 *     정육면체 = ±셀×8, 볼륨 상자 = 소유 GO 위치 ± box×8. 끝에 초기화 fn.</li>
 * <li>초기화 (비 0x21AFD8, 플랑크톤 0x21BB38, 먼지 0x21BED0): PTC +0x6C 최대 개수만큼 정육면체 안 균일 난수 위치, 속도 0, 출생 = 블록 시각.
 *     비는 방향 속성 = normalize(−vec + (0,−10,0)) (0x21AD48 = (0,−10,0)).</li>
 * <li>매 프레임 0x21CF80: 정육면체 중심 = 카메라 + 시선 · (셀×8 − near), 이동량 +0x10 = (이전 중심 − 새 중심) − vec·16·dt
 *     (파티클은 중심 기준 좌표, PTC 플래그 0x20 → 시스템이 중심을 따라감 = 월드에 고정).</li>
 * <li>갱신 (비 0x21AD60, 플랑크톤 0x21B768, 먼지 0x21BCB0): 위치 += 이동량 (+ 비 (0,−160,0)·dt, + 플랑크톤 난수 걸음), 정육면체로 감싸기 (mod),
 *     볼륨 상자 안이면 출생 = 0.5, 밖이면 −1 (VU 가 출생 &lt; 0 을 건너뜀).</li>
 * <li>그리기: PTC VU 식 그대로. 블록 시각 = 시스템 시각 초기값 = PTC 수명 (0x15B9D0 def+0x44) 이고 날씨 경로는 블록 시각을 올리지 않으므로
 *     VU 나이 t = 수명 − 0.5 로 고정 (비 1.0 → 0.5: 알파 380.7·t − 380.7·t² = 95, 줄기 배율 ≈ 8–21) [확실: 경로 / 추정: 숨김 중 블록 시각].</li>
 * </ul>
 */
public final class Weather {

	private Weather() {
	}

	/** 표 0x29F388 +0x10 셀 (×16 = 정육면체 한 변). */
	public static final float[] CELL = {20, 20, 14, 20, 20, 10};
	/** 종류 GO 의 파티클 모양 (gorain 은 레벨 WAD, 나머지는 R_PERMK 상주; 레벨 WAD 에 같은 이름이 있으면 그것). */
	public static final String[] PTC = {"PTC_rain", "PTC_snow", "PTC_plankton", "PTC_dust", "PTC_embers", "PTC_groundfog"};
	/** 비 낙하 (0x21AD48 (0,−10,0) ×16, 초당). */
	public static final float RAIN_FALL = 160f;
	/** 플랑크톤 난수 걸음 (0x21B768): 프레임마다 속도 += U(−0.005, 0.005), 속도 ∈ [−0.05, 0.05] (월드/프레임). */
	public static final float PLANKTON_STEP = 0.005f, PLANKTON_MAX = 0.05f;
	/** 원작 near 대신 엔진 카메라 near (정육면체 중심 = 카메라 + 시선·(셀×8 − near)). */
	static final float NEAR = 2f;

	/** 원작 날씨 상태 (생성자 0x21CBE0 의 객체). 파티클 위치는 엔진에서 월드 좌표로 둔다 (원작 중심 기준 좌표 + 중심 이동과 같음). */
	public static final class Sim {
		public final int type;
		/** 정육면체 반폭 (셀×8). */
		public final float half;
		/** −vec·16 (월드/초) — 모든 종류 공통 표류 (0x21D2E0). */
		public final float[] drift;
		/** 볼륨 상자 (소유 위치 ± box×8). */
		public final float[] volMin, volMax;
		/** 비 줄기 방향 (단위) — 다른 종류는 0. */
		public final float[] dir;
		/** VU 나이 (수명 − 0.5). */
		public final float age;
		/** 초기 파티클 수 (PTC +0x6C). */
		public final int count;
		/** 카메라 추적 (표 +0x14; groundfog 만 0). */
		public final boolean track;
		boolean filled;

		public Sim(int type, float[] vec, float[] box, float[] owner, float life, int count) {
			this.type = type;
			this.half = cell(type) * 8;
			this.drift = new float[]{-vec[0] * 16, -vec[1] * 16, -vec[2] * 16};
			this.volMin = new float[3];
			this.volMax = new float[3];
			for (int k = 0; k < 3; k++) {
				volMin[k] = owner[k] - box[k] * 8;
				volMax[k] = owner[k] + box[k] * 8;
			}
			this.dir = type == 0 ? rainDir(vec) : new float[3];
			this.age = life - 0.5f;
			this.count = count;
			this.track = type != 5;
		}

		/** 이번 프레임 모든 파티클 공통 이동 (월드): 표류 + 비 낙하, 원작처럼 정육면체 한 변으로 자른다. */
		public void frameMove(float dt, float[] out) {
			for (int k = 0; k < 3; k++) {
				out[k] = drift[k] * dt;
			}
			if (type == 0) {
				out[1] -= RAIN_FALL * dt;
			}
			for (int k = 0; k < 3; k++) {
				out[k] = Math.clamp(out[k], -2 * half, 2 * half);
			}
		}

		/** 볼륨 상자 안 (원작 0x21AED8 pceqw: 감싼 위치 = clamp(위치, 볼륨) 이면 보임). */
		public boolean inside(float x, float y, float z) {
			return x >= volMin[0] && x <= volMax[0] && y >= volMin[1] && y <= volMax[1] && z >= volMin[2] && z <= volMax[2];
		}
	}

	static float cell(int type) {
		return type >= 0 && type < CELL.length ? CELL[type] : 20;
	}

	/** 비 줄기 방향 = normalize(−vec + (0,−10,0)) (0x21AFD8: 0 − vec 에 0x21AD48 를 더해 정규화) = 낙하 속도 방향. */
	public static float[] rainDir(float[] vec) {
		float x = -vec[0], y = -vec[1] - 10, z = -vec[2];
		float l = (float) Math.sqrt(x * x + y * y + z * z);
		return l < 1e-6f ? new float[]{0, -1, 0} : new float[]{x / l, y / l, z / l};
	}

	/** 날씨 하나: 원작 PTC 를 찾으면 VU 경로 ({@link Sim}), 없으면 근사 이미터. */
	public record Built(ParticleDef def, Sim sim) {
	}

	/**
	 * @param owner 소유 GO 위치 (볼륨 상자 중심, null 이면 원점)
	 * @param permk R_PERMK 자원 (종류 GO 가 레벨 WAD 에 없을 때; null 가능)
	 */
	public static Built build(EnvParams.Weather w, WadAssets level, float[] owner, Supplier<WadAssets> permk) {
		String name = PTC[Math.clamp(w.type(), 0, PTC.length - 1)];
		WadAssets src = level;
		FxResources.Shape sh = level == null ? null : new FxResources(level.wad()).shapes.get(name);
		if (sh == null && permk != null) {
			src = permk.get();
			sh = src == null ? null : new FxResources(src.wad()).shapes.get(name);
		}
		float[] c = owner == null ? new float[3] : owner;
		if (sh != null && sh.vu() != null && sh.maxCount() > 0) {
			Texture tex = sh.textured() && sh.material() != null ? scopedTexture(src, name, sh.material()) : null;
			float h = cell(w.type()) * 8;
			ParticleDef d = new ParticleDef(name, 0, sh.lifespan(), 0, new float[]{h, h, h}, new float[3], new float[3],
					new float[3], 0, 1, 1, new float[]{1, 1, 1, 1}, new float[]{1, 1, 1, 1}, 0, 0, 0, tex, 1,
					sh.gsBlend() == 0x48 ? RenderModel.Blend.ADD : RenderModel.Blend.ALPHA, -1, 0, 0, 0, 0, 0, sh);
			if (d.vu()) {
				return new Built(d, new Sim(w.type(), w.vec(), w.box(), c, sh.lifespan(), sh.maxCount()));
			}
		}
		return new Built(def(w, level), null);
	}

	/** 근사 (PTC 없음 또는 GOW_PTCVU=off): 원래 손으로 맞춘 값. */
	public static ParticleDef def(EnvParams.Weather w, WadAssets assets) {
		float cell = cell(w.type()) * 16 / 2f;
		float[] box = {cell, cell, cell};
		return switch (w.type()) {
			case 1 -> new ParticleDef("snow", 600, 6, 0.3f, box, new float[]{w.vec()[0], -60, w.vec()[2]}, new float[]{20, 10, 20},
					new float[3], 0, 3, 3, new float[]{1, 1, 1, 0.8f}, new float[]{1, 1, 1, 0.8f}, 0.3f, 0, 1, null, 1, RenderModel.Blend.ALPHA);
			case 2 -> new ParticleDef("plankton", 200, 4, 0.3f, box, new float[3], new float[]{3, 3, 3},
					new float[3], 0, 12, 12, new float[]{0.75f, 1.15f, 1.2f, 0.4f}, new float[]{0.75f, 1.15f, 1.2f, 0.4f}, 0.5f, 0, 0, null, 1,
					RenderModel.Blend.ADD);
			case 3 -> new ParticleDef("dust", 150, 2, 0.3f, box, new float[]{-w.vec()[0] * 16, 0, -w.vec()[2] * 16}, new float[]{5, 5, 5},
					new float[3], 0, 80, 80, new float[]{1.2f, 1.1f, 0.9f, 0.2f}, new float[]{1.2f, 1.1f, 0.9f, 0.2f}, 0.5f, 0, 0, null, 1,
					RenderModel.Blend.ALPHA);
			case 4 -> new ParticleDef("embers", 200, 4, 0.4f, box, new float[]{0, 40, 0}, new float[]{20, 20, 20},
					new float[3], 0.2f, 3, 1, new float[]{1, 0.6f, 0.2f, 1}, new float[]{1, 0.2f, 0, 0}, 0.2f, 0, 0, null, 1, RenderModel.Blend.ADD);
			default -> rain(w, box, assets == null ? null : texture(assets, "MAT_nalRain_M01sfxRain3"));
		};
	}

	/** 근사 비: 낙하 속도 = 원작 이동 (−vec − (0,10,0))·16, 줄기 = 원작 VU 크기 (사각형 반폭 ≈ 14). */
	static ParticleDef rain(EnvParams.Weather w, float[] box, Texture tex) {
		float[] v = {-w.vec()[0] * 16, -(w.vec()[1] + 10) * 16, -w.vec()[2] * 16};
		float life = 1f;
		return new ParticleDef("rain", 1000 / life, life, 0.2f, box, v, new float[3],
				new float[3], 0, 28f, 28f, new float[]{1.05f, 0.97f, 0.8f, 0.74f}, new float[]{1.05f, 0.97f, 0.8f, 0.74f},
				0.05f, 28f, 0, tex, 1, RenderModel.Blend.ADD);
	}

	/**
	 * PTC 뒤 MAT_ 참조를 PTC 앞에서 마지막으로 정의된 같은 이름 MAT_ 로, 그 첫 레이어 TXR 도 MAT 앞 마지막 정의로 푼다
	 * (R_PERMK 에 MAT_lambert2 가 여럿 — PTC_plankton 바로 앞 것이 SFXplankton02).
	 */
	static Texture scopedTexture(WadAssets a, String ptc, String mat) {
		java.util.List<devple.gow.tools.wad.WadTag> tags = a.wad().tags();
		int ip = -1;
		for (int i = 0; i < tags.size() && ip < 0; i++) {
			devple.gow.tools.wad.WadTag t = tags.get(i);
			if (t.tag() == devple.gow.tools.wad.WadTagType.RESOURCE && t.hasData() && t.magic() == 0x13 && t.name().equals(ptc)) {
				ip = i;
			}
		}
		for (int i = ip - 1; i >= 0; i--) {
			devple.gow.tools.wad.WadTag t = tags.get(i);
			if (t.tag() == devple.gow.tools.wad.WadTagType.RESOURCE && t.hasData() && t.size() >= 4
					&& t.magic() == devple.gow.tools.texture.MaterialResource.MAGIC && t.name().equals(mat)) {
				devple.gow.tools.texture.MaterialResource m = devple.gow.tools.texture.MaterialResource.parse(t);
				return m.layers().isEmpty() ? null : a.textureBefore(m.layers().getFirst().texture(), i);
			}
		}
		return texture(a, mat);
	}

	static Texture texture(WadAssets assets, String mat) {
		String t = assets.materialTexture(mat);
		return t == null ? null : assets.texture(t);
	}
}
