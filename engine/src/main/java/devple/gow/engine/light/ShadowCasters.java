package devple.gow.engine.light;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 그림자 오브젝트 (renShadowServer 타입 0x27, 0x160 바이트, 생성자 0x162338) 의 갱신 0x1628E0 + 받는 평면 0x1BF680 (47 문서 §7) — GL 없음.
 * <ul>
 *   <li>그림자 방향 = 모델의 라이트 묶음 (0x1521B0) 비트 4 라이트 합 (+0x100). GO 부모 사슬에 0x810 모델이 있으면 그 방향을 쓴다 (블레이드 → 영웅) [확실]</li>
 *   <li>방향이 0 (주변에 비트 4 라이트 없음) 이면 그리지 않는다 (+0x130 = 0) [확실]</li>
 *   <li>L = 구 중심 + 방향 × 64·반경 (0x162B50) [확실], 구 = 모델 LOD 바운딩 구 × 관절 → 엔진은 관절 AABB [근사]</li>
 *   <li>모드 1·2 (생물 내비게이션 0x1EBD78 → 0x1BF680): 중심에서 L 반대쪽으로 128 유닛 충돌 반직선 (필터 0x1BF658 = NoShadows 면 제외),
 *       맞은 면 = 받는 평면. 못 맞으면 모드 1 은 그리지 않는다 (+0x148 bit 0) [확실]. 영웅 (+0x174 bit 31) = 2, 그 밖 = 1</li>
 *   <li>카메라 거리 d &gt; 1000 이면 배율 (1100 − d)/100, 0 이하면 끔 (0x162DC0 / 0x1BF78C) [확실: 식 / 추정: 목록 0x2CD3F8 = 카메라]</li>
 *   <li>관절 가시성 (+0x64 비트) 이 꺼진 조각은 건너뛴다 (0x163570) [확실]</li>
 * </ul>
 */
public final class ShadowCasters {

	/** 받는 평면 질의 (충돌 반직선). 없으면 null. 평면 (n, d) 은 n·x + d = 0, n 은 아무 쪽이나 (호출자가 L 쪽으로 맞춘다). */
	public interface Receiver {
		Vector4f plane(Vector3f from, Vector3f dir, float maxDist);
	}

	/** 반직선 길이 (0x1BF7E4: 128 × 배율). */
	public static final float RAY = 128f;
	/** 카메라 거리 감쇠 시작 (0x162500: +0x148 bit 1 이 없으면 1000) / 폭 0.1 배. */
	public static final float FADE_START = 1000f;
	/** 사슬 그림자 반폭 = Shadow Diameter 0.13 × 8 (Blades_Init 0x29F2B4) [근사: 폭 해석]. */
	public static final float CHAIN_HALF_WIDTH = 0.13f * 8f;

	private static volatile ShadowCasters current;

	public static ShadowCasters current() {
		return current;
	}

	public static void setCurrent(ShadowCasters c) {
		current = c;
	}

	/** 한 프레임 그림자 드리우는 물체. owner = 모델 (부모 연결 키). */
	public static final class Caster {
		final Object owner, parent;
		final ShadowGeometry geometry;
		final float[][] joints;
		final boolean[] hidden;
		final Matrix4f world = new Matrix4f();
		final Vector4f sphere = new Vector4f();
		final int mode;
		final String key;
		/** 계산 결과: 방향, L, 평면, 그릴지. */
		final Vector3f dir = new Vector3f(), light = new Vector3f();
		Vector4f plane;
		boolean ok;
		float fade = 1f;
		/** 실제 모드 (부모가 있으면 부모 것 — 0x1BF994 가 자식 GO 의 그림자에도 같은 모드·평면). */
		int eff;

		Caster(Object owner, Object parent, ShadowGeometry g, float[][] joints, boolean[] hidden, Matrix4fc world, Vector4fc sphere, int mode,
			   String key) {
			this.owner = owner;
			this.parent = parent;
			this.geometry = g;
			this.joints = joints;
			this.hidden = hidden;
			this.world.set(world);
			this.sphere.set(sphere);
			this.mode = mode;
			this.key = key;
		}
	}

	private record Chain(Object parent, Vector3f[] pts) {
	}

	private final List<Caster> casters = new ArrayList<>();
	private final List<Chain> chains = new ArrayList<>();
	private final Set<String> hiddenKeys = new HashSet<>();
	private final Matrix4f jointTmp = new Matrix4f();
	private boolean collecting;
	/** 마지막 build 결과 (디버그·테스트). */
	public int lastCasters, lastTriangles;

	/** 이번 프레임 모으기 시작 (주 뷰만 — 거울 반사 패스의 그리기는 모으지 않는다). */
	public void begin() {
		casters.clear();
		chains.clear();
		collecting = true;
	}

	public boolean collecting() {
		return collecting;
	}

	/** 모으기를 잠시 멈춘다 (거울 반사 패스의 그리기 — 그림자는 주 뷰만). 모은 것은 그대로. */
	public void pause() {
		collecting = false;
	}

	/** {@link #pause} 뒤 다시 모은다. */
	public void resume() {
		collecting = true;
	}

	public void submit(Object owner, Object parent, ShadowGeometry g, float[][] joints, boolean[] hidden, Matrix4fc world, Vector4fc sphere,
					   int mode, String key) {
		if (!collecting || g == null || key != null && hiddenKeys.contains(key.toLowerCase(Locale.ROOT))) {
			return;
		}
		casters.add(new Caster(owner, parent, g, joints, hidden, world, sphere, mode, key));
	}

	/** 사슬 그림자 (부모 = 블레이드를 든 영웅 모델) — 부모의 L·평면을 쓴다 (0x1BF94C → 0x1FDC80, 블레이드 +0x60/+0x70). */
	public void submitChain(Object parent, Vector3f[] pts) {
		if (collecting && pts.length >= 2) {
			chains.add(new Chain(parent, pts));
		}
	}

	/**
	 * SCR_NoShadow (0x20A718: 대상 GO 의 타입 0x27 컴포넌트 숨김, 끝나면 0x1313B0 로 되살림) / Vis 슬롯 2 (0x1ABB78: 타입 0x27 속성) — 이름별 그림자 끄기.
	 */
	public void setHidden(String key, boolean hide) {
		String k = key.toLowerCase(Locale.ROOT);
		if (hide) {
			hiddenKeys.add(k);
		} else {
			hiddenKeys.remove(k);
		}
	}

	public boolean hidden(String key) {
		return hiddenKeys.contains(key.toLowerCase(Locale.ROOT));
	}

	/**
	 * 모은 물체의 볼륨을 만든다 (모으기 끝).
	 *
	 * @param lights 라이트 서버 (null 이면 그림자 없음 — 방향을 낼 라이트가 없다)
	 * @param eye    카메라 위치 (거리 감쇠)
	 */
	public int build(LightServer lights, Receiver receiver, Vector3f eye, ShadowVolume.Out out) {
		collecting = false;
		lastCasters = 0;
		if (lights == null) {
			lastTriangles = 0;
			return 0;
		}
		Map<Object, Caster> byOwner = new IdentityHashMap<>();
		int tris = 0;
		// 부모 없는 것 먼저 (블레이드는 영웅의 방향·평면)
		for (int pass = 0; pass < 2; pass++) {
			for (Caster c : casters) {
				if ((c.parent == null) != (pass == 0)) {
					continue;
				}
				Caster par = c.parent != null ? byOwner.get(c.parent) : null;
				prepare(c, par, lights, receiver, eye);
				byOwner.put(c.owner, c);
				if (!c.ok) {
					continue;
				}
				lastCasters++;
				Matrix4f m = new Matrix4f();
				float factor = ShadowVolume.extrudeFactor(c.sphere.w) * c.fade;
				for (ShadowGeometry.Piece p : c.geometry.pieces) {
					if (p.joint() >= c.joints.length || c.hidden != null && p.joint() < c.hidden.length && c.hidden[p.joint()]) {
						continue;
					}
					m.set(c.world).mul(jointTmp.set(c.joints[p.joint()]));
					tris += ShadowVolume.build(p, m, c.light, c.eff, c.plane, factor, out);
				}
			}
		}
		for (Chain ch : chains) {
			Caster par = byOwner.get(ch.parent);
			if (par != null && par.ok && par.plane != null) {
				tris += ShadowVolume.chain(ch.pts, CHAIN_HALF_WIDTH, par.light, par.plane, out);
			}
		}
		lastTriangles = tris;
		return tris;
	}

	private static void prepare(Caster c, Caster parent, LightServer lights, Receiver receiver, Vector3f eye) {
		c.ok = false;
		c.eff = parent != null ? parent.eff : c.mode;
		Vector3f center = new Vector3f(c.sphere.x, c.sphere.y, c.sphere.z);
		float r = Math.min(c.sphere.w, LightServer.RADIUS_MAX);
		if (parent != null) {
			c.dir.set(parent.dir);
		} else {
			c.dir.set(lights.gather(center, r, false).shadowDir);
		}
		if (c.dir.lengthSquared() < 1e-8f) {
			return; // +0x130 = 0
		}
		c.light.set(c.dir).mul(ShadowVolume.L_DIST * r).add(center);
		c.fade = fade(center.distance(eye));
		if (c.fade <= 0) {
			return;
		}
		int mode = c.eff;
		if (mode == ShadowVolume.MODE_EXTRUDE) {
			c.plane = null;
			c.ok = true;
			return;
		}
		if (parent != null) {
			c.plane = parent.plane;
		} else {
			Vector3f away = new Vector3f(c.dir).negate();
			Vector4f pl = receiver != null ? receiver.plane(center, away, RAY * c.fade) : null;
			if (pl != null && pl.x * c.dir.x + pl.y * c.dir.y + pl.z * c.dir.z < 0) {
				pl.negate(); // 법선을 L 쪽으로
			}
			c.plane = pl;
		}
		c.ok = c.plane != null;
	}

	/** 카메라 거리 감쇠 (0x162E9C: (1.1·s − d) / (0.1·s), s = 1000). 1 = 그대로, ≤ 0 = 끔. */
	public static float fade(float dist) {
		if (dist <= FADE_START) {
			return 1f;
		}
		return Math.min(1f, (1.1f * FADE_START - dist) / (0.1f * FADE_START));
	}

	/** 실제 모드 (부모가 있으면 부모 것) — 디버그. */
	public List<String> describe() {
		List<String> out = new ArrayList<>();
		for (Caster c : casters) {
			out.add(String.format(Locale.ROOT, "%s 모드 %d %s 방향(%.2f,%.2f,%.2f) 평면 %s", c.geometry.name, c.eff, c.ok ? "켬" : "끔",
					c.dir.x, c.dir.y, c.dir.z, c.plane));
		}
		return out;
	}
}
