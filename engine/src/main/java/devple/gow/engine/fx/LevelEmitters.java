package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.render.Texture;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.fx.FxResources;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.texture.MaterialResource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 레벨 오브젝트 그룹의 FXC_ 이미터 → {@link ParticleSystem} 이미터 (16 문서 §2, 31 문서 §4.3).
 * 이미터 월드 = FXC 로컬 행렬 · 관절 월드(헤더 s16 = 관절 번호 [추정]) · 인스턴스.
 * 커브 이미터 (서브타입 4) 는 0xD 방출 지오메트리 노드의 NCV 커브 위에서 낸다 ({@link #curveSamples}, 16 문서 §2.5). 표면 이미터 (3) 는 메시 표면이
 * 미해석이라 아직 만들지 않는다.
 */
public final class LevelEmitters {

	private LevelEmitters() {
	}

	/** @return 만든 이미터 수 */
	public static int build(LevelData data, WadAssets assets, ParticleSystem ps) {
		return build(data, assets, ps, java.util.Set.of());
	}

	/**
	 * @param skip 만들지 않을 배치 (소문자 인스턴스 이름) — 하늘 GO: SCR_Sky (0x218848) 가 GO 를 매 프레임 카메라로 옮기므로 이미터도 하늘 공간이다.
	 *             월드 고정으로 만들면 미니어처 좌표 (ATHN03A group2 화톳불 y ≈ −72) 가 레벨 아래에 놓여 보이지 않았다 (GameScene.startSkyEffects 가 대신 돌린다).
	 * @return 만든 이미터 수
	 */
	public static int build(LevelData data, WadAssets assets, ParticleSystem ps, java.util.Set<String> skip) {
		FxResources fx = new FxResources(data.wad);
		Map<String, ParticleDef> defs = new HashMap<>();
		int n = 0;
		float[] p = new float[3];
		float[] d = new float[3];
		for (LevelLayout.Placement pl : data.layout.placements()) {
			if (pl.instance().scale() == 0 || skip.contains(pl.instance().name().toLowerCase(Locale.ROOT))) {
				continue;
			}
			for (String ref : pl.object().refs("FXC_")) {
				FxResources.Emitter em = fx.emitters.get(ref);
				// 표면 이미터 (3) 는 지오메트리 미해석 → 생략 (원점 한 점에 몰리면 원작과 다르다). 커브 이미터 (4) = 0xD 노드의 NCV 커브 위 (43 문서 §19)
				if (em == null || em.rate() <= 0 || em.subtype() == 3) {
					continue;
				}
				float[][] curve = null;
				if (em.subtype() == 4) {
					curve = curveSamples(fx, data, em, pl);
					if (curve == null) {
						continue;
					}
				}
				FxResources.Shape sh = fx.byShape.get(em.shape());
				if (sh == null) {
					continue;
				}
				int j = em.header()[0] >= 0 && em.header()[0] < pl.jointWorld().length ? em.header()[0] : 0;
				float[] world = Mat4.mul(em.local(), pl.jointWorld()[j]);
				Mat4.transformPoint(world, 0, 0, 0, p);
				float[] dir = em.subtype() == 1 || em.subtype() == 4 ? em.dir() : new float[]{0, 1, 0};
				Mat4.transformDir(world, dir[0], dir[1], dir[2], d);
				float scale = (float) Math.sqrt(world[0] * world[0] + world[1] * world[1] + world[2] * world[2]);
				ParticleDef def = defs.computeIfAbsent(em.name() + "/" + sh.name(), k -> def(em, sh, assets, scale));
				ParticleSystem.Emitter e = ps.add(def, p[0], p[1], p[2]);
				float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
				if (l > 1e-6f) {
					e.dir.set(d[0] / l, d[1] / l, d[2] / l);
				}
				// PTC 시스템 행렬 = 소유 관절 월드 (0x10/0x20 이면 파티클이 그 회전/이동 공간에 산다, 0x15BE18) [확실: 코드, 관절 = FXC 관절로 근사]
				e.space.set(pl.jointWorld()[j]);
				if (sh.vu() != null && sh.vu().kind == 5 && System.getenv("GOW_PTCVU_LOG") != null) {
					System.out.printf(Locale.ROOT, "  열기 아지랑이 %s @ (%.0f, %.0f, %.0f)%n", sh.name(), p[0], p[1], p[2]);
				}
				e.owner = pl.instance().name().toLowerCase(Locale.ROOT);
				e.lodNear = em.lodNear();
				e.lodFar = em.lodFar();
				if (curve != null) {
					e.curve = curve[0];
					e.curveTan = curve[1];
					e.tangentSpeed = em.tangentSpeed();
					e.normalSpeed = em.normalSpeed();
					e.distMin = em.minDist();
					e.distMax = em.maxDist();
				}
				n++;
			}
		}
		System.out.printf(Locale.ROOT, "  fx 이미터 %d (FXC %d, PTC %d)%n", n, fx.emitters.size(), fx.shapes.size());
		return n;
	}

	/** 커브 표본 수 (구간마다) — 원작은 고른 t 에서 다항식을 바로 계산한다 (표본 사이는 선형 보간 [근사]). */
	static final int CURVE_SAMPLES_PER_SEGMENT = 8;

	/**
	 * 커브 이미터의 월드 커브 표본: 0xD 노드 (이미터 +0x6C 모양 이름) 의 NCV 커브를 노드 월드 = 노드 로컬 (+0x10) · 소유 GO 관절 월드 로 옮겨
	 * t 를 고르게 나눈 점·접선 (원작 0x12EDA8 → 0x29CA68+0xC0 행렬, 0x1249C8) [확실: 노드·커브 연결 / 근사: 행렬 곱 순서는 이미터와 같은 규칙].
	 *
	 * @return {점 xyz…, 접선 xyz…} 또는 노드·커브가 없으면 null
	 */
	public static float[][] curveSamples(FxResources fx, LevelData data, FxResources.Emitter em, LevelLayout.Placement pl) {
		FxResources.Geom g = fx.geoms.get(em.geom());
		if (g == null) {
			return null;
		}
		devple.gow.tools.level.NcvCurve c = data.curves.get(g.curve());
		if (c == null || c.knots.length == 0 || c.tMax() <= 0) {
			return null;
		}
		int gj = g.joint() >= 0 && g.joint() < pl.jointWorld().length ? g.joint() : 0;
		float[] w = Mat4.mul(g.local(), pl.jointWorld()[gj]);
		int n = c.knots.length * CURVE_SAMPLES_PER_SEGMENT + 1;
		float[] pts = new float[n * 3], tan = new float[n * 3];
		float[] a = new float[3], o = new float[3];
		for (int s = 0; s < n; s++) {
			float t = c.tMax() * s / (n - 1);
			c.eval(t, a);
			Mat4.transformPoint(w, a[0], a[1], a[2], o);
			System.arraycopy(o, 0, pts, s * 3, 3);
			c.tangent(t, a);
			Mat4.transformDir(w, a[0], a[1], a[2], o);
			System.arraycopy(o, 0, tan, s * 3, 3);
		}
		return new float[][]{pts, tan};
	}

	/**
	 * FXC + PTC → 파티클 정의. 크기 |PTC+0x74|/16, 속력 (speed ± rnd)·16, 수명 PTC+0x70.
	 * 색/알파 곡선은 VU1 상수(미해석) 대신 머티리얼 레이어 색 + 알파 4u(1−u) [추정].
	 */
	static ParticleDef def(FxResources.Emitter em, FxResources.Shape sh, WadAssets assets, float scale) {
		Texture tex = null;
		RenderModel.Blend blend = RenderModel.Blend.ADD;
		float[] c = {1, 1, 1, 1};
		MaterialResource m = sh.material() == null ? null : assets.material(sh.material());
		if (m != null && !m.layers().isEmpty()) {
			MaterialResource.Layer l = m.layers().getFirst();
			tex = l.texture() == null || l.texture().isEmpty() ? null : assets.texture(l.texture());
			blend = RenderModel.Blend.of(l.blendName()) == RenderModel.Blend.ADD ? RenderModel.Blend.ADD : RenderModel.Blend.ALPHA;
			c = l.rgba().clone();
		}
		// 색 곡선: VU 상수 휴리스틱 (불 (0.66,0.42,0.2)→Δ, 알파 (A·u + B·u²)/128) [추정]
		float[] cc = sh.colorCurve();
		float[] c1;
		int fade = 1;
		float aA = 0, aB = 0;
		if (cc != null) {
			c = new float[]{Math.min(cc[0], 2), Math.min(cc[1], 2), Math.min(cc[2], 2), 1};
			c1 = new float[]{Math.max(cc[0] + cc[3], 0), Math.max(cc[1] + cc[4], 0), Math.max(cc[2] + cc[5], 0), 1};
			if (cc[6] > 0 && cc[7] < 0) {
				fade = 2;
				aA = cc[6];
				aB = cc[7];
			}
		} else {
			c1 = new float[]{c[0] * 0.6f, c[1] * 0.4f, c[2] * 0.3f, Math.min(c[3], 1)};
		}
		// 블렌드: 머티리얼은 전부 0x44 지만 불/불꽃(VU 1, 3)은 텍스처가 회색조 → 가산으로 본다 [추정]
		if (sh.vuType() == 1 || sh.vuType() == 3 || sh.vuType() == 9) {
			blend = RenderModel.Blend.ADD;
		}
		float life = sh.lifespan() > 0 ? sh.lifespan() : 1f;
		// 크기 |PTC+0x74|/16 [추정]. VU 1 (큰 화톳불, 4800) 은 그대로면 300 단위라 화면을 덮는다 → /64 로 줄임 [임시 보정]
		float size = Math.max(Math.abs(sh.size()) / (sh.vuType() == 1 ? 64f : 16f), 0.5f);
		// 커브 이미터 (4) 는 원뿔 이미터와 같은 속력·퍼짐 (0x1249C8 후반) — 위치는 ParticleSystem.spawnOnCurve 가 커브에서 정한다
		boolean cone = em.subtype() == 1 || em.subtype() == 2 || em.subtype() == 4;
		float speed = em.speed() * 16, rnd = em.speedRnd() * 16;
		float spread = em.subtype() == 2 ? 2f : em.spread();
		// 볼륨/표면 이미터: 로컬 행렬 스케일 × sectionRadius 크기의 상자에서 [추정]
		float r = cone ? Math.max(em.minDist(), 0) * 16 : Math.max(scale * Math.max(em.sectionRadius(), 0.5f), 1f);
		float rate = Math.min(em.rate(), 400);
		ParticleDef pd = new ParticleDef(em.name(), rate, life, 0.1f, new float[]{r, r, r}, new float[]{0, speed, 0}, new float[]{rnd, rnd, rnd},
				new float[3], 0, size, size * 0.6f, new float[]{c[0], c[1], c[2], Math.min(c[3], 1)}, c1, 0.05f,
				sh.renderMode() == 6 ? size * 2 : 0, 1.5f, tex, 1, blend, cone ? spread : 0.6f, cone ? speed : Math.max(speed, 8), rnd, fade, aA, aB);
		// 원작 PTC VU 식 (색·크기·위치·블렌드, 16 문서 §2.4)
		return sh.vu() != null ? pd.withPtc(sh) : pd;
	}
}
