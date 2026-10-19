package devple.gow.engine.fx;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 부서지는 물체: u8 가시성 트랙과 파편 물리 (41 문서 §2.1–2.2). */
class BreakDebrisTest {

	private static Path rom() {
		return Path.of(System.getProperty("gow.rom", "../gow-rom"));
	}

	private static Skeleton skel(WadFile w, String name) {
		return Skeleton.loadAll(w).stream().filter(s -> s.name.equalsIgnoreCase(name)).findFirst().orElseThrow();
	}

	private static Animation act(WadFile w, String name) {
		AnimationSet set = AnimationSet.load(w, "ANM_" + name);
		return set.groups.getFirst().acts.getFirst();
	}

	/** 수평 바닥 y = h. */
	private static MagicFx.Surface floor(float h) {
		return new MagicFx.Surface() {
			@Override
			public float[] cast(Vector3f from, Vector3f to) {
				if (from.y >= h && to.y < h) {
					float k = (from.y - h) / (from.y - to.y);
					return new float[]{from.x + (to.x - from.x) * k, h, from.z + (to.z - from.z) * k, 0, 1, 0, 0};
				}
				return null;
			}

			@Override
			public float[] floor(Vector3f p, float maxDrop) {
				return p.y >= h && p.y - h <= maxDrop ? new float[]{h, 0} : null;
			}
		};
	}

	@Test
	void visibilityTracks() throws Exception {
		Assumptions.assumeTrue(Files.exists(rom().resolve("ATHN03A.WAD")), "gow-rom 없음");
		WadFile w = WadFile.load(rom().resolve("ATHN03A.WAD"));
		// 통: barrelVisDisapear (1) [1,1,0] → 2 프레임 (1/60) 뒤 온전한 몸과 그 자식 숨김, barrelBisShards (10) 아래 조각은 보임
		Skeleton sk = skel(w, "breakBarrel2");
		Animation a = act(w, "breakbarrel2");
		assertNull(a.hiddenJoints(sk, 0f));
		boolean[] h = a.hiddenJoints(sk, 0.5f);
		assertNotNull(h);
		assertTrue(h[1] && h[2], "온전한 몸·자식 숨김");
		assertFalse(h[10] || h[11], "조각 묶음 보임");
		// 트랙 (hold 0) 이 끝난 뒤에도 마지막 값 유지
		assertTrue(a.hiddenJoints(sk, a.duration)[1]);
		// 양동이: breakgroup1 [1,1,0], breakgroup2 [0,0,1] → 처음엔 조각 숨김, 뒤엔 온전한 몸 숨김
		Skeleton bk = skel(w, "breakbucket017");
		Animation ba = act(w, "breakbucket017");
		boolean[] h0 = ba.hiddenJoints(bk, 0f);
		assertTrue(!h0[1] && h0[2] && h0[3], "조각 묶음 아래 관절도 숨김");
		boolean[] h1 = ba.hiddenJoints(bk, 0.2f);
		assertTrue(h1[1] && !h1[2] && !h1[3]);
		// 나무문: doorVis [1,1,0] (hold 124 = 2.1 초)
		Skeleton dk = skel(w, "breakableWoodDoor");
		assertTrue(act(w, "breakablewooddoor").hiddenJoints(dk, 1f)[1]);
	}

	@Test
	void potShardsFallOffLedge() throws Exception {
		Assumptions.assumeTrue(Files.exists(rom().resolve("ARENA06.WAD")), "gow-rom 없음");
		WadFile w = WadFile.load(rom().resolve("ARENA06.WAD"));
		Skeleton sk = skel(w, "PotLgDirtyABrk8");
		Animation a = act(w, "potlgdirtyabrk8");
		Matrix4f world = new Matrix4f().translation(0, 500, 0);
		BreakDebris d = new BreakDebris(sk);
		assertTrue(d.pieces() >= 9, "조각 " + d.pieces());
		// 받침 위 (루트 높이) 바닥이 있으면 조각은 애니를 따른다
		BreakDebris onFloor = new BreakDebris(sk);
		MagicFx.Surface base = floor(500);
		float dt = 1 / 60f;
		for (float t = 0; t < a.duration; t += dt) {
			onFloor.step(a.sample(sk, t, false).world(sk), world, base, dt);
		}
		assertEquals(0, onFloor.falling(), "바닥 위에서는 애니 그대로");
		// 조각 아래 16 안의 바닥이 루트 높이와 1.6 넘게 다르면 (계단·비탈) 물리로 떨어져 그 바닥에 멈춘다
		MagicFx.Surface low = floor(490);
		for (float t = 0; t < 4f; t += dt) {
			d.step(a.sample(sk, Math.min(t, a.duration), false).world(sk), world, low, dt);
		}
		assertTrue(d.falling() > 0, "물리로 넘어간 조각 없음");
		for (int i = 0; i < d.pieces(); i++) {
			Vector3f p = d.position(i);
			assertTrue(p.y > 490 - 2, "바닥 아래로 빠짐 " + p);
			assertTrue(Float.isFinite(p.x) && Float.isFinite(p.z));
		}
	}
}
