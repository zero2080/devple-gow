package devple.gow.engine.light;

import devple.gow.engine.render.Shader;
import org.joml.Vector3f;

/**
 * 라이트 묶음 → basic.frag / skinned.vert·basic.vert 유니폼 (uLgt*). 원작은 렌더 플래그 0x800 모델만 묶음을 만든다
 * (0x141EEC: 모델 +0xC0 & 0x800 → 0x1521B0, 0x15986C: & 0x810) [확실].
 */
public final class LightUniforms {

	/** MDL +0x28 렌더 플래그 (→ 모델 +0xC0): 0x800 = 동적 조명, 8 = 탈색, 0x10 = 그림자 방향만 [확실: 0x141EEC / 0x15986C]. */
	public static final long LIT = 0x800, DESAT = 0x8;

	private LightUniforms() {
	}

	public static void off(Shader s) {
		s.set("uLgtOn", 0);
	}

	public static void set(Shader s, LightRig r) {
		s.set("uLgtOn", 1);
		s.set("uLgtDir0", r.dir0.x, r.dir0.y, r.dir0.z);
		s.set("uLgtDir1", r.dir1.x, r.dir1.y, r.dir1.z);
		s.set("uLgtCol0", r.col0.x, r.col0.y, r.col0.z);
		s.set("uLgtCol1", r.col1.x, r.col1.y, r.col1.z);
		s.set("uLgtAmb", r.ambient.x, r.ambient.y, r.ambient.z);
	}

	/**
	 * 현재 서버로 구 (c, radius) 를 비춘다. 서버가 없거나 라이트가 하나도 없거나 렌더 플래그에 0x800 이 없으면 끈다 (예전 고정 조명).
	 *
	 * @return 켰으면 그 묶음, 아니면 null
	 */
	public static LightRig apply(Shader s, long renderFlags, Vector3f c, float radius) {
		LightServer srv = LightServer.current();
		if (srv == null || (renderFlags & LIT) == 0 || !srv.any()) {
			off(s);
			return null;
		}
		LightRig r = srv.gather(c, radius, (renderFlags & DESAT) != 0);
		set(s, r);
		return r;
	}
}
