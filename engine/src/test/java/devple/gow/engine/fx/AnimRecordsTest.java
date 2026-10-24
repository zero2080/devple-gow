package devple.gow.engine.fx;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ANM act 상태 디스크립터의 원작 배치 (05 문서 §2.8.1, 43 §16): 세트 데이터 타입 항목 k 가 디스크립터 p2 개를 차지하고 (원작 채널 id = 앞 p2 합, 0x100EA8),
 * FXC 링크는 그 id 로 찾는다 (0x130240). ATHN03A 성문 불덩이 GateExplosion 이 이 배치에서만 맞다.
 */
class AnimRecordsTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static Animation act(WadFile w, String set, String name) {
		for (AnimationSet.Group g : AnimationSet.load(w, set).groups) {
			for (Animation a : g.acts) {
				if (a.name.equals(name)) {
					return a;
				}
			}
		}
		throw new AssertionError(set + " " + name);
	}

	/** 디스크립터 수 = p2 합: GateExplosion 항목 11 개 [0(3), 10, 10, 10, 11, 10, 10, 10, 10, 11, 11] → 13, 히어로 [0(4), 5, 10×6] → 11. */
	@Test
	void recordCountIsTheSumOfP2() throws Exception {
		Animation ge = act(wad("ATHN03A"), "ANM_gateexplosion", "GateExplosion");
		assertEquals(13, ge.records.size(), "항목 11 개가 아니라 Σ p2");
		int[] types = {0, 0, 0, 10, 10, 10, 11, 10, 10, 10, 10, 11, 11};
		for (int r = 0; r < types.length; r++) {
			assertEquals(types[r], ge.records.get(r).typeId(), "디스크립터 " + r);
		}
		// 원소 크기 (+0x10) 가 형마다 하나: 타입 0 = 회전 s16 · 이동 f32 · 스케일 s16, 타입 10 = f32
		assertEquals(2, ge.records.get(0).unk10());
		assertEquals(4, ge.records.get(1).unk10());
		assertEquals(2, ge.records.get(2).unk10());
		Animation hero = act(wad("R_HERO0"), "ANM_hero", "air360");
		assertEquals(11, hero.records.size());
		assertEquals(1, hero.records.get(4).unk10(), "타입 5 = u8 관절 상태 (예전 \"타입 10 슬롯 2\")");
		assertEquals(4, hero.records.get(3).unk10(), "타입 0 · 3 = 로케이터 이동 (f32)");
		assertFalse(hero.locatorTracks.isEmpty(), "히어로 lWeapOH/rWeapOH");
		assertFalse(hero.byteTracks.isEmpty(), "타입 5 u8");
	}

	/**
	 * 디스크립터 표가 트랙 데이터보다 앞: 첫 트랙 데이터 오프셋 ≥ 0x64 + 0x14 · (p2 합). 항목 수로 끊으면 표 안쪽 기록 (GateExplosion 11·12 = 타입 11) 을 놓친다.
	 */
	@Test
	void trackDataStartsAfterTheWholeTable() throws Exception {
		Animation ge = act(wad("ATHN03A"), "ANM_gateexplosion", "GateExplosion");
		int min = Integer.MAX_VALUE;
		for (Animation.StateDescr d : ge.records) {
			if (d.trackCount() > 0) {
				min = Math.min(min, d.offset());
			}
		}
		assertTrue(min >= 0x64 + 0x14 * ge.records.size(), "0x" + Integer.toHexString(min));
		assertTrue(ge.records.get(11).trackCount() > 0 && ge.records.get(12).trackCount() > 0, "항목 수 너머의 디스크립터도 트랙이 있다");
	}

	/**
	 * 불덩이 (관절 2 Fireball03GE) 는 디스크립터 1 (타입 0 이동) 대로 0.43 초 동안 성문 쪽 (+z) 으로 날고, 관절 1·3 (메시) 은 움직이지 않는다.
	 * 예전 항목 번호 배치는 디스크립터 4 (생성률 150, "타입 11") 를 관절 2 x 로 읽어 불덩이가 0.45 초에 x 1000 옆으로 뛰었다.
	 */
	@Test
	void gateFireballFliesAlongTheTranslationRecord() throws Exception {
		WadFile w = wad("ATHN03A");
		EffectLibrary lib = new EffectLibrary();
		lib.add(w);
		EffectLibrary.EffectDef d = lib.resolve("gogateexplosion");
		assertNotNull(d);
		Animation a = d.anim();
		float[] inst = null;
		for (LevelLayout.Placement p : new LevelData(w).layout.placements()) {
			if (p.instance().name().equalsIgnoreCase("gogateexplosion")) {
				inst = p.instance().matrix();
			}
		}
		assertNotNull(inst);
		float[] j2a = world(d, a, 0f, 2, inst), j2b = world(d, a, 0.45f, 2, inst);
		assertArrayEquals(new float[]{-819, 1074, -1048}, j2a, 2f, "0 초");
		assertArrayEquals(new float[]{-830, 1152, -531}, j2b, 2f, "도착 (프레임 13)");
		for (float t = 0; t <= a.duration; t += 0.05f) {
			assertArrayEquals(world(d, a, 0, 1, inst), world(d, a, t, 1, inst), 0.5f, "관절 1 고정 t=" + t);
			assertArrayEquals(world(d, a, 0, 3, inst), world(d, a, t, 3, inst), 0.5f, "관절 3 고정 t=" + t);
			float z = world(d, a, t, 2, inst)[2];
			assertTrue(z >= -1049 && z <= -530, "불덩이 z 범위 " + z);
		}
		assertArrayEquals(j2b, world(d, a, 0.45f, 2, inst), 1e-3f, "EffectDef.pose = Animation.sample");
	}

	private static float[] world(EffectLibrary.EffectDef d, Animation a, float t, int j, float[] inst) {
		float[] m = Mat4.mul(d.pose(a, t).world(d.skeleton())[j], inst);
		return new float[]{m[12], m[13], m[14]};
	}

	/**
	 * 생성률: FBFemit2GE (링크 4) 는 원작 채널 id 4 = 타입 10 → 0 → 150/초 (프레임 1–13) → 0, FBSemit4GE (3) 50, FXC_GE (5) 100.
	 * 예전 항목 번호 배치는 디스크립터 4 를 "타입 11" 로 보아 채널이 비었다 (불꽃 본체가 안 나옴). 링크 22–25 (폭발 이미터) 는 채널 13 개 밖이라 곡선 없음.
	 */
	@Test
	void gateFireballRatesUseChannelIds() throws Exception {
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("ATHN03A"));
		EffectLibrary.EffectDef d = lib.resolve("gogateexplosion");
		Animation a = d.anim();
		float flame = -1, smoke = -1, ge = -1;
		for (EffectLibrary.EmitterDef e : d.emitters()) {
			switch (e.fxc().name()) {
				case "FXC_FBFemit2GE" -> flame = d.rate(a, e, 0.2f);
				case "FXC_FBSemit4GE" -> smoke = d.rate(a, e, 0.2f);
				case "FXC_GE" -> ge = d.rate(a, e, 0.2f);
				default -> {
					assertTrue(e.channel() >= a.channels.size(), e.fxc().name() + " 링크 " + e.channel());
					assertEquals(0f, d.rate(a, e, 0.2f), 1e-6f);
				}
			}
			assertEquals(0f, d.rate(a, e, 0.8f), 1e-6f, e.fxc().name() + " 끝나면 0");
		}
		assertEquals(150f, flame, 1e-3f);
		assertEquals(50f, smoke, 1e-3f);
		assertEquals(100f, ge, 1e-3f);
		assertEquals(10, a.records.get(4).typeId(), "디스크립터 4 = 항목 2 (타입 10) — 예전 항목 번호 배치는 \"타입 11\" 로 보아 채널이 비었다");
		assertTrue(d.hasRateChannel(a));
	}

	/**
	 * 스케일 = 타입 0 디스크립터 2 (05 §2.8.1): 원작 0x111760 → 0x231B50 이 s16 × (π/2)/4096 (0x39C90FD8, 2607.6 = 1.0) 로 풀어 재생기 스케일 배열에 쓰고,
	 * 행렬 함수 (0x10B488 …) 가 회전 행 i 에 스케일[i] 를 곱한다 (0x10B8A0). ARENA06 횃불 빛 torchGlowMesh (세트 [0(p2 3)], 플래그 0x4000) 는 이 트랙 하나로
	 * 1.0 ↔ 0.85 깜박인다 — 예전 항목 번호 배치는 디스크립터를 1 개만 읽어 늘 1.0.
	 */
	@Test
	void scaleRecordScalesJointRows() throws Exception {
		WadFile w = wad("ARENA06");
		Animation a = act(w, "ANM_torchglowmesh", "torchGlowMesh");
		assertEquals(0, a.records.get(2).typeId());
		assertEquals(2, a.records.get(2).unk10(), "스케일 원소 = s16");
		assertEquals(1, a.scaleTracks.size());
		assertTrue(a.rotTracks.isEmpty() && a.posTracks.isEmpty());
		assertEquals((float) (Math.PI / 2 / 4096), Animation.SCALE_UNIT, 1e-9f);
		devple.gow.tools.anim.Skeleton sk = devple.gow.tools.anim.Skeleton.loadAll(w).stream().filter(x -> x.name.equals("torchglowmesh")).findFirst().orElseThrow();
		float[] raw = a.scaleTracks.getFirst().values()[0];
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for (int f = 0; f < raw.length; f++) {
			var p = a.sample(sk, f * a.frameTime, false);
			assertEquals(raw[Math.min(f, raw.length - 1)] * Animation.SCALE_UNIT, p.scale[0][0], 1e-5f, "프레임 " + f);
			float[] m = p.world(sk)[0];
			float row = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
			assertEquals(p.scale[0][0], row, 1e-4f, "행 0 길이 = 스케일 x");
			min = Math.min(min, p.scale[0][0]);
			max = Math.max(max, p.scale[0][0]);
		}
		assertEquals(1.0f, a.sample(sk, 0, false).scale[0][0], 1e-3f, "2608 = 1.0");
		assertTrue(min < 0.9f && max > 1.05f, "깜박임 " + min + ".." + max);
	}

	/**
	 * 타입 5 u8 = 관절 가시성 (41 §1.3): ARENA06 항아리 PotLgDirtyABrk8 (세트 [0(p2 3), 5, 11] → 디스크립터 3 = 타입 5) 는 온전한 몸 [1,1,0] · 조각 묶음 [0,0,1].
	 * 예전 항목 번호 배치는 디스크립터를 항목 수 (3) 만큼만 읽어 이 트랙을 놓쳐 온전한 몸과 조각을 함께 그렸다.
	 */
	@Test
	void potVisibilityTrackIsTypeFive() throws Exception {
		WadFile w = wad("ARENA06");
		Animation a = act(w, "ANM_potlgdirtyabrk8", "PotLgDirtyABrk8");
		assertEquals(5, a.records.get(3).typeId());
		assertEquals(1, a.records.get(3).unk10());
		devple.gow.tools.anim.Skeleton sk = devple.gow.tools.anim.Skeleton.loadAll(w).stream().filter(x -> x.name.equals("potlgdirtyabrk8")).findFirst().orElseThrow();
		int body = sk.indexOf("pasted__PotLgDirtyA1"), shards = sk.indexOf("pasted__PotLgDirtyABrea");
		boolean[] h0 = a.hiddenJoints(sk, 0), h1 = a.hiddenJoints(sk, a.duration);
		assertFalse(h0[body], "처음: 온전한 항아리 보임");
		assertTrue(h0[shards], "처음: 조각 숨김");
		assertTrue(h1[body], "끝: 온전한 몸 숨김");
		assertFalse(h1[shards], "끝: 조각 보임");
	}

	/** R_PERMK 상자 chestGrab: 타입 0 뒤가 모두 타입 10 인 세트는 두 배치의 채널 번호가 같다 (3/6/9/12 = MSemit1/HSemit1/PSemit1/WSemit1, 40 §3). */
	@Test
	void chestChannelsKeepTheirIds() throws Exception {
		Animation a = act(wad("R_PERMK"), "ANM_chest", "chestGrab");
		for (int id : new int[]{3, 6, 9, 12}) {
			assertTrue(a.hasChannel(id), "채널 " + id);
			assertEquals(10, a.records.get(id).typeId());
		}
	}
}
